#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SEC_MCP_URL="${DEMO_SEC_MCP_URL:-https://api.data-apis.com/mcp}"
TMP_DIR="$(mktemp -d)"

cleanup() {
  local exit_code=$?
  if [[ "$exit_code" -eq 0 ]]; then
    rm -rf "$TMP_DIR"
  else
    printf '\nSEC Filing MCP smoke artifacts retained in %s\n' "$TMP_DIR" >&2
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

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v mvn >/dev/null 2>&1 || fail "mvn is required"

post_mcp() {
  local name="$1"
  local payload="$2"
  local http_code

  http_code="$(curl -sS --max-time 30 \
    -D "$TMP_DIR/$name.headers" \
    -o "$TMP_DIR/$name.json" \
    -w '%{http_code}' \
    -X POST "$SEC_MCP_URL" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -d "$payload")"
  [[ "$http_code" == "200" ]] || fail "$name returned HTTP $http_code"
  grep -Eiq '^content-type:[[:space:]]*application/json' "$TMP_DIR/$name.headers" \
    || fail "$name did not return Content-Type application/json"
  if grep -Eiq '^mcp-session-id:' "$TMP_DIR/$name.headers"; then
    fail "$name unexpectedly requires an MCP session"
  fi
}

print_step "1" "Verify the public SEC Filing MCP protocol at $SEC_MCP_URL"
post_mcp "initialize" \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"openjiuwen-sec-smoke","version":"1.0"}}}'
post_mcp "tools-list" \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
post_mcp "tools-call" \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sec_demo_latest_filings","arguments":{"limit":1}}}'

grep -Fq 'serverInfo' "$TMP_DIR/initialize.json" \
  || fail "initialize response did not contain serverInfo"
grep -Fq 'sec_demo_latest_filings' "$TMP_DIR/tools-list.json" \
  || fail "SEC demo tool is missing from tools/list"
grep -Fq 'companyName' "$TMP_DIR/tools-call.json" \
  || fail "tools/call returned no SEC companyName"
grep -Fq 'acceptanceDateTime' "$TMP_DIR/tools-call.json" \
  || fail "tools/call returned no SEC acceptanceDateTime"
pass "initialize, tools/list, and tools/call returned stateless JSON"

print_step "2" "Verify Core Client and the reusable Java Agent complete round trip"
(
  cd "$SERVICE_DIR"
  mvn -pl agent-service-demo/example/mcp -am \
    -Dtest=McpAgentExternalEndToEndTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddemo.mcp.e2e.server-path="$SEC_MCP_URL" \
    -Ddemo.mcp.e2e.server-id=sec-filing-agent-e2e \
    -Ddemo.mcp.e2e.server-name=sec-filing-tools \
    -Ddemo.mcp.e2e.tool-name=sec_demo_latest_filings \
    '-Ddemo.mcp.e2e.tool-arguments={"limit":1}' \
    -Ddemo.mcp.e2e.expected-content=companyName \
    -Ddemo.mcp.e2e.conversation-id=mcp-sec-external-c1 \
    test
)
pass "Core discovery, Agent tool call, SEC result refill, and audit"

printf '\nSEC Filing MCP protocol and Agent E2E checks passed against %s\n' "$SEC_MCP_URL"
