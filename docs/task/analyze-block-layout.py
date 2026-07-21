#!/usr/bin/env python3
"""JSON-first Tide block layout cleanup implementation.

Supported cleanup types:

1. metadata_missing_path: metadata exists, path is missing; delete metadata only.
2. ttl_expired: metadata exists and ttl expired; delete metadata first, then delete path.
3. untracked_path: path exists on disk but metadata is missing; delete path only.

Default mode is apply. Use --dry-run to print the JSON plan without mutation.
This module intentionally stays Python 3.5 compatible for the py35 entrypoint.

Output design:
- leaf JSON objects stay on one line by default; use --pretty-json for fully
  expanded output;
- TTL fields include days and human-readable expiration duration;
- summary.storage_retention estimates current storage days from the previous
  two available partition-day averages and exposes the current waterline as a
  percentage of TTL capacity.
"""
import argparse
import datetime as dt
import glob
import json
import os
import shutil
import sqlite3
import sys
import threading
import time
from collections import defaultdict, OrderedDict
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_TIDE_HOME = "/opt/tiger/tide"
DEFAULT_TOP = 50
DEFAULT_SAMPLE_LIMIT = 20
MAX_DELETE_WORKERS = 32
DELETE_DISK_QUANTUM = 8
SECONDS_PER_DAY = 86400.0
COMPACT_JSON_MAX_ITEMS = 6
COMPACT_JSON_MAX_CHARS = 180


class BlockRecord(object):
    def __init__(self, db_path, tenant, table, path, data_size, layer, event_time, ttl, disk_path, disk_name, node_name, partition_time, path_exists, ttl_expired):
        self.db_path = db_path
        self.tenant = tenant
        self.table = table
        self.path = path
        self.data_size = data_size
        self.layer = layer
        self.event_time = event_time
        self.ttl = ttl
        self.disk_path = disk_path
        self.disk_name = disk_name
        self.node_name = node_name
        self.partition_time = partition_time
        self.path_exists = path_exists
        self.ttl_expired = ttl_expired

    @property
    def table_key(self):
        return "{}.{}".format(self.tenant, self.table)


class UntrackedPath(object):
    def __init__(self, path, disk_path, data_size):
        self.path = path
        self.disk_path = disk_path
        self.data_size = data_size


def utc_now_epoch():
    return int(time.time())


def local_report_time():
    return dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")


def log_trace(enabled, message):
    if enabled:
        emit_progress("trace", "trace", {"message": message}, trace_only=False)


def log_trace_event(enabled, phase, status, details):
    if enabled:
        emit_progress(phase, status, details, trace_only=False)


def log_error(message):
    sys.stderr.write("[ERROR] {}\n".format(message))
    sys.stderr.flush()


def emit_progress(phase, status, details=None, trace_only=False):
    if trace_only:
        return
    event = {
        "event": "tide_block_layout_cleanup_progress",
        "phase": phase,
        "status": status,
        "datetime": local_report_time(),
        "pid": os.getpid(),
        "tid": threading.get_ident(),
        "thread": threading.current_thread().name,
    }
    if details is not None:
        event["details"] = details
    sys.stderr.write(json.dumps(event) + "\n")
    sys.stderr.flush()


def finish_phase(phase_summaries, phase, started, summary):
    duration = round(time.time() - started, 3)
    phase_summary = {
        "phase": phase,
        "duration_seconds": duration,
        "summary": summary,
    }
    phase_summaries.append(phase_summary)
    event_details = dict(summary)
    event_details["duration_seconds"] = duration
    emit_progress(phase, "done", event_details)
    return phase_summary


def format_bytes(size):
    size = float(size or 0)
    units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"]
    index = 0
    while size >= 1024.0 and index < len(units) - 1:
        size /= 1024.0
        index += 1
    if index == 0:
        return "{} {}".format(int(size), units[index])
    return "{:.2f} {}".format(size, units[index])


def format_duration(seconds):
    if seconds is None:
        return "<unknown>"
    seconds = max(0.0, float(seconds))
    days = seconds / SECONDS_PER_DAY
    if days >= 1:
        rounded_days = round(days)
        if abs(days - rounded_days) < 0.01:
            return "{} days".format(int(rounded_days))
        return "{:.2f} days".format(days)
    hours = seconds / 3600.0
    if hours >= 1:
        return "{:.2f} hours".format(hours)
    minutes = seconds / 60.0
    if minutes >= 1:
        return "{:.2f} minutes".format(minutes)
    return "{:.0f} seconds".format(seconds)


def ttl_days(ttl):
    if ttl is None:
        return None
    return round(float(ttl) / SECONDS_PER_DAY, 2)


def ttl_description(ttl):
    if ttl is None:
        return "no TTL"
    return "TTL {}".format(format_duration(ttl))


def epoch_description(epoch_seconds):
    if epoch_seconds is None:
        return None
    try:
        return dt.datetime.utcfromtimestamp(epoch_seconds).strftime("%Y-%m-%d %H:%M:%S UTC")
    except (OverflowError, OSError, ValueError):
        return None


def normalize_path(path):
    return path.rstrip("/")


def discover_dbs(tide_home, explicit_dbs):
    if explicit_dbs:
        return [str(path) for path in explicit_dbs]
    pattern = os.path.join(tide_home, "node*", "metadata", "fringedb.db")
    return sorted(path for path in glob.glob(pattern) if os.path.isfile(path))


def discover_logical_disk_paths(tide_home):
    disk_paths = []
    for node_path in sorted(glob.glob(os.path.join(tide_home, "node*"))):
        if not os.path.isdir(node_path):
            continue
        for disk_path in sorted(glob.glob(os.path.join(node_path, "disk[0-9][0-9]"))):
            if os.path.basename(disk_path) == "disk00":
                continue
            if os.path.isdir(disk_path):
                disk_paths.append(disk_path)
        for disk_path in sorted(glob.glob(os.path.join(node_path, "ssd_disks_[0-9][0-9]"))):
            if os.path.isdir(disk_path):
                disk_paths.append(disk_path)
    return disk_paths


