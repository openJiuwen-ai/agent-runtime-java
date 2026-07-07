#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="${SERVICE_DIR:-$(cd "$SCRIPT_DIR/../../../../.." && pwd)}"
BASE_PORT="${BASE_PORT:-18900}"
TMP_DIR="$(mktemp -d)"
PASS_COUNT=0
SERVER_PID=""
SERVER_LOG=""

MAIN_CLASS="com.openjiuwen.service.demol1test.query.QueryL1RestExample"
TEST_CLASSES="$SERVICE_DIR/agent-service-demo/target/test-classes"
APP_CP_FILE="$SERVICE_DIR/agent-service-app/target/app.classpath"

cleanup() {
  stop_server
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

require_classpath() {
  if [[ ! -d "$TEST_CLASSES" || ! -f "$APP_CP_FILE" ]]; then
    cat >&2 <<EOF
FAIL missing compiled test classpath.

Run from agent-runtime-java/service:
  mvn -pl agent-service-demo -am test-compile
  mvn -pl agent-service-app dependency:build-classpath -Dmdep.outputFile=target/app.classpath
EOF
    exit 1
  fi
}

java_cp() {
  printf '%s:%s:%s:%s:%s' \
    "$TEST_CLASSES" \
    "$SERVICE_DIR/agent-service-demo/target/classes" \
    "$SERVICE_DIR/agent-service-app/target/classes" \
    "$SERVICE_DIR/agent-service-adapters/target/classes" \
    "$(cat "$APP_CP_FILE")"
}

print_step() {
  printf '\n[%s] %s\n' "$1" "$2"
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS %s\n' "$1"
}

fail_with_log() {
  local message="$1"
  printf 'FAIL %s\n' "$message" >&2
  if [[ -n "$SERVER_LOG" && -f "$SERVER_LOG" ]]; then
    printf '\n--- server log: %s ---\n' "$SERVER_LOG" >&2
    tail -160 "$SERVER_LOG" >&2 || true
  fi
  exit 1
}

start_server() {
  local name="$1"
  local port="$2"
  shift 2

  stop_server
  SERVER_LOG="$TMP_DIR/$name.log"
  java -cp "$(java_cp)" "$MAIN_CLASS" --server.port="$port" "$@" >"$SERVER_LOG" 2>&1 &
  SERVER_PID="$!"

  for _ in $(seq 1 100); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      fail_with_log "$name exited before /health became available"
    fi
    local status
    status="$(curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$port/health" 2>/dev/null || true)"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    sleep 0.25
  done
  fail_with_log "$name did not expose /health on port $port"
}

start_server_without_health() {
  local name="$1"
  local port="$2"
  shift 2

  stop_server
  SERVER_LOG="$TMP_DIR/$name.log"
  java -cp "$(java_cp)" "$MAIN_CLASS" --server.port="$port" "$@" >"$SERVER_LOG" 2>&1 &
  SERVER_PID="$!"

  local probe_body="$TMP_DIR/$name-ready.json"
  for _ in $(seq 1 100); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      fail_with_log "$name exited before query endpoint became available"
    fi
    local status
    status="$(curl -sS -o "$probe_body" -w '%{http_code}' \
      -X POST "http://localhost:$port/v1/query" \
      -H 'Content-Type: application/json' \
      -d '{"conversation_id":"l1-ready","message":"ready","stream":false}' 2>/dev/null || true)"
    if [[ -n "$status" && "$status" != "000" ]]; then
      return 0
    fi
    sleep 0.25
  done
  fail_with_log "$name did not expose /v1/query on port $port"
}

stop_server() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      kill "$SERVER_PID" 2>/dev/null || true
      wait "$SERVER_PID" 2>/dev/null || true
    fi
    SERVER_PID=""
  fi
}

curl_json() {
  local method="$1"
  local port="$2"
  local path="$3"
  local body="$4"
  local out_file="$5"
  local header_file="${6:-}"
  if [[ "$#" -gt 6 ]]; then
    shift 6
  else
    set --
  fi

  if [[ -n "$header_file" ]]; then
    curl -sS -D "$header_file" -o "$out_file" -w '%{http_code}' \
      -X "$method" "http://localhost:$port$path" \
      -H 'Content-Type: application/json' \
      "$@" \
      -d "$body"
  else
    curl -sS -o "$out_file" -w '%{http_code}' \
      -X "$method" "http://localhost:$port$path" \
      -H 'Content-Type: application/json' \
      "$@" \
      -d "$body"
  fi
}

