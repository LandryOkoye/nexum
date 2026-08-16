package com.nexum.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * All SQL over {@code memories}, and the only place it is written.
 *
 * <p>Every agent-facing read here derives its {@code WHERE} clause from
 * {@link MemoryAccessPolicy} rather than composing one locally. That is the
 * mechanical reason the isolation guarantee holds: there is no second query for
 * a reviewer to miss, because there is no second query.
 *
 * <p>Hand-written JDBC, not JPA, for the reason given in the handoff - Hibernate
 * has no mapping for CockroachDB's {@code VECTOR}, and a partially-mapped entity
 * that silently omits the column is worse than no entity at all.
 */
@Repository
public class MemoryRepository {

    /**
     * Columns shared by both retrieval paths, so semantic and structured results
     * are literally the same shape. A judge comparing them is then comparing
     * ranking strategies, not two different views of memory.
     */
    private static final String COLUMNS = """
            m.id, m.goal_id, m.agent_id, m.task_id, m.scope, m.memory_type,
            m.content, m.source, m.confidence, m.embedding_status, m.created_at""";

    private final JdbcTemplate jdbc;
    private final MemoryAccessPolicy policy;

    public MemoryRepository(JdbcTemplate jdbc, MemoryAccessPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    // --- writes ----------------------------------------------------------

    /**
     * Persists a memory with no embedding. The vector arrives later, from
     * {@link EmbeddingWorker}.
     *
     * <p>This is the single most important scheduling decision in the memory
     * layer: writing a memory cannot be made to wait on Bedrock being reachable.
     * An agent that discovers something while the embedding provider is down
     * must still be able to record it, or an outage in a third party becomes
     * amnesia in the collective.
     */
    public UUID create(NewMemory memory, double effectiveConfidence) {
        return this.jdbc.queryForObject("""
                INSERT INTO memories
                    (goal_id, agent_id, task_id, scope, memory_type, content,
                     source, confidence, embedding_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                RETURNING id
                """, UUID.class,
                memory.goalId(), memory.agentId(), memory.taskId(),
                memory.scope().name(), memory.type().name(), memory.content(),
                memory.source(), effectiveConfidence);
    }

    public void addEvidence(UUID memoryId, List<NewMemory.Evidence> evidence) {
        for (NewMemory.Evidence item : evidence) {
            this.jdbc.update("""
                    INSERT INTO memory_evidence
                        (memory_id, source_type, source_ref, excerpt, confidence)
                    VALUES (?, ?, ?, ?, ?)
                    """, memoryId, item.sourceType(), item.sourceRef(), item.excerpt(),
                    item.confidence());
        }
    }

    // --- agent-facing reads ----------------------------------------------

    /**
     * Semantic retrieval: what this agent may see, ranked by meaning.
     *
     * <p>Runs one search per grant rather than one query with a combined
     * predicate. That is what keeps each search on a vector index prefix - an
     * {@code OR} across scopes would be a residual filter, and the optimiser
     * answers those with a full scan and a sort. Two indexed top-k searches over
     * a few hundred vectors beat one scan of every memory on the goal, and more
     * importantly the engine only ever ranks rows this agent is entitled to.
     *
     * <p>{@code <=>} is cosine distance, matching the {@code vector_cosine_ops}
     * class the indexes were built with. A different operator would still return
     * rows - just from a full scan, silently discarding the index this whole
     * design is an argument for.
     *
     * <p>Each grant is asked for the full limit because the merge cannot know in
     * advance which scope holds the best matches; taking {@code limit} from the
     * union afterwards is what makes the result identical to what a single
     * ranked query would have returned.
     */
    public List<ScoredMemory> searchSemantic(UUID agentId, UUID goalId, float[] query, int limit) {
        String vector = toLiteral(query);
        Map<UUID, ScoredMemory> merged = new LinkedHashMap<>();

        for (MemoryAccessPolicy.Grant grant : this.policy.grantsWithin(agentId, goalId)) {
            List<Object> args = new ArrayList<>();
            args.add(vector);
            args.addAll(grant.parameters());
            args.add(limit);

            List<ScoredMemory> hits = this.jdbc.query("""
                    SELECT %s, m.embedding <=> ?::VECTOR AS distance
                    FROM memories m
                    WHERE %s
                    ORDER BY distance
                    LIMIT ?
                    """.formatted(COLUMNS, grant.sql()),
                    (rs, rowNum) -> new ScoredMemory(MAPPER.mapRow(rs, rowNum),
                            rs.getDouble("distance")),
                    args.toArray());

            for (ScoredMemory hit : hits) {
                // A memory with no vector yet has no position to be ranked from;
                // whatever distance the engine reported for a NULL embedding is
                // not a similarity. Dropped here rather than as a SQL predicate,
                // because embedding_status is not an index prefix column and
                // filtering on it would cost the vector index.
                if (hit.memory().embedded()) {
                    merged.putIfAbsent(hit.memory().id(), hit);
                }
            }
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ScoredMemory::distance))
                .limit(limit)
                .toList();
    }

