package com.nexum.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only progress records for a task.
 *
 * <p>This is what makes a killed agent recoverable rather than merely replaced.
 * A replacement claims the orphaned task and reads the highest {@code seq} here
 * to learn what was already done, so the work resumes instead of restarting.
 *
 * <p><strong>Never updated in place.</strong> A single mutable "progress" row
 * would be lost-update bait: the dying run and its replacement can briefly
 * overlap, and the loser of that race would silently erase the winner's
 * progress. Appending makes the history immutable and the restore point simply
 * the maximum - and it means the demo can show the whole trail of a task that
 * changed hands, which a mutable row would have destroyed.
 */
@Repository
public class CheckpointRepository {

    private final JdbcTemplate jdbc;

    public CheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends the next checkpoint for a task.
     *
     * <p>The sequence number is chosen by the database in the same statement
     * that inserts the row, rather than being read and then written back. Two
     * runs writing concurrently - exactly what happens in the seconds around a
     * lease changing hands - would otherwise both read the same maximum and
     * collide on the {@code UNIQUE (task_id, seq)} constraint, or worse, be
     * allowed through by a check that had gone stale.
     */
    public Saved append(UUID taskId, UUID goalId, UUID runId, UUID agentId,
            String progressSummary, String pendingActions, String contextJson) {

        return this.jdbc.queryForObject("""
                INSERT INTO checkpoints
                    (task_id, goal_id, run_id, agent_id, seq, progress_summary,
                     pending_actions, current_context)
                SELECT ?, ?, ?, ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?::JSONB
                FROM checkpoints
                WHERE task_id = ?
                RETURNING id, seq
                """,
                (rs, rowNum) -> new Saved(rs.getObject("id", UUID.class), rs.getInt("seq")),
                taskId, goalId, runId, agentId, progressSummary, pendingActions,
                (contextJson != null) ? contextJson : "{}", taskId);
    }

    /**
     * The point a replacement agent resumes from.
     *
     * <p>Deliberately not filtered by run: the whole design is that the
     * checkpoint belongs to the <em>task</em>, not to whoever was holding it.
     * A replacement reads the dead run's last checkpoint precisely because that
     * work was not the dead run's private property.
     */
    public Optional<Checkpoint> latestFor(UUID taskId) {
        List<Checkpoint> found = this.jdbc.query("""
                SELECT id, seq, run_id, agent_id, progress_summary, pending_actions,
                       current_context::TEXT AS current_context, created_at
                FROM checkpoints
                WHERE task_id = ?
                ORDER BY seq DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new Checkpoint(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("agent_id", UUID.class),
                        rs.getString("progress_summary"),
                        rs.getString("pending_actions"),
                        rs.getString("current_context"),
                        rs.getTimestamp("created_at").toInstant()),
                taskId);

        return found.stream().findFirst();
    }

    public int countFor(UUID taskId) {
        Integer count = this.jdbc.queryForObject(
                "SELECT count(*) FROM checkpoints WHERE task_id = ?", Integer.class, taskId);
        return (count != null) ? count : 0;
    }

    public record Saved(UUID id, int seq) {
    }

    public record Checkpoint(UUID id, int seq, UUID runId, UUID agentId, String progressSummary,
            String pendingActions, String context, Instant createdAt) {
    }
}
