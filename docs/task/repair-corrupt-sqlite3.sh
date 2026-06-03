#!/usr/bin/env bash
set -o pipefail

PROBE_CMDS=(awk date lsof sqlite3)
APPLY_CMDS=(mktemp systemctl)
SERVICE_NAME="tidelet-worker.service"
SERVICE_STOPPED=0
EXECUTION_MUTATED=0
DRY_RUN=0
PORT=""
STATUS="success"
ERROR_MESSAGE=""
STARTED_AT=""
FINISHED_AT=""

LISTENER_PIDS=()
PROBE_MESSAGES=()

CANDIDATE_IDS=()
CANDIDATE_RESOURCE_KEYS=()
CANDIDATE_REASONS=()
CANDIDATE_TARGETS=()
CANDIDATE_SIZES=()
CANDIDATE_SAFE_TO_APPLY=()
CANDIDATE_LISTENER_PIDS=()
CANDIDATE_USER_PIDS=()
CANDIDATE_INTEGRITY_RESULTS=()
CANDIDATE_PLANNED_ACTIONS=()

RESULT_TARGETS=()
RESULT_STATUS=()
RESULT_BACKUPS=()
RESULT_DUMPS=()
RESULT_ERRORS=()
REPAIR_BACKUP_PATH=""
REPAIR_DUMP_PATH=""

function timestamp() {
  if command -v date >/dev/null 2>&1; then
    date +%Y-%m-%d_%H_%M_%S
  else
    printf 'unknown_time\n'
  fi
}

function log_info() {
  printf '[%s] [INFO] %s\n' "$(timestamp)" "$*" >&2
}

function log_warn() {
  printf '[%s] [WARN] %s\n' "$(timestamp)" "$*" >&2
}

function log_error() {
  printf '[%s] [ERROR] %s\n' "$(timestamp)" "$*" >&2
}

function json_string() {
  local value=${1:-}
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  value=${value//$'\r'/\\r}
  value=${value//$'\t'/\\t}
  printf '"%s"' "$value"
}

function json_string_array_from_words() {
  local value
  local first=1

  printf '['
  for value in "$@"; do
    if [ "$first" -eq 0 ]; then
      printf ','
    fi
    first=0
    json_string "$value"
  done
  printf ']'
}

function json_string_array_from_csv() {
  local value=${1:-}
  local parts=()

  if [ -n "$value" ]; then
    IFS=',' read -r -a parts <<< "$value"
  fi
  json_string_array_from_words "${parts[@]}"
}

function print_messages_json() {
  json_string_array_from_words "${PROBE_MESSAGES[@]}"
}

function record_probe_message() {
  PROBE_MESSAGES+=("$1")
}

function mark_failed() {
  STATUS="failed"
  ERROR_MESSAGE=$1
}

function usage() {
  cat >&2 <<EOF
Usage:
  $(basename "$0") -p <port> [options]
  $(basename "$0") --port <port> [options]

Options:
  -p, --port <port>         Port to scan for listener processes
  -s, --service <service>   Systemd service to stop/start during repair
  -n, --dry-run             Probe only and print the repair plan without mutations
  -h, --help                Show this help message
EOF
}

function parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -p|--port)
        if [ "$#" -lt 2 ]; then
          mark_failed "option $1 requires a value"
          return 2
        fi
        PORT=$2
        shift 2
        ;;
      --port=*)
        PORT=${1#*=}
        shift
        ;;
      -s|--service)
        if [ "$#" -lt 2 ]; then
          mark_failed "option $1 requires a value"
          return 2
        fi
        SERVICE_NAME=$2
        shift 2
        ;;
      --service=*)
        SERVICE_NAME=${1#*=}
        shift
        ;;
      -n|--dry-run)
        DRY_RUN=1
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      --)
        shift
        break
        ;;
      -*)
        mark_failed "unknown option: $1"
        return 2
        ;;
      *)
        mark_failed "unexpected positional argument: $1"
        return 2
        ;;
    esac
  done

  if [ -z "$PORT" ]; then
    mark_failed "missing required option: --port"
    return 2
  fi

  if ! [[ "$PORT" =~ ^[0-9]+$ ]]; then
    mark_failed "port must be numeric: $PORT"
    return 2
  fi

  if [ -z "$SERVICE_NAME" ]; then
    mark_failed "service name must not be empty"
    return 2
  fi
}

