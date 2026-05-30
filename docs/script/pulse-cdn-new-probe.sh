#!/usr/bin/env bash

CALL_RISK_LEVEL=read_only

call() {
  local host=$1
  local index=$2
  local ssh_host

  ssh_host=$(adapt "$host")
  echo "EVENT phase=probe host=${host} index=${index} status=start"
  ssh "$ssh_host" 'bash -s' <<'REMOTE'
set -euo pipefail
echo "HOST=$(hostname -f 2>/dev/null || hostname)"
echo "WHOAMI=$(id -un)"
echo "JAVA=$(command -v java || true)"
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | head -n 1
fi
echo "SYSTEMCTL=$(command -v systemctl || true)"
echo "INSTALL_ROOT=/data24/otf/pulse"
if [ -d /data24/otf ]; then
  ls -ld /data24/otf
else
  echo "MISSING=/data24/otf"
fi
ipv6_addrs=$(ip -6 addr show scope global 2>/dev/null | awk '/inet6/{print $2}' | paste -sd, - || true)
port_9966=$(ss -ltn 2>/dev/null | awk '{print $4}' | grep -E '(:|])9966$' || true)
echo "IPV6_ADDRS=${ipv6_addrs}"
echo "PORT_9966=${port_9966}"
systemctl is-active pulse-coordinator.service 2>/dev/null || true
systemctl is-active pulse-agent.service 2>/dev/null || true
REMOTE
  echo "EVENT phase=probe host=${host} index=${index} status=ok"
}
