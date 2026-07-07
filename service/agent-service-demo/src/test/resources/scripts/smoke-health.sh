#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8090}"
TMP_DIR="$(mktemp -d)"
PASS_COUNT=0

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

request() {
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

assert_not_status() {
  local actual="$1"
  local unexpected="$2"
  local label="$3"
  if [[ "$actual" == "$unexpected" ]]; then
    printf 'FAIL %s: expected HTTP status other than %s\n' "$label" "$unexpected" >&2
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

if not eval(expr, {"__builtins__": {}}, {"data": data, "isinstance": isinstance, "str": str, "len": len}):
    print(f"FAIL {label}: assertion failed", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)
    sys.exit(1)
PY
}

print_step "1" "GET /health returns HTTP 200"
body_file="$TMP_DIR/health.json"
status="$(request GET "/health" "$body_file")"
assert_status "$status" "200" "GET /health"
pass "GET /health status"

print_step "2" "health response contains expected readiness fields"
assert_json "$body_file" \
  'data["status"] == "healthy" and data["process_up"] == True and data["agent_loaded"] == True' \
  "health readiness fields"
pass "health readiness fields"

print_step "3" "health response contains app and version metadata"
assert_json "$body_file" \
  'isinstance(data["app"], str) and len(data["app"]) > 0 and isinstance(data["version"], str) and len(data["version"]) > 0' \
  "health app/version fields"
pass "health app/version fields"

print_step "4" "wrong health path is not found"
body_file="$TMP_DIR/wrong_path.txt"
status="$(request GET "/v1/health" "$body_file")"
assert_status "$status" "404" "GET /v1/health"
pass "wrong health path returns 404"

print_step "5" "POST /health is not accepted as a successful health probe"
body_file="$TMP_DIR/post_health.txt"
status="$(request POST "/health" "$body_file")"
assert_not_status "$status" "200" "POST /health"
pass "POST /health not successful"

printf '\nAll %s health smoke checks passed against %s\n' "$PASS_COUNT" "$BASE_URL"