function require_commands() {
  local cmd
  local commands=("${PROBE_CMDS[@]}")

  if [ "$DRY_RUN" -eq 0 ]; then
    commands+=("${APPLY_CMDS[@]}")
  fi

  for cmd in "${commands[@]}"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      mark_failed "required command not found: $cmd"
      log_error "$ERROR_MESSAGE"
      return 2
    fi
  done
}

function get_listening_pids_by_port() {
  local port=$1
  lsof -i :"${port}" -sTCP:LISTEN -t 2>/dev/null | sort -n | uniq || true
}

function get_db_files_for_pid() {
  local pid=$1
  lsof -p "$pid" -Fn 2>/dev/null |
    awk '/^n/ && $0 ~ /\.db$/ { print substr($0, 2) }' |
    sort -u
}

function get_pids_using_file() {
  local db=$1
  lsof -t -- "$db" 2>/dev/null | sort -n | uniq || true
}

function file_size_bytes() {
  local path=$1

  if [ ! -f "$path" ]; then
    printf '0\n'
    return 0
  fi

  wc -c < "$path" | tr -d '[:space:]'
}

function db_integrity_result() {
  local db=$1
  sqlite3 "$db" "PRAGMA integrity_check;" 2>&1 || true
}

function db_seen() {
  local db=$1
  local existing

  for existing in "${CANDIDATE_TARGETS[@]}"; do
    if [ "$existing" = "$db" ]; then
      return 0
    fi
  done

  return 1
}

function append_csv_unique() {
  local csv=${1:-}
  local value=$2
  local item
  local parts=()

  if [ -n "$csv" ]; then
    IFS=',' read -r -a parts <<< "$csv"
    for item in "${parts[@]}"; do
      if [ "$item" = "$value" ]; then
        printf '%s\n' "$csv"
        return 0
      fi
    done
    printf '%s,%s\n' "$csv" "$value"
    return 0
  fi

  printf '%s\n' "$value"
}

function candidate_index_by_db() {
  local db=$1
  local idx

  for idx in "${!CANDIDATE_TARGETS[@]}"; do
    if [ "${CANDIDATE_TARGETS[$idx]}" = "$db" ]; then
      printf '%s\n' "$idx"
      return 0
    fi
  done

  return 1
}

function candidate_safe_to_apply() {
  local db=$1
  local dir

  dir=$(dirname -- "$db")
  if [ ! -f "$db" ]; then
    printf 'false\n'
    return 0
  fi
  if [ ! -r "$db" ]; then
    printf 'false\n'
    return 0
  fi
  if [ ! -w "$db" ]; then
    printf 'false\n'
    return 0
  fi
  if [ ! -w "$dir" ]; then
    printf 'false\n'
    return 0
  fi

  printf 'true\n'
}

function planned_actions_for_db() {
  local db=$1
  printf 'stop_service:%s,terminate_db_users:%s,sqlite_dump:%s,backup_original:%s,remove_wal_shm:%s,restore_from_dump:%s,restart_service:%s\n' \
    "$SERVICE_NAME" "$db" "$db" "$db" "$db" "$db" "$SERVICE_NAME"
}

