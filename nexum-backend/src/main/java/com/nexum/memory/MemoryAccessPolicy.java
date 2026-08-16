package com.nexum.memory;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single place a memory-visibility rule is expressed.
 *
 * <p>Access control that lives in several query strings is access control that
 * will eventually disagree with itself: one retrieval path gets a new filter,
 * another is written months later by someone who did not know the rule, and the
 * leak is invisible until somebody audits every query. So there is exactly one
 * place that decides what an agent may see, and {@link MemoryRepository}
 * composes no {@code WHERE} clause over {@code memories} without it.
 *
 * <p><strong>Visibility is expressed as grants, not as one predicate.</strong>
 * That shape is forced by how CockroachDB serves vector search, and the
 * constraint turns out to improve the design. A vector index is used only when
 * the query constrains its prefix columns and nothing else - measured, not
 * assumed: adding {@code scope} as a residual filter to a {@code (goal_id,
 * embedding)} search made the optimiser abandon the index and full-scan. Since
 * scope <em>is</em> the access boundary, a residual scope filter would also mean
 * ranking memories the caller may not see and discarding them afterwards.
 *
 * <p>So each grant is written to line up with the prefix of the V5 index,
 * {@code (goal_id, scope, embedding)}. Each is executed as its own search and
 * the results merged, which means every candidate the engine ever ranks is one
 * the agent was already entitled to. Scope is enforced <em>by the storage
 * engine, before similarity is computed</em> - the property this project claims
 * for CockroachDB, now actually true rather than aspirational.
 *
 * <p>Membership is checked first and separately. It depends on no row of
 * {@code memories}, so making it a predicate would have penalised every query;
 * as a gate it also means a non-member's search never reaches the memory table
 * at all.
 *
 * <p><strong>On GLOBAL:</strong> the V3 CHECK constraint requires global
 * memories to have a null {@code goal_id}, so they fall outside every
 * goal-prefixed grant by construction. A global search needs its own index and
 * its own path; promotion to global is deferred, so that path is not built. Its
 * absence here is deliberate, not an oversight.
 */
@Component
public class MemoryAccessPolicy {

    private final JdbcTemplate jdbc;

    public MemoryAccessPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What {@code agentId} may read inside {@code goalId}: the goal's shared
     * memory, plus its own private scratch work.
     *
     * <p>Returns no grants at all for a non-member, so a caller looping over
     * grants runs zero queries and can leak nothing. The empty list is the
     * denial - there is no separate "denied" branch for a caller to forget.
     */
    public List<Grant> grantsWithin(UUID agentId, UUID goalId) {
        if (!isActiveMember(agentId, goalId)) {
            return List.of();
        }

        return List.of(
                // Shared goal memory. Both columns are index prefix columns, so
                // this is served entirely from the vector index.
                new Grant("m.goal_id = ? AND m.scope = 'GOAL'",
                        List.of(goalId)),

                // The agent's own private memory. agent_id is a residual filter
                // here, which is acceptable where it was not above: one agent's
                // private memory on one goal is a small bounded set, and the
                // optimiser serves it from memories_agent_scope_idx rather than
                // scanning. Crucially it can only ever narrow to this agent.
                new Grant("m.goal_id = ? AND m.scope = 'PRIVATE' AND m.agent_id = ?",
                        List.of(goalId, agentId)));
    }

    /**
     * Whether an agent is currently an active participant in a goal.
     *
     * <p>"Currently" is the operative word: an agent that has left keeps its
     * identity and its authorship, and loses its access. Agent identity is not
     * the source of truth for goal state - membership is.
     */
    public boolean isActiveMember(UUID agentId, UUID goalId) {
        Boolean member = this.jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM goal_agents
                    WHERE goal_id = ? AND agent_id = ? AND status = 'ACTIVE')
                """, Boolean.class, goalId, agentId);
        return Boolean.TRUE.equals(member);
    }

    /**
     * One slice of what an agent may see, and the parameters it expects.
     *
     * <p>SQL and parameters travel together so a caller cannot bind the
     * parameters of one grant to the SQL of another - the kind of mistake that
     * produces a query which runs, returns rows, and is quietly wrong.
     *
     * <p>The fragment references the {@code memories} table as {@code m}. The
     * first parameter of every grant is the goal, matching the leading index
     * column.
     */
    public record Grant(String sql, List<Object> parameters) {
    }
}
