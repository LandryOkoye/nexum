#!/usr/bin/env bash
# Runs the Nexum backend with .env loaded into the environment.
#
#   ./scripts/run.sh                  # normal boot, local profile
#   ./scripts/run.sh local,skeleton   # the verification probe
#
# Secrets live in .env (gitignored), never in application.yaml. They are
# exported as real environment variables rather than passed as -D properties
# because the AWS SDK's default credential chain reads the environment
# directly - the same mechanism that later picks up the EC2 instance role in
# production, so local and deployed behave identically.
set -euo pipefail

cd "$(dirname "$0")/.."

PROFILES="${1:-local}"

if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
    echo "==> loaded .env"
else
    echo "==> no .env found; copy .env.example to .env and fill it in"
fi

# Warn rather than fail. Nothing in the domain layer depends on either provider
# being reachable - memory writes are persisted with embedding_status PENDING
# and filled in later - so a missing key must not stop the app from booting.
[[ -z "${GROQ_API_KEY:-}" ]] && echo "==> WARNING: GROQ_API_KEY unset; reasoning will 401"
[[ -z "${AWS_ACCESS_KEY_ID:-}" ]] && echo "==> WARNING: no AWS credentials; embeddings stay PENDING"

echo "==> profiles: ${PROFILES}"
exec ./gradlew :nexum-backend:bootRun --console=plain \
    --args="--spring.profiles.active=${PROFILES}"
