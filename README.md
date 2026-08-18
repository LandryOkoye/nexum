# Nexum

> **The unit of intelligence is not the agent. It is the collective pursuing the goal.**

Nexum is a distributed agent runtime and collective-memory system. It is built
around a simple failure mode that shows up quickly in serious agent systems:
workers disappear, but the mission still needs to continue.

Most agent systems tie memory to the agent process. If that process crashes, is
restarted, times out, or is replaced, the next worker often has to rediscover the
same facts. A global shared memory store does not solve the problem cleanly
either; it creates noisy retrieval, weak isolation, and unclear ownership of
knowledge.

Nexum makes the **goal** the persistent cognitive boundary. Agents are temporary
participants in that goal. They can join, claim tasks, contribute knowledge,
save checkpoints, fail, and be replaced. The goal keeps the memory, the evidence,
the task state, and the decisions. The replacement agent does not impersonate the
dead worker; it joins the same goal, restores the last checkpoint, retrieves the
collective memory, and continues.

That is the core idea:

```text
Weak model:     Agent -> Memory

Nexum model:    Goal -> Collective Memory <- Agents
```

The demo proves one specific thing: **the agent can die and the collective keeps
thinking.**

## Current Demo

The deployed demo is available at:

```text
https://nexumm.duckdns.org
```

The current demo mission is a competitive-intelligence workflow for African
fintech pricing and positioning. Multiple agents join a goal, write goal-scoped
memories with evidence, retrieve each other's findings semantically, and recover
from a killed worker through lease expiry and checkpoint restoration.

CockroachDB is not used as a passive backing store. It is the cognitive
substrate:

- goal, task, membership, checkpoint, run, failure, decision, event, and memory
  state live in CockroachDB;
- memory embeddings are stored in `VECTOR(1024)` columns;
- semantic recall uses CockroachDB distributed vector indexing;
- scope is applied before vector ranking, so retrieval is constrained by the
  same relational state that defines access;
- task ownership is enforced through conditional SQL updates and CockroachDB's
  serializable transaction model.

The most important implementation detail is that the goal-scoped vector index is
not "search everything, filter later." The index is goal-prefixed, so CockroachDB
narrows to the permitted goal scope before similarity ranking. That is the
technical reason CockroachDB is central to the product rather than incidental.

## Repository Layout

```text
Nexum/
├── nexum-backend/              Spring Boot control plane and agent runtime
│   ├── src/main/java/com/nexum
│   └── src/main/resources/db/migration
├── frontend/                   Static dashboard shipped inside the backend jar
├── infra/aws/                  EC2 provisioning and release scripts
├── scripts/                    Local operational helpers
├── compose.yaml                Local CockroachDB + Ollama stack
├── compose.prod.yaml           Production app + Caddy stack
├── Nexum_Project_Master_Spec.md
├── Nexum_Spec_Review.md
├── BUILD_PLAN.md
└── HANDOFF.md
```

`Nexum_Project_Master_Spec.md` explains the original product intent.
`Nexum_Spec_Review.md`, `BUILD_PLAN.md`, and `HANDOFF.md` explain the decisions
that shaped the current implementation. Where they disagree, the later review
and handoff documents reflect the code that was actually built.

## Stack

| Layer | Technology | Role |
|---|---|---|
| Control plane | Java 25, Spring Boot 4.1 | Owns authoritative state transitions, APIs, scheduling, validation, and agent orchestration |
| Database | CockroachDB v25.4+ / CockroachDB Cloud | Stores transactional state and vector-indexed collective memory |
| Reasoning | Groq through Spring AI's OpenAI-compatible client | Plans agent steps and decisions |
| Embeddings | Bedrock Titan Text Embeddings v2 in production, Ollama `mxbai-embed-large` locally | Produces 1024-dimensional memory/query vectors |
| Local runtime | Docker Compose | Runs CockroachDB and Ollama on a developer machine |
| Cloud runtime | EC2, Docker Compose, Caddy | Runs the Spring Boot app over HTTPS |
| AWS services | Bedrock, S3, EC2 | Bedrock for embeddings, S3 for artifacts, EC2 for the deployed control plane |

Local and production embeddings are both fixed at **1024 dimensions**. Do not
change that casually; the schema, stored vectors, and vector indexes all depend
on it.

## Core Concepts

Nexum has three memory scopes.

| Scope | Visible to | Use |
|---|---|---|
| `PRIVATE` | The originating agent | Scratch reasoning and temporary execution context |
| `GOAL` | Active members of the same goal | Validated discoveries, evidence, decisions, and mission-specific lessons |
| `GLOBAL` | Everyone, after explicit promotion | Reusable cross-goal knowledge |

