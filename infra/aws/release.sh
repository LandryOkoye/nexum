#!/usr/bin/env bash
#
# Builds the image locally and runs it on the provisioned instance. Repeatable:
# run it again for every subsequent deploy.
#
#   AWS_PROFILE=nexum ./infra/aws/release.sh
#
# The image is built here and shipped over SSH rather than built on the box.
# A Gradle build of a Java 25 project wants more memory than a t3.small has, and
# a deploy that OOMs halfway through compilation at 20:00 is not a risk worth
# taking for the sake of avoiding a few hundred megabytes of upload.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KEY_PATH="${NEXUM_KEY_PATH:-$HOME/.ssh/nexum.pem}"
IMAGE="nexum-backend:latest"
TARBALL="/tmp/nexum-image.tar.gz"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  %-30s %s\n' "$1" "${2:-ok}"; }

[ -f "$HERE/.public-ip" ] || { echo "No .public-ip - run provision.sh first."; exit 1; }
HOST_IP="$(cat "$HERE/.public-ip")"

[ -f "$HERE/.env.prod" ] || {
    echo "Missing $HERE/.env.prod"
    echo "Copy .env.prod.example and fill in the CockroachDB Cloud details."
    exit 1
}

# shellcheck disable=SC1090
set -a; . "$HERE/.env.prod"; set +a

: "${NEXUM_PUBLIC_HOST:?set NEXUM_PUBLIC_HOST in .env.prod}"
: "${NEXUM_DB_URL:?set NEXUM_DB_URL in .env.prod}"

ssh_() { ssh -i "$KEY_PATH" -o StrictHostKeyChecking=accept-new \
    -o ConnectTimeout=10 "ubuntu@$HOST_IP" "$@"; }

# ------------------------------------------------------------------- checks ---

say "Preflight"
ok "target" "$HOST_IP"
ok "public host" "$NEXUM_PUBLIC_HOST"

# Caddy will request a certificate the moment it starts. If DNS does not resolve
# here yet, the ACME challenge fails and Let's Encrypt applies a rate limit that
# outlasts most deadlines. Better to refuse now than to burn the allowance.
RESOLVED="$(dig +short "$NEXUM_PUBLIC_HOST" | tail -1)"
if [ "$RESOLVED" != "$HOST_IP" ]; then
    echo
    echo "  DNS mismatch: $NEXUM_PUBLIC_HOST resolves to '${RESOLVED:-nothing}',"
    echo "  but the instance is $HOST_IP."
    echo
    echo "  Point the A record at $HOST_IP and wait for it to propagate."
    echo "  Releasing now would fail the ACME challenge and eat a rate limit."
    exit 1
fi
ok "dns" "$NEXUM_PUBLIC_HOST -> $HOST_IP"

for i in $(seq 1 30); do
    if ssh_ test -f /opt/nexum/.bootstrapped 2>/dev/null; then break; fi
    [ "$i" = 30 ] && { echo "  Instance never finished bootstrapping."; \
        echo "  Check: ssh -i $KEY_PATH ubuntu@$HOST_IP sudo tail /var/log/cloud-init-output.log"; exit 1; }
    sleep 10
done
ok "instance" "bootstrapped, docker ready"

# -------------------------------------------------------------------- build ---

say "Build"
cd "$ROOT"
docker build -f nexum-backend/Dockerfile -t "$IMAGE" .
ok "image" "$IMAGE"

docker save "$IMAGE" | gzip -1 > "$TARBALL"
ok "tarball" "$(du -h "$TARBALL" | cut -f1)"

# --------------------------------------------------------------------- ship ---

say "Ship"
scp -i "$KEY_PATH" -o StrictHostKeyChecking=accept-new -q \
    "$TARBALL" "ubuntu@$HOST_IP:/tmp/nexum-image.tar.gz"
ok "image uploaded"

# The Caddyfile must land at the path compose.prod.yaml mounts - infra/aws/ -
# rather than flat. Docker does not fail helpfully on a missing bind source: it
# silently creates a *directory* at that path and then refuses to mount it over
# a file, which reads as a permissions problem and is not one.
ssh_ "mkdir -p /opt/nexum/infra/aws && rm -rf /opt/nexum/infra/aws/Caddyfile"
scp -i "$KEY_PATH" -q "$HERE/Caddyfile" "ubuntu@$HOST_IP:/opt/nexum/infra/aws/Caddyfile"
scp -i "$KEY_PATH" -q \
    "$ROOT/compose.prod.yaml" "$HERE/.env.prod" \
    "ubuntu@$HOST_IP:/opt/nexum/"
ssh_ "mv /opt/nexum/.env.prod /opt/nexum/.env && chmod 600 /opt/nexum/.env"
ok "config uploaded"

ssh_ "gunzip -c /tmp/nexum-image.tar.gz | docker load && rm /tmp/nexum-image.tar.gz"
ok "image loaded"

# ----------------------------------------------------------------------- up ---

say "Start"
# --no-build: the image is already here. Without it compose would try to build
# from a source tree the instance does not have.
ssh_ "cd /opt/nexum && docker compose -f compose.prod.yaml up -d --no-build"
ok "stack" "up"

say "Health"
for i in $(seq 1 40); do
    CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 \
        "https://$NEXUM_PUBLIC_HOST/actuator/health" || echo 000)"
    if [ "$CODE" = "200" ]; then
        ok "https" "200"
        echo
        printf '\033[1mLive: https://%s\033[0m\n\n' "$NEXUM_PUBLIC_HOST"
        exit 0
    fi
    printf '  waiting for https (%s) %ds\n' "$CODE" "$((i * 6))"
    sleep 6
done

echo
echo "  Never returned 200. The stack is up but not healthy. Look at:"
echo "    ssh -i $KEY_PATH ubuntu@$HOST_IP 'cd /opt/nexum && docker compose -f compose.prod.yaml logs --tail 80'"
echo
echo "  Most common causes, in order: CockroachDB connection string wrong,"
echo "  Bedrock model access not granted in this region, DNS not yet propagated"
echo "  when Caddy first asked for a certificate."
exit 1