def ensure_schema(conn, db_path):
    rows = conn.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tables', 'block_records')").fetchall()
    names = set(row[0] for row in rows)
    missing = set(["tables", "block_records"]) - names
    if missing:
        raise RuntimeError("{} missing required tables: {}".format(db_path, ", ".join(sorted(missing))))


def load_table_ttls(conn):
    ttl_by_table = {}
    for tenant, table_name, ttl in conn.execute('SELECT tenant, "table", ttl FROM tables'):
        if ttl is not None:
            ttl_by_table[(tenant, table_name)] = int(ttl)
    return ttl_by_table


def parse_disk_path(block_path, tide_home):
    normalized_home = tide_home.rstrip("/")
    prefix = normalized_home + "/"
    if not block_path.startswith(prefix):
        return ("<outside-tide-home>", "<unknown>", "<unknown>")

    parts = block_path[len(prefix):].split("/")
    if len(parts) < 4:
        return ("<unparsed>", "<unknown>", "<unknown>")

    node_name = parts[0]
    disk_name = parts[1]
    if not node_name.startswith("node"):
        return ("<unparsed>", "<unknown>", "<unknown>")
    if not (disk_name.startswith("disk") or disk_name.startswith("ssd_disks_")):
        return ("<unparsed>", "<unknown>", "<unknown>")
    return (os.path.join(normalized_home, node_name, disk_name), disk_name, node_name)


def parse_partition_time(block_path):
    block_id = os.path.basename(block_path.rstrip("/"))
    head = block_id.rsplit("_", 3)[0]
    if "_" not in head:
        return None
    _partition_id, partition_text = head.split("_", 1)
    try:
        parsed = dt.datetime.strptime(partition_text, "%Y-%m-%d_%H:%M:%S")
    except ValueError:
        return None
    return int((parsed - dt.datetime(1970, 1, 1)).total_seconds())


def partition_date_from_epoch(epoch_seconds):
    if epoch_seconds is None:
        return "<unknown>"
    try:
        return (dt.datetime(1970, 1, 1) + dt.timedelta(seconds=epoch_seconds)).strftime("%Y-%m-%d")
    except (OverflowError, OSError, ValueError):
        return "<unknown>"


def load_blocks(db_path, tide_home, now_epoch):
    conn = sqlite3.connect(db_path)
    try:
        ensure_schema(conn, db_path)
        ttl_by_table = load_table_ttls(conn)
        rows = conn.execute('SELECT tenant, "table", path, data_size, layer, event_time FROM block_records').fetchall()
    finally:
        conn.close()

    records = []
    for tenant, table_name, path, data_size, layer, event_time in rows:
        path = str(path)
        ttl = ttl_by_table.get((tenant, table_name))
        disk_path, disk_name, node_name = parse_disk_path(path, tide_home)
        partition_time = parse_partition_time(path)
        path_exists = os.path.exists(path)
        ttl_expired = False
        if ttl is not None and partition_time is not None:
            ttl_expired = partition_time + ttl < now_epoch
        records.append(BlockRecord(
            db_path=db_path,
            tenant=tenant,
            table=table_name,
            path=path,
            data_size=int(data_size or 0),
            layer=int(layer or 0),
            event_time=int(event_time or 0),
            ttl=ttl,
            disk_path=disk_path,
            disk_name=disk_name,
            node_name=node_name,
            partition_time=partition_time,
            path_exists=path_exists,
            ttl_expired=ttl_expired,
        ))
    return records


def load_all_blocks(db_paths, tide_home, now_epoch, trace):
    records = []
    skipped = []
    for db_path in sorted(db_paths):
        if not os.path.isfile(db_path):
            skipped.append({"db_path": db_path, "reason": "missing"})
            continue
        started = time.time()
        log_trace_event(trace, "load_metadata", "task_start", {"db_path": db_path})
        records.extend(load_blocks(db_path, tide_home, now_epoch))
        log_trace_event(trace, "load_metadata", "task_done", {"db_path": db_path, "duration_seconds": round(time.time() - started, 3)})
    return records, skipped


def is_safe_path_under_disk(path, disk_path, tide_home):
    normalized_home = tide_home.rstrip("/")
    path = normalize_path(path)
    disk_path = normalize_path(disk_path)
    if not path.startswith(normalized_home + "/node"):
        return False
    if disk_path in ("<outside-tide-home>", "<unparsed>", ""):
        return False
    if not path.startswith(disk_path + "/"):
        return False
    if path in ("", "/", normalized_home, disk_path):
        return False
    return True


def path_size(path):
    if os.path.isfile(path) or os.path.islink(path):
        try:
            return os.path.getsize(path)
        except OSError:
            return 0
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            file_path = os.path.join(root, name)
            try:
                total += os.path.getsize(file_path)
            except OSError:
                pass
    return total


def discover_block_table_paths_for_disk(disk_path):
    table_paths = []
    if not os.path.isdir(disk_path):
        return table_paths
    try:
        tenant_names = sorted(os.listdir(disk_path))
    except OSError:
        return table_paths
    for tenant_name in tenant_names:
        tenant_path = os.path.join(disk_path, tenant_name)
        if not os.path.isdir(tenant_path):
            continue
        try:
            table_names = sorted(os.listdir(tenant_path))
        except OSError:
            continue
        for table_name in table_names:
            table_path = os.path.join(tenant_path, table_name)
            if os.path.isdir(table_path):
                table_paths.append(table_path)
    return table_paths