The default shared boundary is `GOAL`, not `GLOBAL`. That keeps agents working
on the same mission aligned without letting unrelated goals contaminate each
other's retrieval.

Agent failure is handled through leases, not theatre:

1. an agent run claims a task and receives a lease;
2. the run heartbeats while it is alive;
3. killing a run only stops the worker and heartbeat;
4. the task is left untouched;
5. the reaper detects the expired lease;
6. the task becomes `ORPHANED`;
7. a replacement agent joins the goal and claims the orphaned task normally;
8. the replacement restores the checkpoint and retrieves goal memory.

This distinction matters. Nexum does not resurrect the old agent. It lets a new
agent inherit the goal's collective cognition.

## Prerequisites for Local Development

Install these on the machine that will run Nexum locally:

- Git
- Docker and Docker Compose v2
- Java 25
- `curl`
- `jq` is optional, but useful for reading API responses

You also need these external credentials depending on how much of the system you
want to exercise:

- `GROQ_API_KEY` for live LLM reasoning.
- No AWS credentials are required for the default local path if you use Ollama
  embeddings.
- AWS credentials are required only if you want to test production-style Bedrock
  embeddings from your laptop.
- `TAVILY_API_KEY` is optional. Without it, agents use the built-in offline
  corpus so the demo still runs.

Local services started by `compose.yaml`:

- CockroachDB v25.4 single-node, insecure, SQL on `localhost:26257`.
- CockroachDB DB Console on `http://localhost:8081`.
- Ollama API on `http://localhost:11434`.

The Ollama image is large because the official image bundles GPU runtimes. It is
still the supported local path because smaller unofficial images have been
missing the inference binary needed to run models.

## Environment Variables

Copy the example file:

```bash
cp .env.example .env
```

For local development with Docker Compose, the defaults are enough for the
database:

```env
NEXUM_DB_URL=jdbc:postgresql://localhost:26257/nexum?sslmode=disable
NEXUM_DB_USERNAME=root
NEXUM_DB_PASSWORD=
```

Set `GROQ_API_KEY` if you want real model-driven agent steps:

```env
GROQ_API_KEY=...
NEXUM_GROQ_MODEL=openai/gpt-oss-120b
```

Set `TAVILY_API_KEY` only if you want live web research:

```env
TAVILY_API_KEY=...
```

For local Bedrock testing, set standard AWS credentials or use an AWS profile
that the SDK default credential chain can resolve:

```env
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
NEXUM_S3_BUCKET=...
```

Do not commit `.env`, `.env.prod`, AWS keys, database passwords, or API keys.

## Run Locally with CockroachDB and Ollama

This is the most portable path. It does not require AWS access.

Start the local services:

```bash
docker compose up -d
```

Pull the embedding model:

```bash
curl -s http://localhost:11434/api/pull -d '{"name":"mxbai-embed-large"}'
```

Start the backend:

```bash
./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,ollama'
```

Open:

```text
http://localhost:8080
```

Check health:

```bash
curl -fsS http://localhost:8080/actuator/health
```

Expected response:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

If port `8080` is already in use, run on a random port:

```bash
./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,ollama --server.port=0'
```

Spring will print the selected port in the startup logs.

## Seed and Exercise the Demo Locally

Once the app is running, seed a complete demo mission:

```bash
curl -fsS -X POST http://localhost:8080/api/demo/seed | jq
```

The response contains:

- `goalId`
- three member agents
- one replacement agent that has not joined the goal yet
- seeded memories
- demo tasks

Start an agent run with one of the member agent IDs:

```bash
curl -fsS -X POST \
  http://localhost:8080/api/goals/{goalId}/agents/{agentId}/runs | jq
```

Read memory as that agent:

```bash
curl -fsS \
  "http://localhost:8080/api/goals/{goalId}/memory?asAgent={agentId}&query=pricing&limit=3" | jq
```

You want to see:

```json
"strategy": "SEMANTIC"
```

If it says `STRUCTURED`, embeddings are still pending or the embedding provider
is unavailable. Wait briefly and retry. The memory write path is intentionally
asynchronous, so the application can keep running even when embeddings lag.

Inspect whether CockroachDB is using the vector index:

```bash
curl -fsS \
  "http://localhost:8080/api/cockroach/goals/{goalId}/recall-plan?asAgent={agentId}&query=pricing&limit=3" | jq
```

The strongest proof points are:

```json
"vectorSource": "query embedding"
"vectorSearch": true
"index": "memories@memories_goal_scope_embedding_idx"
```

