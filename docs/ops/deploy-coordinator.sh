#!/usr/bin/env bash
# Deploy pulse.jar to a coordinator node via auto-ops runtime.
#
# Usage (from olap-toolbox):
#   bash scripts/call.sh \
#     -f docs/ops/deploy-coordinator.sh \
#     -t coordinators -i docs/ops/coordinators.hosts \
#     --max-hosts 3 --yes --parallel 3 --timeout 120 \
#     -- target/pulse-0.1.0-SNAPSHOT.jar <sha256>
#
# Preflight:
#   bash scripts/call.sh -f scripts/demand.sh -t coordinators -i docs/ops/coordinators.hosts
set -euo pipefail
CALL_RISK_LEVEL=destructive

call() {
  local host=$1
  local index=$2
  shift 2
  local jar_path=${1:?jar_path required}
  local expected_sha=${2:?expected_sha required}
  local install_root=${3:-/data24/otf/pulse}
  local remote_jar="${install_root}/bin/pulse.jar"

  if [ ! -f "$jar_path" ]; then
    echo "ERROR jar_not_found path=$jar_path" >&2
    return 2
  fi

  local scp_host
  local remote_sha
  local service_state
  local remote_tmp
  scp_host=$(adapt "$host")
  remote_tmp="/tmp/pulse-coordinator-deploy.${index}.$$"

  remote_sha=$(ssh "$host" "if [ -r '${remote_jar}' ]; then sha256sum '${remote_jar}' | awk '{print \$1}'; else echo MISSING; fi" || echo UNKNOWN)
  if [ "$remote_sha" = "$expected_sha" ]; then
    service_state=$(ssh "$host" "systemctl is-active pulse-coordinator.service" || true)
    echo "RESULT host=${host} status=unchanged local_sha=${expected_sha} remote_sha=${remote_sha} service=${service_state}"
    [ "$service_state" = "active" ]
    return
  fi

  echo "RESULT host=${host} status=changed local_sha=${expected_sha} remote_sha=${remote_sha}"
  echo "EVENT host=${host} index=${index} step=upload start"
  ssh "$host" "mkdir -p '$remote_tmp' '$install_root/bin'"
  scp "$jar_path" "${scp_host}:${remote_tmp}/pulse.jar"

  echo "EVENT host=${host} index=${index} step=install start"
  ssh "$host" "set -euo pipefail; actual=\$(sha256sum '${remote_tmp}/pulse.jar' | awk '{print \$1}'); if [ \"\$actual\" != '${expected_sha}' ]; then echo 'SHA_MISMATCH expected=${expected_sha} actual='\$actual; exit 1; fi; cp '${remote_tmp}/pulse.jar' '${remote_jar}'; chmod 0644 '${remote_jar}'; actual=\$(sha256sum '${remote_jar}' | awk '{print \$1}'); if [ \"\$actual\" != '${expected_sha}' ]; then echo 'SHA_MISMATCH expected=${expected_sha} actual='\$actual; exit 1; fi; systemctl restart pulse-coordinator.service; sleep 2; systemctl is-active pulse-coordinator.service; rm -rf '$remote_tmp'"

  echo "RESULT host=${host} status=updated local_sha=${expected_sha} remote_sha=${expected_sha} service=active"
}
