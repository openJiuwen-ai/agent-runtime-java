#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8091}"
CONV_ID="${CONV_ID:-redis-stream-c1}"
CODE_NAME="${CODE_NAME:-REDIS-STREAM-42}"
TMP_DIR="$(mktemp -d)"

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python
else
  echo "FAIL: python3 or python is required for SSE parsing" >&2
  exit 1
fi

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

print_step() {
  printf '\n[%s] %s\n' "$1" "$2"
}

pass() {
  printf 'PASS %s\n' "$1"
}

fail() {
  printf 'FAIL %s\n' "$1" >&2
  exit 1
}

request_stream() {
  local body="$1"
  local out_file="$2"
  curl -sS -N -o "$out_file" -w '%{http_code}' \
    -X POST "$BASE_URL/v1/query" \
    -H 'Content-Type: application/json; charset=utf-8' \
    -d "$body"
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "$label: expected HTTP $expected, got $actual"
  fi
}

assert_stream_response() {
  local file="$1"
  local label="$2"
  $PYTHON - "$file" "$label" <<'PY'
import json
import sys

path, label = sys.argv[1], sys.argv[2]
events = []
with open(path, "r", encoding="utf-8") as f:
    for raw in f:
        line = raw.strip()
        if line.startswith("data:"):
            events.append(json.loads(line[len("data:"):].strip()))

if not events:
    print(f"FAIL {label}: no SSE data events found", file=sys.stderr)
    sys.exit(1)

parts = []
for event in events:
    payload = event.get("payload")
    if not isinstance(payload, dict):
        continue
    for key in ("content", "delta", "output", "response"):
        value = payload.get(key)
        if value:
            parts.append(str(value))

content = "".join(parts)
if not content.strip():
    print(f"FAIL {label}: expected non-empty aggregated SSE content", file=sys.stderr)
    sys.exit(1)

print(content)
PY
}

assert_expected_app() {
  local file="$1"
  $PYTHON - "$file" "demo-redis-agent-service" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

expected = sys.argv[2]
app = data.get("app", "")
if app != expected:
    print(
        f"FAIL health: expected app={expected!r}, got {app!r}. "
        "Start agent-service-demo-redis on port 8091, not main demo on 8090.",
        file=sys.stderr,
    )
    sys.exit(1)

if data.get("status") != "healthy" or data.get("agent_loaded") is not True:
    print("FAIL health: service not ready", file=sys.stderr)
    sys.exit(1)
PY
}

print_step "1" "GET /health (demo-redis-agent-service on 8091)"
health_file="$TMP_DIR/health.json"
health_status="$(curl -sS -o "$health_file" -w '%{http_code}' "$BASE_URL/health")"
assert_status "$health_status" "200" "GET /health"
assert_expected_app "$health_file"
pass "GET /health"

print_step "2" "Round 1 (stream): store code name (conversation_id=$CONV_ID, code=$CODE_NAME)"
round1_file="$TMP_DIR/round1.sse"
round1_status="$(request_stream \
  "{\"conversation_id\":\"$CONV_ID\",\"message\":\"Remember my code name is $CODE_NAME. Reply with received only.\",\"stream\":true}" \
  "$round1_file")"
assert_status "$round1_status" "200" "round 1 stream query"
round1_content="$(assert_stream_response "$round1_file" "round 1 stream")"
pass "round 1 stream (Core SSE path on redis module, ${#round1_content} chars aggregated)"

print_step "3" "Round 2 (stream): recall code name (Redis checkpointer + SSE)"
round2_file="$TMP_DIR/round2.sse"
round2_status="$(request_stream \
  "{\"conversation_id\":\"$CONV_ID\",\"message\":\"What code name did I ask you to remember? Answer with the code only.\",\"stream\":true}" \
  "$round2_file")"
assert_status "$round2_status" "200" "round 2 stream query"
round2_content="$(assert_stream_response "$round2_file" "round 2 stream")"

$PYTHON - "$round2_content" "$CODE_NAME" <<'PY'
import sys

content = sys.argv[1]
code = sys.argv[2]
if code not in content and code.lower() not in content.lower():
    print("FAIL round 2 stream: model did not recall the stored code.", file=sys.stderr)
    print("Expected:", code, file=sys.stderr)
    print("Aggregated response:", content, file=sys.stderr)
    sys.exit(1)
PY
pass "round 2 stream recalls context via SSE (multi-turn / checkpointer)"

printf '\nRedis streaming smoke checks passed against %s\n' "$BASE_URL"
