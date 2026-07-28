#!/usr/bin/env bash
set -euo pipefail

BASE_URL_A="${BASE_URL_A:-http://localhost:18090}"
BASE_URL_B="${BASE_URL_B:-http://localhost:18091}"
BASE_URL_C="${BASE_URL_C:-http://localhost:18092}"
BASE_URL_D="${BASE_URL_D:-http://localhost:18093}"
A2A_REQUEST_TIMEOUT_SECONDS="${A2A_REQUEST_TIMEOUT_SECONDS:-300}"
CONV_ID="${CONV_ID:-a2a-demo-$(date +%Y%m%d%H%M%S)-$$}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SERVICE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
DEFAULT_API_CONFIG="$SERVICE_DIR/agent-service-demo/apiconfig.json"
TMP_DIR="$(mktemp -d)"
AGENT_A_PID=""
AGENT_B_PID=""
AGENT_C_PID=""
AGENT_D_PID=""

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
  PYTHON=python
else
  echo "FAIL: python3 or python is required for JSON parsing" >&2
  exit 1
fi

cleanup() {
  local status=$?
  if [ -n "$AGENT_A_PID" ]; then kill "$AGENT_A_PID" 2>/dev/null || true; fi
  if [ -n "$AGENT_B_PID" ]; then kill "$AGENT_B_PID" 2>/dev/null || true; fi
  if [ -n "$AGENT_C_PID" ]; then kill "$AGENT_C_PID" 2>/dev/null || true; fi
  if [ -n "$AGENT_D_PID" ]; then kill "$AGENT_D_PID" 2>/dev/null || true; fi
  if [ "$status" -eq 0 ]; then
    rm -rf "$TMP_DIR"
  else
    printf '\nLogs and responses retained in %s\n' "$TMP_DIR" >&2
  fi
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
  printf 'Logs and responses are in %s\n' "$TMP_DIR" >&2
  exit 1
}

