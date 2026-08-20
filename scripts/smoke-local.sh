#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
agent_dir="${project_root}/agent"
web_dir="${project_root}/web"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/translator-smoke.XXXXXX")"
agent_binary="${tmp_dir}/translator-agent"
agent_pid=''
web_http_pid=''
cleaned_up=false

fail() {
  printf 'smoke-local: %s\n' "$*" >&2
  exit 1
}

stop_process() {
  local pid="$1"
  [[ -n "$pid" ]] || return 0
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
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
  stop_process "$web_http_pid"
  stop_process "$agent_pid"
  rm -rf -- "$tmp_dir"
  exit "$status"
}

trap cleanup EXIT HUP INT TERM

if [[ -f "${agent_dir}/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${agent_dir}/.env.local"
  set +a
fi

if [[ ! -f "${agent_dir}/internal/officialastproto/products/understanding/ast/ast_service.pb.go" ]]; then
  "${project_root}/scripts/prepare-official-ast.sh"
fi

(
  cd "$agent_dir"
  go build -tags officialast -o "$agent_binary" ./cmd/translator-agent
)

"$agent_binary" &
agent_pid=$!

for _ in $(seq 1 50); do
  if curl --fail --silent --max-time 1 http://127.0.0.1:18765/api/health \
    | grep -Fq '"status":"ok"'; then
    break
  fi
  if ! kill -0 "$agent_pid" 2>/dev/null; then
    wait "$agent_pid" || true
    fail 'Agent exited before its health check succeeded.'
  fi
  sleep 0.1
done

curl --fail --silent --show-error --max-time 2 http://127.0.0.1:18765/api/health \
  | grep -Fq '"service":"translator-agent"' \
  || fail 'Agent health endpoint did not return the expected response.'

origin_status="$(curl --output /dev/null --silent --write-out '%{http_code}' --max-time 2 \
  -H 'Origin: http://127.0.0.1:5173' \
  http://127.0.0.1:18765/ws/translate)"
[[ "$origin_status" == '426' ]] || fail "Agent did not accept the Vite Origin (HTTP ${origin_status})."

invalid_origin_status="$(curl --output /dev/null --silent --write-out '%{http_code}' --max-time 2 \
  -H 'Origin: http://localhost:9999' \
  http://127.0.0.1:18765/ws/translate)"
[[ "$invalid_origin_status" == '403' ]] || fail "Agent did not reject an unknown Origin (HTTP ${invalid_origin_status})."

npm --prefix "$web_dir" run build

http_port="$((18080 + RANDOM % 1000))"
python3 -m http.server "$http_port" --directory "${web_dir}/dist" >"${tmp_dir}/web-http.log" 2>&1 &
web_http_pid=$!

for _ in $(seq 1 30); do
  if curl --fail --silent --max-time 1 "http://127.0.0.1:${http_port}/" \
    | grep -Fq '<div id="root"></div>'; then
    break
  fi
  if ! kill -0 "$web_http_pid" 2>/dev/null; then
    fail 'Temporary Web HTTP server exited before its check succeeded.'
  fi
  sleep 0.1
done

curl --fail --silent --show-error --max-time 2 "http://127.0.0.1:${http_port}/" \
  | grep -Fq '<div id="root"></div>' \
  || fail 'Built Web entry page was not served as expected.'

printf '%s\n' 'smoke-local: passed'
