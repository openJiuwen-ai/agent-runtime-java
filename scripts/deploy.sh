#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="${EDP_DEPLOY_CONFIG:-"$ROOT_DIR/config/deploy.properties"}"
EXAMPLE_CONFIG="$ROOT_DIR/config/deploy.properties.example"
RUNTIME_DIR="$ROOT_DIR/runtime"
PID_DIR="$RUNTIME_DIR/pids"
LOG_DIR="$RUNTIME_DIR/logs"

usage() {
  cat <<'EOF'
Usage:
  scripts/deploy.sh init-config
  scripts/deploy.sh build
  scripts/deploy.sh start
  scripts/deploy.sh stop
  scripts/deploy.sh restart
  scripts/deploy.sh status
  scripts/deploy.sh probe

Configuration:
  Copy config/deploy.properties.example to config/deploy.properties and fill real values.
  Or set EDP_DEPLOY_CONFIG=/path/to/deploy.properties.
EOF
}

load_config() {
  if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "Missing config: $CONFIG_FILE" >&2
    echo "Run: scripts/deploy.sh init-config" >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
}

init_dirs() {
  mkdir -p "$PID_DIR" "$LOG_DIR"
}

init_config() {
  if [[ -f "$CONFIG_FILE" ]]; then
    echo "Config already exists: $CONFIG_FILE"
    return
  fi
  mkdir -p "$(dirname "$CONFIG_FILE")"
  cp "$EXAMPLE_CONFIG" "$CONFIG_FILE"
  echo "Created config: $CONFIG_FILE"
}

build() {
  load_config
  local mvn_bin="${MAVEN_BIN:-mvn}"
  (cd "$ROOT_DIR" && "$mvn_bin" -U -pl a2a_service,versatile_adapter -am package -DskipTests)
}

require_file() {
  local path="$1"
  local hint="$2"
  if [[ ! -f "$path" ]]; then
    echo "Missing file: $path" >&2
    echo "$hint" >&2
    exit 1
  fi
}

validate_config() {
  local missing=0
  for key in REDIS_HOST LLM_API_KEY VERSATILE_URL_TEMPLATE VA_WORKFLOW_RESULT_NODE; do
    if [[ -z "${!key:-}" || "${!key}" == REAL_* || "${!key}" == *REAL_* ]]; then
      echo "Config value is required: $key" >&2
      missing=1
    fi
  done
  if [[ "${REDIS_HOST:-}" == "REDIS_HOST_OR_IP" ]]; then
    echo "Config value is required: REDIS_HOST" >&2
    missing=1
  fi
  if [[ "$missing" -ne 0 ]]; then
    exit 1
  fi
}

pid_file() {
  echo "$PID_DIR/$1.pid"
}