function add_candidate() {
  local db=$1
  local listener_pid=$2
  local integrity_result=$3
  local idx
  local user_pids=()
  local user_pids_csv=''
  local user_pid

  if db_seen "$db"; then
    idx=$(candidate_index_by_db "$db") || return 1
    CANDIDATE_LISTENER_PIDS[idx]=$(append_csv_unique "${CANDIDATE_LISTENER_PIDS[$idx]}" "$listener_pid")
    return 0
  fi

  while IFS= read -r user_pid; do
    [ -n "$user_pid" ] || continue
    user_pids+=("$user_pid")
  done < <(get_pids_using_file "$db")
  for user_pid in "${user_pids[@]}"; do
    user_pids_csv=$(append_csv_unique "$user_pids_csv" "$user_pid")
  done

  CANDIDATE_IDS+=("$db")
  CANDIDATE_RESOURCE_KEYS+=("$db")
  CANDIDATE_REASONS+=("sqlite_integrity_check_failed")
  CANDIDATE_TARGETS+=("$db")
  CANDIDATE_SIZES+=("$(file_size_bytes "$db")")
  CANDIDATE_SAFE_TO_APPLY+=("$(candidate_safe_to_apply "$db")")
  CANDIDATE_LISTENER_PIDS+=("$listener_pid")
  CANDIDATE_USER_PIDS+=("$user_pids_csv")
  CANDIDATE_INTEGRITY_RESULTS+=("$integrity_result")
  CANDIDATE_PLANNED_ACTIONS+=("$(planned_actions_for_db "$db")")
}

function probe() {
  local pid
  local db
  local dbs=()
  local integrity_result

  log_info "[probe] scan listener processes on port ${PORT}"
  LISTENER_PIDS=()
  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    LISTENER_PIDS+=("$pid")
  done < <(get_listening_pids_by_port "$PORT")
  if [ "${#LISTENER_PIDS[@]}" -eq 0 ]; then
    record_probe_message "no listener process found on port ${PORT}"
    log_warn "[probe] no listener process found on port ${PORT}"
    return 0
  fi

  record_probe_message "found ${#LISTENER_PIDS[@]} listener pid(s) on port ${PORT}"
  for pid in "${LISTENER_PIDS[@]}"; do
    log_info "[probe] inspect pid ${pid}"
    dbs=()
    while IFS= read -r db; do
      [ -n "$db" ] || continue
      dbs+=("$db")
    done < <(get_db_files_for_pid "$pid")
    if [ "${#dbs[@]}" -eq 0 ]; then
      record_probe_message "pid ${pid} has no opened .db files"
      continue
    fi

    for db in "${dbs[@]}"; do
      integrity_result=$(db_integrity_result "$db")
      if [ "$integrity_result" = "ok" ]; then
        log_info "[probe] sqlite integrity ok: ${db}"
        continue
      fi

      log_warn "[probe] sqlite integrity failed: ${db}"
      add_candidate "$db" "$pid" "$integrity_result"
    done
  done

  if [ "${#CANDIDATE_TARGETS[@]}" -eq 0 ]; then
    record_probe_message "no corrupted sqlite database found"
  else
    record_probe_message "planned ${#CANDIDATE_TARGETS[@]} corrupted sqlite database repair(s)"
  fi
}

function stop_service_for_repair() {
  if [ "$SERVICE_STOPPED" -eq 1 ]; then
    return 0
  fi

  log_info "[build] stop service ${SERVICE_NAME}"
  if ! systemctl stop "$SERVICE_NAME" >&2; then
    log_warn "[build] failed to stop service ${SERVICE_NAME}"
  fi
  SERVICE_STOPPED=1
}

function restore_service_if_stopped() {
  if [ "$SERVICE_STOPPED" -eq 0 ]; then
    return 0
  fi

  log_info "[build] restart service ${SERVICE_NAME}"
  if ! systemctl start "$SERVICE_NAME" >&2; then
    log_warn "[build] failed to start service ${SERVICE_NAME}"
  fi
  SERVICE_STOPPED=0
}

function ensure_process_terminated() {
  local pid=$1
  local timeout=${2:-15}
  local waited=0

  if ! kill -0 "$pid" 2>/dev/null; then
    return 0
  fi

  kill "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$timeout" ]; then
      log_warn "[build] process ${pid} did not exit after ${timeout}s; sending SIGKILL"
      kill -9 "$pid" 2>/dev/null || true
      break
    fi

    sleep 1
    waited=$((waited + 1))
  done

  ! kill -0 "$pid" 2>/dev/null
}

