package com.nexum.goal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Goals, agent identities, and membership.
 *
 * <p>Reads here are for the dashboard and the API - the operator's view of the
 * system. They are deliberately not the path agents use: an agent never asks
 * this class anything, because everything an agent may see goes through
 * {@code MemoryAccessPolicy} instead.
 *
 * <p>JDBC rather than JPA, consistently with the rest of the data layer. These
 * are aggregate read models joining four tables to answer one screen; expressing
 * that as entity graphs would produce more code and more queries.
 */
@Repository
public class GoalRepository {

    private final JdbcTemplate jdbc;

    public GoalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- goals -----------------------------------------------------------

    public UUID createGoal(String title, String description) {
        return this.jdbc.queryForObject("""
                INSERT INTO goals (title, description) VALUES (?, ?) RETURNING id
                """, UUID.class, title, description);
    }

    /**
     * One goal with its live counts.
     *
     * <p>The counts are correlated subqueries rather than joins on purpose: a
     * join across tasks, members, and memories would multiply rows and need a
     * GROUP BY over every selected column, which is both slower and much easier
     * to get subtly wrong.
     */
    public Optional<GoalView> findGoal(UUID goalId) {
        return this.jdbc.query(GOAL_SQL + " WHERE g.id = ?", GOAL_MAPPER, goalId)
                .stream().findFirst();
    }

    public List<GoalView> listGoals() {
        return this.jdbc.query(GOAL_SQL + " ORDER BY g.created_at DESC LIMIT 50", GOAL_MAPPER);
    }

    private static final String GOAL_SQL = """
            SELECT g.id, g.title, g.description, g.status, g.created_at,
                   (SELECT count(*) FROM tasks t WHERE t.goal_id = g.id) AS task_count,
                   (SELECT count(*) FROM tasks t WHERE t.goal_id = g.id
                        AND t.status = 'COMPLETED') AS completed_task_count,
                   (SELECT count(*) FROM goal_agents ga WHERE ga.goal_id = g.id
                        AND ga.status = 'ACTIVE') AS member_count,
                   (SELECT count(*) FROM memories m WHERE m.goal_id = g.id) AS memory_count
            FROM goals g""";

    private static final RowMapper<GoalView> GOAL_MAPPER = (rs, rowNum) -> new GoalView(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getInt("task_count"),
            rs.getInt("completed_task_count"),
            rs.getInt("member_count"),
            rs.getInt("memory_count"));

    // --- agents ----------------------------------------------------------

    public UUID createAgent(String name, String role) {
        return this.jdbc.queryForObject("""
                INSERT INTO agents (name, role) VALUES (?, ?) RETURNING id
                """, UUID.class, name, role);
    }

    public boolean agentExists(UUID agentId) {
        return Boolean.TRUE.equals(this.jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM agents WHERE id = ?)", Boolean.class, agentId));
    }

    public boolean goalExists(UUID goalId) {
        return Boolean.TRUE.equals(this.jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM goals WHERE id = ?)", Boolean.class, goalId));
    }

    /**
     * Joins an agent to a goal, or re-activates a membership it had left.
     *
     * <p>Idempotent by design, and the re-activation matters: recovery is
     * modelled as <em>joining a goal</em>, and an agent may legitimately rejoin
     * one it previously left. Returns true when this was a rejoin, which the
     * caller uses to pick the right event.
     */
    public boolean joinGoal(UUID goalId, UUID agentId, String role) {
        List<String> existing = this.jdbc.queryForList("""
                SELECT status FROM goal_agents WHERE goal_id = ? AND agent_id = ?
                """, String.class, goalId, agentId);

        if (existing.isEmpty()) {
            this.jdbc.update("""
                    INSERT INTO goal_agents (goal_id, agent_id, role, status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    """, goalId, agentId, role);
            return false;
        }

        this.jdbc.update("""
                UPDATE goal_agents SET status = 'ACTIVE', left_at = NULL
                WHERE goal_id = ? AND agent_id = ?
                """, goalId, agentId);
        return "LEFT".equals(existing.getFirst());
    }

