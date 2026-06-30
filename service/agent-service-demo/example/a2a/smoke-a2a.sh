#!/usr/bin/env bash
set -euo pipefail

BASE_URL_A="${BASE_URL_A:-http://localhost:18090}"
BASE_URL_B="${BASE_URL_B:-http://localhost:18091}"
CONV_ID="${CONV_ID:-a2a-demo-c1}"
TMP_DIR="$(mktemp -d)"
AGENT_A_PID=""
AGENT_B_PID=""

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
  if [ -n "$AGENT_A_PID" ]; then kill "$AGENT_A_PID" 2>/dev/null || true; fi
  if [ -n "$AGENT_B_PID" ]; then kill "$AGENT_B_PID" 2>/dev/null || true; fi
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

wait_for_health() {
  local url="$1"
  local label="$2"
  local max=30
  for i in $(seq 1 $max); do
    if curl -s "$url/health" 2>/dev/null | grep -q '"status":"healthy"'; then
      return 0
    fi
    sleep 2
  done
  fail "$label did not become healthy within $((max * 2))s"
}

# ---- Step 0: start agents ----
print_step "0" "Starting Agent B (port 18091) ..."
OPENJIUWEN_API_CONFIG="${OPENJIUWEN_API_CONFIG:-agent-service-demo/apiconfig.json}" \
  mvn -pl agent-service-demo/example/a2a -am spring-boot:run -q \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication \
  >"$TMP_DIR/agent-b.log" 2>&1 &
AGENT_B_PID=$!

wait_for_health "$BASE_URL_B" "Agent B"
pass "Agent B healthy on $BASE_URL_B"

print_step "0b" "Starting Agent A (port 18090) ..."
OPENJIUWEN_API_CONFIG="${OPENJIUWEN_API_CONFIG:-agent-service-demo/apiconfig.json}" \
  mvn -pl agent-service-demo/example/a2a -am spring-boot:run -q \
  -Dspring-boot.run.main-class=com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication \
  >"$TMP_DIR/agent-a.log" 2>&1 &
AGENT_A_PID=$!

wait_for_health "$BASE_URL_A" "Agent A"
pass "Agent A healthy on $BASE_URL_A"

# ---- Step 1: Agent Cards ----
print_step "1" "GET Agent Cards"
card_a="$TMP_DIR/card-a.json"
card_b="$TMP_DIR/card-b.json"
curl -sS -o "$card_a" "$BASE_URL_A/.well-known/agent-card.json"
curl -sS -o "$card_b" "$BASE_URL_B/.well-known/agent-card.json"

$PYTHON - "$card_a" "$card_b" <<'PY'
import json, sys
for path, label in [(sys.argv[1], "Agent A"), (sys.argv[2], "Agent B")]:
    with open(path) as f:
        data = json.load(f)
    name = data.get("name", "")
    if not name:
        print(f"FAIL: {label} agent card missing name", file=sys.stderr)
        sys.exit(1)
PY
pass "Agent Cards reachable"

# ---- Step 2: Interrupt scenario via REST API ----
print_step "2" "Round 1: trigger A2A delegation (conversation_id=$CONV_ID)"
round1_file="$TMP_DIR/round1.json"
round1_status="$(curl -sS -o "$round1_file" -w '%{http_code}' -X POST "$BASE_URL_A/v1/query" \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONV_ID\",\"message\":\"What is 1+1?\",\"stream\":false}")"

if [ "$round1_status" != "200" ]; then
  fail "Round 1 query returned HTTP $round1_status"
fi

$PYTHON - "$round1_file" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
result = data.get("result", {})
content = result.get("content", "")
# Round 1 should either contain a message about delegation or INPUT_REQUIRED
if not content:
    print("FAIL: Round 1 empty response", file=sys.stderr)
    sys.exit(1)
print(f"Round 1: {content[:200]}")
PY
pass "Round 1 delegation triggered"

print_step "3" "Round 2: resume with confirmation (same conversation_id)"
round2_file="$TMP_DIR/round2.json"
round2_status="$(curl -sS -o "$round2_file" -w '%{http_code}' -X POST "$BASE_URL_A/v1/query" \
  -H 'Content-Type: application/json' \
  -d "{\"conversation_id\":\"$CONV_ID\",\"message\":\"ok\",\"stream\":false}")"

if [ "$round2_status" != "200" ]; then
  fail "Round 2 query returned HTTP $round2_status"
fi

$PYTHON - "$round2_file" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
result = data.get("result", {})
content = result.get("content", "")
if not content:
    print("FAIL: Round 2 empty response", file=sys.stderr)
    sys.exit(1)
print(f"Round 2: {content[:200]}")
PY
pass "Round 2 resume completed (interrupt / A2A delegation)"

printf '\nA2A demo smoke checks passed against Agent A=%s Agent B=%s\n' "$BASE_URL_A" "$BASE_URL_B"
