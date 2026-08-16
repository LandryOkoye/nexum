package com.nexum.memory;

import java.util.List;
import java.util.UUID;

/**
 * A memory an agent is proposing to write, before the backend has judged it.
 *
 * <p>Separate from {@link Memory} because the two are not the same thing: this
 * is a <em>request</em>, carrying a confidence the model asserted about its own
 * work. {@link MemoryService} decides what is actually stored. Collapsing the
 * two types would make it easy to persist a model's self-report unexamined,
 * which is precisely the failure this design guards against.
 */
public record NewMemory(UUID goalId, UUID agentId, UUID taskId, MemoryScope scope,
        MemoryType type, String content, String source, double confidence,
        List<Evidence> evidence) {

    public NewMemory {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("a memory with no content is not a memory");
        }
        // Invariant 3, restated in Java so the failure names itself here rather
        // than arriving as a CHECK-constraint violation from the driver.
        if (scope == MemoryScope.PRIVATE && agentId == null) {
            throw new IllegalArgumentException("PRIVATE memory must name its owning agent");
        }
        if (scope != MemoryScope.GLOBAL && goalId == null) {
            throw new IllegalArgumentException(scope + " memory must belong to a goal");
        }
        evidence = (evidence != null) ? List.copyOf(evidence) : List.of();
    }

    /** Supporting material for a claim. Its presence is what lifts confidence. */
    public record Evidence(String sourceType, String sourceRef, String excerpt,
            double confidence) {
    }
}
