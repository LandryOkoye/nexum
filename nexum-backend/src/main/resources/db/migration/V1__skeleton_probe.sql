-- Walking-skeleton table. Its only job is to prove, end to end, that:
--   * Flyway can migrate a CockroachDB cluster
--   * Hibernate's CockroachDialect can read/write a normal relational row
--   * the VECTOR type round-trips through the PostgreSQL JDBC driver
--   * a conditional UPDATE yields exactly one winner under contention
--     (a dry run of the task-claim primitive in Invariant 1)
--
-- This table is disposable. It is replaced by the real domain schema in V3.

CREATE TABLE IF NOT EXISTS skeleton_probe (
    id         UUID        PRIMARY KEY,
    label      TEXT        NOT NULL,
    claimed_by TEXT,
    embedding  VECTOR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