def scan_disk_untracked_paths(disk_path, known_paths, tide_home, trace):
    started = time.time()
    results = []
    log_trace_event(trace, "scan_untracked_paths", "task_start", {"disk_path": disk_path})
    for table_path in discover_block_table_paths_for_disk(disk_path):
        try:
            names = sorted(os.listdir(table_path))
        except OSError:
            continue
        for name in names:
            block_path = normalize_path(os.path.join(table_path, name))
            if not os.path.exists(block_path):
                continue
            if parse_partition_time(block_path) is None:
                continue
            if block_path in known_paths:
                continue
            if not is_safe_path_under_disk(block_path, disk_path, tide_home):
                raise RuntimeError("unsafe untracked path: {}".format(block_path))
            results.append(UntrackedPath(block_path, disk_path, path_size(block_path)))
    log_trace_event(trace, "scan_untracked_paths", "task_done", {"disk_path": disk_path, "untracked_path_count": len(results), "duration_seconds": round(time.time() - started, 3)})
    return results


def build_known_paths_by_disk(records):
    known = defaultdict(set)
    for record in records:
        if record.disk_path in ("<outside-tide-home>", "<unparsed>"):
            continue
        known[record.disk_path].add(normalize_path(record.path))
    return known


def discover_untracked_paths(tide_home, disk_paths, known_paths_by_disk, trace):
    if not disk_paths:
        return []
    results = []
    workers = min(len(disk_paths), max(1, os.cpu_count() or 1), MAX_DELETE_WORKERS)
    log_trace_event(trace, "scan_untracked_paths", "executor_start", {"workers": workers, "tasks": len(disk_paths)})
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {}
        for disk_path in disk_paths:
            log_trace_event(trace, "scan_untracked_paths", "task_submit", {"disk_path": disk_path})
            futures[executor.submit(scan_disk_untracked_paths, disk_path, known_paths_by_disk.get(disk_path, set()), tide_home, trace)] = disk_path
        for future in as_completed(futures):
            disk_path = futures[future]
            try:
                disk_results = future.result()
                log_trace_event(trace, "scan_untracked_paths", "task_collect", {"disk_path": disk_path, "untracked_path_count": len(disk_results)})
                results.extend(disk_results)
            except Exception as exc:
                raise RuntimeError("{} failed to scan untracked paths: {}".format(disk_path, exc))
    log_trace_event(trace, "scan_untracked_paths", "executor_done", {"result_count": len(results)})
    return sorted(results, key=lambda item: (item.disk_path, item.path))


def classify_cleanup(records, untracked_paths):
    metadata_missing_path = []
    ttl_expired = []
    for record in records:
        if not record.path_exists:
            metadata_missing_path.append(record)
        elif record.ttl_expired:
            ttl_expired.append(record)
    return {
        "metadata_missing_path": sorted(metadata_missing_path, key=lambda item: (item.db_path, item.path)),
        "ttl_expired": sorted(ttl_expired, key=lambda item: (item.db_path, item.path)),
        "untracked_path": sorted(untracked_paths, key=lambda item: (item.disk_path, item.path)),
    }


def ttl_waterline_summary(current_size_bytes, partition_stats, ttl_values):
    dates = sorted(date for date in partition_stats if date != "<unknown>")
    latest_date = dates[-1] if dates else None
    previous_dates = dates[:-1][-2:] if latest_date else []
    baseline_bytes = sum(partition_stats[date]["size_bytes"] for date in previous_dates)
    baseline_days = len(previous_dates)
    daily_capacity_bytes = (baseline_bytes / baseline_days) if baseline_days else 0
    unique_ttl_values = sorted(set(value for value in ttl_values if value is not None))
    ttl_seconds_value = unique_ttl_values[0] if len(unique_ttl_values) == 1 else None
    ttl_days_value = ttl_days(ttl_seconds_value)
    ttl_capacity_bytes = None
    ratio_percent = None
    if baseline_days == 2 and ttl_seconds_value is not None:
        ttl_capacity_bytes = daily_capacity_bytes * ttl_seconds_value / SECONDS_PER_DAY
        if ttl_capacity_bytes:
            ratio_percent = round(current_size_bytes * 100.0 / ttl_capacity_bytes, 2)

    result = OrderedDict()
    result["ratio"] = "{:.2f}%".format(ratio_percent) if ratio_percent is not None else "<unavailable>"
    result["ratio_percent"] = ratio_percent
    result["ttl_days"] = ttl_days_value
    result["ttl_seconds"] = ttl_seconds_value
    result["daily_capacity"] = format_bytes(daily_capacity_bytes) if baseline_days else None
    result["daily_capacity_bytes"] = int(daily_capacity_bytes) if baseline_days else None
    result["ttl_capacity"] = format_bytes(ttl_capacity_bytes) if ttl_capacity_bytes is not None else None
    result["ttl_capacity_bytes"] = int(ttl_capacity_bytes) if ttl_capacity_bytes is not None else None
    result["baseline_partition_dates"] = previous_dates
    result["baseline_days_available"] = baseline_days
    result["current_size"] = format_bytes(current_size_bytes)
    result["current_size_bytes"] = current_size_bytes
    if len(unique_ttl_values) > 1:
        result["description"] = "TTL waterline unavailable: multiple TTL values exist for this scope."
    elif baseline_days != 2:
        result["description"] = (
            "TTL waterline unavailable: expected two previous partition days, found {}."
        ).format(baseline_days)
    elif ttl_seconds_value is None:
        result["description"] = "TTL waterline unavailable: no single TTL value exists for this scope."
    elif ratio_percent is None:
        result["description"] = "TTL waterline unavailable: the day-1/day-2 capacity is zero."
    else:
        result["description"] = (
            "Current storage uses {:.2f}% of the TTL capacity; day-1/day-2 average is one day."
        ).format(ratio_percent)
    return result


