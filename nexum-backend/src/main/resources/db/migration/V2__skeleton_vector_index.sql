-- Distributed vector index (C-SPANN).
--
-- REQUIRES CockroachDB v25.4+. Vector indexing shipped in 25.2, but that release
-- only implemented the default vector_l2_ops operator class; vector_cosine_ops
-- fails there with "unimplemented: operator class vector_cosine_ops is not
-- supported". 25.4 supports vector_l2_ops, vector_cosine_ops and vector_ip_ops.
--
-- Older clusters also gate the feature behind a setting (docker compose handles
-- this; on Cloud run scripts/cockroach-init.sql once as admin):
--     SET CLUSTER SETTING feature.vector_index.enabled = true;
--
-- Cosine distance (<=>) is the right operator class for semantic text
-- similarity - we care about direction, not magnitude. The query in
-- VectorProbe.nearest() MUST use <=> to match, or it silently skips the index.

CREATE VECTOR INDEX IF NOT EXISTS skeleton_probe_embedding_idx
    ON skeleton_probe (embedding vector_cosine_ops);
