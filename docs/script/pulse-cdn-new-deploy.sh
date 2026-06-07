#!/usr/bin/env bash

CALL_RISK_LEVEL=destructive

sha256_file() {
  local file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

last_error_line() {
  local file=$1
  if [ -s "$file" ]; then
    tail -n 1 "$file" | tr '\t\r\n' '   ' | sed 's/  */ /g'
  else
    echo "no_stderr"
  fi
}

run_with_stderr() {
  local host=$1
  local index=$2
  local step=$3
  shift 3
  local err_file
  local rc
  local reason
  err_file=$(mktemp "/tmp/pulse-deploy.${index}.${step}.stderr.XXXXXX")
  "$@" 2>"$err_file"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    if [ -s "$err_file" ]; then
      tail -n 80 "$err_file" >&2
    fi
    reason=$(last_error_line "$err_file")
    echo "ERROR phase=deploy host=${host} index=${index} step=${step} rc=${rc} reason=${reason}" >&2
  fi
  rm -f "$err_file"
  return "$rc"
}

call() {
  local host=$1
  local index=$2
  local jar_path=$3
  local coordinators_csv=$4
  local install_root=${5:-/data24/otf/pulse}
  local jre_tarball=${6:-}
  local cluster_fallback=${7:-unknown}
  local group_plan_path=${8:-}
  local task_dir
  local scp_host
  local remote_tmp
  local expected_jar_sha
  local rc

  if [ ! -f "$jar_path" ]; then
    echo "ERROR phase=deploy host=${host} index=${index} step=local_validate reason=jar_not_found path=${jar_path}" >&2
    return 2
  fi
  expected_jar_sha=$(sha256_file "$jar_path") || {
    rc=$?
    echo "ERROR phase=deploy host=${host} index=${index} step=local_validate reason=jar_sha_failed rc=${rc}" >&2
    return "$rc"
  }
  if [ -n "$jre_tarball" ] && [ "$jre_tarball" != "-" ] && [ ! -f "$jre_tarball" ]; then
    echo "ERROR phase=deploy host=${host} index=${index} step=local_validate reason=jre_not_found path=${jre_tarball}" >&2
    return 3
  fi

  scp_host=$(adapt "$host")
  remote_tmp="/tmp/pulse-deploy.${index}.$$"

  echo "EVENT phase=deploy host=${host} index=${index} status=start root=${install_root}"
  run_with_stderr "$host" "$index" stage_remote_tmp ssh "$host" "rm -rf '$remote_tmp' && mkdir -p '$remote_tmp'" || return "$?"
  run_with_stderr "$host" "$index" upload_jar scp -q "$jar_path" "${scp_host}:${remote_tmp}/pulse.jar" || return "$?"
  task_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../task" && pwd)"
  if [ -d "$task_dir" ]; then
    run_with_stderr "$host" "$index" stage_task_dir ssh "$host" "mkdir -p '$remote_tmp/tasks'" || return "$?"
    run_with_stderr "$host" "$index" upload_tasks scp -q "$task_dir"/prepare-disk-layout.sh "$task_dir"/analyze-block-layout.py "$task_dir"/repair-corrupt-sqlite3.sh "${scp_host}:${remote_tmp}/tasks/" || return "$?"
  fi
  if [ -n "$jre_tarball" ] && [ "$jre_tarball" != "-" ]; then
    run_with_stderr "$host" "$index" upload_jre scp -q "$jre_tarball" "${scp_host}:${remote_tmp}/pulse-jre.tar.gz" || return "$?"
  fi
  if [ -n "$group_plan_path" ] && [ "$group_plan_path" != "-" ] && [ -f "$group_plan_path" ]; then
    run_with_stderr "$host" "$index" upload_group_plan scp -q "$group_plan_path" "${scp_host}:${remote_tmp}/pulse-group-plan.csv" || return "$?"
  fi
  run_with_stderr "$host" "$index" remote_install ssh "$host" 'bash -s' -- "$host" "$coordinators_csv" "$install_root" "$remote_tmp" "$cluster_fallback" "$expected_jar_sha" <<'REMOTE'
set -euo pipefail

host=$1
coordinators_csv=$2
install_root=$3
remote_tmp=$4
cluster_fallback=$5
expected_jar_sha=$6

java_bin="${PULSE_JAVA_BIN:-$(command -v java || true)}"

mkdir -p "$install_root/bin" "$install_root/etc" "$install_root/logs" "$install_root/tasks"
cp "$remote_tmp/pulse.jar" "$install_root/bin/pulse.jar"
chmod 0644 "$install_root/bin/pulse.jar"
actual_jar_sha=$(sha256sum "$install_root/bin/pulse.jar" | awk '{print $1}')
if [ "$actual_jar_sha" != "$expected_jar_sha" ]; then
  echo "ERROR remote JAR SHA mismatch: actual=${actual_jar_sha} expected=${expected_jar_sha}" >&2
  exit 22
fi
if [ -d "$remote_tmp/tasks" ] && compgen -G "$remote_tmp/tasks/*" >/dev/null; then
  cp "$remote_tmp/tasks/"* "$install_root/tasks/"
  chmod 0755 "$install_root/tasks/"*
fi
rm -f "$install_root/tasks/analyze-block-layout-py35.py"

python3_version=$(
  python3 - <<'PY' 2>/dev/null || true
import sys
print('%d.%d' % (sys.version_info[0], sys.version_info[1]))
PY
)
echo "TASK_SCRIPT analyze-block-layout.py variant=standard python3=${python3_version:-unknown}"

java_major_version() {
  local candidate=$1
  local version_line
  local version
  version_line=$("$candidate" -version 2>&1 | head -n 1 || true)
  version=$(printf '%s\n' "$version_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')
  if [[ "$version" == 1.* ]]; then
    printf '%s\n' "$version" | cut -d. -f2
  else
    printf '%s\n' "$version" | cut -d. -f1
  fi
}

if [ -n "$java_bin" ] && [ -x "$java_bin" ]; then
  java_major=$(java_major_version "$java_bin")
  if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
    echo "JAVA_BIN_REJECTED=${java_bin} major=${java_major:-unknown}; require Java 17+"
    java_bin=""
  fi
fi

if { [ -z "$java_bin" ] || [ ! -x "$java_bin" ]; } && [ -f "$remote_tmp/pulse-jre.tar.gz" ]; then
  rm -rf "$install_root/jre" "$install_root/jre.tmp"
  mkdir -p "$install_root/jre.tmp"
  tar -xzf "$remote_tmp/pulse-jre.tar.gz" -C "$install_root/jre.tmp"
  bundled_java=$(find "$install_root/jre.tmp" -type f -path '*/bin/java' -perm -111 | head -n 1 || true)
  if [ -z "$bundled_java" ]; then
    echo "ERROR bundled JRE tarball does not contain executable bin/java" >&2
    exit 21
  fi
  bundled_root=$(cd "$(dirname "$bundled_java")/.." && pwd)
  mv "$bundled_root" "$install_root/jre"
  rm -rf "$install_root/jre.tmp"
  java_bin="$install_root/jre/bin/java"
fi

if { [ -z "$java_bin" ] || [ ! -x "$java_bin" ]; } && [ -x "$install_root/jre/bin/java" ]; then
  java_bin="$install_root/jre/bin/java"
fi

if [ -z "$java_bin" ] || [ ! -x "$java_bin" ]; then
  echo "ERROR java runtime not found; pass a Linux JRE/JDK tarball as arg6 or set PULSE_JAVA_BIN" >&2
  exit 20
fi

java_version=$("$java_bin" -version 2>&1 | head -n 1 || true)
echo "JAVA_BIN=${java_bin}"
echo "JAVA_VERSION=${java_version}"
if [ -z "$(java_major_version "$java_bin")" ] || [ "$(java_major_version "$java_bin")" -lt 17 ]; then
  echo "ERROR Java 17+ runtime required: ${java_version}" >&2
  exit 23
fi

is_coordinator=0
IFS=',' read -r -a coordinators <<< "$coordinators_csv"
coordinator_urls=""
coordinator_peer_urls=""
for coordinator in "${coordinators[@]}"; do
  coordinator_trimmed=$(echo "$coordinator" | xargs)
  [ -z "$coordinator_trimmed" ] && continue
  if [ "$coordinator_trimmed" = "$host" ]; then
    is_coordinator=1
  fi
  if [[ "$coordinator_trimmed" == *:* ]]; then
    url="http://[${coordinator_trimmed}]:9966"
  else
    url="http://${coordinator_trimmed}:9966"
  fi
  if [ -z "$coordinator_urls" ]; then
    coordinator_urls="$url"
  else
    coordinator_urls="${coordinator_urls},${url}"
  fi
  if [ "$coordinator_trimmed" != "$host" ]; then
    if [ -z "$coordinator_peer_urls" ]; then
      coordinator_peer_urls="$url"
    else
      coordinator_peer_urls="${coordinator_peer_urls},${url}"
    fi
  fi
done

hostname_value=$(hostname -f 2>/dev/null || hostname)
ip_value=$(ip -6 addr show scope global 2>/dev/null | awk '/inet6/{print $2}' | cut -d/ -f1 | head -n 1 || true)
if [ -z "$ip_value" ]; then
  ip_value=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
fi

tide_pid=$(pgrep -f tide_worker | head -n 1 || true)
tide_area="unknown"
tide_cluster="unknown"
if [ -n "$tide_pid" ] && [ -r "/proc/$tide_pid/environ" ]; then
  tide_area=$(tr '\0' '\n' < "/proc/$tide_pid/environ" | awk -F= '$1=="_TIDELET_AREA"{print $2; exit}')
  tide_cluster=$(tr '\0' '\n' < "/proc/$tide_pid/environ" | awk -F= '$1=="_TIDELET_CLUSTER_ID"{print $2; exit}')
fi
[ -n "$tide_area" ] || tide_area="unknown"
[ -n "$tide_cluster" ] || tide_cluster="unknown"

group_id="dynamic"
group_mode="dynamic"
group_leader_url=""
group_members=""
if [ -f "$remote_tmp/pulse-group-plan.csv" ]; then
  group_row=$(awk -F, -v target="$host" '$1 == target {print; exit}' "$remote_tmp/pulse-group-plan.csv" || true)
  if [ -n "$group_row" ]; then
    group_id=$(printf '%s\n' "$group_row" | awk -F, '{print $2}')
    group_mode=$(printf '%s\n' "$group_row" | awk -F, '{print $3}')
    group_leader_url=$(printf '%s\n' "$group_row" | awk -F, '{print $4}')
    group_members=$(printf '%s\n' "$group_row" | awk -F, '{print $5}')
  fi
fi
[ -n "$group_id" ] || group_id="dynamic"
[ -n "$group_mode" ] || group_mode="dynamic"

cat > "$install_root/etc/pulse-agent.env" <<ENV
PULSE_COORDINATOR_URLS=${coordinator_urls}
PULSE_AGENT_ID=${hostname_value}
PULSE_AGENT_HOST=${hostname_value}
PULSE_AGENT_IP=${ip_value:-unknown}
PULSE_AGENT_CLUSTER=${tide_cluster}
PULSE_AGENT_AREA=${tide_area}
PULSE_AGENT_ROLE=${cluster_fallback}
PULSE_AGENT_ZONE=${tide_area}
PULSE_HEARTBEAT_INTERVAL_MS=5000
PULSE_TTL_MS=30000
PULSE_GROUP_ID=${group_id}
PULSE_GROUP_MODE=${group_mode}
PULSE_GROUP_LEADER_URL=${group_leader_url}
PULSE_GROUP_MEMBERS=${group_members}
PULSE_GROUP_PORT=9977
PULSE_TASK_DIR=${install_root}/tasks
PULSE_TIDE_DISCOVERY_INTERVAL_MS=60000
PULSE_HEARTBEAT_SUCCESS_LOG_EVERY=12
PULSE_TASK_OUTPUT_MAX_CHARS=262144
PULSE_AGENT_PENDING_REPLY_MAX=512
PULSE_AGENT_JAVA_OPTS=-XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2
ENV

cat > "$install_root/etc/pulse-coordinator.env" <<ENV
PULSE_COORDINATOR_ID=${hostname_value}
PULSE_BIND_HOST=::
PULSE_PORT=9966
PULSE_GROUP_PORT=9977
PULSE_COORDINATOR_PEERS=${coordinator_peer_urls}
PULSE_PEER_TIMEOUT_MS=1000
PULSE_TASK_DIR=${install_root}/tasks
PULSE_METRICS_DB=${install_root}/data/pulse-metrics.db
ENV

install_system_unit() {
  local service_name=$1
  local exec_start=$2
  local env_file=$3
  local unit_path="/etc/systemd/system/${service_name}.service"

  cat > "$unit_path" <<UNIT
[Unit]
Description=Pulse ${service_name}
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=${env_file}
WorkingDirectory=${install_root}
ExecStart=${exec_start}
Restart=always
RestartSec=3
StandardOutput=append:${install_root}/logs/${service_name}.log
StandardError=append:${install_root}/logs/${service_name}.err

[Install]
WantedBy=multi-user.target
UNIT
}

install_user_unit() {
  local service_name=$1
  local exec_start=$2
  local env_file=$3
  local user_systemd_dir="$HOME/.config/systemd/user"
  local unit_path="${user_systemd_dir}/${service_name}.service"

  mkdir -p "$user_systemd_dir"
  cat > "$unit_path" <<UNIT
[Unit]
Description=Pulse ${service_name}
After=network-online.target

[Service]
Type=simple
EnvironmentFile=${env_file}
WorkingDirectory=${install_root}
ExecStart=${exec_start}
Restart=always
RestartSec=3
StandardOutput=append:${install_root}/logs/${service_name}.log
StandardError=append:${install_root}/logs/${service_name}.err

[Install]
WantedBy=default.target
UNIT
}

agent_exec="${java_bin} \$PULSE_AGENT_JAVA_OPTS -cp ${install_root}/bin/pulse.jar com.bytedance.pulse.PulseAgentApp"
coordinator_exec="${java_bin} -jar ${install_root}/bin/pulse.jar"

if [ "$(id -u)" -eq 0 ]; then
  install_system_unit pulse-agent "$agent_exec" "$install_root/etc/pulse-agent.env"
  systemctl daemon-reload
  systemctl enable --now pulse-agent.service
  systemctl restart pulse-agent.service
  if [ "$is_coordinator" -eq 1 ]; then
    install_system_unit pulse-coordinator "$coordinator_exec" "$install_root/etc/pulse-coordinator.env"
    systemctl daemon-reload
    systemctl enable --now pulse-coordinator.service
    systemctl restart pulse-coordinator.service
  else
    systemctl disable --now pulse-coordinator.service >/dev/null 2>&1 || true
  fi
  sleep 2
  agent_status=$(systemctl is-active pulse-agent.service || true)
  if [ "$agent_status" != "active" ]; then
    echo "ERROR pulse-agent.service not active: ${agent_status}" >&2
    journalctl -u pulse-agent.service -n 80 --no-pager || true
    exit 31
  fi
  if [ "$is_coordinator" -eq 1 ]; then
    coordinator_status=$(systemctl is-active pulse-coordinator.service || true)
    if [ "$coordinator_status" != "active" ]; then
      echo "ERROR pulse-coordinator.service not active: ${coordinator_status}" >&2
      journalctl -u pulse-coordinator.service -n 80 --no-pager || true
      exit 32
    fi
  fi
  systemctl --no-pager --full status pulse-agent.service | head -n 20 || true
  if [ "$is_coordinator" -eq 1 ]; then
    systemctl --no-pager --full status pulse-coordinator.service | head -n 20 || true
  fi
else
  install_user_unit pulse-agent "$agent_exec" "$install_root/etc/pulse-agent.env"
  systemctl --user daemon-reload
  systemctl --user enable --now pulse-agent.service
  systemctl --user restart pulse-agent.service
  if [ "$is_coordinator" -eq 1 ]; then
    install_user_unit pulse-coordinator "$coordinator_exec" "$install_root/etc/pulse-coordinator.env"
    systemctl --user daemon-reload
    systemctl --user enable --now pulse-coordinator.service
    systemctl --user restart pulse-coordinator.service
  else
    systemctl --user disable --now pulse-coordinator.service >/dev/null 2>&1 || true
  fi
  sleep 2
  agent_status=$(systemctl --user is-active pulse-agent.service || true)
  if [ "$agent_status" != "active" ]; then
    echo "ERROR user pulse-agent.service not active: ${agent_status}" >&2
    journalctl --user -u pulse-agent.service -n 80 --no-pager || true
    exit 41
  fi
  if [ "$is_coordinator" -eq 1 ]; then
    coordinator_status=$(systemctl --user is-active pulse-coordinator.service || true)
    if [ "$coordinator_status" != "active" ]; then
      echo "ERROR user pulse-coordinator.service not active: ${coordinator_status}" >&2
      journalctl --user -u pulse-coordinator.service -n 80 --no-pager || true
      exit 42
    fi
  fi
  systemctl --user --no-pager --full status pulse-agent.service | head -n 20 || true
  if [ "$is_coordinator" -eq 1 ]; then
    systemctl --user --no-pager --full status pulse-coordinator.service | head -n 20 || true
  fi
fi

rm -rf "$remote_tmp"
echo "VERIFY JAR_SHA=${actual_jar_sha} AGENT=active COORDINATOR=${is_coordinator} JAVA=${java_version}"
echo "ROLE agent=1 coordinator=${is_coordinator} cluster=${tide_cluster} area=${tide_area} group=${group_id} mode=${group_mode} urls=${coordinator_urls}"
REMOTE
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "ERROR phase=deploy host=${host} index=${index} step=remote_install_cleanup rc=${rc} remote_tmp=${remote_tmp}" >&2
    return "$rc"
  fi
  echo "EVENT phase=deploy host=${host} index=${index} status=ok"
}
