#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="${SERVICE_DIR:-$(cd "$SCRIPT_DIR/../../../../.." && pwd)}"
BASE_PORT="${BASE_PORT:-18100}"
TMP_DIR="$(mktemp -d)"
PASS_COUNT=0
SERVER_PID=""
SERVER_LOG=""

MAIN_CLASS="com.openjiuwen.service.demol1test.health.HealthL1ProbeExample"
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
    tail -120 "$SERVER_LOG" >&2 || true
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

  for _ in $(seq 1 80); do
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

stop_server() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      kill "$SERVER_PID" 2>/dev/null || true
      wait "$SERVER_PID" 2>/dev/null || true
    fi
    SERVER_PID=""
  fi
}

request() {
  local method="$1"
  local port="$2"
  local path="$3"
  local body_file="$4"
  local header_file="${5:-}"
  if [[ -n "$header_file" ]]; then
    curl -sS -D "$header_file" -o "$body_file" -w '%{http_code}' \
      -X "$method" "http://localhost:$port$path"
  else
    curl -sS -o "$body_file" -w '%{http_code}' \
      -X "$method" "http://localhost:$port$path"
  fi
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

if not eval(expr, {"__builtins__": {}}, {"data": data, "isinstance": isinstance, "str": str, "len": len}):
    print(f"FAIL {label}: assertion failed", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

wait_json() {
  local port="$1"
  local expr="$2"
  local label="$3"
  local body_file="$TMP_DIR/wait-$port.json"
  for _ in $(seq 1 40); do
    request GET "$port" "/health" "$body_file" >/dev/null
    if python3 - "$body_file" "$expr" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
sys.exit(0 if eval(sys.argv[2], {"__builtins__": {}}, {"data": data}) else 1)
PY
    then
      return 0
    fi
    sleep 0.25
  done
  fail_with_log "$label"
}

run_configured_loaded() {
  local port=$((BASE_PORT + 1))
  print_step "1" "configured app/version, JSON shape, loaded readiness, idempotent probe, methods and paths"
  start_server "configured-loaded" "$port" \
    --spring.application.name=probe-test-app \
    --openjiuwen.service.version=1.2.3-test \
    --example.health.l1.handler=loaded
  wait_json "$port" 'data["agent_loaded"] == True' "configured-loaded did not become agent_loaded=true"

  local body="$TMP_DIR/configured-health.json"
  local headers="$TMP_DIR/configured-health.headers"
  local status
  status="$(request GET "$port" "/health" "$body" "$headers")"
  assert_status "$status" "200" "GET /health"
  assert_header_contains "$headers" "content-type:.*application/json" "GET /health content type"
  assert_json "$body" \
    'data["status"] == "healthy" and data["app"] == "probe-test-app" and data["version"] == "1.2.3-test" and data["process_up"] == True and data["agent_loaded"] == True and "process_up" in data and "agent_loaded" in data and "processUp" not in data and "agentLoaded" not in data' \
    "configured health response"

  for i in 1 2 3; do
    body="$TMP_DIR/repeat-$i.json"
    status="$(request GET "$port" "/health" "$body")"
    assert_status "$status" "200" "repeat GET /health $i"
    assert_json "$body" \
      'data["status"] == "healthy" and data["app"] == "probe-test-app" and data["version"] == "1.2.3-test" and data["process_up"] == True and data["agent_loaded"] == True' \
      "repeat GET /health $i"
  done

  body="$TMP_DIR/query-param.json"
  status="$(request GET "$port" "/health?verbose=true" "$body")"
  assert_status "$status" "200" "GET /health?verbose=true"
  assert_json "$body" \
    'data["status"] == "healthy" and data["app"] == "probe-test-app" and data["version"] == "1.2.3-test"' \
    "GET /health with query parameter"

  body="$TMP_DIR/wrong-path.txt"
  status="$(request GET "$port" "/v1/health" "$body")"
  assert_status "$status" "404" "GET /v1/health"

  for method in POST PUT DELETE; do
    body="$TMP_DIR/$method-health.txt"
    status="$(request "$method" "$port" "/health" "$body")"
    assert_not_status "$status" "200" "$method /health"
  done

  stop_server
  pass "configured metadata, ready state, JSON shape, idempotency, wrong path and wrong methods"
}

run_default_version() {
  local port=$((BASE_PORT + 2))
  print_step "2" "default openjiuwen.service.version is returned"
  start_server "default-version" "$port" \
    --spring.application.name=default-version-app \
    --example.health.l1.handler=loaded
  wait_json "$port" 'data["agent_loaded"] == True' "default-version did not become agent_loaded=true"

  local body="$TMP_DIR/default-version.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "default version GET /health"
  assert_json "$body" \
    'data["app"] == "default-version-app" and data["version"] == "0.1.1.post1" and data["agent_loaded"] == True' \
    "default version response"

  stop_server
  pass "default version"
}

run_blank_identity() {
  local port=$((BASE_PORT + 3))
  print_step "3" "blank app identity falls back to agent-service"
  start_server "blank-identity" "$port" \
    --example.health.l1.identity=blank \
    --example.health.l1.handler=loaded
  wait_json "$port" 'data["agent_loaded"] == True' "blank-identity did not become agent_loaded=true"

  local body="$TMP_DIR/blank-identity.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "blank identity GET /health"
  assert_json "$body" \
    'data["app"] == "agent-service" and data["agent_loaded"] == True' \
    "blank identity fallback"

  stop_server
  pass "blank app identity fallback"
}

run_pre_init() {
  local port=$((BASE_PORT + 4))
  print_step "4" "disabled lifecycle keeps agent_loaded=false before init"
  start_server "pre-init" "$port" \
    --spring.application.name=pre-init-app \
    --example.health.l1.handler=loaded \
    --example.health.l1.lifecycle=disabled

  local body="$TMP_DIR/pre-init.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "pre-init GET /health"
  assert_json "$body" \
    'data["status"] == "healthy" and data["app"] == "pre-init-app" and data["process_up"] == True and data["agent_loaded"] == False' \
    "pre-init readiness"

  stop_server
  pass "pre-init readiness"
}

run_no_handler() {
  local port=$((BASE_PORT + 5))
  print_step "5" "no loaded handler reports agent_loaded=false while /health stays 200"
  start_server "no-handler" "$port" \
    --spring.application.name=no-handler-app \
    --example.health.l1.handler=none

  local body="$TMP_DIR/no-handler.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "no handler GET /health"
  assert_json "$body" \
    'data["status"] == "healthy" and data["app"] == "no-handler-app" and data["process_up"] == True and data["agent_loaded"] == False' \
    "no handler readiness"

  stop_server
  pass "no loaded handler readiness"
}

run_failing_start() {
  local port=$((BASE_PORT + 6))
  print_step "6" "init failure with fail-fast disabled reports agent_loaded=false"
  start_server "failing-start" "$port" \
    --spring.application.name=failing-handler-app \
    --example.health.l1.handler=failing-start \
    --openjiuwen.service.lifecycle.init-fail-fast=false

  local body="$TMP_DIR/failing-start.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "failing start GET /health"
  assert_json "$body" \
    'data["status"] == "healthy" and data["app"] == "failing-handler-app" and data["process_up"] == True and data["agent_loaded"] == False' \
    "failing start readiness"

  stop_server
  pass "init failure readiness"
}

run_shutdown() {
  local port=$((BASE_PORT + 7))
  print_step "7" "shutting down readiness reports process_up=false and agent_loaded=false"
  start_server "shutdown" "$port" \
    --spring.application.name=shutdown-app \
    --example.health.l1.handler=none \
    --example.health.l1.mode=shutdown

  local body="$TMP_DIR/shutdown.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "shutdown GET /health"
  assert_json "$body" \
    'data["status"] == "healthy" and data["process_up"] == False and data["agent_loaded"] == False' \
    "shutdown readiness"

  stop_server
  pass "shutdown readiness"
}

run_process_down() {
  local port=$((BASE_PORT + 8))
  print_step "8" "process down readiness reports process_up=false and agent_loaded=false"
  start_server "process-down" "$port" \
    --spring.application.name=process-down-app \
    --example.health.l1.handler=none \
    --example.health.l1.mode=process-down

  local body="$TMP_DIR/process-down.json"
  local status
  status="$(request GET "$port" "/health" "$body")"
  assert_status "$status" "200" "process down GET /health"
  assert_json "$body" \
    'data["status"] == "healthy" and data["process_up"] == False and data["agent_loaded"] == False' \
    "process down readiness"

  stop_server
  pass "process down readiness"
}

require_classpath
run_configured_loaded
run_default_version
run_blank_identity
run_pre_init
run_no_handler
run_failing_start
run_shutdown
run_process_down

printf '\nAll %s health L1 scenario groups passed\n' "$PASS_COUNT"
