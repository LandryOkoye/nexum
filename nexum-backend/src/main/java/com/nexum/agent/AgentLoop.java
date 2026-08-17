package com.nexum.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import com.nexum.coordination.AgentRunRepository;
import com.nexum.coordination.TaskClaim;
import com.nexum.coordination.TaskRepository;
import com.nexum.event.EventLog;
import com.nexum.event.EventType;
import com.nexum.memory.MemoryScope;
import com.nexum.memory.MemoryService;
import com.nexum.memory.NewMemory;
import com.nexum.support.CockroachRetry;
import com.nexum.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One agent, working one task, for a bounded number of steps.
 *
 * <p>The loop is deliberately small and the interesting properties are in what
 * bounds it:
 *
 * <ul>
 * <li><strong>It is finite.</strong> {@code max_steps} comes from the task row.
 * An agent that cannot finish gives the task back rather than running forever;
 * autonomy here has an edge, by construction rather than by supervision.</li>
 * <li><strong>It stops the instant it loses the lease.</strong> Checked before
 * every step and after every model call. Continuing after the reaper has
 * reassigned the task would put two agents on one task - Invariant 1 broken from
 * the side that is easy to miss.</li>
 * <li><strong>It checkpoints every step.</strong> So whatever it achieved
 * survives it, and the next agent starts from there rather than from nothing.
 * This is the line between "the worker died" and "the work died".</li>
 * <li><strong>It restores before it starts.</strong> A claimed task may carry
 * checkpoints written by an agent that no longer exists. Recovery is therefore
 * not a special path - it is what the ordinary path does when the task it
 * claimed happens to have a history.</li>
 * </ul>
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private static final int MEMORY_RECALL_LIMIT = 5;
    private static final int CORPUS_RESULT_LIMIT = 3;

    private final TaskRepository tasks;
    private final AgentRunRepository runs;
    private final CheckpointRepository checkpoints;
    private final DecisionRepository decisions;
    private final MemoryService memories;
    private final CompetitorCorpus corpus;
    private final StepPlanner planner;
    private final EventLog events;
    private final CockroachRetry retry;
    private final ScheduledExecutorService scheduler;
    private final int leaseSeconds;
    private final long heartbeatIntervalMillis;

    public AgentLoop(TaskRepository tasks, AgentRunRepository runs,
            CheckpointRepository checkpoints, DecisionRepository decisions,
            MemoryService memories, CompetitorCorpus corpus, StepPlanner planner,
            EventLog events, CockroachRetry retry, ScheduledExecutorService scheduler,
            @Value("${nexum.agent.lease-seconds:8}") int leaseSeconds,
            @Value("${nexum.agent.heartbeat-interval-ms:2000}") long heartbeatIntervalMillis) {

        this.tasks = tasks;
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.decisions = decisions;
        this.memories = memories;
        this.corpus = corpus;
        this.planner = planner;
        this.events = events;
        this.retry = retry;
        this.scheduler = scheduler;
        this.leaseSeconds = leaseSeconds;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    public Outcome run(UUID agentId, UUID goalId, UUID runId, RunHandle handle) {
        // The claim contends with every other agent on this goal, so it is the
        // canonical CockroachRetry site: SERIALIZABLE aborts the loser rather
        // than blocking it.
        Optional<TaskClaim> claimed = this.retry.execute("claim-task",
                () -> this.tasks.claimNext(goalId, runId, this.leaseSeconds));

        if (claimed.isEmpty()) {
            // Losing the race is not an error. There was simply nothing to do.
            this.runs.markCompleted(runId);
            return Outcome.NO_TASK;
        }

        TaskClaim task = claimed.get();
        this.runs.attachTask(runId, task.taskId());
        this.events.append(goalId, EventType.TASK_CLAIMED,
                Json.object("taskId", task.taskId(), "runId", runId, "agentId", agentId,
                        "title", task.title(), "attempt", task.attemptCount()));

        LeaseHeartbeat heartbeat = LeaseHeartbeat.start(task.taskId(), runId, this.scheduler,
                this.tasks, this.leaseSeconds, this.heartbeatIntervalMillis);
        handle.attachHeartbeat(heartbeat);

        try {
            return work(agentId, goalId, runId, handle, task, heartbeat);
        }
        catch (RuntimeException ex) {
            log.error("Run {} failed on task {}", runId, task.taskId(), ex);
            this.runs.markDead(runId, "ERROR: " + ex.getClass().getSimpleName());
            // The task is left leased. Not releasing it is intentional: an
            // exception here means we do not know what state the work is in, and
            // letting the lease lapse routes it through the same detection path
            // as any other failure rather than inventing a second one.
            return Outcome.ERROR;
        }
        finally {
            heartbeat.stop();
        }
    }

    private Outcome work(UUID agentId, UUID goalId, UUID runId, RunHandle handle, TaskClaim task,
            LeaseHeartbeat heartbeat) {

        String progress = restore(agentId, goalId, runId, task);
        String lastToolResult = null;

        for (int step = 1; step <= task.maxSteps(); step++) {
            if (heartbeat.isLost() || handle.isKilled()) {
                return standDown(goalId, runId, task, handle);
            }

            List<com.nexum.memory.ScoredMemory> recalled = this.memories
                    .recall(agentId, goalId, task.title() + " " + progress, MEMORY_RECALL_LIMIT)
                    .memories();

            StepPlanner.Proposal proposal = this.planner.plan(new StepPlanner.Context(
                    task.title(), task.description(), step, task.maxSteps(), progress, recalled,
                    lastToolResult));

            // Re-checked after the model call, which is where nearly all of a
            // step's wall-clock time goes and therefore where a kill lands.
            if (heartbeat.isLost() || handle.isKilled()) {
                return standDown(goalId, runId, task, handle);
            }

            this.decisions.record(goalId, agentId, task.taskId(), runId,
                    proposal.action().name(), proposal.reason(), proposal.confidence());

            switch (proposal.action()) {
                case SEARCH -> lastToolResult = search(goalId, runId, proposal);
                case REMEMBER -> remember(agentId, goalId, task, proposal);
                case COMPLETE -> {
                    // Nothing to do here; the completion is handled below so the
                    // final checkpoint is written before the task closes.
                }
            }

            progress = (proposal.summary() != null && !proposal.summary().isBlank())
                    ? proposal.summary() : progress;

            checkpoint(agentId, goalId, runId, task, step, progress, proposal);

            if (proposal.action() == StepPlanner.Proposal.Action.COMPLETE) {
                return complete(goalId, runId, task, progress);
            }
        }

        // Step budget exhausted. The task goes back on the queue rather than
        // being marked failed: it is unfinished, not broken, and another agent
        // resuming from the checkpoints may well finish it.
        this.tasks.release(task.taskId(), runId);
        this.runs.markCompleted(runId);
        this.events.append(goalId, EventType.TASK_FAILED,
                Json.object("taskId", task.taskId(), "runId", runId, "reason", "MAX_STEPS",
                        "progress", progress));
        return Outcome.EXHAUSTED;
    }

    /**
     * Picks up whatever a previous agent left behind.
     *
     * <p>The events emitted here are half of the recovery proof. They say, with
     * timestamps, that this run rejoined a goal and resumed from a checkpoint
     * written by a run that is already dead.
     */
    private String restore(UUID agentId, UUID goalId, UUID runId, TaskClaim task) {
        Optional<CheckpointRepository.Checkpoint> restored =
                this.checkpoints.latestFor(task.taskId());

        if (restored.isEmpty()) {
            return "";
        }

        CheckpointRepository.Checkpoint checkpoint = restored.get();
        this.events.append(goalId, EventType.CHECKPOINT_RESTORED,
                Json.object("taskId", task.taskId(), "checkpointId", checkpoint.id(),
                        "seq", checkpoint.seq(), "restoredByRun", runId,
                        "originallyWrittenByRun", checkpoint.runId(),
                        "originallyWrittenByAgent", checkpoint.agentId(),
                        "progress", checkpoint.progressSummary()));

        this.events.append(goalId, EventType.TASK_RESUMED,
                Json.object("taskId", task.taskId(), "runId", runId, "agentId", agentId,
                        "fromSeq", checkpoint.seq(), "attempt", task.attemptCount()));

        return (checkpoint.progressSummary() != null) ? checkpoint.progressSummary() : "";
    }

    private String search(UUID goalId, UUID runId, StepPlanner.Proposal proposal) {
        List<CompetitorCorpus.Document> found =
                this.corpus.search(proposal.query(), CORPUS_RESULT_LIMIT);

        this.events.append(goalId, EventType.TOOL_CALLED,
                Json.object("tool", "search_corpus", "runId", runId, "query", proposal.query(),
                        "resultCount", found.size()));

        if (found.isEmpty()) {
            return "No documents matched \"" + proposal.query() + "\".";
        }
        return found.stream().map(CompetitorCorpus.Document::asContext)
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    /**
     * Commits a finding to the goal's shared memory.
     *
     * <p>Evidence is resolved from the corpus rather than taken from the model:
     * if it cites a document that does not exist, the memory is written with no
     * evidence and the service caps its confidence accordingly. An invented
     * citation therefore costs the claim its standing instead of laundering it
     * into a confident fact.
     */
    private void remember(UUID agentId, UUID goalId, TaskClaim task,
            StepPlanner.Proposal proposal) {

        if (proposal.finding() == null || proposal.finding().isBlank()) {
            return;
        }

        List<NewMemory.Evidence> evidence = this.corpus.byId(proposal.evidenceDocId())
                .map((document) -> List.of(new NewMemory.Evidence("DOCUMENT", document.id(),
                        document.body(), proposal.confidence())))
                .orElse(List.of());

        this.memories.remember(new NewMemory(goalId, agentId, task.taskId(), MemoryScope.GOAL,
                proposal.memoryType(), proposal.finding(),
                (proposal.evidenceDocId() != null) ? "corpus:" + proposal.evidenceDocId() : "agent",
                proposal.confidence(), evidence));
    }

    private void checkpoint(UUID agentId, UUID goalId, UUID runId, TaskClaim task, int step,
            String progress, StepPlanner.Proposal proposal) {

        CheckpointRepository.Saved saved = this.checkpoints.append(task.taskId(), goalId, runId,
                agentId, progress, proposal.action().name(),
                Json.object("step", step, "action", proposal.action(), "reason",
                        proposal.reason()));

        this.events.append(goalId, EventType.CHECKPOINT_SAVED,
                Json.object("taskId", task.taskId(), "checkpointId", saved.id(), "seq",
                        saved.seq(), "runId", runId, "step", step, "progress", progress));
    }

    private Outcome complete(UUID goalId, UUID runId, TaskClaim task, String progress) {
        if (!this.tasks.complete(task.taskId(), runId)) {
            // The lease moved while we were finishing. Someone else owns this
            // task now, so our completion is not ours to record.
            this.runs.markDead(runId, "LEASE_LOST_BEFORE_COMPLETE");
            return Outcome.LEASE_LOST;
        }

        this.runs.markCompleted(runId);
        this.events.append(goalId, EventType.TASK_COMPLETED,
                Json.object("taskId", task.taskId(), "runId", runId, "summary", progress));
        return Outcome.COMPLETED;
    }

    private Outcome standDown(UUID goalId, UUID runId, TaskClaim task, RunHandle handle) {
        this.events.append(goalId, EventType.RUN_HEARTBEAT_LOST,
                Json.object("taskId", task.taskId(), "runId", runId,
                        "killed", handle.isKilled()));
        this.runs.markDead(runId, handle.isKilled() ? "KILLED" : "LEASE_LOST");
        return handle.isKilled() ? Outcome.KILLED : Outcome.LEASE_LOST;
    }

    public enum Outcome {

        /** Nothing was available to claim. */
        NO_TASK,

        /** The agent finished the task. */
        COMPLETED,

        /** The step budget ran out; the task went back on the queue. */
        EXHAUSTED,

        /** The lease was gone, so the agent stood down. */
        LEASE_LOST,

        /** The worker was killed. The task is left for the reaper to notice. */
        KILLED,

        /** Something threw. The lease is left to lapse. */
        ERROR

    }
}
