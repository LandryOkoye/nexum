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
import org.junit.jupiter.api.BeforeEach;
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
 * Block 3's acceptance gate: one agent claims a task, works it under a lease,
 * checkpoints every step, contributes to goal memory, and completes.
 *
 * <p>The second test is the one that matters more - it shows a memory written by
 * one agent reaching a different agent that never ran alongside it. That is the
 * claim the whole project rests on, and it is worth proving before the recovery
 * path piles more machinery on top of it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AgentLoopTests.ScriptedPlanner.class)
class AgentLoopTests {

    @TestConfiguration
    static class ScriptedPlanner {

        /**
         * Replaces {@link ModelStepPlanner}. Primary rather than excluding the
         * real bean, so the production wiring stays exactly as it ships.
         */
        @Bean
        @Primary
        ScriptedStepPlanner scriptedStepPlanner() {
            return new ScriptedStepPlanner();
        }
    }

    @Autowired
    private AgentDispatcher dispatcher;

    @Autowired
    private ScriptedStepPlanner planner;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private CheckpointRepository checkpoints;

    @Autowired
    private DecisionRepository decisions;

    @Autowired
    private MemoryService memories;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;

    private UUID goal;

    private UUID researcher;

    @BeforeEach
    void setUp() {
        this.data = new TestData(this.jdbc);
        this.planner.reset();
        this.goal = this.data.goal("Analyse competitors in African fintech");
        this.researcher = this.data.agent("researcher", "RESEARCHER");
        this.data.join(this.goal, this.researcher, "RESEARCHER");
    }

    @Test
    @DisplayName("an agent claims a task, checkpoints each step, and completes it")
    void oneAgentCompletesOneTask() {
        UUID task = this.tasks.createTask(this.goal, "Find pricing moves",
                "Identify competitors that changed transaction pricing", 10);

        this.planner.script(
                StepPlanner.Proposal.search("transaction pricing", "need source documents"),
                StepPlanner.Proposal.remember(
                        "Kuda reduced transaction pricing by 8 percent in Q2",
                        MemoryType.FACT, "doc-01", 0.9, "the filing states it directly"),
                StepPlanner.Proposal.complete("Identified one confirmed pricing cut",
                        "the task question is answered"));

        UUID run = this.dispatcher.dispatch(this.researcher, this.goal).orElseThrow();

        awaitTaskStatus(task, "COMPLETED");

        assertThat(this.checkpoints.countFor(task))
                .as("one checkpoint per step, appended not overwritten")
                .isEqualTo(3);
        assertThat(this.checkpoints.latestFor(task).orElseThrow().seq())
                .as("sequence numbers increment from 1")
                .isEqualTo(3);
        assertThat(this.decisions.countFor(task))
                .as("every step records what was decided and why")
                .isEqualTo(3);
        assertThat(runStatus(run)).isEqualTo("COMPLETED");

        assertThat(eventTypesFor(this.goal))
                .contains("TASK_CLAIMED", "TOOL_CALLED", "MEMORY_CREATED", "CHECKPOINT_SAVED",
                        "TASK_COMPLETED");
    }

    @Test
    @DisplayName("a second agent retrieves what the first one learned")
    void memoryOutlivesTheAgentThatWroteIt() {
        UUID task = this.tasks.createTask(this.goal, "Find pricing moves",
                "Identify competitors that changed transaction pricing", 10);

        this.planner.script(
                StepPlanner.Proposal.remember(
                        "Kuda reduced transaction pricing by 8 percent in Q2",
                        MemoryType.FACT, "doc-01", 0.9, "cited from the filing"),
                StepPlanner.Proposal.complete("Recorded the pricing cut", "done"));

        this.dispatcher.dispatch(this.researcher, this.goal).orElseThrow();
        awaitTaskStatus(task, "COMPLETED");

        // An agent that never overlapped with the researcher joins afterwards.
        UUID analyst = this.data.agent("analyst", "ANALYST");
        this.data.join(this.goal, analyst, "ANALYST");

        List<String> visible = this.memories.recall(analyst, this.goal, "pricing", 10)
                .memories().stream().map((scored) -> scored.memory().content()).toList();

        assertThat(visible)
                .as("the finding belongs to the goal, not to the agent that found it")
                .anyMatch((content) -> content.contains("8 percent"));
    }

    @Test
    @DisplayName("a cited document becomes evidence; an invented one does not")
    void confidenceDependsOnEvidenceTheBackendCanVerify() {
        UUID task = this.tasks.createTask(this.goal, "Check pricing claims", "", 10);

        this.planner.script(
                StepPlanner.Proposal.remember("Kuda cut pricing by 8 percent", MemoryType.FACT,
                        "doc-01", 0.95, "real citation"),
                StepPlanner.Proposal.remember("Competitor Z is about to collapse", MemoryType.FACT,
                        "doc-99-does-not-exist", 0.95, "invented citation"),
                StepPlanner.Proposal.complete("Done", "done"));

        this.dispatcher.dispatch(this.researcher, this.goal).orElseThrow();
        awaitTaskStatus(task, "COMPLETED");

        Double cited = confidenceOf("Kuda cut pricing by 8 percent");
        Double invented = confidenceOf("Competitor Z is about to collapse");

        assertThat(cited)
                .as("a claim backed by a real corpus document keeps its confidence")
                .isEqualTo(0.95);
        assertThat(invented)
                .as("a citation that does not resolve leaves the claim unevidenced, so it is capped")
                .isEqualTo(0.5);
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Polls rather than sleeping a fixed time: the loop runs on another thread
     * and a fixed sleep is either slow or flaky, usually both.
     */
    private void awaitTaskStatus(UUID taskId, String expected) {
        await(() -> expected.equals(taskStatus(taskId)),
                "task " + taskId + " to reach " + expected);
    }

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

    private String runStatus(UUID runId) {
        return this.jdbc.queryForObject("SELECT status FROM agent_runs WHERE id = ?", String.class,
                runId);
    }

    private Double confidenceOf(String content) {
        return this.jdbc.queryForObject("SELECT confidence FROM memories WHERE content = ?",
                Double.class, content);
    }

    private List<String> eventTypesFor(UUID goalId) {
        return this.jdbc.queryForList("SELECT event_type FROM events WHERE goal_id = ? ORDER BY seq",
                String.class, goalId);
    }
}
