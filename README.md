# Nexum

> **The unit of intelligence isn't the agent. It's the collective pursuing the goal.**

Nexum is a distributed agent runtime and collective-memory system. Agents are
temporary participants in a persistent, goal-scoped cognitive space: an agent can
join a goal, contribute knowledge, fail, and be replaced without the mission
losing what the collective learned.

See [`Nexum_Project_Master_Spec.md`](Nexum_Project_Master_Spec.md) for the full
specification and [`Nexum_Spec_Review.md`](Nexum_Spec_Review.md) for the
engineering review that shaped the current build order.

## Layout

```
nexum/
├── nexum-backend/   Spring Boot control plane (authoritative state + orchestration)
├── frontend/        React/Next.js dashboard
├── infra/           Lambda handler + AWS definitions (stretch)
├── docs/            architecture notes, demo script
└── scripts/         operational helpers
```

## Stack

| Layer | Choice | Why |
|---|---|---|
| Control plane | Java 25 / Spring Boot 4.1 | transactions, validation, concurrency primitives |
| Cognitive substrate | CockroachDB | relational truth *and* distributed vector indexing in one store |
| Reasoning | Groq (OpenAI-compatible API) | fast, accessible inference; provider stays abstracted |
| Embeddings | Bedrock Titan v2 (1024d) / Ollama `mxbai-embed-large` (1024d) | **Groq serves no embeddings** — this is a separate provider by necessity |
| Artifacts | Amazon S3 | large objects; CockroachDB holds metadata + semantic descriptors |
| Ephemeral execution | `AgentDispatcher` → local thread pool, Lambda optional | worker lifetime stays independent of cognition lifetime |

Local and deployed embedding models are both **1024 dimensions** on purpose, so
one schema serves both profiles.

## Running the walking skeleton

The skeleton proves every layer works together before any domain code depends on
it. Run it first, on a new machine or after any dependency bump.

```bash
docker compose up -d
curl -s http://localhost:11434/api/pull -d '{"name":"mxbai-embed-large"}'
export GROQ_API_KEY=...            # optional; that check is skipped without it

./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,skeleton'
```

Expected output:

```
==============================================================================
  NEXUM WALKING SKELETON
==============================================================================
  PASS   cockroach reachable                          12ms  CockroachDB CCL v25.4...
  PASS   flyway migrations applied                     8ms  2 migrations applied
  PASS   jpa insert/read via CockroachDialect         31ms  round-tripped jpa-roundtrip
  PASS   conditional claim: exactly one winner        44ms  1 winner / 8 contenders
  PASS   embedding provider                          210ms  1024 dimensions
  PASS   vector write                                380ms  3 vectors written
  PASS   vector similarity search                     19ms  nearest=pricing-cut ...
  PASS   groq reasoning                              290ms  responded: NEXUM
==============================================================================
  8 passed, 0 failed, 0 skipped
  Stack verified. Safe to build the domain layer on it.
==============================================================================
```

Exit code is non-zero if any check fails.

### What each check protects against

| Check | Assumption it proves |
|---|---|
| cockroach reachable | you are on CockroachDB, not the PostgreSQL you develop against by accident |
| flyway migrations | Flyway can migrate Cockroach (needs `postgresql.transactional-lock: false`) |
| jpa insert/read | `CockroachDialect` maps the entity model correctly |
| conditional claim | Invariant 1 — a task cannot have two owners — holds under real contention |
| embedding provider | a provider exists *and* its dimensions match `VECTOR(1024)` |
| vector write | the `VECTOR` type round-trips through the PostgreSQL JDBC driver |
| vector similarity | cosine ranking is semantically sane and hits the C-SPANN index |
| groq reasoning | Groq's OpenAI-compatible endpoint is reachable with your key |

## Embedding providers

**Bedrock Titan Text Embeddings v2 (1024-dim) is the default** — no profile
needed. Credentials come from the AWS SDK default chain: env vars
(`AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) locally, the EC2
instance role in production.

Offline fallback, only if you have no AWS access:

```bash
./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,ollama,skeleton'
```

Needs the official `ollama/ollama` image. Vectors from Ollama and Bedrock are
**not** interchangeable — do not mix them in one database.

## CockroachDB Cloud

Vector **indexing** (C-SPANN) requires **v25.4+** (25.2 lacks the cosine operator class); the `VECTOR` type alone is
24.2+. Before the first migration, run once as an admin:

```bash
cockroach sql --url "$NEXUM_DB_URL" -f scripts/cockroach-init.sql
```

## Non-negotiables

1. Develop against real CockroachDB, never PostgreSQL.
2. Every contended transaction goes through `CockroachRetry` (SQLSTATE 40001).
3. Scope filtering happens **before** vector ranking, never after.
4. All memory access goes through one `MemoryAccessPolicy` — REST, agent tools, MCP.
5. The LLM never writes authoritative state directly.
