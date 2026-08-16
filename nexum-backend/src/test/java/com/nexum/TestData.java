package com.nexum;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Builds the goals, agents, and memberships a test needs.
 *
 * <p>Every fixture is created fresh with server-generated ids and no test ever
 * reads a row it did not create, so the suite runs against the same database a
 * developer is using without truncating it between tests. Shared mutable state
 * across a suite is a slow, intermittent kind of pain, and cleaning a database
 * that a running application is also writing to is worse.
 */
public class TestData {

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID goal(String title) {
        return this.jdbc.queryForObject("""
                INSERT INTO goals (title, description) VALUES (?, ?) RETURNING id
                """, UUID.class, title, "created by tests");
    }

    /** An agent identity. Note there is no status to set - liveness is a run's business. */
    public UUID agent(String name, String role) {
        return this.jdbc.queryForObject("""
                INSERT INTO agents (name, role) VALUES (?, ?) RETURNING id
                """, UUID.class, name + "-" + UUID.randomUUID(), role);
    }

    public void join(UUID goalId, UUID agentId, String role) {
        this.jdbc.update("""
                INSERT INTO goal_agents (goal_id, agent_id, role, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, goalId, agentId, role);
    }

    /** An agent that joined and then left. Membership is ended, identity is not. */
    public void leave(UUID goalId, UUID agentId) {
        this.jdbc.update("""
                UPDATE goal_agents SET status = 'LEFT', left_at = now()
                WHERE goal_id = ? AND agent_id = ?
                """, goalId, agentId);
    }

    /**
     * A deterministic unit vector pointing along one axis.
     *
     * <p>Lets the vector path be tested with no embedding provider at all: two
     * memories on the same axis have cosine distance 0, on different axes 1. The
     * ranking under test is CockroachDB's, and it does not care whether the
     * numbers came from Titan or from here.
     */
    public static float[] axis(int index, int dimensions) {
        float[] vector = new float[dimensions];
        vector[index] = 1.0f;
        return vector;
    }
}
