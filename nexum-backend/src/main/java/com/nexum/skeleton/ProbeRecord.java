package com.nexum.skeleton;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA view of {@code skeleton_probe}.
 *
 * <p>Note what is <em>absent</em>: the {@code embedding} column. Hibernate has no
 * mapping for CockroachDB's {@code VECTOR} type, so vector reads and writes go
 * through {@link VectorProbe} with plain JDBC instead. Hibernate's schema
 * validation ignores table columns that no entity maps, so the two coexist
 * cleanly. Keep this split in the real domain model too.
 */
@Entity
@Table(name = "skeleton_probe")
public class ProbeRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ProbeRecord() {
        // for JPA
    }

    public ProbeRecord(UUID id, String label) {
        this.id = id;
        this.label = label;
    }

    public UUID getId() {
        return this.id;
    }

    public String getLabel() {
        return this.label;
    }

    public String getClaimedBy() {
        return this.claimedBy;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }
}
