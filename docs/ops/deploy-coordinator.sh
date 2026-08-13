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

  if ! remote_sha=$(ssh "$host" "set -o pipefail; if [ -r '${remote_jar}' ]; then sha256sum '${remote_jar}' | awk '{print \$1}'; else echo MISSING; fi"); then
    echo "RESULT host=${host} status=failed step=remote_sha" >&2
    return 1
  fi
  if [ "$remote_sha" != "MISSING" ] && [[ ! "$remote_sha" =~ ^[0-9a-f]{64}$ ]]; then
    echo "EVENT host=${host} index=${index} step=remote_sha_invalid value=${remote_sha:-EMPTY}" >&2
    remote_sha=UNKNOWN
  fi
  if [ "$remote_sha" = "$expected_sha" ]; then
    if ! service_state=$(ssh "$host" "systemctl is-active pulse-coordinator.service"); then
      echo "RESULT host=${host} status=failed step=service_check remote_sha=${remote_sha}" >&2
      return 1
    fi
    echo "RESULT host=${host} status=unchanged local_sha=${expected_sha} remote_sha=${remote_sha} service=${service_state}"
    [ "$service_state" = "active" ]
    return
  fi

  echo "RESULT host=${host} status=changed local_sha=${expected_sha} remote_sha=${remote_sha}"
  echo "EVENT host=${host} index=${index} step=upload start"
  if ! ssh "$host" "mkdir -p '$remote_tmp' '$install_root/bin'"; then
    echo "RESULT host=${host} status=failed step=prepare remote_sha=${remote_sha}" >&2
    return 1
  fi
  if ! scp "$jar_path" "${scp_host}:${remote_tmp}/pulse.jar"; then
    echo "RESULT host=${host} status=failed step=upload remote_sha=${remote_sha}" >&2
    return 1
  fi

  echo "EVENT host=${host} index=${index} step=install start"
  if ! ssh "$host" "set -euo pipefail; actual=\$(sha256sum '${remote_tmp}/pulse.jar' | awk '{print \$1}'); if [ \"\$actual\" != '${expected_sha}' ]; then echo 'SHA_MISMATCH expected=${expected_sha} actual='\$actual; exit 1; fi; cp '${remote_tmp}/pulse.jar' '${remote_jar}'; chmod 0644 '${remote_jar}'; actual=\$(sha256sum '${remote_jar}' | awk '{print \$1}'); if [ \"\$actual\" != '${expected_sha}' ]; then echo 'SHA_MISMATCH expected=${expected_sha} actual='\$actual; exit 1; fi; systemctl restart pulse-coordinator.service; sleep 2; systemctl is-active pulse-coordinator.service; rm -rf '$remote_tmp'"; then
    echo "RESULT host=${host} status=failed step=install remote_sha=${remote_sha}" >&2
    return 1
  fi

  echo "RESULT host=${host} status=updated local_sha=${expected_sha} remote_sha=${expected_sha} service=active"
}
