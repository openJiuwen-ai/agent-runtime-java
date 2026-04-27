#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="${EDP_DEPLOY_CONFIG:-"$ROOT_DIR/config/deploy.properties"}"

usage() {
  cat <<'EOF'
Usage:
  scripts/invoke.sh "query text"
  scripts/invoke.sh --query "query text" [--conversation ID] [--project ID] [--agent ID] [--host HOST] [--port PORT] [--stream true|false]

Examples:
  scripts/invoke.sh "帮我查一下账户余额"
  scripts/invoke.sh --query "推荐两款低风险理财产品" --conversation c-rec-1
  scripts/invoke.sh --query "帮我查余额" --host 10.0.0.8 --port 8080
EOF
}

load_config() {
  if [[ -f "$CONFIG_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$CONFIG_FILE"
  fi
}

json_escape() {
  local value="${1:-}"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  value=${value//$'\r'/\\r}
  value=${value//$'\t'/\\t}
  printf '%s' "$value"
}

random_conversation_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    printf 'c-%s' "$(uuidgen | tr '[:upper:]' '[:lower:]')"
  else
    printf 'c-%s' "$(date +%s)"
  fi
}

main() {
  load_config

  local query=""
  local conversation=""
  local project="${INVOKE_PROJECT_ID:-${PROBE_PROJECT_ID:-demo}}"
  local agent="${INVOKE_AGENT_ID:-${PROBE_AGENT_ID:-edp_agent}}"
  local host="${INVOKE_HOST:-${PROBE_HOST:-127.0.0.1}}"
  local port="${A2A_PORT:-8080}"
  local stream="${INVOKE_STREAM:-true}"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --query)
        query="${2:-}"
        shift 2
        ;;
      --conversation)
        conversation="${2:-}"
        shift 2
        ;;
      --project)
        project="${2:-}"
        shift 2
        ;;
      --agent)
        agent="${2:-}"
        shift 2
        ;;
      --host)
        host="${2:-}"
        shift 2
        ;;
      --port)
        port="${2:-}"
        shift 2
        ;;
      --stream)
        stream="${2:-}"
        shift 2
        ;;
      -h|--help|help)
        usage
        exit 0
        ;;
      *)
        if [[ -z "$query" ]]; then
          query="$1"
          shift
        else
          echo "Unknown argument: $1" >&2
          usage
          exit 1
        fi
        ;;
    esac
  done

  if [[ -z "$query" ]]; then
    echo "Query is required." >&2
    usage
    exit 1
  fi

  if [[ -z "$conversation" ]]; then
    conversation="$(random_conversation_id)"
  fi

  local escaped_query
  escaped_query="$(json_escape "$query")"
  local body
  body=$(
    printf '{"agent_id":"%s","conversation_id":"%s","stream":%s,"custom_data":{"inputs":{"query":"%s"}}}' \
      "$agent" "$conversation" "$stream" "$escaped_query"
  )
  local body_file
  body_file="$(mktemp)"
  printf '%s' "$body" >"$body_file"

  local url="http://$host:$port/v1/$project/agents/$agent/conversations/$conversation"
  echo "POST $url"
  echo "conversation_id=$conversation"
  echo "headers:"
  echo "  Content-Type: application/json; charset=utf-8"
  echo "request body:"
  echo "$body"

  if [[ "$stream" == "true" ]]; then
    curl -N -H 'Content-Type: application/json; charset=utf-8' -X POST "$url" --data-binary "@$body_file"
  else
    curl -sS -H 'Content-Type: application/json; charset=utf-8' -X POST "$url" --data-binary "@$body_file"
    printf '\n'
  fi
  rm -f "$body_file"
}

main "$@"
