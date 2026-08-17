package com.nexum.agent;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What an agent chose, and why it said it chose it.
 *
 * <p>Kept separate from checkpoints because they answer different questions. A
 * checkpoint is "where the work got to" and exists so someone else can continue
 * it. A decision is "why the work went that way" and exists so a human can audit
 * it afterwards - including the decisions of a run that later died, which are
 * otherwise the first thing lost when a worker disappears.
 *
 * <p>The reason is the model's own account of itself and is stored as exactly
 * that: a claim, next to the confidence it asserted. Nothing downstream treats
 * it as authoritative.
 */
@Repository
public class DecisionRepository {

    private final JdbcTemplate jdbc;

    public DecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID record(UUID goalId, UUID agentId, UUID taskId, UUID runId, String decision,
            String reason, double confidence) {

        return this.jdbc.queryForObject("""
                INSERT INTO decisions
                    (goal_id, agent_id, task_id, run_id, decision, reason, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, UUID.class,
                goalId, agentId, taskId, runId, decision, reason,
                Math.clamp(confidence, 0.0, 1.0));
    }

    public int countFor(UUID taskId) {
        Integer count = this.jdbc.queryForObject(
                "SELECT count(*) FROM decisions WHERE task_id = ?", Integer.class, taskId);
        return (count != null) ? count : 0;
    }
}