curl_text() {
  local method="$1"
  local port="$2"
  local path="$3"
  local content_type="$4"
  local body="$5"
  local out_file="$6"
  curl -sS -o "$out_file" -w '%{http_code}' \
    -X "$method" "http://localhost:$port$path" \
    -H "Content-Type: $content_type" \
    -d "$body"
}

curl_no_body() {
  local method="$1"
  local port="$2"
  local path="$3"
  local out_file="$4"
  curl -sS -o "$out_file" -w '%{http_code}' \
    -X "$method" "http://localhost:$port$path"
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail_with_log "$label: expected HTTP $expected, got $actual"
  fi
}

assert_not_status() {
  local actual="$1"
  local unexpected="$2"
  local label="$3"
  if [[ "$actual" == "$unexpected" ]]; then
    fail_with_log "$label: expected HTTP status other than $unexpected"
  fi
}

assert_header_contains() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  if ! grep -qi "$pattern" "$file"; then
    printf 'Response headers:\n' >&2
    cat "$file" >&2
    fail_with_log "$label: expected response header matching $pattern"
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

wait_agent_loaded() {
  local port="$1"
  local expected="$2"
  local body_file="$TMP_DIR/wait-health-$port.json"
  for _ in $(seq 1 60); do
    curl_no_body GET "$port" "/health" "$body_file" >/dev/null
    if python3 - "$body_file" "$expected" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
sys.exit(0 if data.get("agent_loaded") == (sys.argv[2] == "true") else 1)
PY
    then
      return 0
    fi
    sleep 0.25
  done
  fail_with_log "agent_loaded did not become $expected on port $port"
}

