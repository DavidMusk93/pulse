#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tide-worker-log.sh [pattern]

Find tide_worker processes whose environment contains
_TIDELET_CONTAINER_IS_VIRTUAL=false, then search their cwd logs:

  grep -a <pattern> /proc/$pid/cwd/logs/tide_worker.*log

Default pattern: occupy
EOF
}

die() {
  echo "error: $*" >&2
  exit 2
}

tide_worker_pids() {
  pidof tide_worker 2>/dev/null || true
}

is_real_container_worker() {
  local pid="$1"
  local environ_file="/proc/$pid/environ"

  [[ -r "$environ_file" ]] || return 1
  strings "$environ_file" 2>/dev/null | grep -Fxq '_TIDELET_CONTAINER_IS_VIRTUAL=false'
}

grep_worker_log() {
  local pid="$1"
  local pattern="$2"
  local log_dir="/proc/$pid/cwd/logs"
  local log_file
  local found=0

  [[ -d "$log_dir" ]] || return 1

  while IFS= read -r -d '' log_file; do
    [[ -r "$log_file" ]] || continue
    found=1
    printf '===== pid=%s log=%s =====\n' "$pid" "$log_file"
    grep -a -- "$pattern" "$log_file" || true
  done < <(find "$log_dir" -maxdepth 1 -type f -name 'tide_worker.*log' -print0 2>/dev/null)

  [[ "$found" -eq 1 ]]
}

main() {
  local pattern="${1:-occupy}"
  local pid_list
  local pid
  local pids=()
  local total=0
  local matched_env=0
  local searched_logs=0

  if [[ "${pattern}" == "--help" || "${pattern}" == "-h" ]]; then
    usage
    return 0
  fi

  [[ "$#" -le 1 ]] || die "too many arguments"
  [[ -n "$pattern" ]] || die "pattern must not be empty"

  pid_list="$(tide_worker_pids)"
  if [[ -z "$pid_list" ]]; then
    echo "summary: total=0 matched_env=0 searched_logs=0"
    return 0
  fi

  read -r -a pids <<<"$pid_list"
  for pid in "${pids[@]}"; do
    total=$((total + 1))
    if ! is_real_container_worker "$pid"; then
      continue
    fi
    matched_env=$((matched_env + 1))
    if grep_worker_log "$pid" "$pattern"; then
      searched_logs=$((searched_logs + 1))
    fi
  done

  echo "summary: total=${total} matched_env=${matched_env} searched_logs=${searched_logs}"
}

main "$@"
