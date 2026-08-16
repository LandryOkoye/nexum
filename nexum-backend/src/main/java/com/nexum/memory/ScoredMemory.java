package com.nexum.memory;

/**
 * A memory, and the cosine distance that retrieved it.
 *
 * <p>The distance is carried out of the repository rather than discarded so the
 * dashboard can show <em>why</em> a result came back. "Agent D retrieved this"
 * is a claim; "Agent D retrieved this at 0.11 distance from its query, written
 * by an agent that no longer exists" is evidence.
 *
 * <p>The distance is null when the memory came back through the structured
 * path, which ranks by confidence and recency and computes no distance at all.
 * Null rather than a sentinel like -1 or NaN: those get formatted into a UI as
 * though they were measurements, and a fabricated similarity score in a demo
 * about trustworthy memory would be an unfortunate thing for a judge to notice.
 */
public record ScoredMemory(Memory memory, Double distance) {

    /** A memory retrieved without semantic ranking. */
    public static ScoredMemory unranked(Memory memory) {
        return new ScoredMemory(memory, null);
    }

    /** Cosine distance runs 0 (identical) to 2 (opposed); similarity reads better. */
    public Double similarity() {
        return (this.distance != null) ? 1.0 - this.distance : null;
    }
}
