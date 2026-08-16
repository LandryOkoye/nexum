package com.nexum.coordination;

import java.util.List;
import java.util.UUID;

import com.nexum.event.EventLog;
import com.nexum.event.EventType;
import com.nexum.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performs the state transition for one lapsed lease, transactionally.
 *
 * <p>This lives in its own bean rather than inside {@link TaskReaper} for a
 * reason that is easy to get wrong: Spring's {@code @Transactional} is applied
 * by a proxy, and a method called from another method of the <em>same</em> bean
 * never crosses that proxy. The reaper's sweep calling its own reap method would
 * have run with no transaction at all - silently, with no error to notice.
 *
 * <p>The transaction is load-bearing here. Orphaning the task, marking the run
 * dead, and recording the failure are one unit. A partial reap is the worst
 * outcome available: a task returned to the pool with no failure row explaining
 * why, or a failure row pointing at a task nobody may claim.
 */
@Component
class LeaseReclaimer {

    private static final Logger log = LoggerFactory.getLogger(LeaseReclaimer.class);

    private final TaskRepository tasks;
    private final AgentRunRepository runs;
    private final EventLog events;
    private final JdbcTemplate jdbc;

    LeaseReclaimer(TaskRepository tasks, AgentRunRepository runs, EventLog events,
            JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.runs = runs;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional
    void reclaim(TaskRepository.ExpiredLease lease) {
        if (!this.tasks.orphan(lease.taskId())) {
            // The holder heartbeated between our scan and this update. It is
            // alive after all - leave it alone.
            return;
        }

        UUID runId = lease.runId();
        UUID agentId = null;
        if (runId != null) {
            this.runs.markDead(runId, "LEASE_EXPIRED");
            try {
                agentId = this.runs.agentIdOf(runId);
            }
            catch (RuntimeException ex) {
                log.debug("Could not resolve agent for run {}", runId);
            }
        }

        // The checkpoint a replacement agent will resume from. Recorded on the
        // failure row so recovery never has to guess how far the dead run got.
        UUID checkpointId = latestCheckpointId(lease.taskId());

        this.jdbc.update("""
                INSERT INTO agent_failures
                    (run_id, agent_id, goal_id, task_id, failure_type,
                     error_message, last_checkpoint_id, recovery_status)
                VALUES (?, ?, ?, ?, 'LEASE_EXPIRED', ?, ?, 'PENDING')
                """,
                runId, agentId, lease.goalId(), lease.taskId(),
                "Worker stopped heartbeating; lease expired", checkpointId);

        this.events.append(lease.goalId(), EventType.AGENT_FAILED,
                Json.object("runId", runId, "agentId", agentId, "taskId", lease.taskId(),
                        "reason", "LEASE_EXPIRED"));
        this.events.append(lease.goalId(), EventType.TASK_ORPHANED,
                Json.object("taskId", lease.taskId(), "lastCheckpointId", checkpointId));

        log.info("Reaped task {} - run {} stopped heartbeating. Goal memory intact; "
                + "task is claimable by any agent on goal {}",
                lease.taskId(), runId, lease.goalId());
    }

    private UUID latestCheckpointId(UUID taskId) {
        List<UUID> found = this.jdbc.queryForList("""
                SELECT id FROM checkpoints
                WHERE task_id = ?
                ORDER BY seq DESC
                LIMIT 1
                """, UUID.class, taskId);
        return found.isEmpty() ? null : found.getFirst();
    }
}
