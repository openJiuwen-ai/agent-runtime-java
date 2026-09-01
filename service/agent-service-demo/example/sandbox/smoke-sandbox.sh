#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SANDBOX_URL="${OPENJIUWEN_SANDBOX_SERVICE_URL:-http://127.0.0.1:8321}"
SANDBOX_URL="${SANDBOX_URL%/}"
TMP_DIR="$(mktemp -d)"
LOG_DIR="$TMP_DIR/logs"

cleanup() {
  local exit_code=$?
  if [[ "$exit_code" -eq 0 ]]; then
    rm -rf "$TMP_DIR"
  else
    printf '\nSandbox smoke artifacts retained in %s\n' "$TMP_DIR" >&2
  fi
}
trap cleanup EXIT

print_step() {
  printf '\n[%s] %s\n' "$1" "$2"
}

fail() {
  printf 'FAIL %s\n' "$1" >&2
  exit 1
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v mvn >/dev/null 2>&1 || fail "mvn is required"

print_step "1" "Check the external JiuwenBox service"
if ! curl -fsS --max-time 5 "$SANDBOX_URL/health" > "$TMP_DIR/health.json"; then
  printf 'JiuwenBox is not reachable at %s\n' "$SANDBOX_URL" >&2
  printf 'Start it first by following: %s\n' \
    'https://gitcode.com/openJiuwen/jiuwenswarm/blob/develop/jiuwenbox/README_CN.md' >&2
  fail "external JiuwenBox health check failed"
fi
printf 'PASS JiuwenBox is ready at %s\n' "$SANDBOX_URL"

print_step "2" "Run the Agent, three Sandbox tools, timeout, circuit breaker, and audit E2E"
(
  cd "$SERVICE_DIR"
  LOG_HOME="$LOG_DIR" mvn -pl agent-service-demo/example/sandbox -am \
    -Dtest=SandboxAgentExternalEndToEndTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddemo.sandbox.e2e.service-url="$SANDBOX_URL" \
    test
) 2>&1 | tee "$TMP_DIR/maven.log"

RUN_LOG="$LOG_DIR/run/run.log"
[[ -f "$RUN_LOG" ]] || fail "audit log was not written to $RUN_LOG"
grep -F 'EXTERNAL_CALL_AUDIT' "$RUN_LOG" | grep -Fq 'method=shell.executeCmd' \
  || fail "shell.executeCmd audit entry was not written"
grep -F 'EXTERNAL_CALL_AUDIT' "$RUN_LOG" | grep -Fq 'method=fs.readFile' \
  || fail "fs.readFile audit entry was not written"
grep -F 'EXTERNAL_CALL_AUDIT' "$RUN_LOG" | grep -Fq 'method=code.executeCode' \
  || fail "code.executeCode audit entry was not written"
grep -F 'EXTERNAL_CALL_AUDIT' "$RUN_LOG" | grep -Fq 'code=EXT_SANDBOX_004' \
  || fail "Sandbox timeout audit entry was not written"

printf '\nSandbox smoke checks passed against JiuwenBox at %s\n' "$SANDBOX_URL"
