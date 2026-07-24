#!/usr/bin/env bash
set -euo pipefail

BASE_URL_A="${BASE_URL_A:-http://localhost:18090}"
BASE_URL_B="${BASE_URL_B:-http://localhost:18091}"
BASE_URL_C="${BASE_URL_C:-http://localhost:18092}"
CONV_ID="${CONV_ID:-a2a-demo-$(date +%Y%m%d%H%M%S)-$$}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
SERVICE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd -P)"
DEFAULT_API_CONFIG="$SERVICE_DIR/agent-service-demo/apiconfig.json"
TMP_DIR="$(mktemp -d)"
AGENT_A_PID=""
AGENT_B_PID=""
AGENT_C_PID=""

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

# ---- Step 0: start agents ----
print_step "0a" "Starting Agent C (DeepAgent, port 18092) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentCDemoApplication" "$TMP_DIR/agent-c.log"
AGENT_C_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_C" "Agent C" "$AGENT_C_PID" "$TMP_DIR/agent-c.log"
pass "Agent C healthy on $BASE_URL_C"

print_step "0b" "Starting Agent B (port 18091) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication" "$TMP_DIR/agent-b.log"
AGENT_B_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_B" "Agent B" "$AGENT_B_PID" "$TMP_DIR/agent-b.log"
pass "Agent B healthy on $BASE_URL_B"

print_step "0c" "Starting Agent A (port 18090) ..."
start_agent "com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication" "$TMP_DIR/agent-a.log"
AGENT_A_PID=$LAST_AGENT_PID
wait_for_health "$BASE_URL_A" "Agent A" "$AGENT_A_PID" "$TMP_DIR/agent-a.log"
pass "Agent A healthy on $BASE_URL_A"

# ---- Step 1: Agent Cards ----
print_step "1" "GET Agent Cards"
card_a="$TMP_DIR/card-a.json"
card_b="$TMP_DIR/card-b.json"
card_c="$TMP_DIR/card-c.json"
curl -sS -o "$card_a" "$BASE_URL_A/.well-known/agent-card.json"
curl -sS -o "$card_b" "$BASE_URL_B/.well-known/agent-card.json"
curl -sS -o "$card_c" "$BASE_URL_C/.well-known/agent-card.json"

$PYTHON - "$card_a" "$card_b" "$card_c" <<'PY'
import json, sys
for path, label in [(sys.argv[1], "Agent A"), (sys.argv[2], "Agent B"), (sys.argv[3], "Agent C")]:
    with open(path) as f:
        data = json.load(f)
    name = data.get("name", "")
    if not name:
        print(f"FAIL: {label} agent card missing name", file=sys.stderr)
        sys.exit(1)
PY
pass "Agent Cards reachable"

# ---- Step 2: original A->B calc path via REST API ----
CONV_ID_B="${CONV_ID}-agent-b"
print_step "2a" "Round 1: trigger original A->B calc delegation (conversation_id=$CONV_ID_B)"
b_round1_file="$TMP_DIR/round-b-1.json"
b_round1_status="$(curl -sS -o "$b_round1_file" -w '%{http_code}' -X POST "$BASE_URL_A/v1/query" \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONV_ID_B\",\"message\":\"What is 1+1? Use Agent B's ordinary calc path.\",\"stream\":false}")"

if [ "$b_round1_status" != "200" ]; then
  fail "A->B Round 1 query returned HTTP $b_round1_status"
fi

