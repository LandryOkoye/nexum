package com.nexum.memory;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fills in the vectors that memory writes deliberately do not wait for.
 *
 * <p>A memory is written with {@code embedding_status = 'PENDING'} and no
 * vector, and this sweep gives it one later. That ordering is a design position,
 * not an optimisation: an agent that discovers something while Bedrock is
 * unreachable must still be able to record it. Making embedding synchronous
 * would turn a third party's outage into amnesia for the collective, and would
 * put a network call on the one path that most needs to be reliable.
 *
 * <p>A {@code @Scheduled} poller rather than a queue. The spec's non-goals rule
 * out Kafka and Redis, and correctly: the work is idempotent, the backlog lives
 * in a column that is already durable, and a restart resumes simply by finding
 * the same PENDING rows again.
 */
@Component
public class EmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingWorker.class);

    private final MemoryRepository memories;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final boolean enabled;
    private final int batchSize;
    private final int expectedDimensions;

    public EmbeddingWorker(MemoryRepository memories, ObjectProvider<EmbeddingModel> embeddingModels,
            @Value("${nexum.embedding.enabled:true}") boolean enabled,
            @Value("${nexum.embedding.batch-size:20}") int batchSize,
            @Value("${nexum.embedding.dimensions:1024}") int expectedDimensions) {
        this.memories = memories;
        this.embeddingModels = embeddingModels;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.expectedDimensions = expectedDimensions;
    }

    @Scheduled(fixedDelayString = "${nexum.embedding.interval-ms:2000}",
            initialDelayString = "${nexum.embedding.interval-ms:2000}")
    public void embedPending() {
        if (!this.enabled) {
            return;
        }

        EmbeddingModel model = this.embeddingModels.getIfAvailable();
        if (model == null) {
            // No provider configured. Memories stay PENDING and remain fully
            // readable through the structured path - the system is degraded,
            // not broken, and recovers by itself once a provider appears.
            return;
        }

        try {
            List<MemoryRepository.Pending> batch = this.memories.findPending(this.batchSize);
            if (batch.isEmpty()) {
                return;
            }
            if (!embedAsBatch(model, batch)) {
                for (MemoryRepository.Pending pending : batch) {
                    embedOne(model, pending);
                }
            }

            // The backlog just drained. Refresh statistics once here rather than
            // after every sweep: this is the moment the vector index finished
            // gaining rows, and it is the last moment before someone queries it.
            if (this.memories.findPending(1).isEmpty()) {
                this.memories.refreshStatistics();
                log.debug("Embedding backlog drained; refreshed statistics on memories");
            }
        }
        catch (RuntimeException ex) {
            // Never let a sweep kill the scheduler thread; that would stop all
            // future embedding silently.
            log.error("Embedding sweep failed; will retry next interval", ex);
        }
    }

    /**
     * Embeds the whole batch in one provider call, returning false if that call
     * could not be used.
     *
     * <p>One request instead of {@code batchSize} of them, because the per-call
     * overhead dominates: measured against a local Ollama, embedding seventy
     * memories one at a time took over three minutes, which is longer than the
     * entire demo it is meant to support. The same work batched is a handful of
     * seconds.
     *
     * <p>Returning false rather than throwing keeps the guarantee the per-memory
     * path was written for. A batch call fails as a unit, so a single unembeddable
     * row would take nineteen healthy ones down with it and they would all be
     * retried together forever. When the batch fails for any reason the caller
     * falls back to embedding one at a time, where a bad row can be identified
     * and marked - so the fast path is an optimisation over the correct path, not
     * a replacement for it.
     */
    private boolean embedAsBatch(EmbeddingModel model, List<MemoryRepository.Pending> batch) {
        List<float[]> vectors;
        try {
            vectors = model.embed(batch.stream().map(MemoryRepository.Pending::content).toList());
        }
        catch (RuntimeException ex) {
            log.warn("Batch embedding of {} memories failed; falling back to one at a time",
                    batch.size(), ex);
            return false;
        }

        // A provider that returns a different number of vectors than it was given
        // has broken the positional contract this method relies on to match each
        // vector to its memory. Attaching them anyway would give memories other
        // memories' meanings - silent, permanent, and undetectable from the
        // outside. Fall back rather than guess.
        if (vectors == null || vectors.size() != batch.size()) {
            log.warn("Batch embedding returned {} vectors for {} memories; falling back",
                    (vectors != null) ? vectors.size() : null, batch.size());
            return false;
        }

        for (int i = 0; i < batch.size(); i++) {
            attach(batch.get(i), vectors.get(i));
        }
        return true;
    }

    /** Stores one vector, or marks the memory unembeddable if it is the wrong width. */
    private void attach(MemoryRepository.Pending pending, float[] vector) {
        if (vector == null || vector.length != this.expectedDimensions) {
            log.error("Embedding provider returned {} dimensions but the schema declares "
                    + "VECTOR({}). Memory {} cannot be embedded - the provider and the "
                    + "schema must agree.", (vector != null) ? vector.length : null,
                    this.expectedDimensions, pending.id());
            this.memories.markEmbeddingFailed(pending.id());
            return;
        }
        this.memories.attachEmbedding(pending.id(), vector);
    }

    /**
     * Embeds one memory, isolating its failure from the rest of the batch.
     *
     * <p>A transient failure leaves the row PENDING to be retried, rather than
     * marking it FAILED: a five-minute Bedrock outage must not permanently cost
     * those memories their place in vector space. Only a dimension mismatch is
     * treated as terminal, because retrying it will never help - and it is worth
     * failing loudly, since a vector of the wrong width is the one error that
     * would otherwise surface much later as an unexplainable insert error.
     *
     * <p>Because the batch is ordered by creation time, a memory that always
     * fails is retried each sweep but never blocks the ones behind it.
     */
    private void embedOne(EmbeddingModel model, MemoryRepository.Pending pending) {
        try {
            attach(pending, model.embed(pending.content()));
        }
        catch (RuntimeException ex) {
            log.warn("Could not embed memory {}; leaving it PENDING for the next sweep",
                    pending.id(), ex);
        }
    }
}