def aggregate_table_layout(records, top):
    stats = {}
    for record in records:
        table = stats.setdefault(record.table_key, {
            "table": record.table_key,
            "blocks": 0,
            "size_bytes": 0,
            "ttl_seconds": set(),
            "disks": {},
            "partition_dates": {},
            "metadata_missing_path_blocks": 0,
            "ttl_expired_blocks": 0,
        })
        table["blocks"] += 1
        table["size_bytes"] += record.data_size
        if record.ttl is not None:
            table["ttl_seconds"].add(record.ttl)
        disk = table["disks"].setdefault(record.disk_path, {"disk_path": record.disk_path, "blocks": 0, "size_bytes": 0})
        disk["blocks"] += 1
        disk["size_bytes"] += record.data_size
        partition_date = partition_date_from_epoch(record.partition_time)
        date_stats = table["partition_dates"].setdefault(partition_date, {"partition_date": partition_date, "blocks": 0, "size_bytes": 0, "disks": set()})
        date_stats["blocks"] += 1
        date_stats["size_bytes"] += record.data_size
        date_stats["disks"].add(record.disk_path)
        if not record.path_exists:
            table["metadata_missing_path_blocks"] += 1
        elif record.ttl_expired:
            table["ttl_expired_blocks"] += 1

    rows = []
    for _table_key, table in sorted(stats.items(), key=lambda item: (-item[1]["size_bytes"], item[0]))[:top]:
        disks = []
        for _disk_path, disk in sorted(table["disks"].items(), key=lambda item: item[0]):
            size_bytes = disk["size_bytes"]
            disks.append(OrderedDict([
                ("disk_name", os.path.basename(disk["disk_path"])),
                ("blocks", disk["blocks"]),
                ("size", format_bytes(size_bytes)),
                ("size_bytes", size_bytes),
                ("table_percent", round((size_bytes * 100.0 / table["size_bytes"]) if table["size_bytes"] else 0.0, 2)),
                ("disk_path", disk["disk_path"]),
            ]))
        partition_dates = []
        for _partition_date, date_stats in sorted(table["partition_dates"].items(), key=lambda item: item[0], reverse=True):
            date_size_bytes = date_stats["size_bytes"]
            partition_dates.append(OrderedDict([
                ("partition_date", date_stats["partition_date"]),
                ("blocks", date_stats["blocks"]),
                ("size", format_bytes(date_size_bytes)),
                ("size_bytes", date_size_bytes),
                ("table_percent", round((date_size_bytes * 100.0 / table["size_bytes"]) if table["size_bytes"] else 0.0, 2)),
                ("disk_count", len(date_stats["disks"])),
            ]))
        ttl_values = sorted(table["ttl_seconds"])
        waterline = ttl_waterline_summary(table["size_bytes"], table["partition_dates"], ttl_values)
        rows.append(OrderedDict([
            ("table_id", table["table"]),
            ("table", table["table"]),
            ("size", format_bytes(table["size_bytes"])),
            ("size_bytes", table["size_bytes"]),
            ("blocks", table["blocks"]),
            ("disk_count", len(disks)),
            ("ttl_description", ", ".join(ttl_description(value) for value in ttl_values) or "no TTL"),
            ("ttl_days", [ttl_days(value) for value in ttl_values]),
            ("ttl_waterline_ratio", waterline["ratio"]),
            ("ttl_waterline_ratio_percent", waterline["ratio_percent"]),
            ("ttl_waterline_daily_capacity", waterline["daily_capacity"]),
            ("ttl_waterline_daily_capacity_bytes", waterline["daily_capacity_bytes"]),
            ("ttl_waterline_capacity", waterline["ttl_capacity"]),
            ("ttl_waterline_capacity_bytes", waterline["ttl_capacity_bytes"]),
            ("metadata_missing_path_blocks", table["metadata_missing_path_blocks"]),
            ("ttl_expired_blocks", table["ttl_expired_blocks"]),
            ("partition_date_distribution", partition_dates),
            ("disk_distribution", disks),
            ("ttl_seconds", ttl_values),
        ]))
    return rows


def partition_storage_stats(records):
    stats = {}
    for record in records:
        partition_date = partition_date_from_epoch(record.partition_time)
        if partition_date == "<unknown>":
            continue
        date_stats = stats.setdefault(partition_date, {
            "partition_date": partition_date,
            "blocks": 0,
            "size_bytes": 0,
        })
        date_stats["blocks"] += 1
        date_stats["size_bytes"] += record.data_size
    return stats


def storage_retention_summary(records, total_bytes, now_epoch):
    stats = partition_storage_stats(records)
    dates = sorted(stats)
    latest_date = dates[-1] if dates else None
    previous_dates = dates[:-1][-2:] if latest_date else []
    baseline_bytes = sum(stats[date]["size_bytes"] for date in previous_dates)
    baseline_days = len(previous_dates)
    baseline_average_bytes = (baseline_bytes / baseline_days) if baseline_days else 0
    estimated_days = (total_bytes / baseline_average_bytes) if baseline_average_bytes else None

    def date_summary(date):
        if date is None:
            return None
        item = stats[date]
        return OrderedDict([
            ("partition_date", date),
            ("blocks", item["blocks"]),
            ("size", format_bytes(item["size_bytes"])),
            ("size_bytes", item["size_bytes"]),
        ])

    result = OrderedDict()
    result["as_of_date"] = partition_date_from_epoch(now_epoch)
    result["latest_partition"] = date_summary(latest_date)
    result["previous_two_partition_days"] = [date_summary(date) for date in previous_dates]
    result["baseline"] = "previous_two_available_partition_days"
    result["baseline_days_available"] = baseline_days
    result["baseline_average_size"] = format_bytes(baseline_average_bytes) if baseline_days else None
    result["baseline_average_size_bytes"] = int(baseline_average_bytes) if baseline_days else None
    result["current_storage_size"] = format_bytes(total_bytes)
    result["current_storage_size_bytes"] = total_bytes
    result["available_partition_days"] = len(dates)
    result["estimated_storage_days"] = round(estimated_days, 2) if estimated_days is not None else None
    result["estimated_storage_days_label"] = (
        "{:.2f} days".format(estimated_days) if estimated_days is not None else "<unavailable>"
    )
    waterline = ttl_waterline_summary(
        total_bytes,
        stats,
        [record.ttl for record in records],
    )
    result["ttl_waterline_ratio"] = waterline["ratio"]
    result["ttl_waterline_ratio_percent"] = waterline["ratio_percent"]
    result["ttl_waterline"] = waterline
    if baseline_days == 2:
        result["description"] = (
            "At the average size of the previous two available partition days, "
            "current storage covers approximately {:.2f} days."
        ).format(estimated_days)
    else:
        result["description"] = (
            "Storage-day estimate unavailable: only {} previous partition day(s) "
            "are available for the two-day baseline."
        ).format(baseline_days)
    return result