Kill a live run:

```bash
curl -fsS -X POST http://localhost:8080/api/runs/{runId}/kill | jq
```

The response should explicitly say the task was not touched. After the lease
expires, the reaper should record the failure and orphan the task.

Watch the event stream:

```bash
curl -N http://localhost:8080/api/goals/{goalId}/events/stream
```

Useful inspection endpoints:

```bash
curl -fsS http://localhost:8080/api/goals | jq
curl -fsS http://localhost:8080/api/goals/{goalId}/tasks | jq
curl -fsS http://localhost:8080/api/goals/{goalId}/members | jq
curl -fsS http://localhost:8080/api/goals/{goalId}/runs | jq
curl -fsS http://localhost:8080/api/goals/{goalId}/failures | jq
curl -fsS http://localhost:8080/api/cockroach | jq
```

## Run the Walking Skeleton

The walking skeleton is a lower-level verification mode. Use it on a new machine
or after dependency/configuration changes.

```bash
docker compose up -d
curl -s http://localhost:11434/api/pull -d '{"name":"mxbai-embed-large"}'
./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,ollama,skeleton'
```

It checks:

- CockroachDB is reachable.
- Flyway migrations apply.
- JPA works with `CockroachDialect`.
- conditional task claiming has exactly one winner under contention;
- a 1024-dimensional embedding provider is available;
- `VECTOR(1024)` round-trips through JDBC;
- vector similarity search behaves correctly;
- Groq responds if `GROQ_API_KEY` is set.

The command exits non-zero if a required check fails.

## Run Locally with Bedrock Embeddings

This path is useful if you want local behavior to match production more closely.
It requires AWS credentials with `bedrock:InvokeModel` for
`amazon.titan-embed-text-v2:0`.

Start CockroachDB only:

```bash
docker compose up -d cockroach cockroach-init
```

Export your AWS and app environment:

```bash
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export GROQ_API_KEY=...
```

Run without the `ollama` profile:

```bash
./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local'
```

In this mode Spring AI's built-in Bedrock Titan auto-configuration is disabled.
Nexum wires a small custom Titan v2 embedding client because Titan v2 expects:

```json
{"inputText":"...","dimensions":1024,"normalize":true}
```

That exact request shape matters. The older Spring AI Bedrock Titan adapter sends
fields Titan v2 rejects.

## Build and Test

Compile:

```bash
./gradlew :nexum-backend:compileJava
```

Run tests:

```bash
./gradlew :nexum-backend:test
```

Build the executable jar:

```bash
./gradlew :nexum-backend:bootJar
```

Build the Docker image:

```bash
docker build -f nexum-backend/Dockerfile -t nexum-backend:latest .
```

## Deploy to AWS

The production shape is intentionally simple:

```text
Browser
  |
  v
Caddy on EC2, HTTPS
  |
  v
Spring Boot control plane
  |
  +-- CockroachDB Cloud
  +-- Bedrock Titan v2
  +-- Groq
  +-- S3
```

CockroachDB does not run on the EC2 instance. Use CockroachDB Cloud so the demo
uses the managed product and the small instance is not competing with the JVM for
memory.

Cloud prerequisites:

- AWS CLI configured with a profile that can create EC2, IAM, security groups,
  Elastic IPs, and S3 permissions.
- A CockroachDB Cloud cluster running v25.4 or later.
- A DNS hostname you can point at the EC2 Elastic IP.
- A Groq API key.
- Optional Tavily API key for live web research.

Provision AWS infrastructure:

```bash
AWS_PROFILE=nexum ./infra/aws/provision.sh
```

The script creates or reuses:

- an EC2 `t3.small` instance;
- an SSH key at `~/.ssh/nexum.pem`;
- a security group with ports `80` and `443` open, and `22` restricted to your
  current IP;
- an Elastic IP;
- an instance role with Bedrock Titan invoke permission and scoped S3 access;
- a Docker-ready Ubuntu host.

Create a CockroachDB Cloud cluster, then run the one-time initialization script
as a database admin:

```bash
cockroach sql --url "$NEXUM_DB_URL" -f scripts/cockroach-init.sql
```

Create the production environment file:

```bash
cp infra/aws/.env.prod.example infra/aws/.env.prod
chmod 600 infra/aws/.env.prod
```

Fill in:

```env
NEXUM_PUBLIC_HOST=your-domain.example
NEXUM_DB_URL='jdbc:postgresql://...:26257/defaultdb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&options=-c%20allow_unsafe_internals%3Dtrue'
NEXUM_DB_USERNAME=...
NEXUM_DB_PASSWORD=...
GROQ_API_KEY=...
NEXUM_GROQ_MODEL=openai/gpt-oss-120b
TAVILY_API_KEY=
AWS_REGION=us-east-1
NEXUM_S3_BUCKET=...
```

Quote `NEXUM_DB_URL` if it contains `&options=...`; otherwise Bash will parse
the `&` as shell syntax when `release.sh` sources the file.

Do **not** put `AWS_ACCESS_KEY_ID` or `AWS_SECRET_ACCESS_KEY` in `.env.prod`.
On EC2 the SDK must use the instance role. Empty AWS key variables can shadow the
role and break Bedrock.

Point your DNS A record at the Elastic IP printed by `provision.sh`. Confirm it:

```bash
dig +short your-domain.example
```

Release:

```bash
./infra/aws/release.sh
```

Do not pipe this command through `tail` or another command; you want the real
exit code. The script:

- checks DNS before Caddy tries to request a certificate;
- builds the Docker image locally;
- uploads it to EC2 over SSH;
- uploads `compose.prod.yaml`, `.env`, and the Caddyfile;
- loads the image remotely;
- restarts the app;
- waits for `https://<host>/actuator/health` to return `200`.

Operate the deployed box:

```bash
ssh -i ~/.ssh/nexum.pem ubuntu@<elastic-ip>
cd /opt/nexum
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs app --tail 100
docker compose -f compose.prod.yaml up -d --force-recreate app
```

## CockroachDB Requirements

Use CockroachDB, not PostgreSQL. CockroachDB is PostgreSQL wire-compatible, but
Nexum relies on CockroachDB behavior:

- serializable isolation and retryable `40001` errors;
- distributed SQL;
- `VECTOR(1024)`;
- distributed vector indexing;
- goal-prefixed vector search;
- transactional task claiming under contention.

Use v25.4 or later. Earlier versions may have the `VECTOR` type but lack the
cosine vector operator class used by this project.

The key index for the demo is:

```text
memories@memories_goal_scope_embedding_idx
```

The inspector endpoint shows whether CockroachDB actually selected it for a
given recall query.

## CockroachDB AI Tooling

Nexum's shipped CockroachDB integration centers on Distributed Vector Indexing.
The application exposes a CockroachDB inspector endpoint that runs
`EXPLAIN ANALYZE` against the same SQL used by memory recall, so the UI can show
whether a query used vector search, which index it used, the prefix spans, and
how long the search took.

The Managed MCP Server is treated as an inspection plane, not as the agent memory
path. That is deliberate. Giving agents a general SQL tool would bypass
`MemoryAccessPolicy` and allow them to read private or unrelated goal memory.
Agent memory access goes through Nexum services; MCP access should use a
read-only role and scope-safe views when connected.

The ccloud CLI is optional and not required for the core demo.

## Troubleshooting

If the app starts but memory recall says `STRUCTURED`, embeddings are not ready.
Wait for the background worker, then retry. If it never switches to `SEMANTIC`,
check the app logs for embedding provider errors.

If Ollama is slow after being idle, confirm the container has:

```env
OLLAMA_KEEP_ALIVE=-1
```

If the app cannot start because port `8080` is already in use, pass
`--server.port=0` or stop the other process.

If Flyway fails against CockroachDB Cloud with `crdb_internal` or `system`
access errors, confirm the JDBC URL includes:

```text
options=-c%20allow_unsafe_internals%3Dtrue
```

If production Bedrock calls fail on EC2, confirm `AWS_ACCESS_KEY_ID` and
`AWS_SECRET_ACCESS_KEY` are absent from the container environment so the instance
role can be used.

If the release script refuses to start Caddy, check DNS first:

```bash
dig +short "$NEXUM_PUBLIC_HOST"
```

Caddy cannot get a Let's Encrypt certificate until the hostname resolves to the
Elastic IP.

## Design Rules Worth Preserving

These are not style preferences; they protect the core product behavior.

1. Develop against CockroachDB, not PostgreSQL.
2. Keep embeddings at 1024 dimensions unless you are ready to rebuild the schema
   and every vector index.
3. Apply memory scope before vector ranking.
4. Keep all memory access behind `MemoryAccessPolicy`.
5. Never give agents a general SQL tool.
6. Keep task claims as explicit JDBC conditional updates.
7. Keep vector columns out of JPA entities.
8. Keep embedding asynchronous.
9. Do not make `kill` directly orphan a task; the reaper must detect failure.
10. Treat LLM output as a proposal. Java services and CockroachDB own
    authoritative state.