    /**
     * Structured retrieval over the same visible set, ranked by confidence and
     * recency instead of similarity.
     *
     * <p>Not a lesser fallback but the honest answer to a real state: before any
     * vectors exist - a fresh deployment, a Bedrock outage, the first seconds of
     * a demo - semantic search over zero embedded rows returns nothing, and an
     * agent that retrieves nothing behaves as though the collective knows
     * nothing. This keeps memory useful in that window.
     *
     * <p>Here the grants are combined with {@code OR} into a single query. There
     * is no vector index at stake on this path, so the reason to keep them apart
     * does not apply, and one ordered query gives a correct global ranking.
     */
    public List<Memory> searchRecent(UUID agentId, UUID goalId, int limit) {
        List<MemoryAccessPolicy.Grant> grants = this.policy.grantsWithin(agentId, goalId);
        if (grants.isEmpty()) {
            return List.of();
        }

        StringJoiner predicate = new StringJoiner(") OR (", "(", ")");
        List<Object> args = new ArrayList<>();
        for (MemoryAccessPolicy.Grant grant : grants) {
            predicate.add(grant.sql());
            args.addAll(grant.parameters());
        }
        args.add(limit);

        return this.jdbc.query("""
                SELECT %s
                FROM memories m
                WHERE %s
                ORDER BY m.confidence DESC, m.created_at DESC
                LIMIT ?
                """.formatted(COLUMNS, predicate),
                MAPPER, args.toArray());
    }

    // --- embedding worker ------------------------------------------------

    /**
     * Memories still waiting for a vector.
     *
     * <p>Intentionally unscoped: this is the system embedding its own store, not
     * an agent reading memory. The content it returns goes to the embedding
     * provider and back into the same row - never to another agent - so the
     * access policy has nothing to decide here. Any future caller of this method
     * that hands rows to an agent is a bug.
     */
    public List<Pending> findPending(int limit) {
        return this.jdbc.query("""
                SELECT id, content
                FROM memories
                WHERE embedding_status = 'PENDING'
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, rowNum) -> new Pending(rs.getObject("id", UUID.class),
                        rs.getString("content")),
                limit);
    }

    /** Conditional on still being PENDING, so a duplicate sweep cannot overwrite. */
    public boolean attachEmbedding(UUID id, float[] vector) {
        return this.jdbc.update("""
                UPDATE memories
                SET embedding = ?::VECTOR,
                    embedding_status = 'READY',
                    updated_at = now()
                WHERE id = ? AND embedding_status = 'PENDING'
                """, toLiteral(vector), id) == 1;
    }

    /**
     * Marks a memory as unembeddable. The row keeps its content and stays
     * readable through the structured path - a failed vector must never look
     * like a lost memory.
     */
    public boolean markEmbeddingFailed(UUID id) {
        return this.jdbc.update("""
                UPDATE memories
                SET embedding_status = 'FAILED', updated_at = now()
                WHERE id = ? AND embedding_status = 'PENDING'
                """, id) == 1;
    }

    // --- plumbing --------------------------------------------------------

    /** CockroachDB accepts a vector as a text literal cast to {@code VECTOR}. */
    static String toLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    private static final RowMapper<Memory> MAPPER = (rs, rowNum) -> new Memory(
            rs.getObject("id", UUID.class),
            rs.getObject("goal_id", UUID.class),
            rs.getObject("agent_id", UUID.class),
            rs.getObject("task_id", UUID.class),
            MemoryScope.valueOf(rs.getString("scope")),
            MemoryType.valueOf(rs.getString("memory_type")),
            rs.getString("content"),
            rs.getString("source"),
            rs.getDouble("confidence"),
            rs.getString("embedding_status"),
            rs.getTimestamp("created_at").toInstant());

    /** A memory awaiting its vector: just enough to embed it. */
    public record Pending(UUID id, String content) {
    }
}