def record_to_json(record, now_epoch=None):
    now_epoch = utc_now_epoch() if now_epoch is None else now_epoch
    expire_at = None
    expired_seconds = None
    if record.ttl is not None and record.partition_time is not None:
        expire_at = record.partition_time + record.ttl
        if record.ttl_expired:
            expired_seconds = max(0, now_epoch - expire_at)
    return OrderedDict([
        ("table_id", record.table_key),
        ("table", record.table),
        ("tenant", record.tenant),
        ("partition_date", partition_date_from_epoch(record.partition_time)),
        ("data_size", format_bytes(record.data_size)),
        ("data_size_bytes", record.data_size),
        ("ttl_description", ttl_description(record.ttl)),
        ("ttl_days", ttl_days(record.ttl)),
        ("expired_description", (
            "expired {} ago".format(format_duration(expired_seconds))
            if expired_seconds is not None else "not expired"
        )),
        ("expired_days", round(expired_seconds / SECONDS_PER_DAY, 2) if expired_seconds is not None else None),
        ("path_exists", record.path_exists),
        ("ttl_seconds", record.ttl),
        ("expire_at_description", epoch_description(expire_at)),
        ("expired_seconds", expired_seconds),
        ("partition_time", record.partition_time),
        ("event_time", record.event_time),
        ("layer", record.layer),
        ("node_name", record.node_name),
        ("disk_name", record.disk_name),
        ("disk_path", record.disk_path),
        ("path", record.path),
        ("db_path", record.db_path),
    ])


def untracked_to_json(item):
    return OrderedDict([
        ("data_size", format_bytes(item.data_size)),
        ("data_size_bytes", item.data_size),
        ("disk_path", item.disk_path),
        ("path", item.path),
    ])


def limited_json(items, converter, limit):
    visible = items if limit == 0 else items[:limit]
    return {
        "count": len(items),
        "hidden": max(0, len(items) - len(visible)),
        "items": [converter(item) for item in visible],
    }


def compact_json(value, level=0):
    compact = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if not isinstance(value, (dict, list)):
        return compact
    if isinstance(value, dict) and all(not isinstance(item, (dict, list)) for item in value.values()):
        return compact
    if isinstance(value, list) and all(not isinstance(item, (dict, list)) for item in value):
        return compact
    item_count = len(value)
    if item_count <= COMPACT_JSON_MAX_ITEMS and len(compact) <= COMPACT_JSON_MAX_CHARS:
        return compact

    indent = "  " * level
    child_indent = "  " * (level + 1)
    if isinstance(value, dict):
        lines = ["{"]
        items = list(value.items())
        for index, (key, item) in enumerate(items):
            suffix = "," if index < len(items) - 1 else ""
            lines.append(
                "{}{}: {}{}".format(
                    child_indent,
                    json.dumps(str(key), ensure_ascii=False),
                    compact_json(item, level + 1),
                    suffix,
                )
            )
        lines.append(indent + "}")
        return "\n".join(lines)

    lines = ["["]
    for index, item in enumerate(value):
        suffix = "," if index < len(value) - 1 else ""
        lines.append("{}{}{}".format(child_indent, compact_json(item, level + 1), suffix))
    lines.append(indent + "]")
    return "\n".join(lines)


def write_json(value, pretty):
    if pretty:
        json.dump(value, sys.stdout, ensure_ascii=False, indent=2)
    else:
        sys.stdout.write(compact_json(value))
    sys.stdout.write("\n")


def delete_metadata_rows(db_path, paths, trace):
    if not paths:
        return {"db_path": db_path, "requested": 0, "deleted": 0, "duration_seconds": 0.0}
    started = time.time()
    deleted = 0
    conn = sqlite3.connect(db_path)
    try:
        with conn:
            for path in sorted(paths):
                cursor = conn.execute("DELETE FROM block_records WHERE path = ?", (path,))
                deleted += cursor.rowcount
        conn.execute("VACUUM")
    finally:
        conn.close()
    duration = time.time() - started
    log_trace_event(trace, "delete_metadata", "task_done", {"db_path": db_path, "requested": len(paths), "deleted": deleted, "duration_seconds": round(duration, 3)})
    return {"db_path": db_path, "requested": len(paths), "deleted": deleted, "duration_seconds": round(duration, 3)}


def delete_metadata_plan(cleanup_plan, trace):
    paths_by_db = defaultdict(set)
    for record in cleanup_plan["metadata_missing_path"]:
        paths_by_db[record.db_path].add(record.path)
    for record in cleanup_plan["ttl_expired"]:
        paths_by_db[record.db_path].add(record.path)
    results = []
    for db_path, paths in sorted(paths_by_db.items()):
        results.append(delete_metadata_rows(db_path, paths, trace))
    return results


def remove_path(path):
    if os.path.isdir(path) and not os.path.islink(path):
        shutil.rmtree(path)
    else:
        os.unlink(path)


def file_delete_items(cleanup_plan, tide_home):
    items = []
    for record in cleanup_plan["ttl_expired"]:
        if not record.path_exists:
            continue
        if not is_safe_path_under_disk(record.path, record.disk_path, tide_home):
            raise RuntimeError("unsafe ttl-expired path: {}".format(record.path))
        items.append({"type": "ttl_expired", "path": record.path, "disk_path": record.disk_path, "size": format_bytes(record.data_size)})
    for item in cleanup_plan["untracked_path"]:
        if not is_safe_path_under_disk(item.path, item.disk_path, tide_home):
            raise RuntimeError("unsafe untracked path: {}".format(item.path))
        items.append({"type": "untracked_path", "path": item.path, "disk_path": item.disk_path, "size": format_bytes(item.data_size)})
    return sorted(items, key=lambda value: (value["disk_path"], value["path"]))


