#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8090}"
MODE="${MODE:-mvc}"
EXPECTED_APP="${EXPECTED_APP:-demo-agent-service}"
TMP_DIR="$(mktemp -d)"
PASS_COUNT=0

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python
else
  echo "FAIL: python3 or python is required for JSON/SSE assertions" >&2
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
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS %s\n' "$1"
}

request_json() {
  local method="$1"
  local path="$2"
  local body="$3"
  local out_file="$4"
  curl -sS -o "$out_file" -w '%{http_code}' \
    -X "$method" "$BASE_URL$path" \
    -H 'Content-Type: application/json' \
    -d "$body"
}

request_json_accept() {
  local method="$1"
  local path="$2"
  local body="$3"
  local accept="$4"
  local out_file="$5"
  curl -sS -o "$out_file" -w '%{http_code}' \
    -X "$method" "$BASE_URL$path" \
    -H 'Content-Type: application/json' \
    -H "Accept: $accept" \
    -d "$body"
}

request_no_body() {
  local method="$1"
  local path="$2"
  local out_file="$3"
  curl -sS -o "$out_file" -w '%{http_code}' -X "$method" "$BASE_URL$path"
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
  $PYTHON - "$file" "$python_expr" "$label" <<'PY'
import json
import sys

path, expr, label = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

if not eval(expr, {"__builtins__": {}}, {"data": data, "len": len, "isinstance": isinstance, "str": str}):
    print(f"FAIL {label}: assertion failed", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

assert_assistant_json() {
  local file="$1"
  local conv_id="$2"
  local label="$3"
  $PYTHON - "$file" "$conv_id" "$label" <<'PY'
import json
import sys

path, conv_id, label = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

if data.get("conversation_id") != conv_id:
    print(f"FAIL {label}: conversation_id mismatch", file=sys.stderr)
    sys.exit(1)

result = data.get("result") or {}
if result.get("role") != "assistant":
    print(f"FAIL {label}: expected role=assistant", file=sys.stderr)
    sys.exit(1)

content = result.get("content")
if not isinstance(content, str) or not content.strip():
    print(f"FAIL {label}: expected non-empty assistant content", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

assert_sse_assistant() {
  local file="$1"
  local conv_id="$2"
  local label="$3"
  $PYTHON - "$file" "$conv_id" "$label" <<'PY'
import json
import sys

path, conv_id, label = sys.argv[1], sys.argv[2], sys.argv[3]
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

for event in events:
    if event.get("type") == "error" or event.get("error"):
        print(f"FAIL {label}: SSE error event", file=sys.stderr)
        print(json.dumps(event, ensure_ascii=False, indent=2), file=sys.stderr)
        sys.exit(1)

parts = []
for event in events:
    if event.get("role") == "assistant" and event.get("content"):
        parts.append(str(event["content"]))
        continue
    payload = event.get("payload")
    if isinstance(payload, dict):
        for key in ("content", "delta", "output", "response"):
            value = payload.get(key)
            if value:
                parts.append(str(value))
                break
    for key in ("content", "delta", "output", "response"):
        value = event.get(key)
        if value:
            parts.append(str(value))

content = "".join(parts)
if not content.strip():
    print(f"FAIL {label}: expected non-empty aggregated SSE content", file=sys.stderr)
    print(json.dumps(events, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)

for event in events:
    event_conv = event.get("conversation_id")
    if event_conv is not None and event_conv != conv_id:
        print(f"FAIL {label}: SSE conversation_id mismatch", file=sys.stderr)
        sys.exit(1)
PY
}

assert_health_app() {
  local file="$1"
  $PYTHON - "$file" "$EXPECTED_APP" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

expected = sys.argv[2]
app = data.get("app", "")
if app != expected:
    print(
        f"FAIL health: expected app={expected!r}, got {app!r}. "
        f"Start agent-service-demo on port 8090 with LLM configured.",
        file=sys.stderr,
    )
    sys.exit(1)

if data.get("status") != "healthy" or data.get("agent_loaded") is not True:
    print("FAIL health: service not ready", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

run_mvc_checks() {
print_step "0" "GET /health confirms target demo service"
health_file="$TMP_DIR/health.json"
health_status="$(request_no_body GET "/health" "$health_file")"
assert_status "$health_status" "200" "GET /health"
assert_health_app "$health_file"
pass "GET /health ($EXPECTED_APP)"

print_step "1" "non-streaming POST /v1/query returns aggregated JSON"
body_file="$TMP_DIR/non_stream.json"
status="$(request_json POST "/v1/query" '{"conversation_id":"query-c1","message":"hello","stream":false}' "$body_file")"
assert_status "$status" "200" "non-streaming /v1/query"
assert_assistant_json "$body_file" "query-c1" "non-streaming response"
pass "non-streaming /v1/query"

print_step "2" "streaming POST /v1/query returns SSE"
body_file="$TMP_DIR/stream.txt"
status="$(request_json POST "/v1/query" '{"conversation_id":"query-c2","message":"stream hello","stream":true}' "$body_file")"
assert_status "$status" "200" "streaming /v1/query"
assert_sse_assistant "$body_file" "query-c2" "streaming SSE response"
pass "streaming /v1/query"

print_step "3" "omitted stream defaults to SSE"
body_file="$TMP_DIR/default_stream.txt"
status="$(request_json POST "/v1/query" '{"conversation_id":"query-c3","message":"default stream"}' "$body_file")"
assert_status "$status" "200" "default stream /v1/query"
assert_sse_assistant "$body_file" "query-c3" "default stream response"
pass "default stream"

print_step "4" "legacy POST /query remains compatible"
body_file="$TMP_DIR/legacy.json"
status="$(request_json POST "/query" '{"conversation_id":"query-c4","message":"legacy","stream":false}' "$body_file")"
assert_status "$status" "200" "legacy /query"
assert_assistant_json "$body_file" "query-c4" "legacy response"
pass "legacy /query"

print_step "5" "messages[] uses the latest user message"
body_file="$TMP_DIR/messages.json"
status="$(request_json POST "/v1/query" '{"conversation_id":"query-c5","messages":[{"role":"user","content":"first"},{"role":"assistant","content":"ignored"},{"role":"user","content":"latest"}],"stream":false}' "$body_file")"
assert_status "$status" "200" "messages[] /v1/query"
assert_assistant_json "$body_file" "query-c5" "messages latest user"
pass "messages[] latest user"

print_step "6" "unicode message is handled"
body_file="$TMP_DIR/unicode.json"
status="$(request_json POST "/v1/query" '{"conversation_id":"query-c6","message":"你好，九问","stream":false}' "$body_file")"
assert_status "$status" "200" "unicode /v1/query"
assert_assistant_json "$body_file" "query-c6" "unicode response"
pass "unicode message"

print_step "7" "unknown fields are ignored"
body_file="$TMP_DIR/unknown_fields.json"
status="$(request_json POST "/v1/query" '{"conversation_id":"query-c7","message":"unknown","stream":false,"extra_field":"ignored"}' "$body_file")"
assert_status "$status" "200" "unknown fields /v1/query"
assert_assistant_json "$body_file" "query-c7" "unknown fields ignored"
pass "unknown fields ignored"

print_step "8" "missing conversation_id returns fixed error JSON"
body_file="$TMP_DIR/missing_conversation.json"
status="$(request_json POST "/v1/query" '{"message":"missing id","stream":false}' "$body_file")"
assert_status "$status" "400" "missing conversation_id"
assert_json "$body_file" \
  'data["type"] == "error" and data["error"] == "conversation_id is required"' \
  "missing conversation_id error"
pass "missing conversation_id"

print_step "9" "blank conversation_id returns fixed error JSON"
body_file="$TMP_DIR/blank_conversation.json"
status="$(request_json POST "/v1/query" '{"conversation_id":" ","message":"blank id","stream":false}' "$body_file")"
assert_status "$status" "400" "blank conversation_id"
assert_json "$body_file" \
  'data["type"] == "error" and data["error"] == "conversation_id is required"' \
  "blank conversation_id error"
pass "blank conversation_id"

print_step "10" "GET /v1/query is not accepted"
body_file="$TMP_DIR/get_query.txt"
status="$(request_no_body GET "/v1/query" "$body_file")"
assert_status "$status" "405" "GET /v1/query"
pass "GET /v1/query returns 405"

print_step "11" "wrong query path is not found"
body_file="$TMP_DIR/wrong_path.txt"
status="$(request_no_body POST "/v1/queries" "$body_file")"
assert_status "$status" "404" "POST /v1/queries"
pass "wrong query path returns 404"
}

run_flux_checks() {
print_step "0" "GET /health confirms target demo service"
health_file="$TMP_DIR/health.json"
health_status="$(request_no_body GET "/health" "$health_file")"
assert_status "$health_status" "200" "GET /health"
assert_health_app "$health_file"
pass "GET /health ($EXPECTED_APP)"

print_step "1" "reactive POST /v1/query/reactive stream=true returns SSE"
body_file="$TMP_DIR/flux_stream.txt"
status="$(request_json_accept POST "/v1/query/reactive" '{"conversation_id":"query-flux-1","message":"flux hello","stream":true}' "text/event-stream" "$body_file")"
assert_status "$status" "200" "streaming /v1/query/reactive"
assert_sse_assistant "$body_file" "query-flux-1" "flux streaming SSE response"
pass "streaming /v1/query/reactive"

print_step "2" "reactive POST /v1/query/reactive stream=false returns aggregated JSON"
body_file="$TMP_DIR/flux_non_stream.json"
status="$(request_json_accept POST "/v1/query/reactive" '{"conversation_id":"query-flux-2","message":"flux json","stream":false}' "application/json" "$body_file")"
assert_status "$status" "200" "non-streaming /v1/query/reactive"
assert_assistant_json "$body_file" "query-flux-2" "flux non-streaming response"
pass "non-streaming /v1/query/reactive"

print_step "3" "reactive missing conversation_id returns fixed error JSON"
body_file="$TMP_DIR/flux_missing_conversation.json"
status="$(request_json POST "/v1/query/reactive" '{"message":"missing flux id","stream":true}' "$body_file")"
assert_status "$status" "400" "missing conversation_id /v1/query/reactive"
assert_json "$body_file" \
  'data["type"] == "error" and data["error"] == "conversation_id is required"' \
  "flux missing conversation_id error"
pass "missing conversation_id /v1/query/reactive"
}

case "$MODE" in
  mvc)
    run_mvc_checks
    ;;
  flux)
    run_flux_checks
    ;;
  *)
    printf 'FAIL unsupported MODE=%s, expected mvc or flux\n' "$MODE" >&2
    exit 1
    ;;
esac

printf '\nAll %s query %s smoke checks passed against %s\n' "$PASS_COUNT" "$MODE" "$BASE_URL"
