#!/usr/bin/env bash

CALL_RISK_LEVEL=destructive

call() {
  local host=$1
  local index=$2
  local jar_path=$3
  local coordinators_csv=$4
  local install_root=${5:-/data24/otf/pulse}
  local jre_tarball=${6:-}
  local cluster_fallback=${7:-unknown}
  local group_plan_path=${8:-}
  local scp_host
  local remote_tmp

  scp_host=$(adapt "$host")
  remote_tmp="/tmp/pulse-deploy.${index}.$$"

  echo "EVENT phase=deploy host=${host} index=${index} status=start root=${install_root}"
  ssh "$host" "mkdir -p '$remote_tmp'"
  scp "$jar_path" "${scp_host}:${remote_tmp}/pulse.jar"
  if [ -n "$jre_tarball" ] && [ "$jre_tarball" != "-" ]; then
    scp "$jre_tarball" "${scp_host}:${remote_tmp}/pulse-jre.tar.gz"
  fi
  if [ -n "$group_plan_path" ] && [ "$group_plan_path" != "-" ] && [ -f "$group_plan_path" ]; then
    scp "$group_plan_path" "${scp_host}:${remote_tmp}/pulse-group-plan.csv"
  fi
  ssh "$host" 'bash -s' -- "$host" "$coordinators_csv" "$install_root" "$remote_tmp" "$cluster_fallback" <<'REMOTE'
set -euo pipefail

host=$1
coordinators_csv=$2
install_root=$3
remote_tmp=$4
cluster_fallback=$5

java_bin="${PULSE_JAVA_BIN:-$(command -v java || true)}"

mkdir -p "$install_root/bin" "$install_root/etc" "$install_root/logs"
cp "$remote_tmp/pulse.jar" "$install_root/bin/pulse.jar"
chmod 0644 "$install_root/bin/pulse.jar"

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

if [ -z "$java_bin" ] || [ ! -x "$java_bin" ]; then
  echo "ERROR java runtime not found; pass a Linux JRE/JDK tarball as arg6 or set PULSE_JAVA_BIN" >&2
  exit 20
fi

java_version=$("$java_bin" -version 2>&1 | head -n 1 || true)
echo "JAVA_BIN=${java_bin}"
echo "JAVA_VERSION=${java_version}"

is_coordinator=0
IFS=',' read -r -a coordinators <<< "$coordinators_csv"
coordinator_urls=""
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
done

hostname_value=$(hostname -f 2>/dev/null || hostname)
ip_value=$(ip -6 addr show scope global 2>/dev/null | awk '/inet6/{print $2}' | cut -d/ -f1 | head -n 1 || true)
if [ -z "$ip_value" ]; then
  ip_value=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
fi

tide_pid=$(pgrep -f tide_worker | head -n 1 || true)
tide_area="unknown"
tide_cluster="$cluster_fallback"
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
PULSE_TTL_MS=15000
PULSE_GROUP_ID=${group_id}
PULSE_GROUP_MODE=${group_mode}
PULSE_GROUP_LEADER_URL=${group_leader_url}
PULSE_GROUP_MEMBERS=${group_members}
PULSE_GROUP_SIZE_LIMIT=7
PULSE_GROUP_PORT=9977
ENV

cat > "$install_root/etc/pulse-coordinator.env" <<ENV
PULSE_COORDINATOR_ID=${hostname_value}
PULSE_BIND_HOST=::
PULSE_PORT=9966
PULSE_GROUP_SIZE_LIMIT=7
PULSE_GROUP_PORT=9977
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

agent_exec="${java_bin} -cp ${install_root}/bin/pulse.jar com.bytedance.pulse.PulseAgentApp"
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
  systemctl --user --no-pager --full status pulse-agent.service | head -n 20 || true
  if [ "$is_coordinator" -eq 1 ]; then
    systemctl --user --no-pager --full status pulse-coordinator.service | head -n 20 || true
  fi
fi

rm -rf "$remote_tmp"
echo "ROLE agent=1 coordinator=${is_coordinator} cluster=${tide_cluster} area=${tide_area} group=${group_id} mode=${group_mode} urls=${coordinator_urls}"
REMOTE
  echo "EVENT phase=deploy host=${host} index=${index} status=ok"
}
