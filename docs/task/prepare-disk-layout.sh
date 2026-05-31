#!/bin/bash

readonly LOG_COLOR_RED='\033[0;31m'
readonly LOG_COLOR_GREEN='\033[0;32m'
readonly LOG_COLOR_YELLOW='\033[1;33m'
readonly LOG_COLOR_BLUE='\033[0;34m'
readonly LOG_COLOR_RESET='\033[0m'

readonly DEFAULT_TIDELET_HOME='/opt/tiger/tide/tidelet'
readonly DEFAULT_ENGINE_HOME='/opt/tiger/tide'
readonly NUMA_MEMORY_LIMIT_MB=$((512 * 1024))
readonly NUMA_FIX_DOC_URL='https://bytedance.larkoffice.com/docx/IjZ5dyj4oobPBtxeDK6cFwVsn9d'
readonly DISK_REPAIR_URL='https://bytebox.bytedance.net/'
readonly EXPLAIN_RIGHT_MARGIN=4
readonly EXPLAIN_DRYRUN_PREFIX_WIDTH=9

TIDELET_HOME_PATH=''
ENGINE_HOME_PATH=''
PURGE_LINK_TARGET_DATA=0
REMOVE_TIDEMQ_OFFSET=0
REPAIR_MISSING_PATH_METADATA=0
DRY_RUN=0
DISALLOWED_DIRS_ARR=()
HDD_DIRS=()
SSD_DIRS=()
PENDING_PURGE_TARGETS=()
EXPLAIN_CPU_MODEL=''
EXPLAIN_NUMA_COUNT=''
EXPLAIN_NUMA_MEMORY_LINES=()
EXPLAIN_DISK_HEALTH_LINES=()
EXPLAIN_RESOURCE_LINES=()
EXPLAIN_LAYOUT_LINES=()
EXPLAIN_ACTION_LINES=()
EXPLAIN_ACTION_KEYS=()
EXPLAIN_METADATA_REPAIR_LINES=()
METADATA_REPAIR_TARGETS=()
SEEN_BLOCK_DEVICES=()
SEEN_BLOCK_DEVICE_MOUNTS=()

function log::print() {
    local color=$1
    local level=$2
    local message=$3

    printf '%b[%s]%b %s\n' "$color" "$level" "$LOG_COLOR_RESET" "$message"
}

function log::info() {
    log::print "$LOG_COLOR_BLUE" "INFO" "$1"
}

function log::warn() {
    log::print "$LOG_COLOR_YELLOW" "WARN" "$1"
}

function log::error() {
    log::print "$LOG_COLOR_RED" "ERROR" "$1" >&2
}

function log::success() {
    log::print "$LOG_COLOR_GREEN" "OK" "$1"
}

function log::dryrun() {
    log::print "$LOG_COLOR_YELLOW" "DRYRUN" "$1"
}

function json::string() {
    local value=$1

    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '"%s"' "$value"
}

function json::print_string_array() {
    local first=1
    local value=''

    printf '['
    for value in "$@"; do
        if (( first == 0 )); then
            printf ','
        fi
        first=0
        json::string "$value"
    done
    printf ']'
}

function json::print_disk_health_array() {
    local first=1
    local line=''
    local status=''
    local mount=''
    local kind=''
    local device=''

    printf '['
    for line in "$@"; do
        IFS='|' read -r status mount kind device <<< "$line"
        if (( first == 0 )); then
            printf ','
        fi
        first=0
        printf '{"status":'
        json::string "${status:-UNKNOWN}"
        printf ',"mount":'
        json::string "${mount:-unknown}"
        printf ',"kind":'
        json::string "${kind:-unknown}"
        printf ',"device":'
        json::string "${device:-unknown}"
        printf '}'
    done
    printf ']'
}

function layout::link_status() {
    local target=$1
    local source=$2
    local current_source=''
    local current_resolved=''
    local source_resolved=''

    if [[ -L "$target" ]]; then
        current_source=$(readlink "$target" 2>/dev/null || true)
        if [[ "$current_source" == "$source" && -e "$target" ]]; then
            printf 'ok\n'
            return 0
        fi

        current_resolved=$(readlink -f "$target" 2>/dev/null || true)
        source_resolved=$(readlink -f "$source" 2>/dev/null || true)
        if [[ -n "$current_resolved" && "$current_resolved" == "$source_resolved" ]]; then
            printf 'ok\n'
            return 0
        fi

        printf 'change_required\n'
        return 0
    fi

    if [[ -e "$target" ]]; then
        printf 'replace_non_symlink\n'
        return 0
    fi

    printf 'missing\n'
}

function json::print_expected_link() {
    local name=$1
    local target=$2
    local source=$3
    local role=$4

    printf '{"name":'
    json::string "$name"
    printf ',"role":'
    json::string "$role"
    printf ',"target":'
    json::string "$target"
    printf ',"source":'
    json::string "$source"
    printf ',"status":'
    json::string "$(layout::link_status "$target" "$source")"
    printf '}'
}

function json::print_manifest_overview_item() {
    local name=$1
    local path=$2
    local expected=$3
    local strategy=$4
    local current=''
    local status='missing'

    if [[ -f "$path" ]]; then
        current=$(<"$path")
        if [[ "$current" == "$expected" ]]; then
            status='ok'
        else
            status='change_required'
        fi
    fi

    printf '{"name":'
    json::string "$name"
    printf ',"path":'
    json::string "$path"
    printf ',"expected_content":'
    json::string "$expected"
    printf ',"current_content":'
    json::string "$current"
    printf ',"strategy":'
    json::string "$strategy"
    printf ',"status":'
    json::string "$status"
    printf '}'
}

function json::print_expected_disk_links() {
    local node_index=$1
    local prefix=$2
    local target_prefix=$3
    shift 3
    local mounts=("$@")
    local first=1
    local index=1
    local mount_path=''
    local name=''
    local target=''
    local source=''

    printf '['
    for mount_path in "${mounts[@]}"; do
        name=$(printf '%s%02d' "$prefix" "$index")
        target=$(printf '%s/node%d/%s%02d' "$ENGINE_HOME_PATH" "$node_index" "$target_prefix" "$index")
        source="${mount_path}/fringedb/data"
        if (( first == 0 )); then
            printf ','
        fi
        first=0
        json::print_expected_link "$name" "$target" "$source" 'data'
        index=$((index + 1))
    done
    printf ']'
}

function json::print_metadata_links() {
    local node_index=$1
    local metadata_dir=$2
    local first=1
    local name=''

    printf '['
    for name in disk00 metadata; do
        if (( first == 0 )); then
            printf ','
        fi
        first=0
        json::print_expected_link "$name" "$ENGINE_HOME_PATH/node${node_index}/${name}" "$metadata_dir" 'metadata'
    done
    printf ']'
}

