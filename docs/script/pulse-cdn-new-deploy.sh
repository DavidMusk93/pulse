#!/usr/bin/env bash

CALL_RISK_LEVEL=destructive

call() {
  local host=$1
  local index=$2
  local jar_path=$3
  local coordinators_csv=$4
  local install_root=${5:-/data24/otf/pulse}
  local ssh_host
  local remote_tmp

  ssh_host=$(adapt "$host")
  remote_tmp="/tmp/pulse-deploy.${index}.$$"

  echo "EVENT phase=deploy host=${host} index=${index} status=start root=${install_root}"
  ssh "$ssh_host" "mkdir -p '$remote_tmp'"
  scp "$jar_path" "${ssh_host}:${remote_tmp}/pulse.jar"
  ssh "$ssh_host" 'bash -s' -- "$host" "$coordinators_csv" "$install_root" "$remote_tmp" <<'REMOTE'
set -euo pipefail

host=$1
coordinators_csv=$2
install_root=$3
remote_tmp=$4

java_bin="${PULSE_JAVA_BIN:-$(command -v java || true)}"
if [ -z "$java_bin" ] || [ ! -x "$java_bin" ]; then
  echo "ERROR java runtime not found; set PULSE_JAVA_BIN or install Java 17+" >&2
  exit 20
fi

mkdir -p "$install_root/bin" "$install_root/etc" "$install_root/logs"
cp "$remote_tmp/pulse.jar" "$install_root/bin/pulse.jar"
chmod 0644 "$install_root/bin/pulse.jar"

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

cat > "$install_root/etc/pulse-agent.env" <<ENV
PULSE_COORDINATOR_URLS=${coordinator_urls}
PULSE_AGENT_ID=${hostname_value}
PULSE_AGENT_HOST=${hostname_value}
PULSE_AGENT_IP=${ip_value:-unknown}
PULSE_AGENT_ROLE=cdn_new
PULSE_AGENT_ZONE=cdn_new
PULSE_HEARTBEAT_INTERVAL_MS=5000
PULSE_TTL_MS=15000
ENV

cat > "$install_root/etc/pulse-coordinator.env" <<ENV
PULSE_COORDINATOR_ID=${hostname_value}
PULSE_BIND_HOST=::
PULSE_PORT=9966
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
  if [ "$is_coordinator" -eq 1 ]; then
    install_system_unit pulse-coordinator "$coordinator_exec" "$install_root/etc/pulse-coordinator.env"
    systemctl daemon-reload
    systemctl enable --now pulse-coordinator.service
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
  if [ "$is_coordinator" -eq 1 ]; then
    install_user_unit pulse-coordinator "$coordinator_exec" "$install_root/etc/pulse-coordinator.env"
    systemctl --user daemon-reload
    systemctl --user enable --now pulse-coordinator.service
  else
    systemctl --user disable --now pulse-coordinator.service >/dev/null 2>&1 || true
  fi
  systemctl --user --no-pager --full status pulse-agent.service | head -n 20 || true
  if [ "$is_coordinator" -eq 1 ]; then
    systemctl --user --no-pager --full status pulse-coordinator.service | head -n 20 || true
  fi
fi

rm -rf "$remote_tmp"
echo "ROLE agent=1 coordinator=${is_coordinator} urls=${coordinator_urls}"
REMOTE
  echo "EVENT phase=deploy host=${host} index=${index} status=ok"
}