run_mvc_ingress() {
  local port=$((BASE_PORT + 1))
  print_step "1" "MVC ingress normalization, headers, content type, legacy path and basic errors"
  start_server "mvc-ingress" "$port" \
    --openjiuwen.service.query.webflux.enabled=false
  wait_agent_loaded "$port" true

  local body headers status

  body="$TMP_DIR/message-shorthand.json"
  headers="$TMP_DIR/message-shorthand.headers"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-msg","message":"hello","stream":false}' "$body" "$headers")"
  assert_status "$status" "200" "message shorthand"
  assert_header_contains "$headers" "content-type:.*application/json" "non-stream content type"
  assert_json "$body" \
    'data["conversation_id"] == "l1-msg" and data["result"]["query"] == "hello" and data["result"]["messages_size"] == 1 and data["result"]["content"] == "query-l1:hello"' \
    "message shorthand"

  body="$TMP_DIR/messages-priority.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-priority","message":"ignored","messages":[{"role":"user","content":"real"}],"stream":false}' "$body")"
  assert_status "$status" "200" "messages priority"
  assert_json "$body" 'data["result"]["query"] == "real"' "messages priority"

  body="$TMP_DIR/latest-user.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-latest","messages":[{"role":"user","content":"first"},{"role":"assistant","content":"ignored"},{"role":"user","content":"latest"}],"stream":false}' "$body")"
  assert_status "$status" "200" "latest user"
  assert_json "$body" 'data["result"]["query"] == "latest"' "latest user"

  body="$TMP_DIR/fallback-last.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-fallback","messages":[{"role":"assistant","content":"fallback"}],"stream":false}' "$body")"
  assert_status "$status" "200" "fallback last message"
  assert_json "$body" 'data["result"]["query"] == "fallback"' "fallback last message"

  body="$TMP_DIR/empty-query.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-empty","stream":false}' "$body")"
  assert_status "$status" "200" "empty messages"
  assert_json "$body" 'data["result"]["query"] == "" and data["result"]["content"] == "query-l1:" and data["result"]["messages_size"] == 0' "empty messages"

  body="$TMP_DIR/unicode.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-cn","message":"你好，九问","stream":false}' "$body")"
  assert_status "$status" "200" "unicode"
  assert_json "$body" 'data["result"]["query"] == "你好，九问" and data["result"]["content"] == "query-l1:你好，九问"' "unicode"

  body="$TMP_DIR/unknown.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-unknown","message":"unknown","stream":false,"extra_field":"ignored"}' "$body")"
  assert_status "$status" "200" "unknown fields"
  assert_json "$body" 'data["result"]["query"] == "unknown"' "unknown fields"

  body="$TMP_DIR/default-context.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-default-context","message":"ctx","stream":false}' "$body")"
  assert_status "$status" "200" "default context"
  assert_json "$body" 'data["result"]["user_id"] == "anonymous" and data["result"]["space_id"] == "default" and data["result"]["tenant_id"] is None' "default context"

  body="$TMP_DIR/header-context.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-header","message":"ctx","user_id":"body-user","space_id":"body-space","tenant_id":"body-tenant","stream":false}' "$body" "" \
    -H 'X-User-ID: header-user' -H 'X-Space-ID: header-space' -H 'X-Tenant-ID: header-tenant')"
  assert_status "$status" "200" "header override"
  assert_json "$body" 'data["result"]["user_id"] == "header-user" and data["result"]["space_id"] == "header-space" and data["result"]["tenant_id"] == "header-tenant"' "header override"

  body="$TMP_DIR/blank-header-context.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-blank-header","message":"ctx","user_id":"body-user","space_id":"body-space","tenant_id":"body-tenant","stream":false}' "$body" "" \
    -H 'X-User-ID:' -H 'X-Space-ID:' -H 'X-Tenant-ID:')"
  assert_status "$status" "200" "blank header"
  assert_json "$body" 'data["result"]["user_id"] == "body-user" and data["result"]["space_id"] == "body-space" and data["result"]["tenant_id"] == "body-tenant"' "blank header"

  body="$TMP_DIR/multi-turn-1.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-multi","message":"a","stream":false}' "$body")"
  assert_status "$status" "200" "multi turn 1"
  assert_json "$body" 'data["result"]["turn"] == 1 and data["result"]["previous_query"] is None' "multi turn 1"
  body="$TMP_DIR/multi-turn-2.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-multi","message":"b","stream":false}' "$body")"
  assert_status "$status" "200" "multi turn 2"
  assert_json "$body" 'data["result"]["turn"] == 2 and data["result"]["previous_query"] == "a"' "multi turn 2"

  body="$TMP_DIR/legacy.json"
  status="$(curl_json POST "$port" "/query" '{"conversation_id":"l1-legacy","message":"legacy","stream":false}' "$body")"
  assert_status "$status" "200" "legacy path"
  assert_json "$body" 'data["result"]["query"] == "legacy"' "legacy path"

  body="$TMP_DIR/stream.txt"
  headers="$TMP_DIR/stream.headers"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-stream","message":"stream","stream":true}' "$body" "$headers" -H 'Accept: text/event-stream')"
  assert_status "$status" "200" "mvc stream"
  assert_header_contains "$headers" "content-type:.*text/event-stream" "mvc stream content type"
  assert_header_contains "$headers" "cache-control:.*no-cache" "mvc stream cache-control"
  assert_header_contains "$headers" "connection:.*keep-alive" "mvc stream connection"
  assert_header_contains "$headers" "x-accel-buffering:.*no" "mvc stream buffering"
  assert_sse_json "$body" 'len(events) == 1 and events[0]["role"] == "assistant" and events[0]["content"] == "query-l1:stream" and events[0]["query"] == "stream" and events[0]["conversation_id"] == "l1-stream"' "mvc stream payload"

  body="$TMP_DIR/missing-conversation.json"
  status="$(curl_json POST "$port" "/v1/query" '{"message":"missing","stream":false}' "$body")"
  assert_status "$status" "400" "missing conversation_id"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "conversation_id is required"' "missing conversation_id"

  body="$TMP_DIR/blank-conversation.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":" ","message":"blank","stream":false}' "$body")"
  assert_status "$status" "400" "blank conversation_id"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "conversation_id is required"' "blank conversation_id"

  body="$TMP_DIR/get-v1-query.txt"
  status="$(curl_no_body GET "$port" "/v1/query" "$body")"
  assert_status "$status" "405" "GET /v1/query"

  body="$TMP_DIR/get-query.txt"
  status="$(curl_no_body GET "$port" "/query" "$body")"
  assert_status "$status" "405" "GET /query"

  body="$TMP_DIR/wrong-query.txt"
  status="$(curl_no_body POST "$port" "/v1/queries" "$body")"
  assert_status "$status" "404" "POST /v1/queries"

  body="$TMP_DIR/non-json.txt"
  status="$(curl_text POST "$port" "/v1/query" "text/plain" '{"conversation_id":"l1-text","message":"text","stream":false}' "$body")"
  assert_not_status "$status" "200" "non-json content type"

  body="$TMP_DIR/bad-json.txt"
  status="$(curl_text POST "$port" "/v1/query" "application/json" '{"conversation_id":' "$body")"
  assert_status "$status" "400" "bad json"

  stop_server
  pass "MVC ingress, normalization, headers, content type, legacy path and basic errors"
}

