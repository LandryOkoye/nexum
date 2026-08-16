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
            for (MemoryRepository.Pending pending : batch) {
                embedOne(model, pending);
            }
        }
        catch (RuntimeException ex) {
            // Never let a sweep kill the scheduler thread; that would stop all
            // future embedding silently.
            log.error("Embedding sweep failed; will retry next interval", ex);
        }
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
            float[] vector = model.embed(pending.content());

            if (vector.length != this.expectedDimensions) {
                log.error("Embedding provider returned {} dimensions but the schema declares "
                        + "VECTOR({}). Memory {} cannot be embedded - the provider and the "
                        + "schema must agree.", vector.length, this.expectedDimensions,
                        pending.id());
                this.memories.markEmbeddingFailed(pending.id());
                return;
            }

            this.memories.attachEmbedding(pending.id(), vector);
        }
        catch (RuntimeException ex) {
            log.warn("Could not embed memory {}; leaving it PENDING for the next sweep",
                    pending.id(), ex);
        }
    }
}