    public List<MemberView> listMembers(UUID goalId) {
        return this.jdbc.query("""
                SELECT a.id, a.name, a.role, ga.status, ga.joined_at,
                       (SELECT count(*) FROM memories m
                        WHERE m.goal_id = ga.goal_id AND m.agent_id = a.id) AS memory_count,
                       (SELECT count(*) FROM agent_runs r
                        WHERE r.goal_id = ga.goal_id AND r.agent_id = a.id
                          AND r.status = 'RUNNING') AS active_runs
                FROM goal_agents ga
                JOIN agents a ON a.id = ga.agent_id
                WHERE ga.goal_id = ?
                ORDER BY ga.joined_at
                """,
                (rs, rowNum) -> new MemberView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("role"),
                        rs.getString("status"),
                        rs.getTimestamp("joined_at").toInstant(),
                        rs.getInt("memory_count"),
                        rs.getInt("active_runs")),
                goalId);
    }

    // --- tasks and runs --------------------------------------------------

    public List<TaskView> listTasks(UUID goalId) {
        return this.jdbc.query("""
                SELECT t.id, t.title, t.description, t.status, t.priority, t.attempt_count,
                       t.max_steps, t.lease_run_id, t.lease_expires_at, t.completed_at,
                       (SELECT count(*) FROM checkpoints c WHERE c.task_id = t.id)
                           AS checkpoint_count
                FROM tasks t
                WHERE t.goal_id = ?
                ORDER BY t.priority DESC, t.created_at
                """,
                (rs, rowNum) -> new TaskView(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getInt("priority"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_steps"),
                        rs.getObject("lease_run_id", UUID.class),
                        instantOrNull(rs.getTimestamp("lease_expires_at")),
                        instantOrNull(rs.getTimestamp("completed_at")),
                        rs.getInt("checkpoint_count")),
                goalId);
    }

    /**
     * Runs on a goal, newest first.
     *
     * <p>Includes dead ones. A dashboard that hid them would hide the entire
     * point: the run that died is the most interesting row on the screen.
     */
    public List<RunView> listRuns(UUID goalId) {
        return this.jdbc.query("""
                SELECT r.id, r.agent_id, a.name AS agent_name, a.role AS agent_role,
                       r.task_id, r.status, r.started_at, r.last_heartbeat_at, r.ended_at,
                       r.termination_reason
                FROM agent_runs r
                JOIN agents a ON a.id = r.agent_id
                WHERE r.goal_id = ?
                ORDER BY r.started_at DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new RunView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("agent_id", UUID.class),
                        rs.getString("agent_name"),
                        rs.getString("agent_role"),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("last_heartbeat_at").toInstant(),
                        instantOrNull(rs.getTimestamp("ended_at")),
                        rs.getString("termination_reason")),
                goalId);
    }

    /** Failures on a goal - the audit trail behind every recovery. */
    public List<FailureView> listFailures(UUID goalId) {
        return this.jdbc.query("""
                SELECT f.id, f.run_id, f.agent_id, f.task_id, f.failure_type, f.error_message,
                       f.last_checkpoint_id, f.recovery_status, f.created_at
                FROM agent_failures f
                WHERE f.goal_id = ?
                ORDER BY f.created_at DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new FailureView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("agent_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("failure_type"),
                        rs.getString("error_message"),
                        rs.getObject("last_checkpoint_id", UUID.class),
                        rs.getString("recovery_status"),
                        rs.getTimestamp("created_at").toInstant()),
                goalId);
    }

    private static Instant instantOrNull(java.sql.Timestamp timestamp) {
        return (timestamp != null) ? timestamp.toInstant() : null;
    }

    // --- views -----------------------------------------------------------

    public record GoalView(UUID id, String title, String description, String status,
            Instant createdAt, int taskCount, int completedTaskCount, int memberCount,
            int memoryCount) {
    }

    public record MemberView(UUID id, String name, String role, String membershipStatus,
            Instant joinedAt, int memoriesAuthored, int activeRuns) {
    }

    public record TaskView(UUID id, String title, String description, String status, int priority,
            int attemptCount, int maxSteps, UUID leaseRunId, Instant leaseExpiresAt,
            Instant completedAt, int checkpointCount) {
    }

    public record RunView(UUID id, UUID agentId, String agentName, String agentRole, UUID taskId,
            String status, Instant startedAt, Instant lastHeartbeatAt, Instant endedAt,
            String terminationReason) {
    }

    public record FailureView(UUID id, UUID runId, UUID agentId, UUID taskId, String failureType,
            String errorMessage, UUID lastCheckpointId, String recoveryStatus, Instant createdAt) {
    }
}