function terminate_db_users_from_plan() {
  local pids_csv=$1
  local pids=()
  local pid

  if [ -z "$pids_csv" ]; then
    return 0
  fi

  IFS=',' read -r -a pids <<< "$pids_csv"
  for pid in "${pids[@]}"; do
    log_info "[build] terminate pid ${pid}"
    ensure_process_terminated "$pid" 150 || log_warn "[build] failed to terminate pid ${pid}"
  done
}

function repair_sqlite_db() {
  local db=$1
  local dir
  local base
  local backup
  local dumpfile

  REPAIR_BACKUP_PATH=""
  REPAIR_DUMP_PATH=""
  dir=$(dirname -- "$db")
  base=$(basename -- "$db")
  dumpfile=$(mktemp "${dir}/${base}.dump.XXXXXX") || dumpfile=$(mktemp)
  backup="${db}.corrupted.bak.$(timestamp)"
  REPAIR_DUMP_PATH=$dumpfile
  REPAIR_BACKUP_PATH=$backup

  log_info "[build] dump sqlite db ${db} to ${dumpfile}"
  sqlite3 "$db" "PRAGMA wal_checkpoint(TRUNCATE);" >&2 || true
  if ! sqlite3 "$db" ".dump" > "$dumpfile"; then
    rm -f "$dumpfile"
    return 1
  fi

  log_info "[build] backup sqlite db ${db} to ${backup}"
  if ! mv -- "$db" "$backup"; then
    rm -f "$dumpfile"
    return 1
  fi
  rm -f "${backup}-wal" "${backup}-shm" "${db}-wal" "${db}-shm"

  log_info "[build] restore sqlite db ${db} from ${dumpfile}"
  if ! sqlite3 "$db" < "$dumpfile"; then
    mv -- "$backup" "$db" || true
    rm -f "$dumpfile"
    return 1
  fi

  rm -f "$dumpfile"
  return 0
}

function add_result() {
  RESULT_TARGETS+=("$1")
  RESULT_STATUS+=("$2")
  RESULT_BACKUPS+=("$3")
  RESULT_DUMPS+=("$4")
  RESULT_ERRORS+=("$5")
}

function safe_candidate_count() {
  local idx
  local count=0

  for idx in "${!CANDIDATE_TARGETS[@]}"; do
    if [ "${CANDIDATE_SAFE_TO_APPLY[$idx]}" = "true" ]; then
      count=$((count + 1))
    fi
  done

  printf '%s\n' "$count"
}

function apply_plan() {
  local idx
  local db
  local safe_count=0

  if [ "${#CANDIDATE_TARGETS[@]}" -eq 0 ]; then
    return 0
  fi

  safe_count=$(safe_candidate_count)
  if [ "$safe_count" -eq 0 ]; then
    for idx in "${!CANDIDATE_TARGETS[@]}"; do
      add_result "${CANDIDATE_TARGETS[$idx]}" "skipped" "" "" "candidate failed safety checks"
    done
    STATUS="failed"
    ERROR_MESSAGE="no safe sqlite repair candidates"
    return 1
  fi

  trap 'restore_service_if_stopped' EXIT
  stop_service_for_repair
  EXECUTION_MUTATED=1

  for idx in "${!CANDIDATE_TARGETS[@]}"; do
    db=${CANDIDATE_TARGETS[$idx]}
    if [ "${CANDIDATE_SAFE_TO_APPLY[$idx]}" != "true" ]; then
      add_result "$db" "skipped" "" "" "candidate failed safety checks"
      continue
    fi

    terminate_db_users_from_plan "${CANDIDATE_USER_PIDS[$idx]}"
    if repair_sqlite_db "$db"; then
      add_result "$db" "repaired" "$REPAIR_BACKUP_PATH" "$REPAIR_DUMP_PATH" ""
    else
      add_result "$db" "failed" "$REPAIR_BACKUP_PATH" "$REPAIR_DUMP_PATH" "sqlite dump or restore failed"
      STATUS="failed"
      ERROR_MESSAGE="one or more sqlite repairs failed"
    fi
  done
}

