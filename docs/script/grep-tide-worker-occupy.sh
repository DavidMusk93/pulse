#!/usr/bin/env bash
set -euo pipefail

pattern=${1:-occupy}

pid_list=$(pidof tide_worker 2>/dev/null || true)
[ -n "$pid_list" ] || exit 0

for pid in $pid_list; do
  proc="/proc/$pid"
  environ_file="$proc/environ"
  log_file="$proc/cwd/logs/tide_worker.log"

  [ -r "$environ_file" ] || continue
  [ -r "$log_file" ] || continue

  if ! tr '\0' '\n' <"$environ_file" 2>/dev/null | grep -Fxq '_TIDELET_CONTAINER_IS_VIRTUAL=false'; then
    continue
  fi

  echo "===== pid=${pid} log=${log_file} ====="
  grep -a -- "$pattern" "$log_file" || true
done
