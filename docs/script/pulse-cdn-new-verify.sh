#!/usr/bin/env bash

CALL_RISK_LEVEL=read_only

call() {
  local host=$1
  local index=$2
  local coordinators_csv=${3:-}

  echo "EVENT phase=verify host=${host} index=${index} status=start"
  ssh "$host" 'bash -s' -- "$host" "$coordinators_csv" <<'REMOTE'
set -euo pipefail

host=$1
coordinators_csv=$2
install_root=${PULSE_INSTALL_ROOT:-/data24/otf/pulse}
is_coordinator=0
IFS=',' read -r -a coordinators <<< "$coordinators_csv"
for coordinator in "${coordinators[@]}"; do
  coordinator_trimmed=$(echo "$coordinator" | xargs)
  if [ "$coordinator_trimmed" = "$host" ]; then
    is_coordinator=1
  fi
done

echo "HOST=$(hostname -f 2>/dev/null || hostname)"
java_bin=$(command -v java || true)
if [ -z "$java_bin" ] && [ -x "$install_root/jre/bin/java" ]; then
  java_bin="$install_root/jre/bin/java"
fi
echo "JAVA_BIN=${java_bin:-missing}"
if [ -n "$java_bin" ]; then
  echo "JAVA_VERSION=$("$java_bin" -version 2>&1 | head -n 1 || true)"
fi
echo "AGENT_EXEC_START=$(systemctl show pulse-agent.service -p ExecStart --value 2>/dev/null || systemctl --user show pulse-agent.service -p ExecStart --value 2>/dev/null || true)"
echo "AGENT_ACTIVE=$(systemctl is-active pulse-agent.service 2>/dev/null || systemctl --user is-active pulse-agent.service 2>/dev/null || true)"
if [ -f "$install_root/etc/pulse-agent.env" ]; then
  grep -E '^(PULSE_AGENT_CLUSTER|PULSE_AGENT_AREA|PULSE_AGENT_ROLE|PULSE_AGENT_ZONE|PULSE_GROUP_ID|PULSE_GROUP_MODE|PULSE_GROUP_LEADER_URL|PULSE_GROUP_SIZE_LIMIT)=' "$install_root/etc/pulse-agent.env" || true
fi
echo "COORDINATOR_EXPECTED=${is_coordinator}"
echo "COORDINATOR_ACTIVE=$(systemctl is-active pulse-coordinator.service 2>/dev/null || systemctl --user is-active pulse-coordinator.service 2>/dev/null || true)"
echo "COORDINATOR_EXEC_START=$(systemctl show pulse-coordinator.service -p ExecStart --value 2>/dev/null || systemctl --user show pulse-coordinator.service -p ExecStart --value 2>/dev/null || true)"
echo "PORT_9966=$(ss -ltn 2>/dev/null | awk '{print $4}' | grep -E '(:|])9966$' || true)"
if [ "$is_coordinator" -eq 1 ]; then
  hosts_json=$(curl -g -s --max-time 2 "http://[::1]:9966/api/hosts" || true)
  host_count=$(printf '%s' "$hosts_json" | grep -o '"agent_id"' | wc -l | tr -d ' ')
  echo "HOST_COUNT=${host_count}"
  printf '%s' "$hosts_json" | head -c 500 || true
  echo
fi
REMOTE
  echo "EVENT phase=verify host=${host} index=${index} status=ok"
}
