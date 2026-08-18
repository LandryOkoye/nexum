package com.nexum.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns a search string into a vector, once.
 *
 * <p>Exists because retrieval and the explanation of retrieval both need the
 * same vector for the same query, and were each embedding it separately -
 * doubling the cost of the slowest step in the whole path and, worse, leaving
 * open the possibility of the dashboard explaining a plan for a slightly
 * different vector than the one that fetched the rows.
 *
 * <p><strong>The cache is the point.</strong> Embedding is deterministic for a
 * given model and text, so a repeated query has exactly one correct answer and
 * recomputing it buys nothing. That matters more than it sounds: measured
 * against a local Ollama on CPU, one embedding call takes about seven seconds,
 * against sixteen milliseconds for the vector search it feeds. Retrieval in this
 * system is not slow because of CockroachDB - it is slow because of the model in
 * front of it, and the cache is what keeps a demo of the former from being a
 * demonstration of the latter. Deployed against Bedrock the call is a couple of
 * hundred milliseconds and the cache is merely polite.
 *
 * <p>Bounded, because an unbounded map keyed by user input on a long-running
 * process is a memory leak waiting for a bored visitor.
 */
@Component
public class QueryEmbedder {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbedder.class);

    private static final int CACHE_LIMIT = 512;

    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final Map<String, float[]> cache;

    public QueryEmbedder(ObjectProvider<EmbeddingModel> embeddingModels) {
        this.embeddingModels = embeddingModels;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > CACHE_LIMIT;
            }
        });
    }

    /**
     * The query as a vector, or empty when no provider is available.
     *
     * <p>Empty rather than an exception: a caller that cannot embed should fall
     * back to structured retrieval, not fail. An agent that cannot reach the
     * embedding provider must still see its collective's work.
     */
    public Optional<float[]> embed(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        String key = query.strip().toLowerCase();
        float[] cached = this.cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        EmbeddingModel model = this.embeddingModels.getIfAvailable();
        if (model == null) {
            return Optional.empty();
        }

        try {
            float[] vector = model.embed(query);
            this.cache.put(key, vector);
            return Optional.of(vector);
        }
        catch (RuntimeException ex) {
            log.warn("Could not embed query [{}]; caller will fall back to structured "
                    + "retrieval: {}", query, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Whether an embedding provider is configured at all, for reporting. */
    public boolean available() {
        return this.embeddingModels.getIfAvailable() != null;
    }
}
