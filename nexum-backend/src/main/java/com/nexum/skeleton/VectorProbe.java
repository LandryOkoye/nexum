package com.nexum.skeleton;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Vector reads/writes over plain JDBC.
 *
 * <p>CockroachDB accepts a vector as a text literal - {@code '[0.1,0.2,...]'} -
 * cast to {@code VECTOR}. Cosine distance ({@code <=>}) is the operator that
 * matches the {@code vector_cosine_ops} index built in migration V2; using a
 * different operator here would silently bypass the index and fall back to a
 * full scan.
 */
@Repository
public class VectorProbe {

    private final JdbcTemplate jdbc;

    public VectorProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String label, float[] embedding) {
        this.jdbc.update("""
                INSERT INTO skeleton_probe (id, label, embedding)
                VALUES (?, ?, ?::VECTOR)
                """, id, label, toLiteral(embedding));
    }

    /** Nearest neighbours by cosine distance. Lower distance = more similar. */
    public List<Neighbour> nearest(float[] query, int limit) {
        return this.jdbc.query("""
                SELECT label, embedding <=> ?::VECTOR AS distance
                FROM skeleton_probe
                WHERE embedding IS NOT NULL
                ORDER BY distance
                LIMIT ?
                """,
                (rs, rowNum) -> new Neighbour(rs.getString("label"), rs.getDouble("distance")),
                toLiteral(query), limit);
    }

    /**
     * Conditional claim - the same shape as the real task-claim primitive.
     * Returns true only for the caller that actually won the row.
     */
    public boolean tryClaim(UUID id, String claimant) {
        int updated = this.jdbc.update("""
                UPDATE skeleton_probe
                SET claimed_by = ?
                WHERE id = ? AND claimed_by IS NULL
                """, claimant, id);
        return updated == 1;
    }

    public int countMigrations() {
        Integer count = this.jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        return (count != null) ? count : 0;
    }

    public String serverVersion() {
        return this.jdbc.queryForObject("SELECT version()", String.class);
    }

    static String toLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    public record Neighbour(String label, double distance) {
    }
}
