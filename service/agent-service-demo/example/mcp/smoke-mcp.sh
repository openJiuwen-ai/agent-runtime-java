#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SERVER_SCRIPT="$SCRIPT_DIR/server/fastmcp_server.py"
REQUIREMENTS_FILE="$SCRIPT_DIR/server/requirements.txt"
PYTHON_COMMAND="${DEMO_FASTMCP_PYTHON:-}"
VENV_DIR="${DEMO_FASTMCP_VENV:-$SCRIPT_DIR/target/fastmcp-venv}"
SERVER_HOST="${DEMO_FASTMCP_HOST:-127.0.0.1}"
SERVER_PORT="${DEMO_FASTMCP_PORT:-18080}"
MCP_URL="http://$SERVER_HOST:$SERVER_PORT/mcp"
TMP_DIR="$(mktemp -d)"
SERVER_PID=""

cleanup() {
  local exit_code=$?
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [[ "$exit_code" -eq 0 ]]; then
    rm -rf "$TMP_DIR"
  else
    printf '\nMCP smoke artifacts retained in %s\n' "$TMP_DIR" >&2
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
  exit 1
}

post_mcp() {
  local name="$1"
  local payload="$2"
  local http_code

  http_code="$(curl -sS --max-time 10 \
    -D "$TMP_DIR/$name.headers" \
    -o "$TMP_DIR/$name.json" \
    -w '%{http_code}' \
    -X POST "$MCP_URL" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -d "$payload")"
  [[ "$http_code" == "200" ]] || fail "$name returned HTTP $http_code"
  grep -Eiq '^content-type:[[:space:]]*application/json' "$TMP_DIR/$name.headers" \
    || fail "$name did not return Content-Type application/json"
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v mvn >/dev/null 2>&1 || fail "mvn is required"

print_step "1" "Prepare the isolated official MCP Python SDK environment"
if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  if [[ -z "$PYTHON_COMMAND" ]]; then
    for candidate in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
      if command -v "$candidate" >/dev/null 2>&1 \
        && "$candidate" -c 'import sys; raise SystemExit(sys.version_info < (3, 10))'; then
        PYTHON_COMMAND="$candidate"
        break
      fi
    done
  fi
  [[ -n "$PYTHON_COMMAND" ]] || fail "Python 3.10 or newer is required"
  command -v "$PYTHON_COMMAND" >/dev/null 2>&1 || fail "$PYTHON_COMMAND is required"
  "$PYTHON_COMMAND" -c 'import sys; raise SystemExit(sys.version_info < (3, 10))' \
    || fail "Python 3.10 or newer is required"
  "$PYTHON_COMMAND" -m venv "$VENV_DIR"
fi
"$VENV_DIR/bin/python" -c 'import sys; raise SystemExit(sys.version_info < (3, 10))' \
  || fail "FastMCP virtual environment must use Python 3.10 or newer"
requirements_checksum="$(cksum "$REQUIREMENTS_FILE" | awk '{print $1 ":" $2}')"
installed_checksum=""
if [[ -f "$VENV_DIR/.requirements-checksum" ]]; then
  installed_checksum="$(<"$VENV_DIR/.requirements-checksum")"
fi
if [[ "$requirements_checksum" != "$installed_checksum" ]]; then
  "$VENV_DIR/bin/python" -m pip install -r "$REQUIREMENTS_FILE" > "$TMP_DIR/pip-install.log" 2>&1
  printf '%s' "$requirements_checksum" > "$VENV_DIR/.requirements-checksum"
fi
"$VENV_DIR/bin/python" -c 'from mcp.server.fastmcp import FastMCP' \
  || fail "official MCP Python SDK is not installed"
pass "official MCP Python SDK environment ready"

print_step "2" "Start FastMCP as an independent process"
DEMO_FASTMCP_HOST="$SERVER_HOST" \
DEMO_FASTMCP_PORT="$SERVER_PORT" \
"$VENV_DIR/bin/python" "$SERVER_SCRIPT" > "$TMP_DIR/fastmcp-server.log" 2>&1 &
SERVER_PID=$!

sleep 0.2
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
  tail -n 20 "$TMP_DIR/fastmcp-server.log" >&2
  fail "FastMCP exited during startup; check whether $SERVER_HOST:$SERVER_PORT is already in use"
fi

ready=false
for _ in $(seq 1 80); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    tail -n 20 "$TMP_DIR/fastmcp-server.log" >&2
    fail "FastMCP exited before readiness"
  fi
  http_code="$(curl -s --max-time 2 -o "$TMP_DIR/readiness.json" -w '%{http_code}' \
    -X POST "$MCP_URL" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"openjiuwen-readiness","version":"1.0"}}}' \
    2>/dev/null || true)"
  if [[ "$http_code" == "200" ]]; then
    ready=true
    break
  fi
  sleep 0.25
done
[[ "$ready" == "true" ]] || fail "FastMCP server did not become ready"
pass "independent FastMCP server ready at $MCP_URL"

print_step "3" "Verify FastMCP initialize, tools/list, and tools/call JSON responses"
post_mcp "initialize" \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"openjiuwen-smoke","version":"1.0"}}}'
post_mcp "tools-list" \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
post_mcp "tools-call" \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"demo_echo","arguments":{"text":"smoke"}}}'

grep -Fq 'openjiuwen-demo-fastmcp' "$TMP_DIR/initialize.json" \
  || fail "initialize did not return FastMCP server information"
grep -Fq 'demo_echo' "$TMP_DIR/tools-list.json" \
  || fail "tools/list did not contain demo_echo"
grep -Fq 'demo_delay' "$TMP_DIR/tools-list.json" \
  || fail "tools/list did not contain demo_delay"
grep -Fq 'demo_fail' "$TMP_DIR/tools-list.json" \
  || fail "tools/list did not contain demo_fail"
grep -Fq 'demo_echo:smoke' "$TMP_DIR/tools-call.json" \
  || fail "tools/call did not return demo_echo:smoke"
pass "FastMCP returned stateless application/json responses"

print_step "4" "Run the MCP Demo Agent and governance E2E tests"
(
  cd "$SERVICE_DIR"
  mvn -pl agent-service-adapters/agent-service-adapters-agentcore,agent-service-demo/example/mcp -am \
    -Dtest=McpGovernanceIntegrationTest,McpAgentExternalEndToEndTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddemo.mcp.integration.server-path="$MCP_URL" \
    -Ddemo.mcp.e2e.server-path="$MCP_URL" \
    test
) 2>&1 | tee "$TMP_DIR/maven.log"

grep -Fq 'MCP_TOOL_CALL tool=demo_echo arguments={"text": "hello"}' "$TMP_DIR/fastmcp-server.log" \
  || fail "FastMCP did not receive the Agent demo_echo tools/call"
grep -F 'EXTERNAL_CALL_AUDIT' "$TMP_DIR/maven.log" | grep -Fq 'method=mcp.tools/call' \
  || fail "MCP tools/call audit entry was not emitted"
pass "configuration, client, decorator, Agent tool call, FastMCP, and result refill"

printf '\nMCP smoke checks passed against FastMCP at %s\n' "$MCP_URL"