def delete_path_trace_details(item, extra=None):
    details = {
        "task_id": item.get("task_id"),
        "type": item["type"],
        "path": item["path"],
        "disk_path": item["disk_path"],
        "size": item.get("size"),
    }
    if extra:
        details.update(extra)
    return details


def public_delete_result(item):
    return dict((key, value) for key, value in item.items() if not key.startswith("_"))


def disk_delete_trace_details(shard, extra=None):
    details = {
        "disk_task_id": shard.get("disk_task_id"),
        "disk_path": shard["disk_path"],
        "remaining": len(shard["items"]),
        "quantum": shard.get("quantum"),
    }
    if extra:
        details.update(extra)
    return details


def remove_one_file_item(item, trace):
    started = time.time()
    result = public_delete_result(item)
    queue_seconds = round(started - item.get("_submitted_at", started), 3)
    log_trace_event(trace, "delete_path", "task_start", delete_path_trace_details(item, {
        "queue_seconds": queue_seconds,
    }))
    try:
        if not os.path.exists(item["path"]):
            duration = round(time.time() - started, 3)
            result.update({"status": "skipped_missing", "duration_seconds": duration})
            log_trace_event(trace, "delete_path", "task_done", delete_path_trace_details(item, {
                "status": "skipped_missing",
                "duration_seconds": duration,
            }))
            return result
        remove_path(item["path"])
        duration = round(time.time() - started, 3)
        result.update({"status": "removed", "duration_seconds": duration})
        log_trace_event(trace, "delete_path", "task_done", delete_path_trace_details(item, {
            "status": "removed",
            "duration_seconds": duration,
        }))
        return result
    except Exception as exc:
        duration = round(time.time() - started, 3)
        log_trace_event(trace, "delete_path", "task_error", delete_path_trace_details(item, {
            "error_type": exc.__class__.__name__,
            "error": str(exc),
            "duration_seconds": duration,
        }))
        raise


def group_delete_items_by_disk(items):
    grouped = OrderedDict()
    for item in sorted(items, key=lambda value: (value["disk_path"], value["path"])):
        grouped.setdefault(item["disk_path"], []).append(item)
    return grouped


def delete_disk_quantum(shard, trace):
    started = time.time()
    results = []
    items = shard["items"]
    quantum = shard["quantum"]
    disk_task_id = shard["disk_task_id"]
    disk_path = shard["disk_path"]
    run_items = items[:quantum]
    remaining_items = items[quantum:]

    log_trace_event(trace, "delete_path", "disk_task_start", disk_delete_trace_details(shard, {
        "batch_count": len(run_items),
    }))
    try:
        for offset, item in enumerate(run_items, 1):
            task_item = dict(item)
            task_item["task_id"] = "{}.{}".format(disk_task_id, offset)
            task_item["disk_task_id"] = disk_task_id
            task_item["_submitted_at"] = shard.get("_submitted_at", started)
            results.append(remove_one_file_item(task_item, trace))
        duration = round(time.time() - started, 3)
        log_trace_event(trace, "delete_path", "disk_task_done", disk_delete_trace_details(shard, {
            "batch_count": len(run_items),
            "remaining_after": len(remaining_items),
            "preempted": bool(remaining_items),
            "duration_seconds": duration,
        }))
        return {
            "disk_task_id": disk_task_id,
            "disk_path": disk_path,
            "results": results,
            "remaining_items": remaining_items,
            "duration_seconds": duration,
            "preempted": bool(remaining_items),
        }
    except Exception as exc:
        duration = round(time.time() - started, 3)
        log_trace_event(trace, "delete_path", "disk_task_error", disk_delete_trace_details(shard, {
            "error_type": exc.__class__.__name__,
            "error": str(exc),
            "duration_seconds": duration,
        }))
        raise


def delete_file_items(items, trace):
    if not items:
        return []
    grouped = group_delete_items_by_disk(items)
    disk_paths = list(grouped.keys())
    workers = min(len(disk_paths), max(1, os.cpu_count() or 1), MAX_DELETE_WORKERS)
    results = []
    pending_shards = []
    active_disks = set()
    disk_task_seq = 0

    for disk_path in disk_paths:
        pending_shards.append({
            "disk_path": disk_path,
            "items": grouped[disk_path],
            "quantum": DELETE_DISK_QUANTUM,
        })

    log_trace_event(trace, "delete_path", "executor_start", {
        "workers": workers,
        "tasks": len(items),
        "disk_count": len(disk_paths),
        "disk_quantum": DELETE_DISK_QUANTUM,
        "concurrency_model": "disk_path",
        "preemption": "quantum",
    })
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {}

        def submit_ready_shards():
            nonlocal disk_task_seq
            submitted = 0
            remaining = []
            for shard in pending_shards:
                if len(futures) >= workers:
                    remaining.append(shard)
                    continue
                if shard["disk_path"] in active_disks:
                    remaining.append(shard)
                    continue
                disk_task_seq += 1
                task_shard = {
                    "disk_task_id": disk_task_seq,
                    "disk_path": shard["disk_path"],
                    "items": shard["items"],
                    "quantum": shard["quantum"],
                    "_submitted_at": time.time(),
                }
                active_disks.add(task_shard["disk_path"])
                log_trace_event(trace, "delete_path", "disk_task_submit", disk_delete_trace_details(task_shard, {
                    "batch_count": min(task_shard["quantum"], len(task_shard["items"])),
                }))
                futures[executor.submit(delete_disk_quantum, task_shard, trace)] = task_shard
                submitted += 1
            pending_shards[:] = remaining
            return submitted

        submit_ready_shards()
        while futures:
            future = next(as_completed(futures))
            task_shard = futures.pop(future)
            active_disks.discard(task_shard["disk_path"])
            try:
                disk_result = future.result()
            except Exception as exc:
                log_trace_event(trace, "delete_path", "disk_task_collect_error", disk_delete_trace_details(task_shard, {
                    "error_type": exc.__class__.__name__,
                    "error": str(exc),
                }))
                raise RuntimeError("failed to delete disk {}: {}".format(task_shard["disk_path"], exc))
            log_trace_event(trace, "delete_path", "disk_task_collect", disk_delete_trace_details(task_shard, {
                "batch_count": len(disk_result["results"]),
                "remaining_after": len(disk_result["remaining_items"]),
                "preempted": disk_result["preempted"],
            }))
            for result in disk_result["results"]:
                log_trace_event(trace, "delete_path", "task_collect", delete_path_trace_details(result, {
                    "status": result["status"],
                }))
            results.extend(disk_result["results"])
            if disk_result["remaining_items"]:
                log_trace_event(trace, "delete_path", "disk_task_preempt", disk_delete_trace_details(task_shard, {
                    "remaining_after": len(disk_result["remaining_items"]),
                }))
                pending_shards.append({
                    "disk_path": disk_result["disk_path"],
                    "items": disk_result["remaining_items"],
                    "quantum": DELETE_DISK_QUANTUM,
                })
            submit_ready_shards()
    log_trace_event(trace, "delete_path", "executor_done", {"result_count": len(results), "disk_count": len(disk_paths)})
    return sorted(results, key=lambda item: (item["type"], item["disk_path"], item["path"]))