if ! [[ "$A2A_REQUEST_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
  fail "A2A_REQUEST_TIMEOUT_SECONDS must be a positive integer"
fi

resolve_api_config() {
  local configured="$1"
  local candidate="$configured"
  if [[ "$candidate" != /* ]]; then
    candidate="$(pwd)/$candidate"
  fi
  if [ ! -f "$candidate" ] || [ ! -r "$candidate" ]; then
    fail "OPENJIUWEN_API_CONFIG does not reference a readable regular file: $candidate"
  fi
  local directory
  directory="$(cd "$(dirname "$candidate")" && pwd -P)"
  printf '%s/%s\n' "$directory" "$(basename "$candidate")"
}

if [ -n "${OPENJIUWEN_API_CONFIG:-}" ]; then
  OPENJIUWEN_API_CONFIG="$(resolve_api_config "$OPENJIUWEN_API_CONFIG")"
  export OPENJIUWEN_API_CONFIG
elif [ -r "$DEFAULT_API_CONFIG" ]; then
  OPENJIUWEN_API_CONFIG="$DEFAULT_API_CONFIG"
  export OPENJIUWEN_API_CONFIG
fi

# Maven module selection is relative to the service reactor, not the caller's
# working directory. Resolve caller-relative configuration first, then run all
# Maven commands from the reactor root so this script works from any directory.
cd "$SERVICE_DIR"

wait_for_health() {
  local url="$1"
  local label="$2"
  local pid="$3"
  local log_file="$4"
  local max=45
  for i in $(seq 1 $max); do
    if ! kill -0 "$pid" 2>/dev/null; then
      local exit_code
      if wait "$pid"; then
        exit_code=0
      else
        exit_code=$?
      fi
      fail "$label exited before becoming healthy (exit code $exit_code; log: $log_file)"
    fi
    if curl -s "$url/health" 2>/dev/null | grep -q '"status":"healthy"'; then
      return 0
    fi
    sleep 2
  done
  fail "$label did not become healthy within $((max * 2))s"
}

start_agent() {
  local main_class="$1"
  local log_file="$2"
  mvn -pl agent-service-demo/example/a2a -am spring-boot:run -q \
    -Dspring-boot.run.main-class="$main_class" \
    >"$log_file" 2>&1 &
  LAST_AGENT_PID=$!
}

write_a2a_request() {
  local method="$1"
  local request_id="$2"
  local context_id="$3"
  local task_id="$4"
  local message="$5"
  local output_file="$6"
  "$PYTHON" - "$method" "$request_id" "$context_id" "$task_id" "$message" "$output_file" <<'PY'
import json, sys

method, request_id, context_id, task_id, message, output_file = sys.argv[1:]
request_message = {
    "role": "ROLE_USER",
    "contextId": context_id,
    "parts": [{"text": message}],
}
if task_id:
    request_message["taskId"] = task_id
payload = {
    "jsonrpc": "2.0",
    "id": request_id,
    "method": method,
    "params": {"message": request_message},
}
with open(output_file, "w", encoding="utf-8") as stream:
    json.dump(payload, stream, ensure_ascii=False)
PY
}

assert_sse_task() {
  local response_file="$1"
  local expected_state="$2"
  local expected_text="${3:-}"
  local second_expected_text="${4:-}"
  local third_expected_text="${5:-}"
  "$PYTHON" - "$response_file" "$expected_state" "$expected_text" "$second_expected_text" \
    "$third_expected_text" <<'PY'
import json, sys

response_file, expected_state, expected_text, second_expected_text, third_expected_text = sys.argv[1:]
events = []
with open(response_file, encoding="utf-8") as stream:
    for line in stream:
        if line.startswith("data:"):
            events.append(json.loads(line[5:].strip()))
if not events:
    raise SystemExit("SSE response contained no JSON-RPC data events")

states = []
task_ids = []
for event in events:
    result = event.get("result") or {}
    update = result.get("statusUpdate") or result.get("artifactUpdate") or {}
    task_id = update.get("taskId")
    if task_id:
        task_ids.append(str(task_id))
    status = (result.get("statusUpdate") or {}).get("status") or {}
    if status.get("state"):
        states.append(status["state"])
if expected_state not in states:
    print(json.dumps(events, ensure_ascii=False)[:4000], file=sys.stderr)
    raise SystemExit(f"SSE response did not reach {expected_state}; states={states}")
if not task_ids or len(set(task_ids)) != 1:
    raise SystemExit(f"SSE response did not contain one stable taskId: {task_ids}")

combined = json.dumps(events, ensure_ascii=False).lower()
if "_remote_invocation" not in combined:
    print(combined[:4000], file=sys.stderr)
    raise SystemExit("SSE response did not contain remote-agent progress")
for expected in (expected_text, second_expected_text, third_expected_text):
    if expected and expected.lower() not in combined:
        print(combined[:4000], file=sys.stderr)
        raise SystemExit(f"SSE response did not contain expected text: {expected}")
print(task_ids[0])
PY
}

assert_sync_task() {
  local response_file="$1"
  local expected_state="$2"
  local expected_text="${3:-}"
  local second_expected_text="${4:-}"
  local third_expected_text="${5:-}"
  "$PYTHON" - "$response_file" "$expected_state" "$expected_text" "$second_expected_text" \
    "$third_expected_text" <<'PY'
import json, sys

response_file, expected_state, expected_text, second_expected_text, third_expected_text = sys.argv[1:]
with open(response_file, encoding="utf-8") as stream:
    response = json.load(stream)
if response.get("error"):
    raise SystemExit(f"JSON-RPC error: {response['error']}")
task = ((response.get("result") or {}).get("task") or {})
state = ((task.get("status") or {}).get("state"))
if state != expected_state:
    print(json.dumps(response, ensure_ascii=False)[:4000], file=sys.stderr)
    raise SystemExit(f"synchronous response state was {state}, expected {expected_state}")
task_id = task.get("id")
if not task_id:
    raise SystemExit("synchronous response did not contain task.id")
combined = json.dumps(response, ensure_ascii=False).lower()
if "_remote_invocation" in combined:
    print(combined[:4000], file=sys.stderr)
    raise SystemExit("synchronous response unexpectedly contained remote-agent progress")
for expected in (expected_text, second_expected_text, third_expected_text):
    if expected and expected.lower() not in combined:
        print(combined[:4000], file=sys.stderr)
        raise SystemExit(f"synchronous response did not contain expected text: {expected}")
print(task_id)
PY
}

assert_plain_terminal_artifacts() {
  local response_file="$1"
  local response_kind="$2"
  "$PYTHON" - "$response_file" "$response_kind" <<'PY'
import json, sys

response_file, response_kind = sys.argv[1:]
with open(response_file, encoding="utf-8") as stream:
    raw = stream.read()
if "\\u003d" in raw.lower():
    raise SystemExit("response contains HTML-escaped equals signs (\\u003d)")

if response_kind == "sse":
    responses = [json.loads(line[5:].strip()) for line in raw.splitlines() if line.startswith("data:")]
    artifacts = [
        (response.get("result") or {}).get("artifactUpdate", {}).get("artifact", {})
        for response in responses
        if (response.get("result") or {}).get("artifactUpdate")
    ]
else:
    response = json.loads(raw)
    task = ((response.get("result") or {}).get("task") or {})
    artifacts = task.get("artifacts") or []

for artifact in artifacts:
    for part in artifact.get("parts") or []:
        text = part.get("text")
        if not isinstance(text, str):
            continue
        try:
            envelope = json.loads(text)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(envelope, dict) and envelope.get("type") in ("answer", "workflow_final") \
                and "payload" in envelope:
            raise SystemExit("AgentCore terminal envelope leaked into parts.text")
PY
}

assert_calculation_result() {
  local response_file="$1"
  local expected_result="$2"
  "$PYTHON" - "$response_file" "$expected_result" <<'PY'
import json, re, sys

response_file, expected_result = sys.argv[1:]
with open(response_file, encoding="utf-8") as stream:
    response = json.load(stream)
task = ((response.get("result") or {}).get("task") or {})
texts = []
for artifact in task.get("artifacts") or []:
    for part in artifact.get("parts") or []:
        text = part.get("text")
        if text is not None:
            texts.append(str(text).strip())
if any(text.lower() == "ok" for text in texts):
    raise SystemExit("calculator returned the confirmation text instead of a result")
result_pattern = re.compile(rf"(?:^|\D){re.escape(expected_result)}(?:\D|$)")
if not any(result_pattern.search(text) for text in texts):
    print(json.dumps(response, ensure_ascii=False)[:4000], file=sys.stderr)
    raise SystemExit(f"calculator artifacts did not contain result {expected_result}")
PY
}

assert_log_contains() {
  local log_file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$log_file"; then
    fail "log $log_file did not contain: $expected"
  fi
}

assert_log_count() {
  local log_file="$1"
  local expected="$2"
  local expected_count="$3"
  local actual_count
  actual_count="$(grep -Fc "$expected" "$log_file" || true)"
  if [ "$actual_count" -ne "$expected_count" ]; then
    fail "log $log_file contained '$expected' $actual_count times; expected $expected_count"
  fi
}

# ---- Step 0: start agents ----
print_step "0a" "Starting Agent D (expense WorkflowAgent, port 18093) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentDDemoApplication" "$TMP_DIR/agent-d.log"
AGENT_D_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_D" "Agent D" "$AGENT_D_PID" "$TMP_DIR/agent-d.log"
pass "Agent D healthy on $BASE_URL_D"

print_step "0b" "Starting Agent C (DeepAgent, port 18092) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentCDemoApplication" "$TMP_DIR/agent-c.log"
AGENT_C_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_C" "Agent C" "$AGENT_C_PID" "$TMP_DIR/agent-c.log"
pass "Agent C healthy on $BASE_URL_C"

print_step "0c" "Starting Agent B (port 18091) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication" "$TMP_DIR/agent-b.log"
AGENT_B_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_B" "Agent B" "$AGENT_B_PID" "$TMP_DIR/agent-b.log"
pass "Agent B healthy on $BASE_URL_B"

print_step "0d" "Starting Agent A (port 18090) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication" "$TMP_DIR/agent-a.log"
AGENT_A_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_A" "Agent A" "$AGENT_A_PID" "$TMP_DIR/agent-a.log"
pass "Agent A healthy on $BASE_URL_A"

# ---- Step 1: Agent Cards ----
print_step "1" "GET Agent Cards"
card_a="$TMP_DIR/card-a.json"
card_b="$TMP_DIR/card-b.json"
card_c="$TMP_DIR/card-c.json"
card_d="$TMP_DIR/card-d.json"
curl -sS -o "$card_a" "$BASE_URL_A/.well-known/agent-card.json"
curl -sS -o "$card_b" "$BASE_URL_B/.well-known/agent-card.json"
curl -sS -o "$card_c" "$BASE_URL_C/.well-known/agent-card.json"
curl -sS -o "$card_d" "$BASE_URL_D/.well-known/agent-card.json"

$PYTHON - "$card_a" "$card_b" "$card_c" "$card_d" <<'PY'
import json, sys
for path, label in [(sys.argv[1], "Agent A"), (sys.argv[2], "Agent B"),
                    (sys.argv[3], "Agent C"), (sys.argv[4], "Agent D")]:
    with open(path) as f:
        data = json.load(f)
    name = data.get("name", "")
    if not name:
        print(f"FAIL: {label} agent card missing name", file=sys.stderr)
        sys.exit(1)
PY
pass "Agent Cards reachable"

# ---- Step 2: A->B calculator over non-streaming A2A ----
CALC_CONTEXT="${CONV_ID}-calc"
print_step "2a" "Round 1: trigger A->B calculator through SendMessage"
calc_request1="$TMP_DIR/calc-request-1.json"
calc_response1="$TMP_DIR/calc-response-1.json"
write_a2a_request "SendMessage" "calc-1" "$CALC_CONTEXT" "" \
  "Please calculate 1+1 through Agent B." "$calc_request1"
curl -sS --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  --data-binary "@$calc_request1" >"$calc_response1"
calc_task_id="$(assert_sync_task "$calc_response1" "TASK_STATE_INPUT_REQUIRED" "reply yes or no")"
pass "A->B calculator reached confirmation (taskId=$calc_task_id)"

print_step "2b" "Round 2: resume the same A->B calculator task"
calc_request2="$TMP_DIR/calc-request-2.json"
calc_response2="$TMP_DIR/calc-response-2.json"
write_a2a_request "SendMessage" "calc-2" "$CALC_CONTEXT" "$calc_task_id" "ok" "$calc_request2"
curl -sS --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  --data-binary "@$calc_request2" >"$calc_response2"
calc_resumed_task_id="$(assert_sync_task "$calc_response2" "TASK_STATE_COMPLETED")"
assert_calculation_result "$calc_response2" "2"
if [ "$calc_resumed_task_id" != "$calc_task_id" ]; then
  fail "A->B calculator resume changed taskId from $calc_task_id to $calc_resumed_task_id"
fi
pass "A->B calculator resumed and completed"

# ---- Step 3: A->B->C DeepAgent over streaming A2A route ----
C_STREAM_CONTEXT="${CONV_ID}-c-stream"
C_STREAM_MESSAGE="Recommend a team lunch dish through Agent C in streaming mode. Agent C must ask for confirmation."
print_step "3a" "Round 1: trigger Agent C through the streaming route"
c_stream_request1="$TMP_DIR/c-stream-request-1.json"
c_stream_response1="$TMP_DIR/c-stream-response-1.txt"
write_a2a_request "SendStreamingMessage" "c-stream-1" "$C_STREAM_CONTEXT" "" \
  "$C_STREAM_MESSAGE" "$c_stream_request1"
curl -sS -N --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' --data-binary "@$c_stream_request1" >"$c_stream_response1"
c_stream_task_id="$(assert_sse_task "$c_stream_response1" "TASK_STATE_INPUT_REQUIRED" "agent c" "confirm")"
pass "Agent C streaming route reached confirmation (taskId=$c_stream_task_id)"

print_step "3b" "Round 2: resume the same Agent C streaming task"
c_stream_request2="$TMP_DIR/c-stream-request-2.json"
c_stream_response2="$TMP_DIR/c-stream-response-2.txt"
write_a2a_request "SendStreamingMessage" "c-stream-2" "$C_STREAM_CONTEXT" "$c_stream_task_id" \
  "approved" "$c_stream_request2"
curl -sS -N --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' --data-binary "@$c_stream_request2" >"$c_stream_response2"
c_stream_resumed_task_id="$(assert_sse_task "$c_stream_response2" "TASK_STATE_COMPLETED" \
  "agent c" "kung pao chicken")"
if [ "$c_stream_resumed_task_id" != "$c_stream_task_id" ]; then
  fail "Agent C streaming resume changed taskId from $c_stream_task_id to $c_stream_resumed_task_id"
fi
pass "Agent C streaming route resumed and completed"

# ---- Step 4: A->B->C DeepAgent over non-streaming A2A route ----
C_NONSTREAM_CONTEXT="${CONV_ID}-c-nonstream"
C_NONSTREAM_MESSAGE="Recommend a team lunch dish through Agent C in non-streaming mode. Agent C must ask for confirmation."
print_step "4a" "Round 1: trigger Agent C through the non-streaming route"
c_nonstream_request1="$TMP_DIR/c-nonstream-request-1.json"
c_nonstream_response1="$TMP_DIR/c-nonstream-response-1.json"
write_a2a_request "SendMessage" "c-nonstream-1" "$C_NONSTREAM_CONTEXT" "" \
  "$C_NONSTREAM_MESSAGE" "$c_nonstream_request1"
curl -sS --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  --data-binary "@$c_nonstream_request1" >"$c_nonstream_response1"
c_nonstream_task_id="$(assert_sync_task "$c_nonstream_response1" "TASK_STATE_INPUT_REQUIRED" "agent c" "confirm")"
pass "Agent C non-streaming route reached confirmation (taskId=$c_nonstream_task_id)"

print_step "4b" "Round 2: resume the same Agent C non-streaming task"
c_nonstream_request2="$TMP_DIR/c-nonstream-request-2.json"
c_nonstream_response2="$TMP_DIR/c-nonstream-response-2.json"
write_a2a_request "SendMessage" "c-nonstream-2" "$C_NONSTREAM_CONTEXT" "$c_nonstream_task_id" \
  "approved" "$c_nonstream_request2"
curl -sS --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  --data-binary "@$c_nonstream_request2" >"$c_nonstream_response2"
c_nonstream_resumed_task_id="$(assert_sync_task "$c_nonstream_response2" "TASK_STATE_COMPLETED" \
  "agent c" "kung pao chicken")"
if [ "$c_nonstream_resumed_task_id" != "$c_nonstream_task_id" ]; then
  fail "Agent C non-streaming resume changed taskId from $c_nonstream_task_id to $c_nonstream_resumed_task_id"
fi
pass "Agent C non-streaming route resumed and completed"

# ---- Step 5: A->B->D WorkflowAgent over the streaming A2A route ----
D_STREAM_CONTEXT="${CONV_ID}-d-stream"
D_STREAM_CLAIM="WF-STREAM-001"
D_STREAM_MESSAGE="Review expense claim $D_STREAM_CLAIM through Agent D in streaming mode: category hotel, 3 nights, unit_price 1000 CNY, total 3000 CNY, currency CNY. Preserve every value exactly."
print_step "5a" "Round 1: trigger Agent D through the streaming route"
d_stream_request1="$TMP_DIR/d-stream-request-1.json"
d_stream_response1="$TMP_DIR/d-stream-response-1.txt"
write_a2a_request "SendStreamingMessage" "d-stream-1" "$D_STREAM_CONTEXT" "" \
  "$D_STREAM_MESSAGE" "$d_stream_request1"
curl -sS -N --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' --data-binary "@$d_stream_request1" >"$d_stream_response1"
d_stream_task_id="$(assert_sse_task "$d_stream_response1" "TASK_STATE_INPUT_REQUIRED" \
  "manual approval" "$D_STREAM_CLAIM")"
pass "Agent D streaming route reached manual approval (taskId=$d_stream_task_id)"

print_step "5b" "Round 2: approve and resume the same Agent D streaming task"
d_stream_request2="$TMP_DIR/d-stream-request-2.json"
d_stream_response2="$TMP_DIR/d-stream-response-2.txt"
write_a2a_request "SendStreamingMessage" "d-stream-2" "$D_STREAM_CONTEXT" "$d_stream_task_id" \
  "approved" "$d_stream_request2"
curl -sS -N --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' --data-binary "@$d_stream_request2" >"$d_stream_response2"
d_stream_resumed_task_id="$(assert_sse_task "$d_stream_response2" "TASK_STATE_COMPLETED" \
  "policy_status" "$D_STREAM_CLAIM" "llm_report")"
assert_plain_terminal_artifacts "$d_stream_response2" "sse"
if [ "$d_stream_resumed_task_id" != "$d_stream_task_id" ]; then
  fail "Agent D streaming resume changed taskId from $d_stream_task_id to $d_stream_resumed_task_id"
fi
pass "Agent D streaming route resumed through the final LLM and completed"

# ---- Step 6: A->B->D WorkflowAgent over the non-streaming A2A route ----
D_NONSTREAM_CONTEXT="${CONV_ID}-d-nonstream"
D_NONSTREAM_CLAIM="WF-NONSTREAM-001"
D_NONSTREAM_MESSAGE="Review expense claim $D_NONSTREAM_CLAIM through Agent D in non-streaming mode: category hotel, 3 nights, unit_price 1000 CNY, total 3000 CNY, currency CNY. Preserve every value exactly."
print_step "6a" "Round 1: trigger Agent D through the non-streaming route"
d_nonstream_request1="$TMP_DIR/d-nonstream-request-1.json"
d_nonstream_response1="$TMP_DIR/d-nonstream-response-1.json"
write_a2a_request "SendMessage" "d-nonstream-1" "$D_NONSTREAM_CONTEXT" "" \
  "$D_NONSTREAM_MESSAGE" "$d_nonstream_request1"
curl -sS --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  --data-binary "@$d_nonstream_request1" >"$d_nonstream_response1"
d_nonstream_task_id="$(assert_sync_task "$d_nonstream_response1" "TASK_STATE_INPUT_REQUIRED" \
  "manual approval" "$D_NONSTREAM_CLAIM")"
pass "Agent D non-streaming route reached manual approval (taskId=$d_nonstream_task_id)"

print_step "6b" "Round 2: approve and resume the same Agent D non-streaming task"
d_nonstream_request2="$TMP_DIR/d-nonstream-request-2.json"
d_nonstream_response2="$TMP_DIR/d-nonstream-response-2.json"
write_a2a_request "SendMessage" "d-nonstream-2" "$D_NONSTREAM_CONTEXT" "$d_nonstream_task_id" \
  "approved" "$d_nonstream_request2"
curl -sS --max-time "$A2A_REQUEST_TIMEOUT_SECONDS" -X POST "$BASE_URL_A/a2a/" \
  -H 'Content-Type: application/json' \
  --data-binary "@$d_nonstream_request2" >"$d_nonstream_response2"
d_nonstream_resumed_task_id="$(assert_sync_task "$d_nonstream_response2" "TASK_STATE_COMPLETED" \
  "policy_status" "$D_NONSTREAM_CLAIM" "llm_report")"
assert_plain_terminal_artifacts "$d_nonstream_response2" "sync"
if [ "$d_nonstream_resumed_task_id" != "$d_nonstream_task_id" ]; then
  fail "Agent D non-streaming resume changed taskId from $d_nonstream_task_id to $d_nonstream_resumed_task_id"
fi
pass "Agent D non-streaming route resumed through the final LLM and completed"

# A remote hop streams only when both the inbound request and route configuration
# enable streaming. Assert both effective modes and every configured downstream route.
assert_log_contains "$TMP_DIR/agent-a.log" "A2A call agent=agentb streaming=true"
assert_log_contains "$TMP_DIR/agent-a.log" "A2A call agent=agentb streaming=false"
assert_log_contains "$TMP_DIR/agent-b.log" "A2A call agent=agentc-streaming streaming=true"
assert_log_contains "$TMP_DIR/agent-b.log" "A2A call agent=agentc-nonstreaming streaming=false"
assert_log_contains "$TMP_DIR/agent-b.log" "A2A call agent=agentd-streaming streaming=true"
assert_log_contains "$TMP_DIR/agent-b.log" "A2A call agent=agentd-nonstreaming streaming=false"
assert_log_count "$TMP_DIR/agent-b.log" "A2A call agent=agentd-streaming streaming=true" 2
assert_log_count "$TMP_DIR/agent-b.log" "A2A call agent=agentd-nonstreaming streaming=false" 2
assert_log_contains "$TMP_DIR/agent-d.log" "Begin to call node [final_response]"
pass "Configured streaming and non-streaming remote routes were exercised"

printf '\nA2A demo smoke checks passed against Agent A=%s Agent B=%s Agent C=%s Agent D=%s\n' \
  "$BASE_URL_A" "$BASE_URL_B" "$BASE_URL_C" "$BASE_URL_D"
