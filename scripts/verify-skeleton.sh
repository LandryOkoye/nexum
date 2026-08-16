#!/usr/bin/env bash
# Walking skeleton: proves the whole stack boots and works together.
# Run from the repo root.
set -euo pipefail

echo "==> starting local stack"
docker compose up -d

echo "==> waiting for cockroach"
until docker compose exec -T cockroach ./cockroach sql --insecure -e "SELECT 1" >/dev/null 2>&1; do
  sleep 2
done

echo "==> waiting for ollama"
until curl -sf http://localhost:11434/api/tags >/dev/null 2>&1; do
  sleep 2
done

# Pull over the HTTP API rather than `docker compose exec ollama ollama pull`,
# so this works regardless of how the image lays out its binaries.
echo "==> pulling embedding model (~670MB, no-op if present)"
curl -s http://localhost:11434/api/pull -d '{"name":"mxbai-embed-large"}' | tail -1
echo

echo "==> running skeleton probe"
./gradlew :nexum-backend:bootRun --args='--spring.profiles.active=local,skeleton'
