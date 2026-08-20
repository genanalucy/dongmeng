#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
agent_dir="${project_root}/agent"
web_dir="${project_root}/web"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/translator-dev.XXXXXX")"
agent_binary="${tmp_dir}/translator-agent"
agent_pid=''
vite_pid=''
cleaned_up=false

log() {
  printf '%s\n' "$*" >&2
}

stop_process() {
  local pid="$1"
  local signal="${2:-TERM}"
  [[ -n "$pid" ]] || return 0
  if kill -0 "$pid" 2>/dev/null; then
    kill -"$signal" "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  local status=$?
  if [[ "$cleaned_up" == true ]]; then
    exit "$status"
  fi
  cleaned_up=true
  trap - EXIT HUP INT TERM
  stop_process "$vite_pid"
  stop_process "$agent_pid"
  rm -rf -- "$tmp_dir"
  exit "$status"
}

forward_signal() {
  local signal="$1"
  log "Received SIG${signal}; stopping local Agent and Vite…"
  stop_process "$vite_pid" "$signal"
  stop_process "$agent_pid" "$signal"
  case "$signal" in
    INT) exit 130 ;;
    TERM) exit 143 ;;
  esac
}

trap cleanup EXIT
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM

if [[ -f "${agent_dir}/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${agent_dir}/.env.local"
  set +a
fi

if [[ ! -f "${agent_dir}/internal/officialastproto/products/understanding/ast/ast_service.pb.go" ]]; then
  log 'Preparing pinned official AST protobuf files…'
  "${project_root}/scripts/prepare-official-ast.sh"
fi

log 'Building real AST-enabled local Agent…'
(
  cd "$agent_dir"
  go build -tags officialast -o "$agent_binary" ./cmd/translator-agent
)

log 'Starting local Agent on http://127.0.0.1:18765…'
"$agent_binary" &
agent_pid=$!

log 'Starting Vite on http://127.0.0.1:5173…'
(
  cd "$web_dir"
  exec ./node_modules/.bin/vite
) &
vite_pid=$!

while :; do
  if ! kill -0 "$agent_pid" 2>/dev/null; then
    if wait "$agent_pid"; then
      status=0
    else
      status=$?
    fi
    log 'Local Agent exited; stopping Vite.'
    exit "$status"
  fi
  if ! kill -0 "$vite_pid" 2>/dev/null; then
    if wait "$vite_pid"; then
      status=0
    else
      status=$?
    fi
    log 'Vite exited; stopping local Agent.'
    exit "$status"
  fi
  sleep 0.2
done