function json::print_layout_node() {
    local node_index=$1
    local hdd_per_node=$2
    local ssd_per_node=$3
    local hdd_start=$((node_index * hdd_per_node))
    local ssd_start=$((node_index * ssd_per_node))
    local hdd_mounts=()
    local ssd_mounts=()
    local metadata_source_type='none'
    local metadata_mount=''
    local metadata_dir=''

    if (( hdd_per_node > 0 )); then
        hdd_mounts=("${HDD_DIRS[@]:hdd_start:hdd_per_node}")
    fi
    if (( ssd_per_node > 0 )); then
        ssd_mounts=("${SSD_DIRS[@]:ssd_start:ssd_per_node}")
    fi

    if (( ${#ssd_mounts[@]} > 0 )); then
        metadata_source_type='ssd'
        metadata_mount=${ssd_mounts[0]}
        metadata_dir="${metadata_mount}/fringedb/node${node_index}"
    elif (( ${#hdd_mounts[@]} > 0 )); then
        metadata_source_type='hdd'
        metadata_mount=${hdd_mounts[0]}
        metadata_dir="${metadata_mount}/fringedb/node${node_index}"
    fi

    printf '{"node":"node%s","node_path":' "$node_index"
    json::string "$ENGINE_HOME_PATH/node${node_index}"
    printf ',"metadata":{"source_type":'
    json::string "$metadata_source_type"
    printf ',"mount":'
    json::string "$metadata_mount"
    printf ',"directory":'
    json::string "$metadata_dir"
    printf ',"links":'
    if [[ -n "$metadata_dir" ]]; then
        json::print_metadata_links "$node_index" "$metadata_dir"
    else
        printf '[]'
    fi
    printf '},"hdd":{"mounts":'
    json::print_string_array "${hdd_mounts[@]}"
    printf ',"links":'
    json::print_expected_disk_links "$node_index" 'disk' 'disk' "${hdd_mounts[@]}"
    printf '},"ssd":{"mounts":'
    json::print_string_array "${ssd_mounts[@]}"
    printf ',"links":'
    json::print_expected_disk_links "$node_index" 'ssd_disks_' 'ssd_disks_' "${ssd_mounts[@]}"
    printf '}}'
}

function json::print_layout_nodes() {
    local numa_count=${EXPLAIN_NUMA_COUNT:-0}
    local total_hdd=${#HDD_DIRS[@]}
    local total_ssd=${#SSD_DIRS[@]}
    local hdd_per_node=0
    local ssd_per_node=0
    local node_index=0

    if (( numa_count > 0 && total_hdd > 0 )); then
        hdd_per_node=$((total_hdd / numa_count))
    fi
    if (( numa_count > 0 && total_ssd > 0 )); then
        ssd_per_node=$((total_ssd / numa_count))
    fi

    printf '['
    for ((node_index = 0; node_index < numa_count; node_index++)); do
        if (( node_index > 0 )); then
            printf ','
        fi
        json::print_layout_node "$node_index" "$hdd_per_node" "$ssd_per_node"
    done
    printf ']'
}

function json::print_layout_overview() {
    local numa_count=${EXPLAIN_NUMA_COUNT:-0}
    local total_hdd=${#HDD_DIRS[@]}
    local total_ssd=${#SSD_DIRS[@]}
    local hdd_per_node=0
    local ssd_per_node=0
    local disks_manifest=''
    local ssd_manifest=''
    local metadata_source='none'
    local ssd_strategy='dedicated_ssd'

    if (( numa_count > 0 && total_hdd > 0 )); then
        hdd_per_node=$((total_hdd / numa_count))
        disks_manifest=$(manifest::build_disk_names 'disk' "$hdd_per_node")
    fi
    if (( numa_count > 0 && total_ssd > 0 )); then
        ssd_per_node=$((total_ssd / numa_count))
        ssd_manifest=$(manifest::build_disk_names 'ssd_disks_' "$ssd_per_node")
        metadata_source='ssd'
    elif (( total_hdd > 0 )); then
        ssd_strategy='reuse_hdd_disk_manifest'
        ssd_manifest="$disks_manifest"
        disks_manifest=''
        metadata_source='hdd'
    else
        ssd_strategy='no_disks'
    fi

    printf '{\n'
    printf '    "summary": {\n'
    printf '      "numa_count": %s,\n' "$numa_count"
    printf '      "hdd_total": %s,\n' "$total_hdd"
    printf '      "ssd_total": %s,\n' "$total_ssd"
    printf '      "hdd_per_node": %s,\n' "$hdd_per_node"
    printf '      "ssd_per_node": %s,\n' "$ssd_per_node"
    printf '      "metadata_source": '
    json::string "$metadata_source"
    printf ',\n'
    printf '      "ssd_strategy": '
    json::string "$ssd_strategy"
    printf '\n'
    printf '    },\n'
    printf '    "manifests": ['
    json::print_manifest_overview_item 'disks' "$TIDELET_HOME_PATH/disks" "$disks_manifest" 'hdd_data_links'
    printf ','
    json::print_manifest_overview_item 'ssd_disks' "$TIDELET_HOME_PATH/ssd_disks" "$ssd_manifest" "$ssd_strategy"
    printf '],\n'
    printf '    "nodes": '
    json::print_layout_nodes
    printf '\n'
    printf '  }'
}

function json::print_planned_mutation_count() {
    local type=$1
    local count=0
    local line=''

    for line in "${EXPLAIN_ACTION_LINES[@]}"; do
        case "$type:$line" in
        directories:ensure\ directory:*) count=$((count + 1)) ;;
        links:link\ *) count=$((count + 1)) ;;
        manifests:write\ file:* | manifests:copy\ file:* | manifests:truncate\ file:*) count=$((count + 1)) ;;
        cleanup:remove\ path:* | cleanup:purge\ symlink\ target\ data:*) count=$((count + 1)) ;;
        kept:keep\ path:*) count=$((count + 1)) ;;
        esac
    done

    printf '%s' "$count"
}

function json::print_directory_mutations() {
    local first=1
    local line=''
    local path=''

    printf '['
    for line in "${EXPLAIN_ACTION_LINES[@]}"; do
        [[ "$line" == ensure\ directory:* ]] || continue
        path=${line#ensure directory: }
        if (( first == 0 )); then
            printf ','
        fi
        first=0
        printf '{"action":"ensure_directory","path":'
        json::string "$path"
        printf '}'
    done
    printf ']'
}

function json::print_link_mutations() {
    local first=1
    local line=''
    local target=''
    local source=''
    local node='Global'
    local name=''

    printf '['
    for line in "${EXPLAIN_ACTION_LINES[@]}"; do
        [[ "$line" == link\ * ]] || continue
        target=${line#link }
        source=${target#* -> }
        target=${target%% -> *}
        name=${target##*/}
        node='Global'
        if [[ "$target" =~ /node([0-9]+)(/|$) ]]; then
            node="node${BASH_REMATCH[1]}"
        fi
        if (( first == 0 )); then
            printf ','
        fi
        first=0
        printf '{"action":"link","node":'
        json::string "$node"
        printf ',"name":'
        json::string "$name"
        printf ',"target":'
        json::string "$target"
        printf ',"source":'
        json::string "$source"
        printf '}'
    done
    printf ']'
}

function json::print_manifest_mutations() {
    local first=1
    local line=''
    local path=''
    local content=''
    local source=''
    local target=''

    printf '['
    for line in "${EXPLAIN_ACTION_LINES[@]}"; do
        case "$line" in
        write\ file:*)
            path=${line#write file: }
            content=${path#* = }
            path=${path%% = *}
            ;;
        copy\ file:*)
            source=${line#copy file: }
            target=${source#* -> }
            source=${source%% -> *}
            ;;
        truncate\ file:*)
            path=${line#truncate file: }
            ;;
        *)
            continue
            ;;
        esac

        if (( first == 0 )); then
            printf ','
        fi
        first=0

        if [[ "$line" == write\ file:* ]]; then
            printf '{"action":"write_file","path":'
            json::string "$path"
            printf ',"content":'
            json::string "$content"
            printf '}'
        elif [[ "$line" == copy\ file:* ]]; then
            printf '{"action":"copy_file","source":'
            json::string "$source"
            printf ',"target":'
            json::string "$target"
            printf '}'
        else
            printf '{"action":"truncate_file","path":'
            json::string "$path"
            printf '}'
        fi
    done
    printf ']'
}

function json::print_cleanup_mutations() {
    local first=1
    local line=''
    local path=''
    local action=''

    printf '['
    for line in "${EXPLAIN_ACTION_LINES[@]}"; do
        case "$line" in
        remove\ path:*)
            action='remove_path'
            path=${line#remove path: }
            ;;
        purge\ symlink\ target\ data:*)
            action='purge_symlink_target_data'
            path=${line#purge symlink target data: }
            ;;
        *)
            continue
            ;;
        esac

        if (( first == 0 )); then
            printf ','
        fi
        first=0
        printf '{"action":'
        json::string "$action"
        printf ',"path":'
        json::string "$path"
        printf '}'
    done
    printf ']'
}

function json::print_planned_mutations() {
    printf '{\n'
    printf '    "summary": {\n'
    printf '      "total": %s,\n' "${#EXPLAIN_ACTION_LINES[@]}"
    printf '      "directories": '
    json::print_planned_mutation_count directories
    printf ',\n'
    printf '      "links": '
    json::print_planned_mutation_count links
    printf ',\n'
    printf '      "manifests": '
    json::print_planned_mutation_count manifests
    printf ',\n'
    printf '      "cleanup": '
    json::print_planned_mutation_count cleanup
    printf ',\n'
    printf '      "kept": '
    json::print_planned_mutation_count kept
    printf '\n'
    printf '    },\n'
    printf '    "links": '
    json::print_link_mutations
    printf ',\n'
    printf '    "manifests": '
    json::print_manifest_mutations
    printf ',\n'
    printf '    "directories": '
    json::print_directory_mutations
    printf ',\n'
    printf '    "cleanup": '
    json::print_cleanup_mutations
    printf ',\n'
    printf '    "raw_actions": '
    json::print_string_array "${EXPLAIN_ACTION_LINES[@]}"
    printf '\n'
    printf '  }'
}

function explain::get_terminal_width() {
    local raw_width=''

    if [[ -r /dev/tty ]] && command -v stty >/dev/null 2>&1; then
        raw_width=$(stty size </dev/tty 2>/dev/null | awk '{print $2}')
    fi

    if [[ -z "$raw_width" ]] && command -v tput >/dev/null 2>&1; then
        raw_width=$(tput cols 2>/dev/null || true)
    fi

    if [[ -z "$raw_width" && -n "${COLUMNS:-}" && "${COLUMNS}" =~ ^[0-9]+$ ]]; then
        raw_width=$COLUMNS
    fi

    if ! [[ "$raw_width" =~ ^[0-9]+$ ]]; then
        raw_width=100
    fi

    printf '%s\n' "$raw_width"
}

function explain::get_plain_output_width() {
    local terminal_width=0
    local width=0

    terminal_width=$(explain::get_terminal_width)
    width=$((terminal_width * 2 / 3 - EXPLAIN_RIGHT_MARGIN))

    if (( width < 60 )); then
        width=60
    fi

    printf '%s\n' "$width"
}

function explain::get_dryrun_box_content_width() {
    local terminal_width=0
    local width=0

    terminal_width=$(explain::get_terminal_width)
    width=$((terminal_width * 2 / 3 - EXPLAIN_DRYRUN_PREFIX_WIDTH - EXPLAIN_RIGHT_MARGIN - 4))

    if (( width < 40 )); then
        width=40
    fi

    printf '%s\n' "$width"
}

function explain::repeat_char() {
    local char=$1
    local count=$2
    local output=''

    printf -v output '%*s' "$count" ''
    output=${output// /$char}
    printf '%s\n' "$output"
}

function explain::print_wrapped_line() {
    local prefix=$1
    local text=$2
    local width=$3
    local line=''
    local first_line=1
    local align_prefix=''

    printf -v align_prefix '%*s' "${#prefix}" ''

    while IFS= read -r line; do
        if (( first_line == 1 )); then
            log::dryrun "${prefix}${line}"
            first_line=0
            continue
        fi

        log::dryrun "${align_prefix}${line}"
    done < <(printf '%s\n' "$text" | fold -s -w "$width")
}

function explain::record_numa_memory() {
    EXPLAIN_NUMA_MEMORY_LINES+=("$1")
}

function explain::record_resource() {
    EXPLAIN_RESOURCE_LINES+=("$1")
}

function explain::record_disk_health() {
    EXPLAIN_DISK_HEALTH_LINES+=("$1")
}

function explain::print_disk_health_table() {
    local lines=("$@")
    local available_width=0
    local header_line=''
    local status=''
    local mount=''
    local kind=''
    local device=''
    local col_status=8
    local col_mount=16
    local col_kind=6
    local col_device=0
    local line=''
    local sep=''
    local fields=()

    if (( ${#lines[@]} == 0 )); then
        printf '  %s\n' "- <none>"
        return 0
    fi

    available_width=$(explain::get_plain_output_width)
    if (( available_width < 56 )); then
        available_width=56
    fi

    col_device=$((available_width - col_status - col_mount - col_kind - 15))
    if (( col_device < 12 )); then
        col_device=12
    fi

    printf -v sep "  +-%s-+-%s-+-%s-+-%s-+" \
        "$(explain::repeat_char '-' "$col_status")" \
        "$(explain::repeat_char '-' "$col_mount")" \
        "$(explain::repeat_char '-' "$col_kind")" \
        "$(explain::repeat_char '-' "$col_device")"
    printf '%s\n' "$sep"
    printf -v header_line "  | %-*s | %-*s | %-*s | %-*s |" \
        "$col_status" "STATUS" \
        "$col_mount" "MOUNT" \
        "$col_kind" "KIND" \
        "$col_device" "DEVICE"
    printf '%s\n' "$header_line"
    printf '%s\n' "$sep"

    for line in "${lines[@]}"; do
        IFS='|' read -r -a fields <<< "$line"
        status=${fields[0]:-UNKNOWN}
        mount=${fields[1]:-unknown}
        kind=${fields[2]:-unknown}
        device=${fields[3]:-unknown}

        printf -v header_line "  | %-*s | %-*s | %-*s | %-*s |" \
            "$col_status" "$status" \
            "$col_mount" "$mount" \
            "$col_kind" "$kind" \
            "$col_device" "$device"
        printf '%s\n' "$header_line"
    done

    printf '%s\n' "$sep"
}

function explain::print_command_table() {
    local title=$1
    shift
    local rows=("$@")
    local available_width=0
    local col_check=18
    local col_status=8
    local col_detail=0
    local sep=''
    local line=''
    local check_name=''
    local status=''
    local detail=''
    local detail_line=''
    local fields=()

    available_width=$(explain::get_plain_output_width)
    col_detail=$((available_width - col_check - col_status - 12))
    if (( col_detail < 24 )); then
        col_detail=24
    fi

    printf '  %s\n' "$title"
    printf -v sep "  +-%s-+-%s-+-%s-+" \
        "$(explain::repeat_char '-' "$col_check")" \
        "$(explain::repeat_char '-' "$col_status")" \
        "$(explain::repeat_char '-' "$col_detail")"
    printf '%s\n' "$sep"
    printf -v line "  | %-*s | %-*s | %-*s |" \
        "$col_check" "CHECK" \
        "$col_status" "STATUS" \
        "$col_detail" "DETAIL"
    printf '%s\n' "$line"
    printf '%s\n' "$sep"

    for line in "${rows[@]}"; do
        IFS='|' read -r -a fields <<< "$line"
        check_name=${fields[0]:-unknown}
        status=${fields[1]:-UNKNOWN}
        detail=${fields[2]:-}

        if [[ -z "$detail" ]]; then
            printf -v line "  | %-*s | %-*s | %-*s |" \
                "$col_check" "$check_name" \
                "$col_status" "$status" \
                "$col_detail" ""
            printf '%s\n' "$line"
            continue
        fi

        while IFS= read -r detail_line; do
            printf -v line "  | %-*s | %-*s | %-*s |" \
                "$col_check" "$check_name" \
                "$col_status" "$status" \
                "$col_detail" "$detail_line"
            printf '%s\n' "$line"
            check_name=''
            status=''
        done < <(printf '%s\n' "$detail" | fold -s -w "$col_detail")
    done

    printf '%s\n' "$sep"
}

function explain::record_layout() {
    EXPLAIN_LAYOUT_LINES+=("$1")
}

function explain::record_metadata_repair() {
    EXPLAIN_METADATA_REPAIR_LINES+=("$1")
}

function explain::record_action() {
    local action=$1
    local existing=''

    for existing in "${EXPLAIN_ACTION_KEYS[@]}"; do
        [[ "$existing" == "$action" ]] && return 0
    done

    EXPLAIN_ACTION_KEYS+=("$action")
    EXPLAIN_ACTION_LINES+=("$action")
}

function explain::print_section() {
    local title=$1
    shift
    local lines=("$@")
    local available_width=0
    local line=''
    local border=''

    available_width=$(explain::get_dryrun_box_content_width)
    if (( available_width < 40 )); then
        available_width=40
    fi

    border="+$(explain::repeat_char '-' "$((available_width + 2))")+"
    log::dryrun "$border"
    printf -v line "| %-*s |" "$available_width" "$title"
    log::dryrun "$line"
    log::dryrun "$border"

    if (( ${#lines[@]} == 0 )); then
        log::dryrun "  - <none>"
        return 0
    fi

    for line in "${lines[@]}"; do
        explain::print_wrapped_line "  - " "$line" "$((available_width - 2))"
    done
}

function explain::action_node_key() {
    local action=$1

    if [[ "$action" =~ /node([0-9]+)(/|$) ]]; then
        printf 'node%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi

    printf 'Global\n'
}

function explain::print_mutation_block() {
    local title=$1
    shift
    local lines=("$@")
    local available_width=0
    local line=''

    if (( ${#lines[@]} == 0 )); then
        return 0
    fi

    available_width=$(explain::get_dryrun_box_content_width)
    if (( available_width < 40 )); then
        available_width=40
    fi

    log::dryrun ""
    log::dryrun "  [ ${title} ]"
    for line in "${lines[@]}"; do
        explain::print_wrapped_line "    - " "$line" "$((available_width - 4))"
    done
}

function explain::print_mutation_blocks() {
    local title=$1
    shift
    local lines=("$@")
    local available_width=0
    local line=''
    local border=''
    local key=''
    local node_key=''
    local global_lines=()
    local node_keys=()
    local node_lines=()
    local idx=0
    local found=0
    local block_lines=()

    available_width=$(explain::get_dryrun_box_content_width)
    if (( available_width < 40 )); then
        available_width=40
    fi

    border="+$(explain::repeat_char '-' "$((available_width + 2))")+"
    log::dryrun "$border"
    printf -v line "| %-*s |" "$available_width" "$title"
    log::dryrun "$line"
    log::dryrun "$border"

    if (( ${#lines[@]} == 0 )); then
        log::dryrun "  - <none>"
        return 0
    fi

    for line in "${lines[@]}"; do
        key=$(explain::action_node_key "$line")
        if [[ "$key" == "Global" ]]; then
            global_lines+=("$line")
            continue
        fi

        found=0
        for idx in "${!node_keys[@]}"; do
            if [[ "${node_keys[$idx]}" == "$key" ]]; then
                node_lines[idx]+=$'\n'"$line"
                found=1
                break
            fi
        done

        if (( found == 0 )); then
            node_keys+=("$key")
            node_lines+=("$line")
        fi
    done

    explain::print_mutation_block "Global" "${global_lines[@]}"

    while IFS= read -r node_key; do
        [[ -n "$node_key" ]] || continue
        block_lines=()
        for idx in "${!node_keys[@]}"; do
            if [[ "${node_keys[$idx]}" == "$node_key" ]]; then
                while IFS= read -r line; do
                    block_lines+=("$line")
                done <<< "${node_lines[$idx]}"
                break
            fi
        done

        explain::print_mutation_block "$node_key" "${block_lines[@]}"
    done < <(printf '%s\n' "${node_keys[@]}" | sort -V)
}

function explain::print_summary() {
    if (( DRY_RUN == 0 )); then
        return 0
    fi

    printf '{\n'
    printf '  "report_type": "prepare_disk_layout",\n'
    printf '  "mode": "dry-run",\n'
    printf '  "environment": {\n'
    printf '    "numa_count": '
    if [[ "${EXPLAIN_NUMA_COUNT:-}" =~ ^[0-9]+$ ]]; then
        printf '%s' "$EXPLAIN_NUMA_COUNT"
    else
        json::string "${EXPLAIN_NUMA_COUNT:-unknown}"
    fi
    printf ',\n'
    printf '    "cpu_model": '
    json::string "${EXPLAIN_CPU_MODEL:-unknown}"
    printf ',\n'
    printf '    "tidelet_home_path": '
    json::string "$TIDELET_HOME_PATH"
    printf ',\n'
    printf '    "engine_home_path": '
    json::string "$ENGINE_HOME_PATH"
    printf ',\n'
    printf '    "purge_link_target_data": %s,\n' "$PURGE_LINK_TARGET_DATA"
    printf '    "remove_tidemq_offset": %s,\n' "$REMOVE_TIDEMQ_OFFSET"
    printf '    "repair_missing_path_metadata": %s\n' "$REPAIR_MISSING_PATH_METADATA"
    printf '  },\n'
    printf '  "mounts": {\n'
    printf '    "hdd": '
    json::print_string_array "${HDD_DIRS[@]}"
    printf ',\n'
    printf '    "ssd": '
    json::print_string_array "${SSD_DIRS[@]}"
    printf ',\n'
    printf '    "disallowed": '
    json::print_string_array "${DISALLOWED_DIRS_ARR[@]}"
    printf '\n'
    printf '  },\n'
    printf '  "numa_memory_validation": '
    json::print_string_array "${EXPLAIN_NUMA_MEMORY_LINES[@]}"
    printf ',\n'
    printf '  "disk_health_validation": '
    json::print_disk_health_array "${EXPLAIN_DISK_HEALTH_LINES[@]}"
    printf ',\n'
    printf '  "discovered_resources": '
    json::print_string_array "${EXPLAIN_RESOURCE_LINES[@]}"
    printf ',\n'
    printf '  "layout_overview": '
    json::print_layout_overview
    printf ',\n'
    printf '  "planned_layout": '
    json::print_string_array "${EXPLAIN_LAYOUT_LINES[@]}"
    printf ',\n'
    printf '  "metadata_repair_plan": '
    json::print_string_array "${EXPLAIN_METADATA_REPAIR_LINES[@]}"
    printf ',\n'
    printf '  "planned_mutations": '
    json::print_planned_mutations
    printf '\n'
    printf '}\n'
}

function init::usage() {
    local script_name=''

    script_name=$(basename "$0")
    cat <<EOF
Usage:
  ${script_name} [-n] [-p] [-o] [-m] [-l PATH] [-t PATH] [-x DIRS]
  ${script_name} [--dry-run] [--purge-link-target-data] [--remove-tidemq-offset] [--repair-missing-path-metadata] [--tidelet-home PATH] [--tide-home PATH] [--disallowed-dirs DIRS]

Options:
  -n, --dry-run              Print planned operations without changing files, links, or data
  -p, --purge-link-target-data
                             Remove data inside the current symlink target before removing the symlink
  -o, --remove-tidemq-offset Remove node*/tidemq_offset directories during cleanup
  -m, --repair-missing-path-metadata
                             Scan every block_records.path and delete rows whose \$path/metadata is missing
  -l, --tidelet-home PATH    Override TIDELET_HOME
  -t, --tide-home PATH       Override TIDE_HOME
  -x, --disallowed-dirs DIRS Colon-separated mount paths to skip
  -h, --help                 Show this help message
EOF
}

function init::require_option_value() {
    local option=$1

    if (( $# < 2 )) || [[ -z "${2:-}" ]]; then
        log::error "${option} requires a value"
        return 1
    fi

    return 0
}

function init::parse_args() {
    while (( $# > 0 )); do
        case "$1" in
        -n|--dry-run)
            DRY_RUN=1
            shift
            ;;
        -p|--purge-link-target-data)
            PURGE_LINK_TARGET_DATA=1
            shift
            ;;
        -o|--remove-tidemq-offset)
            REMOVE_TIDEMQ_OFFSET=1
            shift
            ;;
        -m|--repair-missing-path-metadata)
            REPAIR_MISSING_PATH_METADATA=1
            shift
            ;;
        -l|--tidelet-home)
            if ! init::require_option_value "$1" "$@"; then
                return 1
            fi
            TIDELET_HOME_PATH=$2
            shift 2
            ;;
        -t|--tide-home)
            if ! init::require_option_value "$1" "$@"; then
                return 1
            fi
            ENGINE_HOME_PATH=$2
            shift 2
            ;;
        -x|--disallowed-dirs)
            if ! init::require_option_value "$1" "$@"; then
                return 1
            fi
            DISALLOWED_DIRS=$2
            shift 2
            ;;
        --disallowed-dirs=*)
            DISALLOWED_DIRS=${1#*=}
            shift
            ;;
        --tidelet-home=*)
            TIDELET_HOME_PATH=${1#*=}
            shift
            ;;
        --tide-home=*)
            ENGINE_HOME_PATH=${1#*=}
            shift
            ;;
        -h|--help)
            init::usage
            exit 0
            ;;
        *)
            log::error "Unexpected argument: $1"
            return 1
            ;;
        esac
    done
}

function init::set_paths() {
    if [[ -z "$TIDELET_HOME_PATH" ]]; then
        TIDELET_HOME_PATH=${TIDELET_HOME:-$DEFAULT_TIDELET_HOME}
    fi

    if [[ -z "$ENGINE_HOME_PATH" ]]; then
        ENGINE_HOME_PATH=${TIDE_HOME:-$DEFAULT_ENGINE_HOME}
    fi
}

function init::load_disallowed_dirs() {
    DISALLOWED_DIRS_ARR=()

    if [[ -n "${DISALLOWED_DIRS:-}" ]]; then
        IFS=':' read -r -a DISALLOWED_DIRS_ARR <<< "$DISALLOWED_DIRS"
    fi
}

function init::detect_numa_count() {
    local numa_count=1

    if command -v numactl >/dev/null 2>&1; then
        numa_count=$(numactl --hardware | awk '/node [0-9]+ cpus:/ {count++} END {print count + 0}')
        if (( numa_count == 0 )); then
            numa_count=1
        fi
    fi

    printf '%s\n' "$numa_count"
}

function system::get_cpu_model() {
    local cpu_model=''

    if command -v lscpu >/dev/null 2>&1; then
        cpu_model=$(lscpu | awk -F ':' '/Model name:/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}')
    fi

    if [[ -z "$cpu_model" && -r /proc/cpuinfo ]]; then
        cpu_model=$(awk -F ':' '/model name/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}' /proc/cpuinfo)
    fi

    if [[ -z "$cpu_model" ]]; then
        cpu_model='unknown'
    fi

    printf '%s\n' "$cpu_model"
}

function system::sanitize_line() {
    local value=$1

    value=${value//$'\t'/ }
    value=${value//$'\r'/}
    while [[ "$value" == *"  "* ]]; do
        value=${value//  / }
    done
    printf '%s\n' "$value"
}

function storage::is_disallowed_dir() {
    local dir=$1
    local disallowed=''

    for disallowed in "${DISALLOWED_DIRS_ARR[@]}"; do
        if [[ "$dir" == "$disallowed" ]]; then
            return 0
        fi
    done

    return 1
}

function storage::sort_mounts() {
    if (( $# == 0 )); then
        return 0
    fi

    printf '%s\n' "$@" | sort -V
}

function storage::get_mount_source() {
    local path=$1
    local source=''

    if command -v findmnt >/dev/null 2>&1; then
        source=$(findmnt -n -o SOURCE --target "$path" 2>/dev/null | head -n 1)
    fi

    if [[ -z "$source" ]]; then
        source=$(df -P "$path" | awk 'NR==2 {print $1}')
    fi

    printf '%s\n' "$source"
}

function storage::get_block_device_name() {
    local source=$1
    local device_name=''

    if [[ -z "$source" ]]; then
        return 1
    fi

    if command -v lsblk >/dev/null 2>&1; then
        device_name=$(lsblk -ndo PKNAME "$source" 2>/dev/null | head -n 1)
        if [[ -z "$device_name" ]]; then
            device_name=$(lsblk -ndo NAME "$source" 2>/dev/null | head -n 1)
        fi
    fi

    if [[ -z "$device_name" ]]; then
        device_name=${source##*/}
        if [[ "$device_name" =~ ^(nvme[0-9]+n[0-9]+)p[0-9]+$ ]]; then
            device_name=${BASH_REMATCH[1]}
        elif [[ "$device_name" =~ ^(sd[a-z]+)[0-9]+$ ]]; then
            device_name=${BASH_REMATCH[1]}
        elif [[ "$device_name" =~ ^(vd[a-z]+)[0-9]+$ ]]; then
            device_name=${BASH_REMATCH[1]}
        elif [[ "$device_name" =~ ^(xvd[a-z]+)[0-9]+$ ]]; then
            device_name=${BASH_REMATCH[1]}
        fi
    fi

    printf '%s\n' "$device_name"
}

function storage::get_device_path() {
    local source=$1

    if [[ -z "$source" ]]; then
        return 1
    fi

    if [[ "$source" == /dev/* ]]; then
        printf '%s\n' "$source"
        return 0
    fi

    printf '/dev/%s\n' "${source##*/}"
}

function storage::is_ssd_mount() {
    local path=$1
    local source=''
    local block_device=''
    local rotational=''

    source=$(storage::get_mount_source "$path")
    block_device=$(storage::get_block_device_name "$source")

    if [[ -z "$block_device" ]]; then
        return 1
    fi

    if [[ "$block_device" == nvme* ]]; then
        return 0
    fi

    if [[ -r "/sys/block/$block_device/queue/rotational" ]]; then
        rotational=$(cat "/sys/block/$block_device/queue/rotational")
        [[ "$rotational" == "0" ]]
        return
    fi

    return 1
}

function storage::discover_mounts() {
    local root_device=''
    local dir=''
    local device=''
    local sorted_mounts=()

    HDD_DIRS=()
    SSD_DIRS=()
    root_device=$(stat -c %d /)

    shopt -s nullglob
    for dir in /data*; do
        if ! [[ "$dir" =~ ^/data[0-9]+$ ]]; then
            continue
        fi

        if storage::is_disallowed_dir "$dir"; then
            if (( DRY_RUN == 0 )); then
                log::warn "Skip disallowed mount: $dir"
            fi
            continue
        fi

        device=$(stat -c %d "$dir")
        if [[ "$device" == "$root_device" ]]; then
            if (( DRY_RUN == 0 )); then
                log::warn "Skip root filesystem mount: $dir"
            fi
            continue
        fi

        if storage::is_ssd_mount "$dir"; then
            SSD_DIRS+=("$dir")
        else
            HDD_DIRS+=("$dir")
        fi
    done
    shopt -u nullglob

    if (( ${#HDD_DIRS[@]} > 0 )); then
        sorted_mounts=()
        while IFS= read -r dir; do
            sorted_mounts+=("$dir")
        done < <(storage::sort_mounts "${HDD_DIRS[@]}")
        HDD_DIRS=("${sorted_mounts[@]}")
    fi

    if (( ${#SSD_DIRS[@]} > 0 )); then
        sorted_mounts=()
        while IFS= read -r dir; do
            sorted_mounts+=("$dir")
        done < <(storage::sort_mounts "${SSD_DIRS[@]}")
        SSD_DIRS=("${sorted_mounts[@]}")
    fi

    explain::record_resource "HDD mounts (${#HDD_DIRS[@]}): ${HDD_DIRS[*]:-<none>}"
    explain::record_resource "SSD mounts (${#SSD_DIRS[@]}): ${SSD_DIRS[*]:-<none>}"
    if (( DRY_RUN == 0 )); then
        log::info "Sorted HDD mounts: ${HDD_DIRS[*]:-<none>}"
        log::info "Sorted SSD mounts: ${SSD_DIRS[*]:-<none>}"
    fi
}

function validate::ensure_even_split() {
    local resource_name=$1
    local count=$2
    local numa_count=$3

    if (( count == 0 )); then
        return 0
    fi

    if (( count % numa_count != 0 )); then
        log::error "$resource_name count ($count) cannot be evenly split across $numa_count NUMA nodes."
        log::error "This machine does not satisfy the required disk layout rule. Please check the hardware or mount configuration."
        return 1
    fi
}

function validate::check_numa_memory_limit() {
    local cpu_model=''
    local node_index=''
    local size_mb=''

    if ! command -v numactl >/dev/null 2>&1; then
        explain::record_numa_memory "numactl unavailable: skip NUMA memory validation"
        if (( DRY_RUN == 0 )); then
            log::warn "Skip NUMA memory validation because numactl is not available."
        fi
        return 0
    fi

    while read -r node_index size_mb; do
        if [[ -z "$node_index" || -z "$size_mb" ]]; then
            continue
        fi

        explain::record_numa_memory "node ${node_index}: ${size_mb} MB"
        if (( DRY_RUN == 0 )); then
            log::info "NUMA node ${node_index} memory: ${size_mb} MB"
        fi

        if (( size_mb > NUMA_MEMORY_LIMIT_MB )); then
            cpu_model=$(system::get_cpu_model)
            EXPLAIN_CPU_MODEL=$cpu_model
            explain::record_numa_memory "FAILED: node ${node_index} exceeds 512G"
            log::error "NUMA node ${node_index} memory (${size_mb} MB) exceeds 512G."
            log::error "This usually means the NUMA configuration is incorrect."
            log::error "CPU model: ${cpu_model}"
            log::error "Please fix NUMA configuration by following: ${NUMA_FIX_DOC_URL}"
            return 1
        fi
    done < <(numactl --hardware | awk '/node [0-9]+ size:/ {print $2, $4}')

    explain::record_numa_memory "PASSED: all NUMA nodes are within the 512G limit"
    return 0
}

function validate::fail_disk_health() {
    local mount_path=$1
    local source=$2
    local block_device=$3
    local reason=$4

    explain::record_disk_health "FAILED|${mount_path}|unknown|${block_device:-unknown}"
    log::error "Disk health check failed for mount ${mount_path}."
    log::error "Mount source: ${source:-unknown}, block device: ${block_device:-unknown}"
    log::error "Reason: ${reason}"
    log::error "Please repair the disk in Bytebox: ${DISK_REPAIR_URL}"
    return 1
}

function validate::record_disk_health_status() {
    local status=$1
    local mount_path=$2
    local disk_kind=$3
    local block_device=$4

    explain::record_disk_health "${status}|${mount_path}|${disk_kind}|${block_device:-unknown}"
}

function validate::print_disk_health_detail() {
    local mount_path=$1
    local disk_kind=$2
    shift 2
    local rows=("$@")

    if (( DRY_RUN == 1 )); then
        return 0
    fi

    log::info "Disk health detail: ${mount_path} (${disk_kind})"
    explain::print_command_table "Disk: ${mount_path} (${disk_kind})" "${rows[@]}"
}

function validate::extract_smart_detail_lines() {
    local smart_output=$1
    local matched_lines=''

    matched_lines=$(printf '%s\n' "$smart_output" | grep -E 'Reallocated_Sector_Ct|Current_Pending_Sector|Offline_Uncorrectable|Power_On_Hours|Critical Warning|Percentage Used|Media and Data Integrity Errors|Available Spare|SMART overall-health' 2>/dev/null || true)
    if [[ -z "$matched_lines" ]]; then
        printf '%s\n' "details=none"
        return 0
    fi

    matched_lines=$(printf '%s\n' "$matched_lines" | awk '{$1=$1; print}' | paste -sd ';' -)
    system::sanitize_line "details=${matched_lines}"
}

function validate::extract_nvme_detail_summary() {
    local nvme_output=$1
    local critical_warning=''
    local media_errors=''
    local percentage_used=''
    local available_spare=''

    critical_warning=$(printf '%s\n' "$nvme_output" | awk -F ':' '/critical_warning/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}')
    media_errors=$(printf '%s\n' "$nvme_output" | awk -F ':' '/media_errors/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}')
    percentage_used=$(printf '%s\n' "$nvme_output" | awk -F ':' '/percentage_used/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}')
    available_spare=$(printf '%s\n' "$nvme_output" | awk -F ':' '/available_spare/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}')

    system::sanitize_line "details=critical_warning=${critical_warning:-unknown};media_errors=${media_errors:-unknown};percentage_used=${percentage_used:-unknown};available_spare=${available_spare:-unknown}"
}

function validate::check_duplicate_block_devices() {
    local mount_path=$1
    local source=$2
    local block_device=$3
    local idx=0
    local existing_mount=''

    for idx in "${!SEEN_BLOCK_DEVICES[@]}"; do
        existing_mount=${SEEN_BLOCK_DEVICE_MOUNTS[$idx]}
        if [[ "${SEEN_BLOCK_DEVICES[$idx]}" == "$block_device" ]]; then
            validate::fail_disk_health "$mount_path" "$source" "$block_device" "shares the same underlying disk with ${existing_mount}" || return 1
        fi
    done

    SEEN_BLOCK_DEVICES+=("$block_device")
    SEEN_BLOCK_DEVICE_MOUNTS+=("$mount_path")
}

function validate::check_dmesg_health() {
    local mount_path=$1
    local source=$2
    local block_device=$3
    local dmesg_output=''
    local dmesg_lines=''
    local sanitized=''

    if ! command -v dmesg >/dev/null 2>&1; then
        printf '%s\n' "SKIPPED: dmesg unavailable"
        return 0
    fi

    dmesg_output=$(dmesg 2>&1 || true)
    if [[ "$dmesg_output" =~ [Rr]ead\ kernel\ buffer\ failed|[Oo]peration\ not\ permitted|[Pp]ermission\ denied ]]; then
        printf '%s\n' "UNKNOWN: dmesg inaccessible"
        return 0
    fi

    dmesg_lines=$(printf '%s\n' "$dmesg_output" | grep -Ei "(${block_device}|${source##*/}).*(I/O error|Buffer I/O error|blk_update_request|critical medium error|medium error|end_request)" | tail -n 3 || true)
    if [[ -n "$dmesg_lines" ]]; then
        sanitized=$(printf '%s\n' "$dmesg_lines" | tr '\n' ';' | sed 's/;*$//')
        sanitized=$(system::sanitize_line "$sanitized")
        validate::fail_disk_health "$mount_path" "$source" "$block_device" "recent kernel I/O errors detected: ${sanitized}" || return 1
    fi

    printf '%s\n' "PASSED: no recent kernel I/O errors"
}

function validate::check_disk_health() {
    local mount_path=$1
    local disk_kind=$2
    local source=''
    local block_device=''
    local device_path=''
    local read_only=''
    local kernel_state=''
    local health_status='PASSED'
    local health_summary='health telemetry passed'
    local smart_output=''
    local smart_details=''
    local nvme_output=''
    local nvme_details=''
    local critical_warning=''
    local dmesg_status=''
    local readonly_status='PASSED'
    local readonly_detail=''
    local kernel_status='PASSED'
    local kernel_detail=''
    local telemetry_status='PASSED'
    local telemetry_command='smartctl'
    local telemetry_detail=''
    local dmesg_check_status='PASSED'
    local dmesg_detail=''
    local detail_rows=()

    source=$(storage::get_mount_source "$mount_path")
    block_device=$(storage::get_block_device_name "$source")

    if [[ -z "$source" || -z "$block_device" ]]; then
        validate::fail_disk_health "$mount_path" "$source" "$block_device" "cannot resolve mount source or block device" || return 1
    fi

    validate::check_duplicate_block_devices "$mount_path" "$source" "$block_device" || return 1
    device_path=$(storage::get_device_path "$block_device")

    if command -v lsblk >/dev/null 2>&1; then
        read_only=$(lsblk -ndo RO "$device_path" 2>/dev/null | head -n 1)
        if [[ "$read_only" == "1" ]]; then
            validate::fail_disk_health "$mount_path" "$source" "$block_device" "device is read-only" || return 1
        fi
        readonly_detail="ro=${read_only:-unknown}"
    else
        read_only='unknown'
        readonly_status='UNKNOWN'
        readonly_detail='lsblk unavailable'
        health_status='UNKNOWN'
    fi

    if [[ -r "/sys/block/$block_device/device/state" ]]; then
        kernel_state=$(cat "/sys/block/$block_device/device/state")
        kernel_detail="state=${kernel_state}"
        case "$kernel_state" in
        running | live | active | unknown) ;;
        *)
            validate::fail_disk_health "$mount_path" "$source" "$block_device" "kernel device state is ${kernel_state}" || return 1
            ;;
        esac
    else
        kernel_state='unknown'
        kernel_status='UNKNOWN'
        kernel_detail='kernel state file unavailable'
        health_status='UNKNOWN'
    fi

    if command -v smartctl >/dev/null 2>&1; then
        smart_output=$(smartctl -H -A "$device_path" 2>/dev/null || true)
        smart_details=$(validate::extract_smart_detail_lines "$smart_output")
        if [[ "$smart_output" =~ PASSED|OK ]]; then
            health_summary="SMART: PASSED ${smart_details}"
            telemetry_status='PASSED'
            telemetry_command='smartctl'
            telemetry_detail=$smart_details
        elif [[ "$smart_output" =~ FAILED|BAD ]]; then
            validate::fail_disk_health "$mount_path" "$source" "$block_device" "SMART overall health check failed" || return 1
        else
            health_status='UNKNOWN'
            health_summary="SMART: UNKNOWN ${smart_details}"
            telemetry_status='UNKNOWN'
            telemetry_command='smartctl'
            telemetry_detail=$smart_details
        fi
    elif [[ "$block_device" == nvme* ]] && command -v nvme >/dev/null 2>&1; then
        telemetry_command='nvme smart-log'
        nvme_output=$(nvme smart-log "$device_path" 2>/dev/null || true)
        nvme_details=$(validate::extract_nvme_detail_summary "$nvme_output")
        critical_warning=$(printf '%s\n' "$nvme_output" | awk -F ':' '/critical_warning/ {gsub(/^[[:space:]]+/, "", $2); print $2; exit}')
        if [[ -n "$critical_warning" ]]; then
            if [[ "$critical_warning" == "0x0" || "$critical_warning" == "0" ]]; then
                health_summary="NVMe: PASSED ${nvme_details}"
                telemetry_status='PASSED'
                telemetry_detail=$nvme_details
            else
                validate::fail_disk_health "$mount_path" "$source" "$block_device" "nvme critical_warning=${critical_warning}" || return 1
            fi
        else
            health_status='UNKNOWN'
            health_summary="NVMe: UNKNOWN ${nvme_details}"
            telemetry_status='UNKNOWN'
            telemetry_detail=$nvme_details
        fi
    else
        health_status='SKIPPED'
        health_summary='SMART/NVMe telemetry skipped'
        telemetry_status='SKIPPED'
        telemetry_command='smartctl/nvme'
        telemetry_detail='SMART/NVMe telemetry skipped'
    fi

    dmesg_status=$(validate::check_dmesg_health "$mount_path" "$source" "$block_device") || return 1
    dmesg_check_status=${dmesg_status%%:*}
    dmesg_detail=${dmesg_status#*: }
    health_summary=$(system::sanitize_line "source=${source} device=${block_device} ro=${read_only:-unknown} state=${kernel_state:-unknown} ${health_summary} ${dmesg_status}")

    detail_rows=(
        "resolve|PASSED|source=${source} device=${block_device}"
        "lsblk ro|${readonly_status}|${readonly_detail}"
        "kernel state|${kernel_status}|${kernel_detail}"
        "${telemetry_command}|${telemetry_status}|${telemetry_detail}"
        "dmesg|${dmesg_check_status}|${dmesg_detail}"
    )
    validate::print_disk_health_detail "$mount_path" "$disk_kind" "${detail_rows[@]}"

    if [[ "$health_status" == "PASSED" ]]; then
        validate::record_disk_health_status "PASSED" "$mount_path" "$disk_kind" "$block_device"
        if (( DRY_RUN == 0 )); then
            log::info "Disk health passed for ${mount_path}: ${health_summary}"
        fi
    elif [[ "$health_status" == "UNKNOWN" ]]; then
        validate::record_disk_health_status "UNKNOWN" "$mount_path" "$disk_kind" "$block_device"
        if (( DRY_RUN == 0 )); then
            log::warn "Disk health is partially unknown for ${mount_path}: ${health_summary}"
        fi
    else
        validate::record_disk_health_status "SKIPPED" "$mount_path" "$disk_kind" "$block_device"
        if (( DRY_RUN == 0 )); then
            log::warn "Disk health telemetry skipped for ${mount_path}: ${health_summary}"
        fi
    fi
}

function validate::check_all_disk_health() {
    local mount_path=''

    SEEN_BLOCK_DEVICES=()
    SEEN_BLOCK_DEVICE_MOUNTS=()

    for mount_path in "${HDD_DIRS[@]}"; do
        validate::check_disk_health "$mount_path" "HDD" || return 1
    done

    for mount_path in "${SSD_DIRS[@]}"; do
        validate::check_disk_health "$mount_path" "SSD" || return 1
    done

    if (( ${#HDD_DIRS[@]} == 0 && ${#SSD_DIRS[@]} == 0 )); then
        explain::record_disk_health "SKIPPED|<none>|unknown|no discovered disks"
    fi
}

function validate::check_missing_resources() {
    local numa_count=$1

    validate::check_numa_memory_limit || return 1

    if (( ${#HDD_DIRS[@]} == 0 )) && (( ${#SSD_DIRS[@]} == 0 )); then
        explain::record_resource "FAILED: no usable /dataXX mounts discovered"
        log::error "No usable /dataXX mounts were discovered."
        log::error "Initialization cannot continue without storage resources."
        return 1
    fi

    if (( ${#SSD_DIRS[@]} == 0 )); then
        explain::record_resource "SSD missing: HDD mounts will also back metadata and ssd_disks"
        if (( DRY_RUN == 0 )); then
            log::warn "No SSD mounts found. HDD mounts will be reused for metadata and ssd_disks."
        fi
    fi

    validate::check_all_disk_health || return 1
    validate::ensure_even_split "HDD" "${#HDD_DIRS[@]}" "$numa_count" || return 1
    validate::ensure_even_split "SSD" "${#SSD_DIRS[@]}" "$numa_count" || return 1
}

function filesystem::ensure_dir() {
    local path=$1

    if [[ -d "$path" ]]; then
        return 0
    fi

    explain::record_action "ensure directory: $path"
    if (( DRY_RUN == 1 )); then
        return 0
    fi

    mkdir -p "$path"
    log::info "Ensure directory: $path"
}

function cleanup::ensure_safe_purge_path() {
    local path=$1

    if [[ "$path" =~ ^/data[0-9]+/fringedb(/.*)?$ ]]; then
        return 0
    fi

    log::error "Refuse to purge unexpected path: $path"
    return 1
}

function cleanup::remove_directory_contents() {
    local dir_path=$1
    local entries=()
    local entry=''

    shopt -s nullglob dotglob
    entries=("$dir_path"/*)
    shopt -u nullglob dotglob

    for entry in "${entries[@]}"; do
        rm -rf -- "$entry"
    done
}

function cleanup::get_mount_root() {
    local path=$1

    if [[ "$path" =~ ^(/data[0-9]+)(/.*)?$ ]]; then
        printf '%s\n' "${BASH_REMATCH[1]}"
        return 0
    fi

    printf '%s\n' "__other__"
}

function cleanup::has_pending_purge_target() {
    local target=$1
    local existing=''

    for existing in "${PENDING_PURGE_TARGETS[@]}"; do
        [[ "$existing" == "$target" ]] && return 0
    done

    return 1
}

function cleanup::queue_symlink_target_purge() {
    local link_path=$1
    local resolved_target=''

    if (( PURGE_LINK_TARGET_DATA == 0 )); then
        return 0
    fi

    if ! [[ -L "$link_path" ]]; then
        return 0
    fi

    resolved_target=$(readlink "$link_path")
    if [[ -z "$resolved_target" || ! -e "$resolved_target" ]]; then
        return 0
    fi

    cleanup::ensure_safe_purge_path "$resolved_target" || return 1

    explain::record_action "purge symlink target data: $resolved_target"
    if (( DRY_RUN == 1 )); then
        return 0
    fi

    if cleanup::has_pending_purge_target "$resolved_target"; then
        return 0
    fi

    PENDING_PURGE_TARGETS+=("$resolved_target")
}

function cleanup::run_pending_purges_for_mount() {
    local mount_root=$1
    local target=''

    for target in "${PENDING_PURGE_TARGETS[@]}"; do
        [[ "$(cleanup::get_mount_root "$target")" == "$mount_root" ]] || continue
        log::warn "Purge data in symlink target: $target"
        cleanup::remove_directory_contents "$target" || return 1
    done
}

function cleanup::run_pending_purges() {
    local unique_mount_roots=()
    local target=''
    local mount_root=''
    local existing=''
    local pid=0
    local pids=()
    local failed=0

    if (( PURGE_LINK_TARGET_DATA == 0 || ${#PENDING_PURGE_TARGETS[@]} == 0 )); then
        return 0
    fi

    for target in "${PENDING_PURGE_TARGETS[@]}"; do
        mount_root=$(cleanup::get_mount_root "$target")

        for existing in "${unique_mount_roots[@]}"; do
            if [[ "$existing" == "$mount_root" ]]; then
                mount_root=''
                break
            fi
        done

        [[ -n "$mount_root" ]] && unique_mount_roots+=("$mount_root")
    done

    for mount_root in "${unique_mount_roots[@]}"; do
        cleanup::run_pending_purges_for_mount "$mount_root" &
        pids+=("$!")
    done

    for pid in "${pids[@]}"; do
        wait "$pid" || failed=1
    done

    PENDING_PURGE_TARGETS=()
    (( failed == 0 ))
}

function filesystem::remove_path_if_present() {
    local path=$1

    if [[ -e "$path" || -L "$path" ]]; then
        cleanup::queue_symlink_target_purge "$path" || return 1

        explain::record_action "remove path: $path"
        if (( DRY_RUN == 1 )); then
            return 0
        fi

        rm -rf "$path"
        log::info "Remove path: $path"
    fi
}

function metadata::escape_sql_literal() {
    local value=$1

    printf "%s\n" "${value//\'/\'\'}"
}

function metadata::file_size_bytes() {
    local file_path=$1

    if [[ ! -f "$file_path" ]]; then
        printf '0\n'
        return 0
    fi

    wc -c < "$file_path" | tr -d '[:space:]'
}

function metadata::format_size_bytes() {
    local size_bytes=$1
    local kib=1024
    local mib=$((1024 * 1024))
    local gib=$((1024 * 1024 * 1024))

    if (( size_bytes >= gib )); then
        awk -v size="$size_bytes" -v unit="$gib" 'BEGIN { printf "%.2f GiB", size / unit }'
        return 0
    fi

    if (( size_bytes >= mib )); then
        awk -v size="$size_bytes" -v unit="$mib" 'BEGIN { printf "%.2f MiB", size / unit }'
        return 0
    fi

    if (( size_bytes >= kib )); then
        awk -v size="$size_bytes" -v unit="$kib" 'BEGIN { printf "%.2f KiB", size / unit }'
        return 0
    fi

    printf '%s B' "$size_bytes"
}

function metadata::list_metadata_dbs() {
    local metadata_db=''

    shopt -s nullglob
    for metadata_db in "$ENGINE_HOME_PATH"/node*/metadata/fringedb.db; do
        [[ -f "$metadata_db" ]] || continue
        printf '%s\n' "$metadata_db"
    done
    shopt -u nullglob
}

function metadata::ensure_sqlite3() {
    if command -v sqlite3 >/dev/null 2>&1; then
        return 0
    fi

    if ! command -v apt-get >/dev/null 2>&1; then
        log::error "sqlite3 is missing and apt-get is unavailable."
        return 1
    fi

    if (( EUID != 0 )); then
        log::error "sqlite3 is missing; rerun as root so the script can install it with apt-get."
        return 1
    fi

    log::warn "sqlite3 not found, install it with apt-get."
    DEBIAN_FRONTEND=noninteractive apt-get update || return 1
    DEBIAN_FRONTEND=noninteractive apt-get install -y sqlite3 || return 1

    if ! command -v sqlite3 >/dev/null 2>&1; then
        log::error "sqlite3 installation finished but sqlite3 is still unavailable."
        return 1
    fi

    log::success "sqlite3 is ready: $(command -v sqlite3)"
}

function metadata::check_sqlite_requirements() {
    local metadata_db=$1
    local operation_name=$2

    if ! metadata::ensure_sqlite3; then
        log::error "sqlite3 is required to ${operation_name}."
        return 1
    fi

    if [[ ! -f "$metadata_db" ]]; then
        log::warn "Metadata DB not found, skip ${operation_name}: $metadata_db"
        return 1
    fi

    if ! sqlite3 "$metadata_db" "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'block_records';" | grep -q 1; then
        log::warn "block_records table not found, skip ${operation_name}: $metadata_db"
        return 1
    fi

    return 0
}

function metadata::has_repair_target() {
    local target=$1
    local existing=''

    for existing in "${METADATA_REPAIR_TARGETS[@]}"; do
        [[ "$existing" == "$target" ]] && return 0
    done

    return 1
}

function metadata::link_repair_reason() {
    local target=$1
    local source=$2
    local current_source=''

    if [[ ! -e "$target" && ! -L "$target" ]]; then
        printf '%s\n' "missing link"
        return 0
    fi

    if [[ ! -L "$target" ]]; then
        printf '%s\n' "not symlink"
        return 0
    fi

    current_source=$(readlink "$target")
    if [[ -z "$current_source" ]]; then
        printf '%s\n' "unreadable symlink"
        return 0
    fi

    if [[ ! -e "$target" ]]; then
        printf '%s\n' "broken symlink -> ${current_source}"
        return 0
    fi

    if [[ "$current_source" != "$source" ]]; then
        printf '%s\n' "target mismatch: ${current_source} -> ${source}"
        return 0
    fi

    return 1
}

function metadata::queue_repair_for_link() {
    local target=$1
    local source=$2
    local node_index=''
    local link_name=''
    local repair_target=''
    local metadata_db=''
    local reason=''

    if ! [[ "$target" =~ ^${ENGINE_HOME_PATH}/node([0-9]+)/(disk[0-9][0-9]|ssd_disks_[0-9][0-9])$ ]]; then
        return 0
    fi

    node_index=${BASH_REMATCH[1]}
    link_name=${BASH_REMATCH[2]}
    if [[ "$link_name" == "disk00" ]]; then
        return 0
    fi

    reason=$(metadata::link_repair_reason "$target" "$source") || return 0

    repair_target="${node_index}|${link_name}|${target}|${source}|${reason}"
    if metadata::has_repair_target "$repair_target"; then
        return 0
    fi

    METADATA_REPAIR_TARGETS+=("$repair_target")
    metadata_db="${ENGINE_HOME_PATH}/node${node_index}/metadata/fringedb.db"
    explain::record_metadata_repair "${reason}: scan ${metadata_db} for ${target}/, delete missing block_records.path, then VACUUM"
}

function metadata::vacuum_db() {
    local metadata_db=$1
    local operation_name=$2
    local before_size=0
    local after_size=0
    local delta_size=0
    local delta_sign=''

    before_size=$(metadata::file_size_bytes "$metadata_db")
    log::info "Run VACUUM for ${operation_name}: ${metadata_db}"
    if ! sqlite3 "$metadata_db" "VACUUM;"; then
        log::error "Failed to VACUUM metadata DB: $metadata_db"
        return 1
    fi

    after_size=$(metadata::file_size_bytes "$metadata_db")
    delta_size=$((after_size - before_size))
    if (( delta_size > 0 )); then
        delta_sign='+'
    fi

    log::success "VACUUM completed for ${metadata_db}: $(metadata::format_size_bytes "$before_size") -> $(metadata::format_size_bytes "$after_size") (${delta_sign}${delta_size} B)"
}

function metadata::repair_link_records() {
    local node_index=$1
    local link_name=$2
    local target=$3
    local source=$4
    local metadata_db="${ENGINE_HOME_PATH}/node${node_index}/metadata/fringedb.db"
    local like_prefix=''
    local escaped_prefix=''
    local escaped_path=''
    local path=''
    local paths=''
    local checked=0
    local deleted=0

    if (( DRY_RUN == 1 )); then
        return 0
    fi

    if ! metadata::check_sqlite_requirements "$metadata_db" "repair metadata records for node${node_index}/${link_name}"; then
        return 0
    fi

    like_prefix="%${target}/%"
    escaped_prefix=$(metadata::escape_sql_literal "$like_prefix")
    log::info "Repair metadata records for node${node_index}/${link_name}: db=${metadata_db}, source=${source}"

    if ! paths=$(sqlite3 "$metadata_db" "SELECT path FROM block_records WHERE path LIKE '${escaped_prefix}';"); then
        log::error "Failed to query block_records in metadata DB: $metadata_db"
        return 1
    fi

    while IFS= read -r path || [[ -n "$path" ]]; do
        [[ -n "$path" ]] || continue
        checked=$((checked + 1))
        if [[ -e "$path" ]]; then
            continue
        fi

        escaped_path=$(metadata::escape_sql_literal "$path")
        sqlite3 "$metadata_db" "DELETE FROM block_records WHERE path = '${escaped_path}';"
        deleted=$((deleted + 1))
        log::warn "Deleted stale block_records item: $path"
    done <<< "$paths"

    metadata::vacuum_db "$metadata_db" "node${node_index}/${link_name} metadata repair" || return 1

    log::success "Metadata repair completed for node${node_index}/${link_name}: checked=${checked}, deleted=${deleted}"
}

function metadata::repair_missing_path_metadata_db() {
    local metadata_db=$1
    local escaped_path=''
    local metadata_path=''
    local path=''
    local paths=''
    local checked=0
    local deleted=0

    if (( DRY_RUN == 1 )); then
        return 0
    fi

    if ! metadata::check_sqlite_requirements "$metadata_db" "scan block_records.path metadata entries"; then
        return 0
    fi

    log::info "Scan metadata path entries in ${metadata_db}: delete rows whose path/metadata is missing"

    if ! paths=$(sqlite3 "$metadata_db" "SELECT path FROM block_records;"); then
        log::error "Failed to query block_records in metadata DB: $metadata_db"
        return 1
    fi

    while IFS= read -r path || [[ -n "$path" ]]; do
        [[ -n "$path" ]] || continue
        checked=$((checked + 1))
        metadata_path="${path%/}/metadata"
        if [[ -e "$metadata_path" ]]; then
            continue
        fi

        escaped_path=$(metadata::escape_sql_literal "$path")
        sqlite3 "$metadata_db" "DELETE FROM block_records WHERE path = '${escaped_path}';"
        deleted=$((deleted + 1))
        log::warn "Deleted block_records item with missing metadata path: ${path} (expected ${metadata_path})"
    done <<< "$paths"

    metadata::vacuum_db "$metadata_db" "missing path metadata scan" || return 1

    log::success "Metadata path scan completed for ${metadata_db}: checked=${checked}, deleted=${deleted}"
}

function metadata::repair_missing_path_metadata_all() {
    local metadata_db=''
    local found=0

    if (( REPAIR_MISSING_PATH_METADATA == 0 )); then
        return 0
    fi

    while IFS= read -r metadata_db || [[ -n "$metadata_db" ]]; do
        [[ -n "$metadata_db" ]] || continue
        found=1
        explain::record_metadata_repair "full scan ${metadata_db}: delete block_records rows whose path/metadata is missing, then VACUUM"
        metadata::repair_missing_path_metadata_db "$metadata_db" || return 1
    done < <(metadata::list_metadata_dbs)

    if (( found == 0 )); then
        explain::record_metadata_repair "full scan skipped: no node*/metadata/fringedb.db found under ${ENGINE_HOME_PATH}"
    fi

    return 0
}

function metadata::repair_all() {
    local target=''
    local node_index=''
    local link_name=''
    local link_path=''
    local source=''
    local reason=''

    if (( ${#METADATA_REPAIR_TARGETS[@]} > 0 )); then
        for target in "${METADATA_REPAIR_TARGETS[@]}"; do
            IFS='|' read -r node_index link_name link_path source reason <<< "$target"
            metadata::repair_link_records "$node_index" "$link_name" "$link_path" "$source" || return 1
        done
    fi

    metadata::repair_missing_path_metadata_all || return 1
}

function filesystem::replace_link() {
    local target=$1
    local source=$2
    local current_source=''
    local current_resolved=''
    local source_resolved=''

    if [[ -L "$target" ]]; then
        current_source=$(readlink "$target" 2>/dev/null || true)
        if [[ "$current_source" == "$source" && -e "$target" ]]; then
            return 0
        fi

        if command -v readlink >/dev/null 2>&1; then
            current_resolved=$(readlink -f "$target" 2>/dev/null || true)
            source_resolved=$(readlink -f "$source" 2>/dev/null || true)
            if [[ -n "$current_resolved" && "$current_resolved" == "$source_resolved" ]]; then
                return 0
            fi
        fi
    fi

    metadata::queue_repair_for_link "$target" "$source"
    filesystem::remove_path_if_present "$target" || return 1
    explain::record_action "link ${target} -> ${source}"

    if (( DRY_RUN == 1 )); then
        return 0
    fi

    ln -snf "$source" "$target"
    log::success "Link $target -> $source"
}

function cleanup::clear_node_layout() {
    local node_dir=$1

    # Expected layout links are reconciled later by filesystem::replace_link.
    # Do not remove them here, otherwise real runs turn every disk into a
    # "missing link" and expand metadata repair beyond the dry-run plan.
    cleanup::clear_unexpected_node_entries "$node_dir"
}

function cleanup::clear_unexpected_node_entries() {
    local node_dir=$1
    local path=''
    local name=''

    shopt -s nullglob dotglob
    for path in "$node_dir"/*; do
        [[ -e "$path" || -L "$path" ]] || continue
        name=${path##*/}

        if [[ "$name" == "tidemq_offset" && $REMOVE_TIDEMQ_OFFSET -eq 0 ]]; then
            explain::record_action "keep path: $path"
            continue
        fi

        if [[ "$name" =~ ^disk[0-9][0-9]$ || "$name" =~ ^ssd_disks_[0-9][0-9]$ || "$name" == "metadata" ]]; then
            continue
        fi

        filesystem::remove_path_if_present "$path"
    done
    shopt -u nullglob dotglob
}

function cleanup::clear_stale_numbered_links() {
    local node_dir=$1
    local prefix=$2
    local keep_count=$3
    local path=''
    local name=''
    local number=''

    shopt -s nullglob
    for path in "$node_dir"/"${prefix}"[0-9][0-9]; do
        [[ -e "$path" || -L "$path" ]] || continue
        name=${path##*/}
        number=${name#"$prefix"}

        if [[ "$prefix" == "disk" && "$number" == "00" ]]; then
            continue
        fi

        if (( 10#$number > keep_count )); then
            filesystem::remove_path_if_present "$path"
        fi
    done
    shopt -u nullglob
}

function cleanup::clear_extra_node_layouts() {
    local numa_count=$1
    local node_dir=''
    local node_name=''
    local node_index=''

    shopt -s nullglob
    for node_dir in "$ENGINE_HOME_PATH"/node*; do
        node_name=${node_dir##*/}
        node_index=${node_name#node}

        if ! [[ "$node_index" =~ ^[0-9]+$ ]]; then
            continue
        fi

        if (( node_index >= numa_count )); then
            if (( DRY_RUN == 0 )); then
                log::warn "Cleanup stale node layout: $node_dir"
            fi
            cleanup::clear_node_layout "$node_dir"
        fi
    done
    shopt -u nullglob
}

function init::prepare_directories() {
    local numa_count=$1
    local node_index=0

    PENDING_PURGE_TARGETS=()
    filesystem::ensure_dir "$TIDELET_HOME_PATH"
    filesystem::ensure_dir "$ENGINE_HOME_PATH"
    if (( DRY_RUN == 0 )); then
        filesystem::remove_path_if_present "$TIDELET_HOME_PATH/disks"
        filesystem::remove_path_if_present "$TIDELET_HOME_PATH/ssd_disks"
    fi

    for ((node_index = 0; node_index < numa_count; node_index++)); do
        filesystem::ensure_dir "$ENGINE_HOME_PATH/node${node_index}"
        cleanup::clear_node_layout "$ENGINE_HOME_PATH/node${node_index}"
    done

    cleanup::clear_extra_node_layouts "$numa_count"
    cleanup::run_pending_purges || return 1
}

function manifest::build_disk_names() {
    local prefix=$1
    local count=$2
    local index=1
    local result=''
    local name=''

    for ((index = 1; index <= count; index++)); do
        name=$(printf '%s%02d' "$prefix" "$index")
        if [[ -z "$result" ]]; then
            result=$name
        else
            result="${result},${name}"
        fi
    done

    printf '%s\n' "$result"
}

function manifest::write_file() {
    local path=$1
    local content=$2
    local current=''

    if [[ -f "$path" ]]; then
        current=$(<"$path")
        if [[ "$current" == "$content" ]]; then
            return 0
        fi
    fi

    explain::record_action "write file: ${path} = ${content}"
    if (( DRY_RUN == 1 )); then
        return 0
    fi

    printf '%s\n' "$content" > "$path"
    log::success "Write file: $path"
}

function filesystem::copy_file() {
    local source=$1
    local target=$2

    if [[ -f "$source" && -f "$target" ]] && cmp -s "$source" "$target"; then
        return 0
    fi

    explain::record_action "copy file: ${source} -> ${target}"
    if (( DRY_RUN == 1 )); then
        return 0
    fi

    cp "$source" "$target"
    log::success "Copy file: $source -> $target"
}

function manifest::truncate_file() {
    local path=$1

    if [[ -f "$path" && ! -s "$path" ]]; then
        return 0
    fi

    explain::record_action "truncate file: $path"
    if (( DRY_RUN == 1 )); then
        return 0
    fi

    : > "$path"
    log::success "Truncate file: $path"
}

function layout::configure_hdd_node() {
    local node_index=$1
    shift

    local mounts=("$@")
    local node_dir="$ENGINE_HOME_PATH/node${node_index}"
    local mount_path=''
    local metadata_dir=''
    local data_dir=''
    local target_path=''
    local disk_index=1

    if (( DRY_RUN == 0 )); then
        log::info "Node ${node_index} HDD mounts: ${mounts[*]:-<none>}"
    fi
    explain::record_layout "node${node_index} HDD mounts: ${mounts[*]:-<none>}"

    if (( ${#mounts[@]} > 0 )) && (( ${#SSD_DIRS[@]} == 0 )); then
        metadata_dir="${mounts[0]}/fringedb/node${node_index}"
        explain::record_layout "node${node_index} metadata from HDD: ${metadata_dir}"
        filesystem::ensure_dir "$metadata_dir"
        filesystem::replace_link "$node_dir/disk00" "$metadata_dir"
        filesystem::replace_link "$node_dir/metadata" "$metadata_dir"
    fi

    for mount_path in "${mounts[@]}"; do
        data_dir="${mount_path}/fringedb/data"
        target_path=$(printf '%s/node%d/disk%02d' "$ENGINE_HOME_PATH" "$node_index" "$disk_index")

        filesystem::ensure_dir "$data_dir"
        filesystem::replace_link "$target_path" "$data_dir"
        disk_index=$((disk_index + 1))
    done

    cleanup::clear_stale_numbered_links "$node_dir" "disk" "$((disk_index - 1))"
}

function layout::configure_ssd_node() {
    local node_index=$1
    shift

    local mounts=("$@")
    local node_dir="$ENGINE_HOME_PATH/node${node_index}"
    local mount_path=''
    local metadata_dir=''
    local data_dir=''
    local target_path=''
    local disk_index=1

    if (( DRY_RUN == 0 )); then
        log::info "Node ${node_index} SSD mounts: ${mounts[*]:-<none>}"
    fi
    explain::record_layout "node${node_index} SSD mounts: ${mounts[*]:-<none>}"

    if (( ${#mounts[@]} == 0 )); then
        return 0
    fi

    metadata_dir="${mounts[0]}/fringedb/node${node_index}"
    explain::record_layout "node${node_index} metadata from SSD: ${metadata_dir}"
    filesystem::ensure_dir "$metadata_dir"
    filesystem::replace_link "$node_dir/disk00" "$metadata_dir"
    filesystem::replace_link "$node_dir/metadata" "$metadata_dir"

    for mount_path in "${mounts[@]}"; do
        data_dir="${mount_path}/fringedb/data"
        target_path=$(printf '%s/node%d/ssd_disks_%02d' "$ENGINE_HOME_PATH" "$node_index" "$disk_index")

        filesystem::ensure_dir "$data_dir"
        filesystem::replace_link "$target_path" "$data_dir"
        disk_index=$((disk_index + 1))
    done

    cleanup::clear_stale_numbered_links "$node_dir" "ssd_disks_" "$((disk_index - 1))"
}

function layout::apply_hdd_layout() {
    local numa_count=$1
    local total_hdd=${#HDD_DIRS[@]}
    local hdd_per_node=0
    local node_index=0
    local start=0
    local mounts=()
    local disks_manifest=''

    if (( total_hdd == 0 )); then
        for ((node_index = 0; node_index < numa_count; node_index++)); do
            cleanup::clear_stale_numbered_links "$ENGINE_HOME_PATH/node${node_index}" "disk" 0
        done
        return 0
    fi

    hdd_per_node=$((total_hdd / numa_count))
    explain::record_layout "HDD per node: ${hdd_per_node}"
    if (( DRY_RUN == 0 )); then
        log::info "Create HDD layout with $hdd_per_node mount(s) per NUMA node."
    fi

    for ((node_index = 0; node_index < numa_count; node_index++)); do
        start=$((node_index * hdd_per_node))
        mounts=("${HDD_DIRS[@]:start:hdd_per_node}")
        layout::configure_hdd_node "$node_index" "${mounts[@]}"
    done

    disks_manifest=$(manifest::build_disk_names 'disk' "$hdd_per_node")
    explain::record_layout "disks manifest: ${disks_manifest:-<empty>}"
    manifest::write_file "$TIDELET_HOME_PATH/disks" "$disks_manifest"
}

function layout::apply_ssd_layout() {
    local numa_count=$1
    local total_ssd=${#SSD_DIRS[@]}
    local ssd_per_node=0
    local node_index=0
    local start=0
    local mounts=()
    local ssd_manifest=''

    if (( total_ssd == 0 )); then
        explain::record_layout "SSD per node: 0"
        for ((node_index = 0; node_index < numa_count; node_index++)); do
            cleanup::clear_stale_numbered_links "$ENGINE_HOME_PATH/node${node_index}" "ssd_disks_" 0
        done
        if [[ -s "$TIDELET_HOME_PATH/disks" ]]; then
            filesystem::copy_file "$TIDELET_HOME_PATH/disks" "$TIDELET_HOME_PATH/ssd_disks"
            manifest::truncate_file "$TIDELET_HOME_PATH/disks"
            if (( DRY_RUN == 0 )); then
                log::warn "Reuse HDD disk list as ssd_disks because no SSD mounts were found."
            fi
        fi
        return 0
    fi

    ssd_per_node=$((total_ssd / numa_count))
    explain::record_layout "SSD per node: ${ssd_per_node}"
    if (( DRY_RUN == 0 )); then
        log::info "Create SSD layout with $ssd_per_node mount(s) per NUMA node."
    fi

    for ((node_index = 0; node_index < numa_count; node_index++)); do
        start=$((node_index * ssd_per_node))
        mounts=("${SSD_DIRS[@]:start:ssd_per_node}")
        layout::configure_ssd_node "$node_index" "${mounts[@]}"
    done

    ssd_manifest=$(manifest::build_disk_names 'ssd_disks_' "$ssd_per_node")
    explain::record_layout "ssd_disks manifest: ${ssd_manifest:-<empty>}"
    manifest::write_file "$TIDELET_HOME_PATH/ssd_disks" "$ssd_manifest"
}

function init::main() {
    local numa_count=1

    EXPLAIN_CPU_MODEL=$(system::get_cpu_model)

    # Flow:
    #   +--------------------+
    #   |     parse args     |
    #   +--------------------+
    #              |
    #              v
    #   +--------------------+
    #   |  load config/env   |
    #   +--------------------+
    #              |
    #              v
    #   +------------------------------+
    #   | discover and classify mounts |
    #   | - scan /dataXX               |
    #   | - classify HDD / SSD         |
    #   | - sort by mount name         |
    #   +------------------------------+
    #              |
    #              v
    #   +------------------------------+
    #   |      validate resources      |
    #   | - fail if NUMA memory > 512G |
    #   | - fail if disk health bad    |
    #   | - fail if no usable mounts   |
    #   | - fail if split is uneven    |
    #   +------------------------------+
    #              |
    #              v
    #   +------------------------------+
    #   | clean unexpected entries     |
    #   | - keep valid layout links    |
    #   | - optionally purge targets   |
    #   +------------------------------+
    #              |
    #              v
    #   +--------------------+
    #   |  build HDD layout  |
    #   +--------------------+
    #              |
    #              v
    #   +--------------------+
    #   |  build SSD layout  |
    #   +--------------------+
    #              |
    #              v
    #   +--------------------+
    #   | write manifests    |
    #   | and finish         |
    #   +--------------------+
    init::parse_args "$@" || return 1
    init::set_paths
    init::load_disallowed_dirs
    numa_count=$(init::detect_numa_count)
    EXPLAIN_NUMA_COUNT=$numa_count

    if (( DRY_RUN == 0 )); then
        log::info "NUMA count: $numa_count"
        log::info "TIDELET_HOME_PATH: $TIDELET_HOME_PATH"
        log::info "ENGINE_HOME_PATH: $ENGINE_HOME_PATH"
        log::info "PURGE_LINK_TARGET_DATA: $PURGE_LINK_TARGET_DATA"
        log::info "DRY_RUN: $DRY_RUN"
    fi

    storage::discover_mounts
    if ! validate::check_missing_resources "$numa_count"; then
        explain::print_summary
        return 1
    fi

    if ! init::prepare_directories "$numa_count"; then
        explain::print_summary
        return 1
    fi

    if ! layout::apply_hdd_layout "$numa_count"; then
        explain::print_summary
        return 1
    fi

    if ! layout::apply_ssd_layout "$numa_count"; then
        explain::print_summary
        return 1
    fi

    if ! metadata::repair_all; then
        explain::print_summary
        return 1
    fi

    explain::print_summary

    if (( DRY_RUN == 0 )); then
        log::success "Disk layout initialization completed."
    fi
}

init::main "$@"