def plan_summary(cleanup_plan, now_epoch=None):
    now_epoch = utc_now_epoch() if now_epoch is None else now_epoch
    missing = cleanup_plan["metadata_missing_path"]
    expired = cleanup_plan["ttl_expired"]
    untracked = cleanup_plan["untracked_path"]
    expired_file_bytes = sum(record.data_size for record in expired if record.path_exists)
    untracked_bytes = sum(item.data_size for item in untracked)
    expired_seconds = [
        max(0, now_epoch - (record.partition_time + record.ttl))
        for record in expired
        if record.partition_time is not None and record.ttl is not None
    ]
    return {
        "metadata_missing_path": {"count": len(missing), "metadata_delete_count": len(missing)},
        "ttl_expired": {
            "count": len(expired),
            "metadata_delete_count": len(expired),
            "path_delete_count": sum(1 for record in expired if record.path_exists),
            "path_delete_size": format_bytes(expired_file_bytes),
            "path_delete_size_bytes": expired_file_bytes,
            "oldest_expired_for": format_duration(max(expired_seconds)) if expired_seconds else None,
            "oldest_expired_for_days": round(max(expired_seconds) / SECONDS_PER_DAY, 2) if expired_seconds else None,
        },
        "untracked_path": {
            "count": len(untracked),
            "path_delete_count": len(untracked),
            "path_delete_size": format_bytes(untracked_bytes),
            "path_delete_size_bytes": untracked_bytes,
        },
    }


def build_payload(args, db_paths, skipped_dbs, disk_paths, records, cleanup_plan, mode, phase_summaries, action_results=None):
    total_bytes = sum(record.data_size for record in records)
    payload = OrderedDict()
    payload["report_type"] = "tide_block_layout_cleanup"
    payload["report_version"] = 2
    payload["report_time"] = local_report_time()
    payload["mode"] = mode
    payload["tide_home"] = args.tide_home
    payload["now_epoch"] = args.now
    payload["block_layout"] = {
        "tables_order": "size_desc",
        "table_limit": args.top,
        "tables": aggregate_table_layout(records, args.top),
    }
    payload["metadata_dbs"] = db_paths
    payload["skipped_dbs"] = skipped_dbs
    payload["disk_paths"] = disk_paths
    payload["summary"] = {
        "metadata_db_count": len(db_paths),
        "disk_count": len(disk_paths),
        "metadata_record_count": len(records),
        "metadata_record_size": format_bytes(total_bytes),
        "metadata_record_size_bytes": total_bytes,
        "storage_retention": storage_retention_summary(records, total_bytes, args.now),
        "cleanup": plan_summary(cleanup_plan, args.now),
    }
    payload["phase_summaries"] = phase_summaries
    payload["cleanup_plan"] = {
        "metadata_missing_path": limited_json(
            cleanup_plan["metadata_missing_path"],
            lambda item: record_to_json(item, args.now),
            args.sample_limit,
        ),
        "ttl_expired": limited_json(
            cleanup_plan["ttl_expired"],
            lambda item: record_to_json(item, args.now),
            args.sample_limit,
        ),
        "untracked_path": limited_json(cleanup_plan["untracked_path"], untracked_to_json, args.sample_limit),
    }
    payload["semantics"] = [
            {"type": "metadata_missing_path", "condition": "metadata row exists and path is missing", "action": "delete metadata only"},
            {"type": "ttl_expired", "condition": "metadata row exists, path exists, and partition_time + ttl < now", "action": "delete metadata first, then delete path"},
            {"type": "untracked_path", "condition": "path exists on disk but metadata row is missing", "action": "delete path only"},
    ]
    if action_results is not None:
        payload["action_results"] = action_results
    payload["presentation"] = {
        "json_layout": "pretty" if args.pretty_json else "compact_small_objects",
        "small_object_max_items": COMPACT_JSON_MAX_ITEMS,
        "small_object_max_chars": COMPACT_JSON_MAX_CHARS,
        "description": "Small objects are kept on one line; large objects and arrays remain expanded.",
    }
    return payload


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Analyze Tide block metadata/path drift and clean the three supported cleanup types.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("-d", "--db", action="append", default=[], help="SQLite fringedb.db path; repeatable")
    parser.add_argument("-t", "--tide-home", default=os.environ.get("TIDE_HOME", DEFAULT_TIDE_HOME), help="TIDE_HOME used to discover DBs and disk paths")
    parser.add_argument("-n", "--dry-run", action="store_true", help="Only print JSON plan; default without this flag applies cleanup")
    parser.add_argument("--now", type=int, default=utc_now_epoch(), help="Override current epoch seconds")
    parser.add_argument("--top", type=int, default=DEFAULT_TOP, help="Maximum table layout entries in JSON output")
    parser.add_argument("--sample-limit", type=int, default=DEFAULT_SAMPLE_LIMIT, help="Maximum sample items per cleanup type; 0 means all")
    parser.add_argument("--trace", action="store_true", help="Print JSONL concurrency task traces with datetime/pid/tid/thread to stderr")
    parser.add_argument("--pretty-json", action="store_true", help="Expand every JSON object; compact small objects by default")
    return parser.parse_args(argv)


