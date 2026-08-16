package com.nexum.coordination;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Agent <em>runs</em> - the ephemeral execution instances.
 *
 * <p>This is the table that makes "recovery is not resurrection" structural. The
 * agent identity in {@code agents} never dies; a run does. A replacement agent
 * starts a fresh run against the same goal and task, and inherits the
 * collective's memory rather than the dead worker's identity.
 */
@Repository
public class AgentRunRepository {

    private final JdbcTemplate jdbc;

    public AgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID start(UUID agentId, UUID goalId) {
        return this.jdbc.queryForObject("""
                INSERT INTO agent_runs (agent_id, goal_id, status)
                VALUES (?, ?, 'RUNNING')
                RETURNING id
                """, UUID.class, agentId, goalId);
    }

    public void attachTask(UUID runId, UUID taskId) {
        this.jdbc.update("UPDATE agent_runs SET task_id = ? WHERE id = ?", taskId, runId);
    }

    public boolean markDead(UUID runId, String reason) {
        return this.jdbc.update("""
                UPDATE agent_runs
                SET status = 'DEAD', ended_at = now(), termination_reason = ?
                WHERE id = ? AND status = 'RUNNING'
                """, reason, runId) == 1;
    }

    public boolean markCompleted(UUID runId) {
        return this.jdbc.update("""
                UPDATE agent_runs
                SET status = 'COMPLETED', ended_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, runId) == 1;
    }

    /**
     * Simulate worker death for the demo: the run simply stops heartbeating.
     *
     * <p>Note what this does NOT do - it does not touch the task. The task is
     * still RUNNING and still leased. The reaper has to notice, on its own, that
     * the lease lapsed. That distinction is the difference between a demo that
     * flips a status and one that actually detects failure.
     */
    public boolean stopHeartbeat(UUID runId) {
        return this.jdbc.update("""
                UPDATE agent_runs
                SET last_heartbeat_at = now() - INTERVAL '1 hour'
                WHERE id = ? AND status = 'RUNNING'
                """, runId) == 1;
    }

    public UUID agentIdOf(UUID runId) {
        return this.jdbc.queryForObject(
                "SELECT agent_id FROM agent_runs WHERE id = ?", UUID.class, runId);
    }
}
