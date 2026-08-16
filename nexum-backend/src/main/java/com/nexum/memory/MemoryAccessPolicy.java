package com.nexum.memory;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The single place a memory-visibility rule is expressed.
 *
 * <p>Access control that lives in several query strings is access control that
 * will eventually disagree with itself: one retrieval path gets a new filter,
 * another is written months later by someone who did not know the rule, and the
 * leak is invisible until somebody audits every query. So there is exactly one
 * predicate builder, and {@link MemoryRepository} constructs no {@code WHERE}
 * clause over {@code memories} without it.
 *
 * <p><strong>The goal equality is deliberately the first conjunct.</strong> The
 * vector index built in V4 is {@code (goal_id, embedding vector_cosine_ops)}, so
 * a top-level {@code goal_id = ?} lets CockroachDB narrow to the goal
 * <em>inside the storage engine</em> and rank only that goal's vectors. This is
 * the property that makes the design defensible: the relational scope predicate
 * and the semantic ranking are the same index, so scope is not something the
 * application remembers to apply afterwards. Retrieval that ranked globally and
 * filtered after would be wrong twice over - worse recall, and an access-control
 * decision made after the data has already been read.
 *
 * <p>Membership is a gate, not a row filter: it references no column of
 * {@code memories}, so a non-member matches nothing at all on the goal rather
 * than matching "only the public parts of it".
 *
 * <p><strong>On GLOBAL:</strong> the V3 CHECK constraint requires global
 * memories to have a null {@code goal_id}, so they can never satisfy the goal
 * equality above and are absent from this predicate by construction. That is
 * honest rather than accidental - a global search cannot share a goal-prefixed
 * index scan and needs its own path. Promotion to global is deferred, so that
 * path is not built.
 */
@Component
public class MemoryAccessPolicy {

    /**
     * What {@code agentId} may read inside {@code goalId}: the goal's shared
     * memory if it is an active member, plus its own private scratch work.
     *
     * <p>Private memory of <em>other</em> agents is unreachable here, and no
     * caller can widen it - the predicate is opaque to them.
     */
    public Predicate withinGoal(UUID agentId, UUID goalId) {
        String sql = """
                m.goal_id = ?
                  AND EXISTS (
                      SELECT 1 FROM goal_agents ga
                      WHERE ga.goal_id = ?
                        AND ga.agent_id = ?
                        AND ga.status = 'ACTIVE')
                  AND (m.scope = 'GOAL'
                       OR (m.scope = 'PRIVATE' AND m.agent_id = ?))""";

        return new Predicate(sql, List.of(goalId, goalId, agentId, agentId));
    }

    /**
     * A SQL fragment and the parameters it expects, in order.
     *
     * <p>Kept together so a caller cannot bind the parameters of one predicate
     * to the SQL of another - the kind of mistake that produces a query which
     * runs, returns rows, and is quietly wrong.
     *
     * <p>The fragment references the {@code memories} table as {@code m}.
     */
    public record Predicate(String sql, List<Object> parameters) {
    }
}
