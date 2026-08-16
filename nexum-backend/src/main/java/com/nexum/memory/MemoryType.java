package com.nexum.memory;

/**
 * What kind of claim a memory makes.
 *
 * <p>The type is not decoration: it is how a later agent judges what it found.
 * A {@code HYPOTHESIS} retrieved from goal memory must not be acted on the way a
 * {@code FACT} backed by evidence is, and an agent that cannot tell them apart
 * will confidently build on someone else's guess.
 *
 * <p>Mirrors the {@code memories_type_valid} CHECK constraint in V3.
 */
public enum MemoryType {

    FACT,
    OBSERVATION,
    DECISION,
    LESSON,
    HYPOTHESIS,
    OUTCOME,
    SUMMARY

}