run_legacy_disabled() {
  local port=$((BASE_PORT + 2))
  print_step "2" "legacy path can be disabled"
  start_server "legacy-disabled" "$port" \
    --openjiuwen.service.query.legacy-path-enabled=false \
    --openjiuwen.service.query.webflux.enabled=false
  wait_agent_loaded "$port" true

  local body status
  body="$TMP_DIR/legacy-disabled-v1.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-v1","message":"main","stream":false}' "$body")"
  assert_status "$status" "200" "main path with legacy disabled"

  body="$TMP_DIR/legacy-disabled-query.txt"
  status="$(curl_json POST "$port" "/query" '{"conversation_id":"l1-disabled","message":"legacy","stream":false}' "$body")"
  assert_status "$status" "404" "legacy disabled /query"

  stop_server
  pass "legacy path disabled"
}

run_reactive_disabled() {
  local port=$((BASE_PORT + 3))
  print_step "3" "reactive path is not registered by default"
  start_server "reactive-disabled" "$port" \
    --openjiuwen.service.query.webflux.enabled=false
  wait_agent_loaded "$port" true

  local body status
  body="$TMP_DIR/reactive-disabled.txt"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-reactive-disabled","message":"reactive","stream":false}' "$body")"
  assert_status "$status" "404" "reactive path disabled"

  stop_server
  pass "reactive path disabled"
}

run_path_coexistence() {
  local port=$((BASE_PORT + 4))
  print_step "4" "MVC, legacy and reactive paths coexist when webflux path is enabled"
  start_server "path-coexistence" "$port" \
    --openjiuwen.service.query.webflux.enabled=true
  wait_agent_loaded "$port" true

  local body status
  body="$TMP_DIR/coexist-mvc.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-coexist-mvc","message":"mvc","stream":false}' "$body")"
  assert_status "$status" "200" "coexist /v1/query"
  assert_json "$body" 'data["result"]["query"] == "mvc"' "coexist /v1/query"

  body="$TMP_DIR/coexist-legacy.json"
  status="$(curl_json POST "$port" "/query" '{"conversation_id":"l1-coexist-legacy","message":"legacy","stream":false}' "$body")"
  assert_status "$status" "200" "coexist /query"
  assert_json "$body" 'data["result"]["query"] == "legacy"' "coexist /query"

  body="$TMP_DIR/coexist-reactive.json"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-coexist-reactive","message":"reactive","stream":false}' "$body")"
  assert_status "$status" "200" "coexist /v1/query/reactive"
  assert_json "$body" 'data["result"]["query"] == "reactive"' "coexist /v1/query/reactive"

  stop_server
  pass "path coexistence"
}

run_flux() {
  local port=$((BASE_PORT + 5))
  print_step "5" "WebFlux JSON, SSE, default stream, unicode and reactive errors"
  start_server "flux" "$port" \
    --spring.main.web-application-type=reactive \
    --openjiuwen.service.query.webflux.enabled=true
  wait_agent_loaded "$port" true

  local body headers status
  body="$TMP_DIR/flux-json.json"
  headers="$TMP_DIR/flux-json.headers"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-flux-json","message":"json","stream":false}' "$body" "$headers" -H 'Accept: application/json')"
  assert_status "$status" "200" "flux json"
  assert_header_contains "$headers" "content-type:.*application/json" "flux json content type"
  assert_json "$body" 'data["result"]["query"] == "json"' "flux json"

  body="$TMP_DIR/flux-stream.txt"
  headers="$TMP_DIR/flux-stream.headers"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-flux-stream","message":"flux","stream":true}' "$body" "$headers" -H 'Accept: text/event-stream')"
  assert_status "$status" "200" "flux stream"
  assert_header_contains "$headers" "content-type:.*text/event-stream" "flux stream content type"
  assert_sse_json "$body" 'len(events) == 1 and events[0]["role"] == "assistant" and events[0]["content"] == "query-l1:flux" and events[0]["query"] == "flux" and events[0]["conversation_id"] == "l1-flux-stream"' "flux stream payload"

  body="$TMP_DIR/flux-default-stream.txt"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-flux-default","message":"default"}' "$body" "" -H 'Accept: text/event-stream')"
  assert_status "$status" "200" "flux default stream"
  assert_sse_json "$body" 'len(events) == 1 and events[0]["content"] == "query-l1:default" and events[0]["query"] == "default"' "flux default stream"

  body="$TMP_DIR/flux-cn.json"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-flux-cn","message":"你好，Flux","stream":false}' "$body")"
  assert_status "$status" "200" "flux unicode"
  assert_json "$body" 'data["result"]["query"] == "你好，Flux"' "flux unicode"

  body="$TMP_DIR/flux-missing.json"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"message":"missing","stream":false}' "$body")"
  assert_status "$status" "400" "flux missing conversation_id"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "conversation_id is required"' "flux missing conversation_id"

  body="$TMP_DIR/flux-blank.json"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":" ","message":"blank","stream":false}' "$body")"
  assert_status "$status" "400" "flux blank conversation_id"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "conversation_id is required"' "flux blank conversation_id"

  body="$TMP_DIR/flux-wrong-path.txt"
  status="$(curl_json POST "$port" "/v1/query/flux" '{"conversation_id":"l1-flux-wrong","message":"wrong","stream":false}' "$body")"
  assert_status "$status" "404" "wrong reactive path"

  body="$TMP_DIR/flux-bad-json.txt"
  status="$(curl_text POST "$port" "/v1/query/reactive" "application/json" '{"conversation_id":' "$body")"
  assert_status "$status" "400" "flux bad json"

  stop_server
  pass "WebFlux JSON, SSE, default stream, unicode and reactive errors"
}

