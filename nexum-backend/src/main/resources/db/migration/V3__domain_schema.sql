-- Nexum core domain schema.
--
-- Design notes that matter:
--
--  * Agent is IDENTITY ONLY - it has no liveness column. An agent can
--    participate in several goals at once, so a single Agent.status could never
--    be correct. Liveness belongs to agent_runs, one row per execution instance.
--    This is what makes "recovery is not resurrection" structural rather than
--    just documented: a run dies, the identity is irrelevant to recovery.
--
--  * tasks carries a LEASE (lease_holder_run_id + lease_expires_at). A worker
--    holds a task only while it keeps heartbeating. When it stops, the lease
--    expires and the reaper orphans the task. Failure is DETECTED, not declared.
--
--  * memories.embedding is nullable with an explicit embedding_status, so a
--    memory write never depends on the embedding provider being reachable.

-- ---------------------------------------------------------------- goals ----

CREATE TABLE IF NOT EXISTS goals (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title        TEXT        NOT NULL,
    description  TEXT,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT goals_status_valid CHECK (
        status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'FAILED', 'ARCHIVED'))
);

-- --------------------------------------------------------------- agents ----

-- Logical identity. Deliberately has no status/liveness column.
CREATE TABLE IF NOT EXISTS agents (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT        NOT NULL,
    role         TEXT        NOT NULL,
    capabilities TEXT[]      NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT agents_role_valid CHECK (
        role IN ('RESEARCHER', 'ANALYST', 'PLANNER', 'STRATEGIST',
                 'REVIEWER', 'EXECUTOR', 'SUPERVISOR'))
);

-- Membership. Memory sharing is scoped by THIS, not by global agent visibility.
CREATE TABLE IF NOT EXISTS goal_agents (
    goal_id   UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    agent_id  UUID        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    role      TEXT        NOT NULL,
    status    TEXT        NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at   TIMESTAMPTZ,
    PRIMARY KEY (goal_id, agent_id),
    CONSTRAINT goal_agents_status_valid CHECK (status IN ('ACTIVE', 'LEFT'))
);

CREATE INDEX IF NOT EXISTS goal_agents_agent_idx ON goal_agents (agent_id);

-- ---------------------------------------------------------------- tasks ----

