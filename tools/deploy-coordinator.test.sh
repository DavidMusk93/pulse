#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
callee="${repo_root}/docs/ops/deploy-coordinator.sh"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/pulse-deploy-coordinator-test.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT

jar="${tmp_dir}/pulse.jar"
printf 'pulse-test\n' > "$jar"
expected_sha=$(shasum -a 256 "$jar" | awk '{print $1}')

cat > "${tmp_dir}/harness.sh" <<'HARNESS'
#!/usr/bin/env bash
set -euo pipefail
source "$CALLEE"
adapt() {
  printf '%s\n' "$1"
}
if call test-host 1 "$JAR" "$EXPECTED_SHA"; then
  exit 0
else
  exit $?
fi
HARNESS
chmod +x "${tmp_dir}/harness.sh"

run_case() {
  local name=$1
  local ssh_mode=$2
  local scp_mode=$3
  local expected_status=$4
  local expected_result=${5:-none}
  local output="${tmp_dir}/${name}.log"

  cat > "${tmp_dir}/ssh" <<'SSH'
#!/usr/bin/env bash
case "$SSH_MODE:$2" in
  remote_sha_fail:*) exit 42 ;;
  unchanged:*sha256sum*) printf '%s\n' "$EXPECTED_SHA" ;;
  unchanged:*is-active*) printf '%s\n' active ;;
  service_fail:*sha256sum*) printf '%s\n' "$EXPECTED_SHA" ;;
  service_fail:*is-active*) exit 44 ;;
  malformed_sha:*sha256sum*) printf '%s\n' not-a-sha ;;
  prepare_fail:*set\ -o\ pipefail*) printf '%064d\n' 0 ;;
  prepare_fail:*mkdir*) exit 45 ;;
  scp_fail:*set\ -o\ pipefail*) printf '%064d\n' 0 ;;
  install_fail:*set\ -o\ pipefail*) printf '%064d\n' 0 ;;
  install_fail:*mkdir*) exit 0 ;;
  install_fail:*) exit 46 ;;
  changed_success:*set\ -o\ pipefail*) printf '%064d\n' 0 ;;
  changed_success:*) printf '%s\n' active ;;
  *) exit 0 ;;
esac
SSH
  cat > "${tmp_dir}/scp" <<'SCP'
#!/usr/bin/env bash
[ "$SCP_MODE" = "fail" ] && exit 43
exit 0
SCP
  chmod +x "${tmp_dir}/ssh" "${tmp_dir}/scp"

  export EXPECTED_SHA SSH_MODE SCP_MODE
  set +e
  PATH="${tmp_dir}:$PATH" \
    CALLEE="$callee" \
    JAR="$jar" \
    EXPECTED_SHA="$expected_sha" \
    SSH_MODE="$ssh_mode" \
    SCP_MODE="$scp_mode" \
    "${tmp_dir}/harness.sh" > "$output" 2>&1
  status=$?
  set -e

  if [ "$expected_status" = "zero" ]; then
    [ "$status" -eq 0 ]
  else
    [ "$status" -ne 0 ]
  fi
  if [ "$expected_result" = "updated" ]; then
    grep -q 'status=updated' "$output"
  elif grep -q 'status=updated' "$output"; then
    echo "${name}: false updated result" >&2
    return 1
  fi
}

run_case remote-sha-failure remote_sha_fail ok nonzero
run_case malformed-sha malformed_sha ok zero updated
run_case service-check-failure service_fail ok nonzero
run_case prepare-failure prepare_fail ok nonzero
run_case upload-failure scp_fail fail nonzero
run_case install-failure install_fail ok nonzero
run_case unchanged-active unchanged ok zero
run_case changed-success changed_success ok zero updated

echo "deploy-coordinator fail-closed tests passed"
