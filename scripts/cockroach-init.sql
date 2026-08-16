-- Run once per cluster, as an admin user.
-- Required before any CREATE VECTOR INDEX statement will succeed.
--
-- Local:  docker compose exec cockroach ./cockroach sql --insecure -f -
-- Cloud:  cockroach sql --url "$NEXUM_DB_URL" -f scripts/cockroach-init.sql

CREATE DATABASE IF NOT EXISTS nexum;

-- Vector indexing (C-SPANN) is gated behind a cluster setting in v25.2.
SET CLUSTER SETTING feature.vector_index.enabled = true;

-- Confirm the cluster is new enough for vector indexing.
SELECT version();
