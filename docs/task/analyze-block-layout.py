#!/usr/bin/env python3
"""JSON-first Tide block layout cleanup implementation.

Supported cleanup types:

1. metadata_missing_path: metadata exists, path is missing; delete metadata only.
2. ttl_expired: metadata exists and ttl expired; delete metadata first, then delete path.
3. untracked_path: path exists on disk but metadata is missing; delete path only.

Default mode is apply. Use --dry-run to print the JSON plan without mutation.
This module intentionally stays Python 3.5 compatible for the py35 entrypoint.
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
            disks.append({
                "disk_path": disk["disk_path"],
                "blocks": disk["blocks"],
                "size": format_bytes(size_bytes),
                "table_percent": round((size_bytes * 100.0 / table["size_bytes"]) if table["size_bytes"] else 0.0, 2),
            })
        partition_dates = []
        for _partition_date, date_stats in sorted(table["partition_dates"].items(), key=lambda item: item[0], reverse=True):
            date_size_bytes = date_stats["size_bytes"]
            partition_dates.append({
                "partition_date": date_stats["partition_date"],
                "blocks": date_stats["blocks"],
                "size": format_bytes(date_size_bytes),
                "table_percent": round((date_size_bytes * 100.0 / table["size_bytes"]) if table["size_bytes"] else 0.0, 2),
                "disk_count": len(date_stats["disks"]),
            })
        rows.append({
            "table": table["table"],
            "blocks": table["blocks"],
            "size": format_bytes(table["size_bytes"]),
            "ttl_seconds": sorted(table["ttl_seconds"]),
            "partition_date_distribution": partition_dates,
            "disk_count": len(disks),
            "metadata_missing_path_blocks": table["metadata_missing_path_blocks"],
            "ttl_expired_blocks": table["ttl_expired_blocks"],
            "disk_distribution": disks,
        })
    return rows


def record_to_json(record):
    expire_at = None
    expired_seconds = None
    if record.ttl is not None and record.partition_time is not None:
        expire_at = record.partition_time + record.ttl
        if record.ttl_expired:
            expired_seconds = max(0, utc_now_epoch() - expire_at)
    return {
        "db_path": record.db_path,
        "tenant": record.tenant,
        "table": record.table,
        "table_key": record.table_key,
        "path": record.path,
        "path_exists": record.path_exists,
        "disk_path": record.disk_path,
        "disk_name": record.disk_name,
        "node_name": record.node_name,
        "data_size": format_bytes(record.data_size),
        "layer": record.layer,
        "event_time": record.event_time,
        "ttl_seconds": record.ttl,
        "partition_time": record.partition_time,
        "partition_date": partition_date_from_epoch(record.partition_time),
        "expire_at": expire_at,
        "expired_seconds": expired_seconds,
    }


def untracked_to_json(item):
    return {
        "path": item.path,
        "disk_path": item.disk_path,
        "data_size": format_bytes(item.data_size),
    }


def limited_json(items, converter, limit):
    visible = items if limit == 0 else items[:limit]
    return {
        "count": len(items),
        "hidden": max(0, len(items) - len(visible)),
        "items": [converter(item) for item in visible],
    }


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


def delete_file_items(items, trace):
    if not items:
        return []
    workers = min(len(items), max(1, os.cpu_count() or 1), MAX_DELETE_WORKERS)
    results = []
    log_trace_event(trace, "delete_path", "executor_start", {"workers": workers, "tasks": len(items)})
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {}
        for task_id, item in enumerate(items, 1):
            task_item = dict(item)
            task_item["task_id"] = task_id
            task_item["_submitted_at"] = time.time()
            log_trace_event(trace, "delete_path", "task_submit", delete_path_trace_details(task_item))
            futures[executor.submit(remove_one_file_item, task_item, trace)] = task_item
        for future in as_completed(futures):
            task_item = futures[future]
            try:
                result = future.result()
            except Exception as exc:
                log_trace_event(trace, "delete_path", "task_collect_error", delete_path_trace_details(task_item, {
                    "error_type": exc.__class__.__name__,
                    "error": str(exc),
                }))
                raise RuntimeError("failed to delete path {}: {}".format(task_item["path"], exc))
            log_trace_event(trace, "delete_path", "task_collect", delete_path_trace_details(result, {
                "status": result["status"],
            }))
            results.append(result)
    log_trace_event(trace, "delete_path", "executor_done", {"result_count": len(results)})
    return sorted(results, key=lambda item: (item["type"], item["disk_path"], item["path"]))


def plan_summary(cleanup_plan):
    missing = cleanup_plan["metadata_missing_path"]
    expired = cleanup_plan["ttl_expired"]
    untracked = cleanup_plan["untracked_path"]
    expired_file_bytes = sum(record.data_size for record in expired if record.path_exists)
    untracked_bytes = sum(item.data_size for item in untracked)
    return {
        "metadata_missing_path": {"count": len(missing), "metadata_delete_count": len(missing)},
        "ttl_expired": {
            "count": len(expired),
            "metadata_delete_count": len(expired),
            "path_delete_count": sum(1 for record in expired if record.path_exists),
            "path_delete_size": format_bytes(expired_file_bytes),
        },
        "untracked_path": {
            "count": len(untracked),
            "path_delete_count": len(untracked),
            "path_delete_size": format_bytes(untracked_bytes),
        },
    }


def build_payload(args, db_paths, skipped_dbs, disk_paths, records, cleanup_plan, mode, phase_summaries, action_results=None):
    total_bytes = sum(record.data_size for record in records)
    payload = OrderedDict()
    payload["report_type"] = "tide_block_layout_cleanup"
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
        "cleanup": plan_summary(cleanup_plan),
    }
    payload["phase_summaries"] = phase_summaries
    payload["cleanup_plan"] = {
        "metadata_missing_path": limited_json(cleanup_plan["metadata_missing_path"], record_to_json, args.sample_limit),
        "ttl_expired": limited_json(cleanup_plan["ttl_expired"], record_to_json, args.sample_limit),
        "untracked_path": limited_json(cleanup_plan["untracked_path"], untracked_to_json, args.sample_limit),
    }
    payload["semantics"] = [
            {"type": "metadata_missing_path", "condition": "metadata row exists and path is missing", "action": "delete metadata only"},
            {"type": "ttl_expired", "condition": "metadata row exists, path exists, and partition_time + ttl < now", "action": "delete metadata first, then delete path"},
            {"type": "untracked_path", "condition": "path exists on disk but metadata row is missing", "action": "delete path only"},
    ]
    if action_results is not None:
        payload["action_results"] = action_results
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
        cleanup_summary = plan_summary(cleanup_plan)
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
    json.dump(payload, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1:]))