CREATE TABLE IF NOT EXISTS tasks (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id       UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    title         TEXT        NOT NULL,
    description   TEXT,
    status        TEXT        NOT NULL DEFAULT 'AVAILABLE',
    priority      INT         NOT NULL DEFAULT 0,
    attempt_count INT         NOT NULL DEFAULT 0,
    -- Bounded autonomy: an agent loop exits on COMPLETED or step exhaustion.
    max_steps     INT         NOT NULL DEFAULT 6,
    -- Lease. Not a FK to agent_runs: a run references its task, so a FK both
    -- ways would force insert ordering for no benefit.
    lease_run_id     UUID,
    lease_expires_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    CONSTRAINT tasks_status_valid CHECK (
        status IN ('AVAILABLE', 'RUNNING', 'BLOCKED', 'ORPHANED',
                   'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS tasks_goal_status_idx ON tasks (goal_id, status);
-- Drives the reaper sweep: find RUNNING tasks whose lease has lapsed.
CREATE INDEX IF NOT EXISTS tasks_lease_idx ON tasks (status, lease_expires_at);

-- ----------------------------------------------------------- agent runs ----

-- One ephemeral execution instance. This is what dies.
CREATE TABLE IF NOT EXISTS agent_runs (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id           UUID        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    goal_id            UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    task_id            UUID        REFERENCES tasks (id) ON DELETE SET NULL,
    status             TEXT        NOT NULL DEFAULT 'RUNNING',
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at           TIMESTAMPTZ,
    termination_reason TEXT,
    CONSTRAINT agent_runs_status_valid CHECK (
        status IN ('RUNNING', 'COMPLETED', 'DEAD'))
);

CREATE INDEX IF NOT EXISTS agent_runs_task_idx ON agent_runs (task_id);
CREATE INDEX IF NOT EXISTS agent_runs_goal_status_idx ON agent_runs (goal_id, status);

-- ---------------------------------------------------------- checkpoints ----

-- Append-only. Restore = highest seq for the task. Never updated in place:
-- an in-place version column invites lost updates under concurrent writes.
CREATE TABLE IF NOT EXISTS checkpoints (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id          UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    goal_id          UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    run_id           UUID        NOT NULL,
    agent_id         UUID        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    seq              INT         NOT NULL,
    progress_summary TEXT,
    pending_actions  TEXT,
    current_context  JSONB       NOT NULL DEFAULT '{}'::JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, seq)
);

-- ------------------------------------------------------------- memories ----

CREATE TABLE IF NOT EXISTS memories (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id     UUID        REFERENCES goals (id) ON DELETE CASCADE,
    agent_id    UUID        REFERENCES agents (id) ON DELETE SET NULL,
    task_id     UUID        REFERENCES tasks (id) ON DELETE SET NULL,
    scope       TEXT        NOT NULL,
    memory_type TEXT        NOT NULL,
    content     TEXT        NOT NULL,
    source      TEXT,
    confidence  DECIMAL(3, 2) NOT NULL DEFAULT 0.50,
    -- Nullable by design. A memory write must never depend on the embedding
    -- provider being up; a background worker fills these in.
    embedding        VECTOR(1024),
    embedding_status TEXT   NOT NULL DEFAULT 'PENDING',
    supersedes_id    UUID   REFERENCES memories (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT memories_scope_valid CHECK (scope IN ('PRIVATE', 'GOAL', 'GLOBAL')),
    CONSTRAINT memories_type_valid CHECK (
        memory_type IN ('FACT', 'OBSERVATION', 'DECISION', 'LESSON',
                        'HYPOTHESIS', 'OUTCOME', 'SUMMARY')),
    CONSTRAINT memories_embedding_status_valid CHECK (
        embedding_status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT memories_confidence_range CHECK (confidence >= 0 AND confidence <= 1),
    -- Invariant 2: a goal-scoped memory belongs to exactly one goal.
    -- Only GLOBAL memories may be goal-less.
    CONSTRAINT memories_goal_required CHECK (
        (scope = 'GLOBAL' AND goal_id IS NULL) OR
        (scope <> 'GLOBAL' AND goal_id IS NOT NULL)),
    -- Invariant 3: private memory must name its owning agent.
    CONSTRAINT memories_private_has_agent CHECK (
        scope <> 'PRIVATE' OR agent_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS memories_goal_scope_idx ON memories (goal_id, scope);
CREATE INDEX IF NOT EXISTS memories_agent_scope_idx ON memories (agent_id, scope);
-- Drives the async embedding worker.
CREATE INDEX IF NOT EXISTS memories_embedding_status_idx ON memories (embedding_status);

CREATE TABLE IF NOT EXISTS memory_evidence (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    memory_id     UUID        NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    source_type   TEXT        NOT NULL,
    source_ref    TEXT,
    excerpt       TEXT,
    confidence    DECIMAL(3, 2) NOT NULL DEFAULT 0.50,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS memory_evidence_memory_idx ON memory_evidence (memory_id);

CREATE TABLE IF NOT EXISTS memory_relationships (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_memory_id  UUID        NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    target_memory_id  UUID        NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    relationship_type TEXT        NOT NULL,
    confidence        DECIMAL(3, 2) NOT NULL DEFAULT 0.50,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT memory_rel_type_valid CHECK (
        relationship_type IN ('SUPPORTS', 'CONTRADICTS', 'REFINES',
                              'SUPERSEDES', 'DERIVED_FROM', 'RELATED_TO'))
);

-- Audit trail for PRIVATE -> GOAL -> GLOBAL promotion. Append-only.
CREATE TABLE IF NOT EXISTS memory_promotions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    memory_id   UUID        NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    from_scope  TEXT        NOT NULL,
    to_scope    TEXT        NOT NULL,
    promoted_by UUID,
    reason      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------ decisions ----

CREATE TABLE IF NOT EXISTS decisions (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id    UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    agent_id   UUID        REFERENCES agents (id) ON DELETE SET NULL,
    task_id    UUID        REFERENCES tasks (id) ON DELETE SET NULL,
    run_id     UUID,
    decision   TEXT        NOT NULL,
    reason     TEXT,
    confidence DECIMAL(3, 2) NOT NULL DEFAULT 0.50,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS decisions_goal_idx ON decisions (goal_id, created_at);

-- ------------------------------------------------------------- failures ----

CREATE TABLE IF NOT EXISTS agent_failures (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id             UUID        NOT NULL,
    agent_id           UUID        REFERENCES agents (id) ON DELETE SET NULL,
    goal_id            UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    task_id            UUID        REFERENCES tasks (id) ON DELETE SET NULL,
    failure_type       TEXT        NOT NULL,
    error_message      TEXT,
    last_checkpoint_id UUID        REFERENCES checkpoints (id) ON DELETE SET NULL,
    recovery_status    TEXT        NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT failure_type_valid CHECK (
        failure_type IN ('PROCESS_CRASH', 'LEASE_EXPIRED', 'TIMEOUT',
                         'TOOL_FAILURE', 'MODEL_FAILURE', 'INFRASTRUCTURE_FAILURE')),
    CONSTRAINT recovery_status_valid CHECK (
        recovery_status IN ('PENDING', 'RECOVERING', 'RECOVERED', 'ABANDONED'))
);

-- --------------------------------------------------------------- events ----

-- Append-only event log. SSE is served FROM this, not instead of it, so a
-- browser reconnect mid-demo can replay rather than losing the narrative.
CREATE TABLE IF NOT EXISTS events (
    seq        SERIAL      PRIMARY KEY,
    goal_id    UUID        REFERENCES goals (id) ON DELETE CASCADE,
    event_type TEXT        NOT NULL,
    payload    JSONB       NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS events_goal_seq_idx ON events (goal_id, seq);