run_agent_not_loaded() {
  local port=$((BASE_PORT + 6))
  print_step "6" "MVC query returns 503 when agent is not loaded"
  start_server "agent-not-loaded-mvc" "$port" \
    --example.query.l1.lifecycle=disabled \
    --openjiuwen.service.query.webflux.enabled=false

  local body status
  body="$TMP_DIR/agent-not-loaded-mvc.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-not-loaded","message":"blocked","stream":false}' "$body")"
  assert_status "$status" "503" "MVC agent not loaded"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "agent not loaded"' "MVC agent not loaded"

  stop_server
  pass "MVC agent not loaded"
}

run_flux_agent_not_loaded() {
  local port=$((BASE_PORT + 7))
  print_step "7" "Flux query returns 503 when agent is not loaded"
  start_server "agent-not-loaded-flux" "$port" \
    --spring.main.web-application-type=reactive \
    --openjiuwen.service.query.webflux.enabled=true \
    --example.query.l1.lifecycle=disabled

  local body status
  body="$TMP_DIR/agent-not-loaded-flux.json"
  status="$(curl_json POST "$port" "/v1/query/reactive" '{"conversation_id":"l1-flux-not-loaded","message":"blocked","stream":false}' "$body")"
  assert_status "$status" "503" "Flux agent not loaded"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "agent not loaded"' "Flux agent not loaded"

  stop_server
  pass "Flux agent not loaded"
}

run_no_orchestrator() {
  local port=$((BASE_PORT + 8))
  print_step "8" "MVC query returns 503 when no ServeOrchestrator is configured"
  start_server_without_health "no-orchestrator" "$port" \
    --spring.autoconfigure.exclude=com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration \
    --example.query.l1.controller=query-only \
    --example.query.l1.handler=none \
    --openjiuwen.service.query.webflux.enabled=false

  local body status
  body="$TMP_DIR/no-orchestrator.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-no-orchestrator","message":"blocked","stream":false}' "$body")"
  assert_status "$status" "503" "no orchestrator"
  assert_json "$body" 'data["type"] == "error" and data["error"] == "no agent handler configured"' "no orchestrator"

  stop_server
  pass "no ServeOrchestrator"
}

run_client_interrupt() {
  local port=$((BASE_PORT + 9))
  print_step "9" "streaming client timeout does not break later requests"
  start_server "client-interrupt" "$port" \
    --example.query.l1.stream-chunks=20 \
    --example.query.l1.stream-delay-ms=200 \
    --openjiuwen.service.query.webflux.enabled=false
  wait_agent_loaded "$port" true

  local body status curl_exit
  body="$TMP_DIR/client-interrupt.txt"
  set +e
  curl --max-time 1 -sS -N -o "$body" \
    -X POST "http://localhost:$port/v1/query" \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d '{"conversation_id":"l1-client-interrupt","message":"slow","stream":true}' >/dev/null 2>&1
  curl_exit="$?"
  set -e
  if [[ "$curl_exit" == "0" ]]; then
    fail_with_log "client interrupt: expected curl timeout/non-zero exit"
  fi

  body="$TMP_DIR/client-interrupt-after.json"
  status="$(curl_json POST "$port" "/v1/query" '{"conversation_id":"l1-client-interrupt-after","message":"after","stream":false}' "$body")"
  assert_status "$status" "200" "request after client interrupt"
  assert_json "$body" 'data["result"]["query"] == "after"' "request after client interrupt"

  stop_server
  pass "client interrupt"
}

require_classpath
run_mvc_ingress
run_legacy_disabled
run_reactive_disabled
run_path_coexistence
run_flux
run_agent_not_loaded
run_flux_agent_not_loaded
run_no_orchestrator
run_client_interrupt

printf '\nAll %s query L1 scenario groups passed\n' "$PASS_COUNT"
