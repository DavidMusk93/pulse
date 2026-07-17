#!/usr/bin/env bash

# Upload only a changed task script. This intentionally does not restart
# pulse-agent.service: the script is consumed by the next task execution.
CALL_RISK_LEVEL=write

sha256_file() {
  local file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

before() {
  task_script=${TASK_SCRIPT_PATH:-docs/task/analyze-block-layout.py}
  remote_task_path=${REMOTE_TASK_PATH:-/data24/otf/pulse/tasks/analyze-block-layout.py}
  test -f "$task_script" || {
    echo "missing task script: $task_script" >&2
    return 1
  }
}

call() {
  local host=$1
  local index=$2
  local task_script=${TASK_SCRIPT_PATH:-docs/task/analyze-block-layout.py}
  local remote_task_path=${REMOTE_TASK_PATH:-/data24/otf/pulse/tasks/analyze-block-layout.py}
  local expected_sha
  local actual_sha
  local remote_tmp

  expected_sha=$(sha256_file "$task_script") || return $?
  actual_sha=$(remote::ssh "$host" "sha256sum '$remote_task_path' 2>/dev/null | awk '{print \$1}'" 2>/dev/null || true)
  if [ "$actual_sha" = "$expected_sha" ]; then
    echo "EVENT phase=task-diff-sync host=${host} index=${index} status=unchanged sha=${expected_sha}"
    return 0
  fi

  remote_tmp="/tmp/pulse-task-diff.${index}.$$"
  echo "EVENT phase=task-diff-sync host=${host} index=${index} status=upload sha=${expected_sha} remote_sha=${actual_sha:-missing}"
  remote::ssh "$host" "mkdir -p '$remote_tmp'" || return $?
  remote::scp "$task_script" "$(remote::adapt_host "$host"):${remote_tmp}/analyze-block-layout.py" || return $?
  remote::ssh "$host" "set -e; actual=\$(sha256sum '${remote_tmp}/analyze-block-layout.py' | awk '{print \$1}'); test \"\$actual\" = '${expected_sha}'; install -m 0755 '${remote_tmp}/analyze-block-layout.py' '${remote_task_path}'; test \"\$(sha256sum '${remote_task_path}' | awk '{print \$1}')\" = '${expected_sha}'; rm -rf '${remote_tmp}'" || return $?
  echo "EVENT phase=task-diff-sync host=${host} index=${index} status=updated sha=${expected_sha}"
}