$PYTHON - "$b_round1_file" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
result = data.get("result", {})
interrupt = result.get("_interrupt") or {}
interrupt_message = str(interrupt.get("message", "")).lower()
if not interrupt:
    print("FAIL: A->B Round 1 did not return an INPUT_REQUIRED/_interrupt response", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
if "confirm" not in interrupt_message or "agent c" in interrupt_message:
    print("FAIL: A->B Round 1 did not use Agent B's ordinary calc confirmation", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
print(f"A->B Round 1 interrupt: {interrupt.get('message', '')[:300]}")
PY
pass "Original A->B calc path reached Agent B confirmation"

print_step "2b" "Round 2: resume original A->B calc path"
b_round2_file="$TMP_DIR/round-b-2.json"
b_round2_status="$(curl -sS -o "$b_round2_file" -w '%{http_code}' -X POST "$BASE_URL_A/v1/query" \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONV_ID_B\",\"message\":\"2\",\"stream\":false}")"

if [ "$b_round2_status" != "200" ]; then
  fail "A->B Round 2 query returned HTTP $b_round2_status"
fi

$PYTHON - "$b_round2_file" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
result = data.get("result", {})
content = str(result.get("content", ""))
if not content:
    print("FAIL: A->B Round 2 empty response", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
combined = content.lower()
if "2" not in combined or "agent c" in combined:
    print("FAIL: A->B Round 2 did not stay on the ordinary Agent B calc path", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
print(f"A->B Round 2: {content[:300]}")
PY
pass "Original A->B calc path completed"

# ---- Step 3: A->B->C DeepAgent interrupt scenario via REST API ----
print_step "3a" "Round 1: trigger A->B->C delegation (conversation_id=$CONV_ID)"
round1_file="$TMP_DIR/round1.json"
round1_status="$(curl -sS -o "$round1_file" -w '%{http_code}' -X POST "$BASE_URL_A/v1/query" \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONV_ID\",\"message\":\"Recommend a dish for a team lunch. Let Agent C provide the food recommendation after confirmation.\",\"stream\":false}")"

if [ "$round1_status" != "200" ]; then
  fail "A->B->C Round 1 query returned HTTP $round1_status"
fi

$PYTHON - "$round1_file" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
result = data.get("result", {})
interrupt = result.get("_interrupt") or {}
interrupt_message = str(interrupt.get("message", "")).lower()
if not interrupt:
    print("FAIL: A->B->C Round 1 did not return an INPUT_REQUIRED/_interrupt response", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
if "agent c" not in interrupt_message or not any(token in interrupt_message for token in ["confirm", "确认"]):
    print("FAIL: A->B->C Round 1 interrupt message did not come from Agent C confirmation", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
print(f"A->B->C Round 1 interrupt: {interrupt.get('message', '')[:300]}")
PY
pass "A->B->C path reached Agent C confirmation"

print_step "3b" "Round 2: resume A->B->C delegation"
round2_file="$TMP_DIR/round2.json"
round2_status="$(curl -sS -o "$round2_file" -w '%{http_code}' -X POST "$BASE_URL_A/v1/query" \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONV_ID\",\"message\":\"ok, confirmed\",\"stream\":false}")"

if [ "$round2_status" != "200" ]; then
  fail "A->B->C Round 2 query returned HTTP $round2_status"
fi

$PYTHON - "$round2_file" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
result = data.get("result", {})
content = result.get("content")
if result.get("_interrupt"):
    print("FAIL: A->B->C Round 2 remained interrupted after confirmation", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
if not isinstance(content, str) or not content.strip():
    print("FAIL: A->B->C Round 2 returned an empty response", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
combined = content.lower()
failure_markers = ["unavailable", "unable", "failed", "failure", "error", "remote_"]
recommendation_markers = ["宫保鸡丁", "kung pao"]
if ("agent c" not in combined or not any(marker in combined for marker in recommendation_markers)
        or any(marker in combined for marker in failure_markers)):
    print("FAIL: A->B->C Round 2 did not return a successful Agent C result", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False)[:1000], file=sys.stderr)
    sys.exit(1)
print(f"A->B->C Round 2: {content[:300]}")
PY
pass "A->B->C path resumed and completed"

printf '\nA2A demo smoke checks passed against Agent A=%s Agent B=%s Agent C=%s\n' "$BASE_URL_A" "$BASE_URL_B" "$BASE_URL_C"
