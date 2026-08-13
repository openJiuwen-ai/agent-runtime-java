#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8096}"
SESSIONS="${SESSIONS:-6}"
CONCURRENCY="${CONCURRENCY:-3}"
STREAM="${STREAM:-false}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SERVICE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"

print_step() {
  printf '\n[%s] %s\n' "$1" "$2"
}

fail() {
  printf 'FAIL %s\n' "$1" >&2
  exit 1
}

print_step "1/2" "Checking health at $BASE_URL"
status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/health" || true)"
if [[ "$status" != "200" ]]; then
  fail "health check failed (HTTP $status). Start service: mvn -pl agent-service-demo/example/concurrency -am spring-boot:run"
fi

print_step "2/2" "Running concurrent benchmark (sessions=$SESSIONS concurrency=$CONCURRENCY stream=$STREAM)"
cd "$SERVICE_DIR"
mvn -pl agent-service-demo/example/concurrency -am -q exec:java \
  -Ddemo.concurrency.mode=query \
  -Ddemo.concurrency.base-url="$BASE_URL" \
  -Ddemo.concurrency.sessions="$SESSIONS" \
  -Ddemo.concurrency.concurrency="$CONCURRENCY" \
  -Ddemo.concurrency.stream="$STREAM" \
  -Ddemo.concurrency.min-success-rate=0.80

printf '\nPASS concurrency benchmark\n'
