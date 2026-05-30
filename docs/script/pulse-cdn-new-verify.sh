#!/usr/bin/env bash

CALL_RISK_LEVEL=read_only

call() {
  local host=$1
  local index=$2
  local coordinators_csv=${3:-}
  local ssh_host

  ssh_host=$(adapt "$host")
  echo "EVENT phase=verify host=${host} index=${index} status=start"
  ssh "$ssh_host" 'bash -s' -- "$host" "$coordinators_csv" <<'REMOTE'
set -euo pipefail

host=$1
coordinators_csv=$2
is_coordinator=0
IFS=',' read -r -a coordinators <<< "$coordinators_csv"
for coordinator in "${coordinators[@]}"; do
  coordinator_trimmed=$(echo "$coordinator" | xargs)
  if [ "$coordinator_trimmed" = "$host" ]; then
    is_coordinator=1
  fi
done

echo "HOST=$(hostname -f 2>/dev/null || hostname)"
echo "JAVA=$(java -version 2>&1 | head -n 1 || true)"
echo "AGENT_ACTIVE=$(systemctl is-active pulse-agent.service 2>/dev/null || systemctl --user is-active pulse-agent.service 2>/dev/null || true)"
echo "COORDINATOR_EXPECTED=${is_coordinator}"
echo "COORDINATOR_ACTIVE=$(systemctl is-active pulse-coordinator.service 2>/dev/null || systemctl --user is-active pulse-coordinator.service 2>/dev/null || true)"
echo "PORT_9966=$(ss -ltn 2>/dev/null | awk '{print $4}' | grep -E '(:|])9966$' || true)"
if [ "$is_coordinator" -eq 1 ]; then
  curl -g -s --max-time 2 "http://[::1]:9966/api/hosts" | head -c 500 || true
  echo
fi
REMOTE
  echo "EVENT phase=verify host=${host} index=${index} status=ok"
}
