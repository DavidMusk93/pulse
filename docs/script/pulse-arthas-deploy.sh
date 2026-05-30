#!/usr/bin/env bash

CALL_RISK_LEVEL=write

call() {
  local host=$1
  local index=$2
  local arthas_boot_jar=$3
  local install_root=${4:-/data24/otf/pulse}
  local scp_host
  local remote_tmp

  scp_host=$(adapt "$host")
  remote_tmp="/tmp/pulse-arthas.${index}.$$"

  echo "EVENT phase=arthas_deploy host=${host} index=${index} status=start root=${install_root}"
  ssh "$host" "mkdir -p '$remote_tmp'"
  scp "$arthas_boot_jar" "${scp_host}:${remote_tmp}/arthas-boot.jar"
  ssh "$host" 'bash -s' -- "$install_root" "$remote_tmp" <<'REMOTE'
set -euo pipefail
install_root=$1
remote_tmp=$2

mkdir -p "$install_root/tools/arthas"
cp "$remote_tmp/arthas-boot.jar" "$install_root/tools/arthas/arthas-boot.jar"
chmod 0644 "$install_root/tools/arthas/arthas-boot.jar"
rm -rf "$remote_tmp"

java_bin=$(command -v java || true)
if [ -z "$java_bin" ] && [ -x "$install_root/jre/bin/java" ]; then
  java_bin="$install_root/jre/bin/java"
fi

echo "ARTHAS_BOOT=$install_root/tools/arthas/arthas-boot.jar"
echo "JAVA_BIN=${java_bin:-missing}"
if [ -n "$java_bin" ]; then
  "$java_bin" -jar "$install_root/tools/arthas/arthas-boot.jar" --version || true
fi
REMOTE
  echo "EVENT phase=arthas_deploy host=${host} index=${index} status=ok"
}
