package com.nexum.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * A single remembered claim, as read back from the store.
 *
 * <p>Carries {@code embeddingStatus} deliberately. A memory is durable the
 * moment it is written, but only <em>semantically</em> reachable once a vector
 * exists for it, and the gap between the two is visible in the demo: a judge
 * watching memories appear as PENDING and turn READY is watching the async
 * embedding path work rather than being told it exists.
 *
 * <p>The vector itself is absent by design. Nothing above the repository has any
 * use for 1024 floats, and reading them into every row would make retrieval
 * needlessly expensive.
 */
public record Memory(UUID id, UUID goalId, UUID agentId, UUID taskId, MemoryScope scope,
        MemoryType type, String content, String source, double confidence,
        String embeddingStatus, Instant createdAt) {

    public boolean embedded() {
        return "READY".equals(this.embeddingStatus);
    }
}
