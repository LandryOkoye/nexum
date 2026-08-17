package com.nexum.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.nexum.TestData;
import com.nexum.coordination.TaskRepository;
import com.nexum.memory.MemoryService;
import com.nexum.memory.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The submission, as a test.
 *
 * <p>An agent is killed mid-task. Nothing tells the system it died. The lease it
 * was holding stops being renewed, the reaper works out on its own that the
 * lease has lapsed, and the task returns to the pool as ORPHANED - at which
 * point a different agent claims it through the ordinary claim path, restores
 * the dead agent's checkpoint, reads memory written by an agent it never
 * overlapped with, and finishes the work.
 *
 * <p>Written as a test rather than left as a click-path for two reasons. It has
 * to be run dozens of times while the surrounding code changes, and "is this
 * real or is it staged?" is a question a passing test answers better than a
 * video does.
 *
 * <p>The lease and sweep intervals are compressed here so the sequence completes
 * in seconds. Nothing else about the mechanism differs from the deployed
 * configuration.
 */
@SpringBootTest(properties = {
        "nexum.reaper.enabled=true",
        "nexum.reaper.interval-ms=500",
        "nexum.agent.lease-seconds=2",
        "nexum.agent.heartbeat-interval-ms=400"
})
@ActiveProfiles("test")
@Import(AgentRecoveryTests.ScriptedPlanner.class)
class AgentRecoveryTests {

    @TestConfiguration
    static class ScriptedPlanner {

        @Bean
        @Primary
        ScriptedStepPlanner scriptedStepPlanner() {
            return new ScriptedStepPlanner();
        }
    }

    private static final String DISCOVERY =
            "Kuda reduced transaction pricing by 8 percent in Q2";

    @Autowired
    private AgentDispatcher dispatcher;

    @Autowired
    private ScriptedStepPlanner planner;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private CheckpointRepository checkpoints;

    @Autowired
    private MemoryService memories;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("an agent dies mid-task and the collective finishes the work")
    void theAgentDiesAndTheCollectiveKeepsThinking() {
        TestData data = new TestData(this.jdbc);
        this.planner.reset();

        UUID goal = data.goal("Analyse competitors in African fintech");
        UUID alice = data.agent("alice", "RESEARCHER");
        data.join(goal, alice, "RESEARCHER");

        UUID task = this.tasks.createTask(goal, "Find pricing moves",
                "Identify competitors that changed transaction pricing", 10);

        // ---- 1. Alice contributes to goal memory, then parks mid-task -------

        this.planner.script(StepPlanner.Proposal.remember(DISCOVERY, MemoryType.FACT, "doc-01",
                0.9, "cited from the Q2 filing"));
        this.planner.scriptBlockUntilKilled();

        UUID aliceRun = this.dispatcher.dispatch(alice, goal).orElseThrow();

        await(() -> this.checkpoints.countFor(task) >= 1, "alice to checkpoint her discovery");
        assertThat(taskStatus(task)).isEqualTo("RUNNING");
        assertThat(leaseRunId(task)).isEqualTo(aliceRun);

        UUID checkpointBeforeDeath = this.checkpoints.latestFor(task).orElseThrow().id();

        // ---- 2. Kill her. The task must not be touched by the kill ----------

        assertThat(this.dispatcher.kill(aliceRun)).isTrue();

        assertThat(taskStatus(task))
                .as("killing a worker must not write the task; nothing knows yet that she is gone")
                .isEqualTo("RUNNING");
        assertThat(leaseRunId(task))
                .as("the task is still leased to the dead run - that is what the reaper detects")
                .isEqualTo(aliceRun);

        // ---- 3. Nobody declares the failure. The system notices -------------

        await(() -> "ORPHANED".equals(taskStatus(task)),
                "the reaper to detect the lapsed lease and orphan the task");

        assertThat(runStatus(aliceRun)).isEqualTo("DEAD");
        assertThat(leaseRunId(task)).as("an orphaned task holds no lease").isNull();

        Failure failure = failureFor(task);
        assertThat(failure.failureType()).isEqualTo("LEASE_EXPIRED");
        assertThat(failure.lastCheckpointId())
                .as("the failure record points at the work that survived her")
                .isEqualTo(checkpointBeforeDeath);

        assertThat(memoryContents(goal))
                .as("agent failure must not cost the collective what it learned")
                .contains(DISCOVERY);

        // ---- 4. A different agent picks it up -------------------------------

        this.planner.reset();
        this.planner.script(StepPlanner.Proposal.complete(
                "Confirmed the pricing cut Alice found and closed the task",
                "the collective already had the finding"));

        UUID dave = data.agent("dave", "ANALYST");
        data.join(goal, dave, "ANALYST");
        UUID daveRun = this.dispatcher.dispatch(dave, goal).orElseThrow();

        await(() -> "COMPLETED".equals(taskStatus(task)), "dave to finish alice's task");

        // ---- 5. What the replacement actually saw ---------------------------

        assertThat(daveRun).isNotEqualTo(aliceRun);
        assertThat(completedByRun(task)).isNotEqualTo(aliceRun);

        List<StepPlanner.Context> daveSaw = this.planner.seen();
        assertThat(daveSaw).isNotEmpty();

        assertThat(daveSaw.getFirst().progressSummary())
                .as("dave resumed from alice's checkpoint rather than starting over")
                .isNotBlank();

        assertThat(daveSaw.stream()
                .flatMap((context) -> context.recalled().stream())
                .map((scored) -> scored.memory().content()))
                .as("dave was shown a finding written by an agent he never ran alongside")
                .contains(DISCOVERY);

        assertThat(this.checkpoints.countFor(task))
                .as("checkpoints are appended across the handover, never overwritten")
                .isGreaterThan(1);

        // ---- 6. The five events, in order -----------------------------------

        List<String> timeline = eventTypesFor(goal);
        assertThat(timeline).containsSubsequence("AGENT_FAILED", "TASK_ORPHANED",
                "AGENT_REJOINED_GOAL", "CHECKPOINT_RESTORED", "TASK_RESUMED");
        assertThat(timeline).endsWith("TASK_COMPLETED");
    }

    // --- helpers ---------------------------------------------------------

    private void await(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + what, ex);
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private String taskStatus(UUID taskId) {
        return this.jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class,
                taskId);
    }

    private UUID leaseRunId(UUID taskId) {
        return this.jdbc.queryForObject("SELECT lease_run_id FROM tasks WHERE id = ?", UUID.class,
                taskId);
    }

    private String runStatus(UUID runId) {
        return this.jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?", String.class,
                runId);
    }

    private UUID completedByRun(UUID taskId) {
        return this.jdbc.queryForObject("""
                SELECT run_id FROM checkpoints WHERE task_id = ? ORDER BY seq DESC LIMIT 1
                """, UUID.class, taskId);
    }

    private Failure failureFor(UUID taskId) {
        return this.jdbc.queryForObject("""
                SELECT failure_type, last_checkpoint_id FROM agent_failures WHERE task_id = ?
                """,
                (rs, rowNum) -> new Failure(rs.getString("failure_type"),
                        rs.getObject("last_checkpoint_id", UUID.class)),
                taskId);
    }

    private List<String> memoryContents(UUID goalId) {
        return this.jdbc.queryForList("SELECT content FROM memories WHERE goal_id = ?",
                String.class, goalId);
    }

    private List<String> eventTypesFor(UUID goalId) {
        return this.jdbc.queryForList("SELECT event_type FROM events WHERE goal_id = ? ORDER BY seq",
                String.class, goalId);
    }

    private record Failure(String failureType, UUID lastCheckpointId) {
    }
}