is_running() {
  local pid_path
  pid_path="$(pid_file "$1")"
  [[ -f "$pid_path" ]] || return 1
  local pid
  pid="$(cat "$pid_path")"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

start_redis_if_needed() {
  if [[ "${REDIS_START_MODE:-external}" != "docker" ]]; then
    return
  fi
  if ! command -v docker >/dev/null 2>&1; then
    echo "REDIS_START_MODE=docker but docker is not available" >&2
    exit 1
  fi
  local name="${REDIS_DOCKER_NAME:-edp-redis}"
  if docker ps --format '{{.Names}}' | grep -Fxq "$name"; then
    echo "Redis docker container already running: $name"
    return
  fi
  if docker ps -a --format '{{.Names}}' | grep -Fxq "$name"; then
    docker start "$name" >/dev/null
  else
    docker run -d --name "$name" -p "${REDIS_PORT:-6379}:6379" "${REDIS_DOCKER_IMAGE:-redis:7}" >/dev/null
  fi
  echo "Redis docker container started: $name"
}

start_process() {
  local name="$1"
  shift
  if is_running "$name"; then
    echo "$name already running, pid=$(cat "$(pid_file "$name")")"
    return
  fi
  local log_path="$LOG_DIR/$name.log"
  echo "Starting $name, log=$log_path"
  (
    cd "$ROOT_DIR"
    nohup "$@" >"$log_path" 2>&1 &
    echo $! >"$(pid_file "$name")"
  )
}

wait_health() {
  local name="$1"
  local url="$2"
  local max_seconds="${3:-60}"
  echo -n "Waiting for $name health "
  for _ in $(seq 1 "$max_seconds"); do
    if curl -fs "$url" >/dev/null 2>&1; then
      echo "OK"
      return 0
    fi
    echo -n "."
    sleep 1
  done
  echo "FAILED"
  echo "Check log: $LOG_DIR/$name.log" >&2
  return 1
}

stop_process() {
  local name="$1"
  local pid_path
  pid_path="$(pid_file "$name")"
  if [[ ! -f "$pid_path" ]]; then
    echo "$name not running"
    return
  fi
  local pid
  pid="$(cat "$pid_path")"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    echo "Stopping $name, pid=$pid"
    kill "$pid" 2>/dev/null || true
    for _ in {1..30}; do
      if ! kill -0 "$pid" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "Force stopping $name, pid=$pid"
      kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$pid_path"
}

start_services() {
  load_config
  validate_config
  init_dirs
  require_file "$ROOT_DIR/a2a_service/target/a2a-service-0.1.7.jar" "Run: scripts/deploy.sh build"
  require_file "$ROOT_DIR/versatile_adapter/target/versatile-adapter-0.1.7.jar" "Run: scripts/deploy.sh build"
  start_redis_if_needed

  local java_bin="${JAVA_BIN:-java}"
  local java_opts=()
  if [[ -n "${JAVA_OPTS:-}" ]]; then
    # Intentionally split JAVA_OPTS like a shell command line.
    # shellcheck disable=SC2206
    java_opts=(${JAVA_OPTS})
  fi

  start_process versatile_adapter \
    "$java_bin" "${java_opts[@]}" -jar "$ROOT_DIR/versatile_adapter/target/versatile-adapter-0.1.7.jar" \
    "--server.port=${VERSATILE_ADAPTER_PORT:-8081}" \
    "--versatile-adapter.versatile-url-template=${VERSATILE_URL_TEMPLATE}" \
    "--versatile-adapter.versatile-timeout=${VERSATILE_TIMEOUT:-600}"
  wait_health versatile_adapter "http://127.0.0.1:${VERSATILE_ADAPTER_PORT:-8081}/health" 60

  export MCP_XSQ_URL="${MCP_XSQ_URL:-}"
  export MCP_JD_URL="${MCP_JD_URL:-}"
  export MCP_ACCESS_TOKEN="${MCP_ACCESS_TOKEN:-}"
  export MCP_APP_NAME="${MCP_APP_NAME:-}"
  export MCP_TIMEOUT="${MCP_TIMEOUT:-25}"

  local redis_password_args=()
  if [[ -n "${REDIS_PASSWORD:-}" ]]; then
    redis_password_args+=("--a2a-service.redis-password=${REDIS_PASSWORD}")
    redis_password_args+=("--dpa.redis-password=${REDIS_PASSWORD}")
  fi

  local optional_llm_args=()
  [[ -n "${LLM_TOKEN:-}" ]] && optional_llm_args+=("--dpa.llm-token=${LLM_TOKEN}")
  [[ -n "${LLM_TOKEN_HEADER:-}" ]] && optional_llm_args+=("--dpa.llm-token-header=${LLM_TOKEN_HEADER}")
  [[ -n "${LLM_USER_ID:-}" ]] && optional_llm_args+=("--dpa.llm-user-id=${LLM_USER_ID}")
  [[ -n "${LLM_USER_ID_HEADER:-}" ]] && optional_llm_args+=("--dpa.llm-user-id-header=${LLM_USER_ID_HEADER}")
  if declare -p LLM_EXTRA_HEADER_ARGS >/dev/null 2>&1; then
    # shellcheck disable=SC2154
    optional_llm_args+=("${LLM_EXTRA_HEADER_ARGS[@]}")
  fi

  start_process a2a_service \
    "$java_bin" "${java_opts[@]}" -jar "$ROOT_DIR/a2a_service/target/a2a-service-0.1.7.jar" \
    "--server.port=${A2A_PORT:-8080}" \
    "--a2a-service.redis-host=${REDIS_HOST:-127.0.0.1}" \
    "--a2a-service.redis-port=${REDIS_PORT:-6379}" \
    "--a2a-service.redis-db=${REDIS_DB:-0}" \
    "--a2a-service.redis-session-ttl=${REDIS_SESSION_TTL:-1800}" \
    "--dpa.redis-host=${REDIS_HOST:-127.0.0.1}" \
    "--dpa.redis-port=${REDIS_PORT:-6379}" \
    "--dpa.redis-db=${REDIS_DB:-0}" \
    "--dpa.redis-checkpointer-ttl-minutes=${DPA_REDIS_CHECKPOINTER_TTL_MINUTES:-60}" \
    "${redis_password_args[@]}" \
    "--a2a-service.versatile-adapter-url=${VERSATILE_ADAPTER_URL:-http://127.0.0.1:8081/}" \
    "--a2a-service.va-workflow-result-node=${VA_WORKFLOW_RESULT_NODE}" \
    "--a2a-service.rate-limit-max-requests=${RATE_LIMIT_MAX_REQUESTS:-100}" \
    "--a2a-service.rate-limit-window-seconds=${RATE_LIMIT_WINDOW_SECONDS:-10}" \
    "--a2a-service.global-rate-limit-max-requests=${GLOBAL_RATE_LIMIT_MAX_REQUESTS:-100}" \
    "--a2a-service.global-rate-limit-window-seconds=${GLOBAL_RATE_LIMIT_WINDOW_SECONDS:-10}" \
    "--dpa.llm-provider=${LLM_PROVIDER:-OpenAI}" \
    "--dpa.llm-api-base=${LLM_API_BASE}" \
    "--dpa.llm-model-name=${LLM_MODEL_NAME}" \
    "--dpa.llm-api-key=${LLM_API_KEY}" \
    "--dpa.llm-verify-ssl=${LLM_VERIFY_SSL:-false}" \
    "--dpa.llm-timeout=${LLM_TIMEOUT:-120}" \
    "${optional_llm_args[@]}"
  wait_health a2a_service "http://127.0.0.1:${A2A_PORT:-8080}/health" 120
}

stop_services() {
  init_dirs
  stop_process a2a_service
  stop_process versatile_adapter
  if [[ -f "$CONFIG_FILE" ]]; then
    load_config
    if [[ "${REDIS_START_MODE:-external}" == "docker" && -n "${REDIS_DOCKER_NAME:-}" ]] && command -v docker >/dev/null 2>&1; then
      docker stop "${REDIS_DOCKER_NAME}" >/dev/null 2>&1 || true
    fi
  fi
}

status_services() {
  init_dirs
  for name in versatile_adapter a2a_service; do
    if is_running "$name"; then
      echo "$name: RUNNING pid=$(cat "$(pid_file "$name")")"
    else
      echo "$name: STOPPED"
    fi
  done
}

probe() {
  load_config
  local conv="${PROBE_CONVERSATION_ID:-c-probe}"
  local project="${PROBE_PROJECT_ID:-demo}"
  local agent="${PROBE_AGENT_ID:-edp_agent}"
  local host="${PROBE_HOST:-127.0.0.1}"
  local url="http://$host:${A2A_PORT:-8080}/v1/$project/agents/$agent/conversations/$conv"
  curl -N -H 'Content-Type: application/json' \
    -X POST "$url" \
    -d "{\"agent_id\":\"$agent\",\"input\":{\"query\":\"flow-control-test:$conv\"},\"conversation_id\":\"$conv\",\"stream\":true,\"custom_data\":{\"inputs\":{\"query\":\"flow-control-test:$conv\"}}}"
}

main() {
  local command="${1:-}"
  case "$command" in
    init-config) init_config ;;
    build) build ;;
    start) start_services ;;
    stop) stop_services ;;
    restart) stop_services; start_services ;;
    status) status_services ;;
    probe) probe ;;
    ""|-h|--help|help) usage ;;
    *) echo "Unknown command: $command" >&2; usage; exit 1 ;;
  esac
}

main "$@"
