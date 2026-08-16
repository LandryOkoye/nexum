package com.nexum.memory;

/**
 * Who can see a memory.
 *
 * <p>Three scopes rather than one pool, because a single shared context is what
 * makes multi-agent systems leak: one agent's half-formed reasoning becomes
 * another's premise. The boundary that matters is the <em>goal</em>, not the
 * agent - {@link #GOAL} memory outlives the agent that wrote it and is the whole
 * point of Nexum.
 *
 * <p>Mirrors the {@code memories_scope_valid} CHECK constraint in V3. The
 * database is the enforcement; this enum only keeps the application from
 * inventing a fourth value and failing at insert time.
 */
public enum MemoryScope {

    /** Scratch reasoning. Visible to the originating agent alone. */
    PRIVATE,

    /** Validated discoveries, shared with every active member of the goal. */
    GOAL,

    /** Reusable across missions. Reachable only by explicit, audited promotion. */
    GLOBAL

}
