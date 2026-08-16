package com.nexum.memory;

/**
 * A memory with the cosine distance that retrieved it.
 *
 * <p>The distance is carried out of the repository rather than discarded so the
 * dashboard can show <em>why</em> a result came back. "Agent D retrieved this"
 * is a claim; "Agent D retrieved this at 0.11 distance from its query, written
 * by an agent that no longer exists" is evidence.
 */
public record ScoredMemory(Memory memory, double distance) {

    /** Cosine distance runs 0 (identical) to 2 (opposed); similarity is friendlier to read. */
    public double similarity() {
        return 1.0 - this.distance;
    }
}
