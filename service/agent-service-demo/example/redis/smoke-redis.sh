#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8091}"
CONV_ID="${CONV_ID:-redis-demo-c1}"
TMP_DIR="$(mktemp -d)"

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python
else
  echo "FAIL: python3 or python is required for JSON parsing" >&2
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

request_json() {
  local path="$1"
  local body="$2"
  local out_file="$3"
  curl -sS -o "$out_file" -w '%{http_code}' \
    -X POST "$BASE_URL$path" \
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

assert_non_empty_json() {
  local file="$1"
  local label="$2"
  if [[ ! -s "$file" ]]; then
    fail "$label: empty response body (check Redis checkpointer and service logs)"
  fi
}

extract_content() {
  local file="$1"
  $PYTHON - "$file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
content = data.get("result", {}).get("content", "")
if isinstance(content, str):
    print(content)
else:
    print(json.dumps(content, ensure_ascii=False))
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
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

print_step "1" "GET /health (demo-redis-agent-service on 8091)"
health_file="$TMP_DIR/health.json"
health_status="$(curl -sS -o "$health_file" -w '%{http_code}' "$BASE_URL/health")"
assert_status "$health_status" "200" "GET /health"
assert_expected_app "$health_file"
pass "GET /health"

print_step "2" "Round 1: store a fact in Core Session (conversation_id=$CONV_ID)"
round1_file="$TMP_DIR/round1.json"
round1_status="$(request_json "/v1/query" \
  "{\"conversation_id\":\"$CONV_ID\",\"message\":\"请记住：我的代号是 REDIS-DEMO-42。回复收到即可。\",\"stream\":false}" \
  "$round1_file")"
assert_status "$round1_status" "200" "round 1 query"
assert_non_empty_json "$round1_file" "round 1"
round1_content="$(extract_content "$round1_file")"
pass "round 1 (Core path on redis module)"

print_step "3" "Round 2: recall the fact (same conversation_id, Redis checkpointer)"
round2_file="$TMP_DIR/round2.json"
round2_status="$(request_json "/v1/query" \
  "{\"conversation_id\":\"$CONV_ID\",\"message\":\"我刚才让你记住的代号是什么？只回答代号。\",\"stream\":false}" \
  "$round2_file")"
assert_status "$round2_status" "200" "round 2 query"
assert_non_empty_json "$round2_file" "round 2"
round2_content="$(extract_content "$round2_file")"

$PYTHON - "$round2_content" <<'PY'
import sys

content = sys.argv[1]
if "REDIS-DEMO-42" not in content and "redis-demo-42" not in content.lower():
    print("FAIL round 2: model did not recall the stored code.", file=sys.stderr)
    print("Response:", content, file=sys.stderr)
    print("", file=sys.stderr)
    print("If the model ignored the instruction, retry manually or check apiconfig.", file=sys.stderr)
    sys.exit(1)
PY
pass "round 2 recalls context (multi-turn / checkpointer)"

printf '\nRedis example smoke checks passed against %s\n' "$BASE_URL"
printf 'Optional: stop and restart the demo process, then run round 2 again with the same conversation_id to verify cross-process Redis recovery.\n'
