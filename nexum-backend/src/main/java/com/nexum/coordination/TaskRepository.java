package com.nexum.coordination;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Task coordination SQL.
 *
 * <p>Hand-written JDBC rather than JPA, deliberately. Every statement here is a
 * conditional update whose <em>affected-row count is the authority</em> - the
 * thing that decides who owns a task. JPA's dirty-checking would obscure exactly
 * the semantics that must stay explicit.
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically claim the highest-priority unclaimed task on a goal and take a
     * lease on it.
     *
     * <p>Invariant 1 lives here. The inner SELECT picks a candidate, but the
     * outer WHERE re-checks the status, so two racing claims cannot both
     * succeed: one updates the row, the other matches zero rows (or aborts with
     * SQLSTATE 40001 and is retried by the caller). An empty result means you
     * lost the race - it is not an error.
     *
     * <p>ORPHANED is claimable alongside AVAILABLE: that is precisely how a
     * replacement agent picks up work abandoned by a dead run.
     */
    public Optional<TaskClaim> claimNext(UUID goalId, UUID runId, int leaseSeconds) {
        List<TaskClaim> claimed = this.jdbc.query("""
                UPDATE tasks
                SET status = 'RUNNING',
                    lease_run_id = ?,
                    lease_expires_at = now() + ?::INTERVAL,
                    attempt_count = attempt_count + 1,
                    updated_at = now()
                WHERE id = (
                    SELECT id FROM tasks
                    WHERE goal_id = ?
                      AND status IN ('AVAILABLE', 'ORPHANED')
                    ORDER BY priority DESC, created_at
                    LIMIT 1
                )
                AND status IN ('AVAILABLE', 'ORPHANED')
                RETURNING id, goal_id, title, description, max_steps, attempt_count
                """,
                (rs, rowNum) -> new TaskClaim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("goal_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getInt("max_steps"),
                        rs.getInt("attempt_count")),
                runId, leaseSeconds + " seconds", goalId);

        return claimed.stream().findFirst();
    }

    /**
     * Extend the lease. Returns false if this run no longer holds it - which
     * means the reaper already declared it dead and someone else may own the
     * task now. A worker that gets false MUST stop working immediately;
     * continuing would violate Invariant 1 from the other direction.
     */
    public boolean heartbeat(UUID taskId, UUID runId, int leaseSeconds) {
        int updated = this.jdbc.update("""
                UPDATE tasks
                SET lease_expires_at = now() + ?::INTERVAL,
                    updated_at = now()
                WHERE id = ? AND lease_run_id = ? AND status = 'RUNNING'
                """, leaseSeconds + " seconds", taskId, runId);

        if (updated == 1) {
            this.jdbc.update("""
                    UPDATE agent_runs SET last_heartbeat_at = now()
                    WHERE id = ? AND status = 'RUNNING'
                    """, runId);
        }
        return updated == 1;
    }

    /** Tasks whose holder has stopped heartbeating. Drives the reaper. */
    public List<ExpiredLease> findExpiredLeases(int limit) {
        return this.jdbc.query("""
                SELECT id, goal_id, lease_run_id
                FROM tasks
                WHERE status = 'RUNNING'
                  AND lease_expires_at IS NOT NULL
                  AND lease_expires_at < now()
                ORDER BY lease_expires_at
                LIMIT ?
                """,
                (rs, rowNum) -> new ExpiredLease(
                        rs.getObject("id", UUID.class),
                        rs.getObject("goal_id", UUID.class),
                        rs.getObject("lease_run_id", UUID.class)),
                limit);
    }

    /**
     * Orphan a task whose lease lapsed. Conditional on the lease still being
     * expired, so a worker that heartbeats in the same instant keeps its task.
     */
    public boolean orphan(UUID taskId) {
        return this.jdbc.update("""
                UPDATE tasks
                SET status = 'ORPHANED',
                    lease_run_id = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_expires_at < now()
                """, taskId) == 1;
    }

    public boolean complete(UUID taskId, UUID runId) {
        return this.jdbc.update("""
                UPDATE tasks
                SET status = 'COMPLETED',
                    lease_run_id = NULL,
                    lease_expires_at = NULL,
                    completed_at = now(),
                    updated_at = now()
                WHERE id = ? AND lease_run_id = ? AND status = 'RUNNING'
                """, taskId, runId) == 1;
    }

    /** Release voluntarily - the task goes back on the queue, not to FAILED. */
    public boolean release(UUID taskId, UUID runId) {
        return this.jdbc.update("""
                UPDATE tasks
                SET status = 'AVAILABLE',
                    lease_run_id = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ? AND lease_run_id = ? AND status = 'RUNNING'
                """, taskId, runId) == 1;
    }

    public UUID createTask(UUID goalId, String title, String description, int priority) {
        return this.jdbc.queryForObject("""
                INSERT INTO tasks (goal_id, title, description, priority)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, UUID.class, goalId, title, description, priority);
    }

    public record ExpiredLease(UUID taskId, UUID goalId, UUID runId) {
    }
}