function print_candidates_json() {
  local idx

  printf '['
  for idx in "${!CANDIDATE_TARGETS[@]}"; do
    if [ "$idx" -gt 0 ]; then
      printf ','
    fi
    printf '{"id":'
    json_string "${CANDIDATE_IDS[$idx]}"
    printf ',"resource_key":'
    json_string "${CANDIDATE_RESOURCE_KEYS[$idx]}"
    printf ',"reason":'
    json_string "${CANDIDATE_REASONS[$idx]}"
    printf ',"target":'
    json_string "${CANDIDATE_TARGETS[$idx]}"
    printf ',"size_bytes":%s' "${CANDIDATE_SIZES[$idx]}"
    printf ',"safe_to_apply":%s' "${CANDIDATE_SAFE_TO_APPLY[$idx]}"
    printf ',"listener_pids":'
    json_string_array_from_csv "${CANDIDATE_LISTENER_PIDS[$idx]}"
    printf ',"current_user_pids":'
    json_string_array_from_csv "${CANDIDATE_USER_PIDS[$idx]}"
    printf ',"integrity_result":'
    json_string "${CANDIDATE_INTEGRITY_RESULTS[$idx]}"
    printf ',"planned_mutations":'
    json_string_array_from_csv "${CANDIDATE_PLANNED_ACTIONS[$idx]}"
    printf '}'
  done
  printf ']'
}

function print_results_json() {
  local idx

  printf '['
  for idx in "${!RESULT_TARGETS[@]}"; do
    if [ "$idx" -gt 0 ]; then
      printf ','
    fi
    printf '{"target":'
    json_string "${RESULT_TARGETS[$idx]}"
    printf ',"status":'
    json_string "${RESULT_STATUS[$idx]}"
    printf ',"backup_path":'
    json_string "${RESULT_BACKUPS[$idx]}"
    printf ',"dump_path":'
    json_string "${RESULT_DUMPS[$idx]}"
    printf ',"error":'
    json_string "${RESULT_ERRORS[$idx]}"
    printf '}'
  done
  printf ']'
}

function print_report() {
  FINISHED_AT=$(timestamp)
  printf '{\n'
  printf '  "report_type": "repair_corrupt_sqlite3",\n'
  printf '  "mode": '
  if [ "$DRY_RUN" -eq 1 ]; then
    json_string "dry-run"
  else
    json_string "apply"
  fi
  printf ',\n'
  printf '  "status": '
  json_string "$STATUS"
  printf ',\n'
  printf '  "error": '
  json_string "$ERROR_MESSAGE"
  printf ',\n'
  printf '  "started_at": '
  json_string "$STARTED_AT"
  printf ',\n'
  printf '  "finished_at": '
  json_string "$FINISHED_AT"
  printf ',\n'
  printf '  "probe": {\n'
  printf '    "port": '
  json_string "$PORT"
  printf ',\n'
  printf '    "service": '
  json_string "$SERVICE_NAME"
  printf ',\n'
  printf '    "listener_pids": '
  json_string_array_from_words "${LISTENER_PIDS[@]}"
  printf ',\n'
  printf '    "messages": '
  print_messages_json
  printf '\n'
  printf '  },\n'
  printf '  "plan": {\n'
  printf '    "candidate_count": %s,\n' "${#CANDIDATE_TARGETS[@]}"
  printf '    "phase_order": ["probe","build"],\n'
  printf '    "candidates": '
  print_candidates_json
  printf '\n'
  printf '  },\n'
  printf '  "execution": {\n'
  printf '    "mutated": %s,\n' "$EXECUTION_MUTATED"
  printf '    "service_stopped": %s,\n' "$SERVICE_STOPPED"
  printf '    "results": '
  print_results_json
  printf '\n'
  printf '  }\n'
  printf '}\n'
}

function main() {
  STARTED_AT=$(timestamp)

  if ! parse_args "$@"; then
    usage
    print_report
    return 2
  fi

  if ! require_commands; then
    print_report
    return 2
  fi

  if ! probe; then
    mark_failed "probe failed"
    print_report
    return 1
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    print_report
    return 0
  fi

  apply_plan
  print_report

  if [ "$STATUS" = "failed" ]; then
    return 1
  fi
  return 0
}

main "$@"
