#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8090}"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

pass_count=0

print_step() {
  printf '\n[%s] %s\n' "$1" "$2"
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL %s: expected HTTP %s, got %s\n' "$label" "$expected" "$actual" >&2
    return 1
  fi
}

assert_json() {
  local file="$1"
  local python_expr="$2"
  local label="$3"
  python3 - "$file" "$python_expr" "$label" <<'PY'
import json
import sys

path, expr, label = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

if not eval(expr, {"__builtins__": {}}, {"data": data, "len": len}):
    print(f"FAIL {label}: assertion failed", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

assert_sse_json() {
  local file="$1"
  local python_expr="$2"
  local label="$3"
  python3 - "$file" "$python_expr" "$label" <<'PY'
import json
import sys

path, expr, label = sys.argv[1], sys.argv[2], sys.argv[3]
events = []
with open(path, "r", encoding="utf-8") as f:
    for raw in f:
        line = raw.strip()
        if line.startswith("data:"):
            events.append(json.loads(line[len("data:"):].strip()))

if not events:
    print(f"FAIL {label}: no SSE data events found", file=sys.stderr)
    with open(path, "r", encoding="utf-8") as f:
        print(f.read(), file=sys.stderr)
    sys.exit(1)

if not eval(expr, {"__builtins__": {}}, {"events": events, "len": len}):
    print(f"FAIL {label}: assertion failed", file=sys.stderr)
    print(json.dumps(events, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

post_json() {
  local path="$1"
  local body="$2"
  local out_file="$3"
  curl -sS -o "$out_file" -w '%{http_code}' \
    "$BASE_URL$path" \
    -H 'Content-Type: application/json' \
    -d "$body"
}

get_json() {
  local path="$1"
  local out_file="$2"
  curl -sS -o "$out_file" -w '%{http_code}' "$BASE_URL$path"
}

pass() {
  pass_count=$((pass_count + 1))
  printf 'PASS %s\n' "$1"
}

print_step "0" "GET /health returns process and agent readiness"
body_file="$TMP_DIR/health.json"
status="$(get_json "/health" "$body_file")"
assert_status "$status" "200" "health"
assert_json "$body_file" \
  'data["status"] == "healthy" and data["app"] == "demo-agent-service" and data["version"] == "0.1.0" and data["process_up"] == True and data["agent_loaded"] == True' \
  "health response shape"
pass "health"

print_step "1" "non-streaming POST /v1/query returns aggregated JSON"
body_file="$TMP_DIR/non_stream.json"
status="$(post_json "/v1/query" '{"conversation_id":"demo-c1","message":"hello","stream":false}' "$body_file")"
assert_status "$status" "200" "non-streaming /v1/query"
assert_json "$body_file" \
  'data["conversation_id"] == "demo-c1" and data["result"]["role"] == "assistant" and data["result"]["content"] == "demo:hello" and data["result"]["conversation_id"] == "demo-c1"' \
  "non-streaming response shape"
pass "non-streaming /v1/query"

print_step "2" "streaming POST /v1/query returns Python-style SSE data"
body_file="$TMP_DIR/stream.txt"
status="$(post_json "/v1/query" '{"conversation_id":"demo-c2","message":"stream hello","stream":true}' "$body_file")"
assert_status "$status" "200" "streaming /v1/query"
assert_sse_json "$body_file" \
  'len(events) == 1 and events[0]["role"] == "assistant" and events[0]["content"] == "demo:stream hello" and events[0]["conversation_id"] == "demo-c2"' \
  "streaming SSE payload"
pass "streaming /v1/query"

print_step "3" "legacy POST /query remains compatible"
body_file="$TMP_DIR/legacy.json"
status="$(post_json "/query" '{"conversation_id":"demo-c3","message":"legacy","stream":false}' "$body_file")"
assert_status "$status" "200" "legacy /query"
assert_json "$body_file" \
  'data["conversation_id"] == "demo-c3" and data["result"]["content"] == "demo:legacy"' \
  "legacy response shape"
pass "legacy /query"

print_step "4" "messages[] input uses the latest user message"
body_file="$TMP_DIR/messages.json"
status="$(post_json "/v1/query" '{"conversation_id":"demo-c4","messages":[{"role":"user","content":"first"},{"role":"assistant","content":"ignored"},{"role":"user","content":"latest"}],"stream":false}' "$body_file")"
assert_status "$status" "200" "messages[] /v1/query"
assert_json "$body_file" \
  'data["conversation_id"] == "demo-c4" and data["result"]["content"] == "demo:latest"' \
  "messages[] latest user content"
pass "messages[] latest user content"

print_step "5" "omitted stream defaults to SSE"
body_file="$TMP_DIR/default_stream.txt"
status="$(post_json "/v1/query" '{"conversation_id":"demo-c5","message":"default stream"}' "$body_file")"
assert_status "$status" "200" "default stream /v1/query"
assert_sse_json "$body_file" \
  'len(events) == 1 and events[0]["content"] == "demo:default stream" and events[0]["conversation_id"] == "demo-c5"' \
  "default stream SSE payload"
pass "default stream behavior"

print_step "6" "missing conversation_id returns fixed error JSON"
body_file="$TMP_DIR/missing_conversation.json"
status="$(post_json "/v1/query" '{"message":"missing id","stream":false}' "$body_file")"
assert_status "$status" "400" "missing conversation_id"
assert_json "$body_file" \
  'data["type"] == "error" and data["error"] == "conversation_id is required"' \
  "missing conversation_id error"
pass "missing conversation_id error"

printf '\nAll %s query smoke tests passed against %s\n' "$pass_count" "$BASE_URL"
