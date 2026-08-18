package com.nexum.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nexum.event.EventLog;
import com.nexum.event.EventType;
import com.nexum.support.CockroachRetry;
import com.nexum.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * What agents actually call to remember and to recall.
 *
 * <p>Two jobs, both of which exist to keep the model from being trusted with
 * something it should not be: the backend decides what a claim is worth
 * (§{@link #effectiveConfidence}), and the backend decides what an agent is
 * allowed to see ({@link MemoryAccessPolicy}). The LLM proposes; this class
 * persists. No agent is given a SQL tool, so this is the only door.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /**
     * The most an unevidenced claim may be worth, however sure the model says
     * it is.
     *
     * <p>Models report their own confidence and are systematically bad at it -
     * fluent invention reads as certainty. Left alone, a fabricated "fact"
     * asserted at 0.95 outranks a carefully evidenced observation at 0.8 in
     * every structured retrieval, and a later agent - one that never saw where
     * the claim came from - builds on it. Capping at the schema's neutral 0.50
     * means an unsupported claim can be remembered but cannot outrank supported
     * ones. Evidence, not assertion, is what buys confidence here.
     */
    private static final double UNEVIDENCED_CEILING = 0.50;

    private final MemoryRepository memories;
    private final MemoryWriter writer;
    private final EventLog events;
    private final CockroachRetry retry;
    private final QueryEmbedder embedder;

    public MemoryService(MemoryRepository memories, MemoryWriter writer, EventLog events,
            CockroachRetry retry, QueryEmbedder embedder) {
        this.memories = memories;
        this.writer = writer;
        this.events = events;
        this.retry = retry;
        this.embedder = embedder;
    }

    /**
     * Records a memory. Returns as soon as it is durable - the vector follows
     * asynchronously, and is never on this path.
     *
     * <p>The retry wraps the outside of the transaction, not the inside:
     * concurrent goal-memory writes are the normal case here, several agents on
     * one goal is the entire premise, and CockroachDB aborts conflicting
     * SERIALIZABLE transactions rather than making them wait.
     */
    public UUID remember(NewMemory proposed) {
        double confidence = effectiveConfidence(proposed);

        UUID id = this.retry.execute("remember",
                () -> this.writer.write(proposed, confidence));

        // Emitted after the commit, deliberately. An event announcing a memory
        // that then rolled back would put a claim in the timeline that is not
        // in the database.
        this.events.append(proposed.goalId(), EventType.MEMORY_CREATED,
                Json.object("memoryId", id, "agentId", proposed.agentId(),
                        "taskId", proposed.taskId(), "scope", proposed.scope(),
                        "type", proposed.type(), "confidence", confidence,
                        "evidenceCount", proposed.evidence().size(),
                        "content", proposed.content()));

        return id;
    }

    /**
     * Retrieves what this agent may see on this goal, best matches first.
     *
     * <p>Prefers semantic ranking and falls back to the structured path when no
     * vector is available - no embedding provider configured, the provider is
     * down, or nothing on the goal has been embedded yet. The caller is told
     * which strategy ran rather than being left to assume; a demo that showed
     * recency-ordered rows while claiming vector search would be a lie told
     * accidentally.
     */
    public Recall recall(UUID agentId, UUID goalId, String query, int limit) {
        Optional<float[]> vector = this.embedder.embed(query);

        if (vector.isPresent()) {
            List<ScoredMemory> hits =
                    this.memories.searchSemantic(agentId, goalId, vector.get(), limit);
            if (!hits.isEmpty()) {
                return record(agentId, goalId, new Recall(Recall.Strategy.SEMANTIC, hits));
            }
        }

        List<ScoredMemory> hits = this.memories.searchRecent(agentId, goalId, limit).stream()
                .map(ScoredMemory::unranked)
                .toList();
        return record(agentId, goalId, new Recall(Recall.Strategy.STRUCTURED, hits));
    }

    /**
     * Caps the confidence of a claim with nothing behind it.
     *
     * <p>Also clamps to the range the schema permits: a model asked for a number
     * between 0 and 1 will occasionally answer 95, and a CHECK-constraint
     * violation would fail a memory write for what is really a formatting
     * mistake.
     */
    static double effectiveConfidence(NewMemory proposed) {
        double clamped = Math.clamp(proposed.confidence(), 0.0, 1.0);
        return proposed.evidence().isEmpty() ? Math.min(clamped, UNEVIDENCED_CEILING) : clamped;
    }

    private Recall record(UUID agentId, UUID goalId, Recall recall) {
        this.events.append(goalId, EventType.MEMORY_RETRIEVED,
                Json.object("agentId", agentId, "strategy", recall.strategy(),
                        "resultCount", recall.memories().size()));
        return recall;
    }

    /**
     * Retrieval results, labelled with how they were ranked.
     */
    public record Recall(Strategy strategy, List<ScoredMemory> memories) {

        public enum Strategy {

            /** Cosine ranking over the goal-prefixed vector index. */
            SEMANTIC,

            /** Confidence and recency, used while vectors are missing. */
            STRUCTURED

        }
    }
}