def validate_args(args):
    if args.top <= 0:
        raise SystemExit("--top must be positive")
    if args.sample_limit < 0:
        raise SystemExit("--sample-limit must be zero or positive")


def run(argv):
    args = parse_args(argv)
    validate_args(args)
    phase_summaries = []
    emit_progress("start", "start", {
        "mode": "dry-run" if args.dry_run else "apply",
        "tide_home": args.tide_home,
        "sample_limit": args.sample_limit,
        "top": args.top,
    })
    phase_started = time.time()
    db_paths = discover_dbs(args.tide_home, args.db)
    finish_phase(phase_summaries, "discover_metadata_dbs", phase_started, {
        "metadata_db_count": len(db_paths),
        "explicit_db_count": len(args.db),
    })
    if not db_paths:
        log_error("No metadata DB found under {}/node*/metadata/fringedb.db".format(args.tide_home))
        emit_progress("finish", "failed", {"reason": "no_metadata_db"})
        return 1
    try:
        phase_started = time.time()
        emit_progress("load_metadata", "start", {"metadata_db_count": len(db_paths)})
        records, skipped_dbs = load_all_blocks(db_paths, args.tide_home, args.now, args.trace)
        total_bytes = sum(record.data_size for record in records)
        finish_phase(phase_summaries, "load_metadata", phase_started, {
            "metadata_record_count": len(records),
            "metadata_record_size": format_bytes(total_bytes),
            "skipped_db_count": len(skipped_dbs),
        })

        phase_started = time.time()
        emit_progress("discover_disks", "start", {"tide_home": args.tide_home})
        disk_paths = discover_logical_disk_paths(args.tide_home)
        finish_phase(phase_summaries, "discover_disks", phase_started, {
            "disk_count": len(disk_paths),
        })

        phase_started = time.time()
        emit_progress("scan_untracked_paths", "start", {"disk_count": len(disk_paths)})
        known_paths_by_disk = build_known_paths_by_disk(records)
        untracked_paths = discover_untracked_paths(args.tide_home, disk_paths, known_paths_by_disk, args.trace)
        finish_phase(phase_summaries, "scan_untracked_paths", phase_started, {
            "disk_count": len(disk_paths),
            "untracked_path_count": len(untracked_paths),
        })

        phase_started = time.time()
        emit_progress("classify_cleanup", "start", {
            "metadata_record_count": len(records),
            "untracked_path_count": len(untracked_paths),
        })
        cleanup_plan = classify_cleanup(records, untracked_paths)
        cleanup_summary = plan_summary(cleanup_plan, args.now)
        finish_phase(phase_summaries, "classify_cleanup", phase_started, cleanup_summary)

        if args.dry_run:
            emit_progress("dry_run_plan", "done", cleanup_summary)
            payload = build_payload(args, db_paths, skipped_dbs, disk_paths, records, cleanup_plan, "dry-run", phase_summaries)
        else:
            phase_started = time.time()
            metadata_delete_count = len(cleanup_plan["metadata_missing_path"]) + len(cleanup_plan["ttl_expired"])
            emit_progress("apply_metadata", "start", {"requested_rows": metadata_delete_count})
            metadata_results = delete_metadata_plan(cleanup_plan, args.trace)
            metadata_summary = {
                "db_count": len(metadata_results),
                "requested_rows": sum(item["requested"] for item in metadata_results),
                "deleted_rows": sum(item["deleted"] for item in metadata_results),
            }
            finish_phase(phase_summaries, "apply_metadata", phase_started, metadata_summary)

            phase_started = time.time()
            file_items = file_delete_items(cleanup_plan, args.tide_home)
            emit_progress("apply_paths", "start", {"requested_paths": len(file_items)})
            file_results = delete_file_items(file_items, args.trace)
            visible_results = file_results if args.sample_limit == 0 else file_results[:args.sample_limit]
            path_summary = {
                "requested": len(file_items),
                "removed": sum(1 for item in file_results if item.get("status") == "removed"),
                "skipped_missing": sum(1 for item in file_results if item.get("status") == "skipped_missing"),
                "hidden": max(0, len(file_results) - len(visible_results)),
            }
            finish_phase(phase_summaries, "apply_paths", phase_started, path_summary)
            payload = build_payload(args, db_paths, skipped_dbs, disk_paths, records, cleanup_plan, "apply", phase_summaries, {
                "metadata": {
                    "db_results": metadata_results,
                    "requested_rows": metadata_summary["requested_rows"],
                    "deleted_rows": metadata_summary["deleted_rows"],
                },
                "paths": {
                    "requested": path_summary["requested"],
                    "removed": path_summary["removed"],
                    "skipped_missing": path_summary["skipped_missing"],
                    "hidden": path_summary["hidden"],
                    "results": visible_results,
                },
            })
    except Exception as exc:
        log_error(str(exc))
        emit_progress("finish", "failed", {"error": str(exc)})
        return 1
    emit_progress("finish", "done", {
        "mode": payload["mode"],
        "metadata_record_count": payload["summary"]["metadata_record_count"],
        "cleanup": payload["summary"]["cleanup"],
    })
    write_json(payload, args.pretty_json)
    return 0


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
