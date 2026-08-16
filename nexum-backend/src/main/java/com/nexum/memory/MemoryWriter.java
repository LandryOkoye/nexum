package com.nexum.memory;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of a memory write.
 *
 * <p>Its own bean for the same reason {@code LeaseReclaimer} is: a
 * {@code @Transactional} method invoked from another method of the same bean
 * never crosses the proxy, and would run with no transaction at all - silently.
 *
 * <p>The unit being protected is "a claim and the evidence for it". Storing the
 * claim without its evidence would leave a memory that reads as unsupported when
 * it is not, and the confidence recorded against it was calculated <em>on the
 * assumption that the evidence exists</em>. Half of this write is worse than
 * none of it.
 */
@Component
class MemoryWriter {

    private final MemoryRepository memories;

    MemoryWriter(MemoryRepository memories) {
        this.memories = memories;
    }

    @Transactional
    UUID write(NewMemory proposed, double effectiveConfidence) {
        UUID id = this.memories.create(proposed, effectiveConfidence);
        this.memories.addEvidence(id, proposed.evidence());
        return id;
    }
}
