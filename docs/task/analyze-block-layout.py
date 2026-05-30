#!/usr/bin/env python3
"""Analyze Tide block layout distribution and cleanup candidates.

Flow:

    +---------------------+
    | parse CLI arguments |
    +----------+----------+
               |
               v
    +---------------------+
    | discover SQLite DBs |
    +----------+----------+
               |
               v
    +-------------------------------+
    | probe: load DBs sequentially   |
    +----------+--------------------+
               |
               v
    +-----------------------------+
    | parse disk path + block time|
    +----------+------------------+
               |
               v
    +-----------------------------+
    | aggregate table/disk layout |
    +----------+------------------+
               |
               v
    +-----------------------------+
    | mark obsolete/out-of-date   |
    +----------+------------------+
               |
               v
    +------------------------------------+
    | build per-disk metadata path sets  |
    +----------+-------------------------+
               |
               v
    +------------------------------------+
    | probe: scan untracked by disk      |
    +----------+-------------------------+
               |
               v
    +-------------------------------+
    | probe: build execution plan    |
    +----------+--------------------+
               |
               v
    +-------------------------------+
    | build: rm by disk in parallel |
    +----------+--------------------+
               |
               v
    +-------------------------------+
    | build: delete rows per DB     |
    +-------------------------------+
"""

from __future__ import annotations

import argparse
import ctypes
import dataclasses
import datetime as dt
import os
import shutil
import sqlite3
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Iterable


DEFAULT_TIDE_HOME = "/opt/tiger/tide"
MAX_CELL_WIDTH = 72
MIN_CELL_WIDTH = 8
TERMINAL_RIGHT_MARGIN = 2
DELETE_WORK_STEAL_CHUNK_SIZE = 128
MAX_DELETE_WORKERS = 32
LOG_COLOR_RED = "\033[0;31m"
LOG_COLOR_YELLOW = "\033[1;33m"
LOG_COLOR_BLUE = "\033[0;34m"
LOG_COLOR_GREEN = "\033[0;32m"
LOG_COLOR_RESET = "\033[0m"


@dataclasses.dataclass
class BlockRecord:
    db_path: str
    tenant: str
    table: str
    path: str
    data_size: int
    layer: int
    event_time: int
    ttl: int | None
    disk_path: str
    disk_name: str
    node_name: str
    partition_time: int | None
    obsolete: bool
    out_of_date: bool

    @property
    def table_key(self) -> str:
        return f"{self.tenant}.{self.table}"


@dataclasses.dataclass
class TableStats:
    blocks: int = 0
    bytes_total: int = 0
    obsolete_blocks: int = 0
    obsolete_bytes: int = 0
    outdated_blocks: int = 0
    outdated_bytes: int = 0
    disk_bytes: dict[str, int] = dataclasses.field(default_factory=lambda: defaultdict(int))
    disk_blocks: dict[str, int] = dataclasses.field(default_factory=lambda: defaultdict(int))
    ttl_values: set[int] = dataclasses.field(default_factory=set)


@dataclasses.dataclass
class LoadResult:
    db_path: str
    records: list[BlockRecord]
    duration: float


@dataclasses.dataclass
class CleanupResult:
    db_path: str
    deleted: int
    duration: float


@dataclasses.dataclass
class RemoveResult:
    disk_path: str
    removed: int
    skipped: int
    duration: float


@dataclasses.dataclass
class FileRemovalCandidate:
    path: str
    disk_path: str
    data_size: int


@dataclasses.dataclass
class RemoveTask:
    resource_key: str
    disk_path: str
    candidates: list[FileRemovalCandidate]


@dataclasses.dataclass
class UntrackedBlock:
    path: str
    disk_path: str
    data_size: int


@dataclasses.dataclass
class ExecutionPlan:
    dry_run: bool
    cleanup_requested: bool
    selected_records: list[BlockRecord]
    selected_untracked: list[UntrackedBlock]
    file_removal_candidates: list[FileRemovalCandidate]
    remove_plan: dict[str, list[FileRemovalCandidate]]
    db_delete_plan: dict[str, set[str]]

    @property
    def file_removal_count(self) -> int:
        return sum(len(candidates) for candidates in self.remove_plan.values())

    @property
    def db_delete_count(self) -> int:
        return sum(len(paths) for paths in self.db_delete_plan.values())


def log_timestamp() -> str:
    return dt.datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def log_thread_id() -> int:
    native_id = getattr(threading, "get_native_id", None)
    if native_id is not None:
        return int(native_id())

    if sys.platform.startswith("linux"):
        libc = ctypes.CDLL(None, use_errno=True)
        gettid = getattr(libc, "gettid", None)
        if gettid is not None:
            gettid.restype = ctypes.c_long
            tid = int(gettid())
            if tid > 0:
                return tid

        syscall = getattr(libc, "syscall", None)
        if syscall is not None:
            syscall.restype = ctypes.c_long
            machine = os.uname().machine.lower()
            sys_gettid_by_machine = {
                "x86_64": 186,
                "amd64": 186,
                "i386": 224,
                "i686": 224,
                "aarch64": 178,
                "arm64": 178,
                "armv7l": 224,
                "armv6l": 224,
                "ppc64le": 207,
                "s390x": 236,
            }
            sys_gettid = sys_gettid_by_machine.get(machine)
            if sys_gettid is not None:
                tid = int(syscall(ctypes.c_long(sys_gettid)))
                if tid > 0:
                    return tid

    return threading.get_ident()


def colorize(value: str, color: str) -> str:
    if not sys.stderr.isatty():
        return value
    return f"{color}{value}{LOG_COLOR_RESET}"


def log_event(level: str, message: str, resource_key: str | None = None) -> None:
    level_colors = {
        "ERROR": LOG_COLOR_RED,
        "WARN": LOG_COLOR_YELLOW,
        "TRACE": LOG_COLOR_BLUE,
        "OK": LOG_COLOR_GREEN,
    }
    level_text = colorize(f"{level:<5}", level_colors.get(level, ""))
    fields = [
        log_timestamp(),
        f"pid={os.getpid()}",
        f"tid={log_thread_id()}",
        level_text,
    ]
    if resource_key is not None:
        fields.append(f"resource={resource_key}")
    fields.append(message)
    print(" ".join(fields), file=sys.stderr)


def log_error(message: str) -> None:
    log_event("ERROR", message)


def log_warn(message: str) -> None:
    log_event("WARN", message)


def log_trace(enabled: bool, resource_key: str, message: str) -> None:
    if enabled:
        log_event("TRACE", message, resource_key)


def format_bytes(size: int) -> str:
    units = ("B", "KiB", "MiB", "GiB", "TiB")
    value = float(size)
    for unit in units:
        if abs(value) < 1024.0 or unit == units[-1]:
            if unit == "B":
                return f"{int(value)} {unit}"
            return f"{value:.2f} {unit}"
        value /= 1024.0
    return f"{size} B"


def format_ttl_values(ttl_values: set[int]) -> str:
    if not ttl_values:
        return "-"

    formatted = []
    for ttl in sorted(ttl_values):
        if ttl % 86400 == 0:
            formatted.append(f"{ttl}s/{ttl // 86400}d")
        elif ttl % 3600 == 0:
            formatted.append(f"{ttl}s/{ttl // 3600}h")
        else:
            formatted.append(f"{ttl}s")
    return ",".join(formatted)


def format_duration(seconds: int) -> str:
    seconds = max(0, seconds)
    days, remainder = divmod(seconds, 86400)
    hours, remainder = divmod(remainder, 3600)
    minutes, seconds = divmod(remainder, 60)

    parts = []
    if days:
        parts.append(f"{days}d")
    if hours:
        parts.append(f"{hours}h")
    if minutes and not days:
        parts.append(f"{minutes}m")
    if not parts:
        parts.append(f"{seconds}s")
    return " ".join(parts)


def format_ttl_value(ttl: int | None) -> str:
    if ttl is None:
        return "-"
    return format_ttl_values({ttl})


def format_block_size_pair(blocks: int, size: int) -> str:
    return f"{blocks} blocks\n{format_bytes(size)}"


def format_expire_duration(record: BlockRecord, now_epoch: int) -> str:
    if record.ttl is None or record.partition_time is None:
        return "-"

    expire_at = record.partition_time + record.ttl
    if expire_at < now_epoch:
        return f"ttl {format_ttl_value(record.ttl)}\nexpired {format_duration(now_epoch - expire_at)} ago"
    return f"ttl {format_ttl_value(record.ttl)}\nexpires in {format_duration(expire_at - now_epoch)}"


def wrap_cell(value: object, width: int) -> list[str]:
    text = str(value)
    if width <= 0:
        return [text]
    if text == "":
        return [""]

    wrapped_lines = []
    for segment in text.splitlines():
        wrapped_lines.extend(wrap_cell_segment(segment, width))
    return wrapped_lines or [""]


def wrap_cell_segment(text: str, width: int) -> list[str]:
    lines = []
    remaining = text
    while len(remaining) > width:
        cut = remaining.rfind("/", 1, width + 1)
        if cut <= 0 or cut < width // 2:
            cut = width
        else:
            cut += 1
        lines.append(remaining[:cut])
        remaining = remaining[cut:]
    if remaining:
        lines.append(remaining)
    return lines


def output_width() -> int:
    columns = shutil.get_terminal_size((120, 24)).columns
    return max(60, columns - TERMINAL_RIGHT_MARGIN)


def table_border_width(column_count: int) -> int:
    return 3 * column_count + 1


def fit_column_widths(headers: list[str], rows: list[list[str]], max_width: int) -> list[int]:
    widths = [len(header) for header in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], len(cell))

    table_padding = table_border_width(len(headers))
    available_content_width = max_width - table_padding
    if sum(widths) <= available_content_width:
        return widths

    min_widths = [max(MIN_CELL_WIDTH, min(len(header), MAX_CELL_WIDTH)) for header in headers]
    while sum(widths) > available_content_width:
        shrink_index = max(range(len(widths)), key=lambda index: widths[index] - min_widths[index])
        if widths[shrink_index] <= min_widths[shrink_index]:
            break
        widths[shrink_index] -= 1

    return widths


def print_table(title: str, headers: list[str], rows: list[list[object]], group_by: int = 0) -> None:
    print()
    print(title)
    if not rows:
        print("  <none>")
        return

    max_width = output_width()
    string_rows = [[str(cell) for cell in row] for row in rows]
    widths = fit_column_widths(headers, string_rows, max_width)

    border = "+-" + "-+-".join("-" * width for width in widths) + "-+"
    header_line = "| " + " | ".join(header.ljust(widths[index]) for index, header in enumerate(headers)) + " |"

    print(border)
    print(header_line)
    print(border)
    previous_group = None
    for row_index, row in enumerate(string_rows):
        current_group = tuple(row[:group_by]) if group_by > 0 else (row_index,)
        if row_index > 0 and current_group != previous_group:
            print(border)

        wrapped_cells = [wrap_cell(cell, widths[index]) for index, cell in enumerate(row)]
        row_height = max(len(cell_lines) for cell_lines in wrapped_cells)
        for line_index in range(row_height):
            line_cells = []
            for column_index, cell_lines in enumerate(wrapped_cells):
                cell = cell_lines[line_index] if line_index < len(cell_lines) else ""
                line_cells.append(cell.ljust(widths[column_index]))
            print("| " + " | ".join(line_cells) + " |")
        previous_group = current_group
    print(border)


def discover_dbs(tide_home: str, explicit_dbs: list[str]) -> list[str]:
    if explicit_dbs:
        return [str(Path(db)) for db in explicit_dbs]

    tide_path = Path(tide_home)
    return sorted(str(path) for path in tide_path.glob("node*/metadata/fringedb.db") if path.is_file())


def discover_logical_disk_paths(tide_home: str) -> list[str]:
    tide_path = Path(tide_home)
    disk_paths: list[str] = []
    for node_path in sorted(tide_path.glob("node*")):
        if not node_path.is_dir():
            continue
        for disk_path in sorted(node_path.glob("disk[0-9][0-9]")):
            if disk_path.name == "disk00":
                continue
            if disk_path.is_dir():
                disk_paths.append(str(disk_path))
        for disk_path in sorted(node_path.glob("ssd_disks_[0-9][0-9]")):
            if disk_path.is_dir():
                disk_paths.append(str(disk_path))
    return disk_paths


def ensure_schema(conn: sqlite3.Connection, db_path: str) -> None:
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tables', 'block_records')"
    ).fetchall()
    names = {row[0] for row in rows}
    missing = {"tables", "block_records"} - names
    if missing:
        raise RuntimeError(f"{db_path} missing required tables: {', '.join(sorted(missing))}")


def load_table_ttls(conn: sqlite3.Connection) -> dict[tuple[str, str], int]:
    ttl_by_table: dict[tuple[str, str], int] = {}
    for tenant, table_name, ttl in conn.execute('SELECT tenant, "table", ttl FROM tables'):
        ttl_by_table[(tenant, table_name)] = int(ttl)
    return ttl_by_table


def parse_disk_path(block_path: str, tide_home: str) -> tuple[str, str, str]:
    normalized_home = tide_home.rstrip("/")
    prefix = f"{normalized_home}/"
    if not block_path.startswith(prefix):
        return ("<outside-tide-home>", "<unknown>", "<unknown>")

    parts = block_path[len(prefix) :].split("/")
    if len(parts) < 4:
        return ("<unparsed>", "<unknown>", "<unknown>")

    node_name = parts[0]
    disk_name = parts[1]
    if not (
        node_name.startswith("node")
        and (disk_name.startswith("disk") or disk_name.startswith("ssd_disks_"))
    ):
        return ("<unparsed>", "<unknown>", "<unknown>")

    return (f"{normalized_home}/{node_name}/{disk_name}", disk_name, node_name)


def parse_partition_time(block_path: str) -> int | None:
    block_id = os.path.basename(block_path.rstrip("/"))
    head = block_id.rsplit("_", 3)[0]
    if "_" not in head:
        return None

    _partition_id, partition_text = head.split("_", 1)
    try:
        parsed = dt.datetime.strptime(partition_text, "%Y-%m-%d_%H:%M:%S")
    except ValueError:
        return None

    return int(parsed.replace(tzinfo=dt.timezone.utc).timestamp())


def load_blocks(db_path: str, tide_home: str, now_epoch: int) -> list[BlockRecord]:
    conn = sqlite3.connect(db_path)
    try:
        ensure_schema(conn, db_path)
        ttl_by_table = load_table_ttls(conn)
        rows = conn.execute(
            'SELECT tenant, "table", path, data_size, layer, event_time FROM block_records'
        ).fetchall()
    finally:
        conn.close()

    records: list[BlockRecord] = []
    for tenant, table_name, path, data_size, layer, event_time in rows:
        ttl = ttl_by_table.get((tenant, table_name))
        disk_path, disk_name, node_name = parse_disk_path(path, tide_home)
        partition_time = parse_partition_time(path)
        obsolete = not os.path.exists(path)
        out_of_date = False
        if ttl is not None and partition_time is not None:
            out_of_date = partition_time + ttl < now_epoch

        records.append(
            BlockRecord(
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
                obsolete=obsolete,
                out_of_date=out_of_date,
            )
        )
    return records


def load_blocks_task(db_path: str, tide_home: str, now_epoch: int, trace: bool) -> LoadResult:
    started_at = time.monotonic()
    log_trace(trace, db_path, "start load block metadata")
    records = load_blocks(db_path, tide_home, now_epoch)
    duration = time.monotonic() - started_at
    log_trace(trace, db_path, f"done load blocks={len(records)} duration={duration:.2f}s")
    return LoadResult(db_path=db_path, records=records, duration=duration)


def load_all_blocks(db_paths: list[str], tide_home: str, now_epoch: int, trace: bool) -> list[BlockRecord]:
    valid_db_paths: list[str] = []
    for db_path in db_paths:
        if not Path(db_path).is_file():
            log_warn(f"Skip missing DB: {db_path}")
            continue
        valid_db_paths.append(db_path)

    if not valid_db_paths:
        return []

    records: list[BlockRecord] = []
    for db_path in sorted(valid_db_paths):
        try:
            result = load_blocks_task(db_path, tide_home, now_epoch, trace)
        except Exception as exc:
            raise RuntimeError(f"{db_path} failed to load: {exc}") from exc
        records.extend(result.records)
    return records


def aggregate_table_stats(records: Iterable[BlockRecord]) -> dict[str, TableStats]:
    stats: dict[str, TableStats] = defaultdict(TableStats)
    for record in records:
        table_stats = stats[record.table_key]
        table_stats.blocks += 1
        table_stats.bytes_total += record.data_size
        table_stats.disk_bytes[record.disk_path] += record.data_size
        table_stats.disk_blocks[record.disk_path] += 1
        if record.ttl is not None:
            table_stats.ttl_values.add(record.ttl)

        if record.obsolete:
            table_stats.obsolete_blocks += 1
            table_stats.obsolete_bytes += record.data_size
        if record.out_of_date:
            table_stats.outdated_blocks += 1
            table_stats.outdated_bytes += record.data_size
    return stats


def candidate_reasons(record: BlockRecord, include_obsolete: bool, include_outdated: bool) -> list[str]:
    reasons: list[str] = []
    if include_obsolete and record.obsolete:
        reasons.append("obsolete")
    if include_outdated and record.out_of_date:
        reasons.append("out-of-date")
    return reasons


def selected_candidates(
    records: list[BlockRecord],
    include_obsolete: bool,
    include_outdated: bool,
) -> list[BlockRecord]:
    return [record for record in records if candidate_reasons(record, include_obsolete, include_outdated)]


def normalize_path(path: str) -> str:
    return path.rstrip("/")


def path_size(path: str) -> int:
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
                continue
    return total


def is_safe_block_path(record: BlockRecord, tide_home: str) -> bool:
    normalized_home = tide_home.rstrip("/")
    if not record.path.startswith(f"{normalized_home}/node"):
        return False
    if record.disk_path in ("<outside-tide-home>", "<unparsed>"):
        return False
    if not record.path.startswith(f"{record.disk_path}/"):
        return False
    if record.path in ("", "/", normalized_home, record.disk_path):
        return False
    return True


def is_safe_path_under_disk(path: str, disk_path: str, tide_home: str) -> bool:
    normalized_home = tide_home.rstrip("/")
    if not path.startswith(f"{normalized_home}/node"):
        return False
    if disk_path in ("<outside-tide-home>", "<unparsed>"):
        return False
    if not path.startswith(f"{disk_path}/"):
        return False
    if path in ("", "/", normalized_home, disk_path):
        return False
    return True


def build_known_paths_by_disk(records: Iterable[BlockRecord]) -> dict[str, set[str]]:
    known_paths_by_disk: dict[str, set[str]] = defaultdict(set)
    for record in records:
        if record.disk_path in ("<outside-tide-home>", "<unparsed>"):
            continue
        known_paths_by_disk[record.disk_path].add(normalize_path(record.path))
    return known_paths_by_disk


def discover_block_table_paths_for_disk(disk_path: str) -> list[str]:
    table_paths: list[str] = []
    disk = Path(disk_path)
    if not disk.is_dir():
        return table_paths
    try:
        tenant_paths = sorted(disk.iterdir())
    except OSError:
        return table_paths
    for tenant_path in tenant_paths:
        if not tenant_path.is_dir():
            continue
        try:
            children = sorted(tenant_path.iterdir())
        except OSError:
            continue
        for table_path in children:
            if table_path.is_dir():
                table_paths.append(str(table_path))
    return table_paths


def scan_table_untracked_blocks(
    disk_path: str,
    table_path: str,
    known_paths: set[str],
    tide_home: str,
    trace: bool,
) -> list[UntrackedBlock]:
    log_trace(trace, table_path, "start scan untracked blocks")
    untracked: list[UntrackedBlock] = []
    table = Path(table_path)
    if not table.is_dir():
        return untracked

    try:
        block_paths = sorted(table.iterdir())
    except OSError:
        return untracked

    for block_path in block_paths:
        if not block_path.exists():
            continue
        block_path_text = normalize_path(str(block_path))
        if parse_partition_time(block_path_text) is None:
            continue
        if block_path_text in known_paths:
            continue
        if not is_safe_path_under_disk(block_path_text, disk_path, tide_home):
            raise RuntimeError(f"Unsafe untracked block path for removal: {block_path_text}")
        untracked.append(
            UntrackedBlock(
                path=block_path_text,
                disk_path=disk_path,
                data_size=path_size(block_path_text),
            )
        )

    log_trace(trace, table_path, f"done scan untracked blocks={len(untracked)}")
    return untracked


def scan_disk_untracked_blocks(
    disk_path: str,
    known_paths: set[str],
    tide_home: str,
    trace: bool,
) -> list[UntrackedBlock]:
    started_at = time.monotonic()
    table_paths = discover_block_table_paths_for_disk(disk_path)
    log_trace(trace, disk_path, f"start scan untracked tables={len(table_paths)} known_paths={len(known_paths)}")
    untracked: list[UntrackedBlock] = []
    for table_path in table_paths:
        untracked.extend(scan_table_untracked_blocks(disk_path, table_path, known_paths, tide_home, trace))
    duration = time.monotonic() - started_at
    log_trace(trace, disk_path, f"done scan untracked blocks={len(untracked)} duration={duration:.2f}s")
    return untracked


def discover_untracked_blocks(
    tide_home: str,
    disk_paths: list[str],
    known_paths_by_disk: dict[str, set[str]],
    trace: bool,
) -> list[UntrackedBlock]:
    if not disk_paths:
        return []

    log_trace(trace, "untracked", f"resource=disk tasks={len(disk_paths)}")
    results: list[UntrackedBlock] = []
    with ThreadPoolExecutor(max_workers=len(disk_paths)) as executor:
        futures = {
            executor.submit(
                scan_disk_untracked_blocks,
                disk_path,
                known_paths_by_disk.get(disk_path, set()),
                tide_home,
                trace,
            ): disk_path
            for disk_path in disk_paths
        }
        for future in as_completed(futures):
            disk_path = futures[future]
            try:
                results.extend(future.result())
            except Exception as exc:
                raise RuntimeError(f"{disk_path} failed to scan untracked blocks: {exc}") from exc

    return sorted(results, key=lambda item: (item.disk_path, item.path))


def file_candidates_from_records(records: list[BlockRecord], tide_home: str) -> list[FileRemovalCandidate]:
    candidates: list[FileRemovalCandidate] = []
    for record in records:
        if not os.path.exists(record.path):
            continue
        if not is_safe_block_path(record, tide_home):
            raise RuntimeError(f"Unsafe block path for removal: {record.path}")
        candidates.append(
            FileRemovalCandidate(
                path=record.path,
                disk_path=record.disk_path,
                data_size=record.data_size,
            )
        )
    return candidates


def file_candidates_from_untracked(blocks: list[UntrackedBlock]) -> list[FileRemovalCandidate]:
    return [
        FileRemovalCandidate(path=block.path, disk_path=block.disk_path, data_size=block.data_size)
        for block in blocks
    ]


def build_remove_plan(candidates: list[FileRemovalCandidate], tide_home: str) -> dict[str, list[FileRemovalCandidate]]:
    plan: dict[str, list[FileRemovalCandidate]] = defaultdict(list)
    for candidate in candidates:
        if not os.path.exists(candidate.path):
            continue
        if not is_safe_path_under_disk(candidate.path, candidate.disk_path, tide_home):
            raise RuntimeError(f"Unsafe block path for removal: {candidate.path}")
        plan[candidate.disk_path].append(candidate)
    return plan


def is_hdd_disk_path(disk_path: str) -> bool:
    return Path(disk_path).name.startswith("disk")


def chunked_candidates(
    candidates: list[FileRemovalCandidate],
    chunk_size: int = DELETE_WORK_STEAL_CHUNK_SIZE,
) -> Iterable[list[FileRemovalCandidate]]:
    for index in range(0, len(candidates), chunk_size):
        yield candidates[index:index + chunk_size]


def build_remove_tasks(remove_plan: dict[str, list[FileRemovalCandidate]]) -> list[RemoveTask]:
    tasks: list[RemoveTask] = []
    for disk_path, disk_candidates in sorted(remove_plan.items()):
        sorted_candidates = sorted(disk_candidates, key=lambda item: item.path)
        if is_hdd_disk_path(disk_path) and len(sorted_candidates) > DELETE_WORK_STEAL_CHUNK_SIZE:
            for index, chunk in enumerate(chunked_candidates(sorted_candidates), start=1):
                tasks.append(
                    RemoveTask(
                        resource_key=f"{disk_path}#delete-{index}",
                        disk_path=disk_path,
                        candidates=chunk,
                    )
                )
            continue
        tasks.append(
            RemoveTask(
                resource_key=disk_path,
                disk_path=disk_path,
                candidates=sorted_candidates,
            )
        )
    return tasks


def resolve_delete_workers(tasks: list[RemoveTask], disk_count: int) -> int:
    if not tasks:
        return 1
    cpu_count = os.cpu_count() or 1
    natural_workers = max(disk_count, cpu_count)
    return min(len(tasks), natural_workers, MAX_DELETE_WORKERS)


def aggregate_remove_results(results: list[RemoveResult]) -> list[RemoveResult]:
    by_disk: dict[str, RemoveResult] = {}
    for result in results:
        current = by_disk.get(result.disk_path)
        if current is None:
            by_disk[result.disk_path] = RemoveResult(
                disk_path=result.disk_path,
                removed=result.removed,
                skipped=result.skipped,
                duration=result.duration,
            )
            continue
        current.removed += result.removed
        current.skipped += result.skipped
        current.duration += result.duration
    return sorted(by_disk.values(), key=lambda item: item.disk_path)


def remove_path(path: str) -> None:
    if os.path.isdir(path) and not os.path.islink(path):
        shutil.rmtree(path)
        return
    os.unlink(path)


def remove_task_candidates(task: RemoveTask, trace: bool) -> RemoveResult:
    started_at = time.monotonic()
    log_trace(trace, task.resource_key, f"start rm disk={task.disk_path} candidates={len(task.candidates)}")
    removed = 0
    skipped = 0
    for candidate in task.candidates:
        if not os.path.exists(candidate.path):
            skipped += 1
            continue
        remove_path(candidate.path)
        removed += 1
    duration = time.monotonic() - started_at
    log_trace(
        trace,
        task.resource_key,
        f"done rm disk={task.disk_path} removed={removed} skipped={skipped} duration={duration:.2f}s",
    )
    return RemoveResult(disk_path=task.disk_path, removed=removed, skipped=skipped, duration=duration)


def remove_block_files(
    remove_plan: dict[str, list[FileRemovalCandidate]],
    trace: bool,
) -> list[RemoveResult]:
    if not remove_plan:
        return []

    tasks = build_remove_tasks(remove_plan)
    worker_count = resolve_delete_workers(tasks, len(remove_plan))
    steal_tasks = sum(1 for task in tasks if "#" in task.resource_key)
    log_trace(
        trace,
        "rm",
        f"tasks={len(tasks)} disks={len(remove_plan)} workers={worker_count} work_steal_tasks={steal_tasks}",
    )
    results: list[RemoveResult] = []
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        futures = {
            executor.submit(remove_task_candidates, task, trace): task.resource_key
            for task in tasks
        }
        for future in as_completed(futures):
            resource_key = futures[future]
            try:
                results.append(future.result())
            except Exception as exc:
                raise RuntimeError(f"{resource_key} failed to remove block files: {exc}") from exc

    return aggregate_remove_results(results)


def delete_db_candidates(db_path: str, paths: set[str], trace: bool) -> CleanupResult:
    started_at = time.monotonic()
    log_trace(trace, db_path, f"start cleanup candidates={len(paths)}")
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
    duration = time.monotonic() - started_at
    log_trace(trace, db_path, f"done cleanup deleted={deleted} duration={duration:.2f}s")
    return CleanupResult(db_path=db_path, deleted=deleted, duration=duration)


def build_db_delete_plan(records: list[BlockRecord], include_obsolete: bool, include_outdated: bool) -> dict[str, set[str]]:
    paths_by_db: dict[str, set[str]] = defaultdict(set)
    for record in selected_candidates(records, include_obsolete, include_outdated):
        paths_by_db[record.db_path].add(record.path)
    return paths_by_db


def delete_candidates(db_delete_plan: dict[str, set[str]], trace: bool) -> int:
    paths_by_db = db_delete_plan
    if not paths_by_db:
        return 0

    results: list[CleanupResult] = []
    for db_path, paths in sorted(paths_by_db.items()):
        try:
            results.append(delete_db_candidates(db_path, paths, trace))
        except Exception as exc:
            raise RuntimeError(f"{db_path} failed to cleanup: {exc}") from exc

    deleted = 0
    for result in sorted(results, key=lambda item: item.db_path):
        deleted += result.deleted
    return deleted


def build_summary_rows(stats: dict[str, TableStats], top: int) -> list[list[object]]:
    rows: list[list[object]] = []
    sorted_items = sorted(stats.items(), key=lambda item: item[0])
    for table_key, table_stats in sorted_items[:top]:
        rows.append(
            [
                table_key,
                format_ttl_values(table_stats.ttl_values),
                table_stats.blocks,
                format_bytes(table_stats.bytes_total),
                format_block_size_pair(table_stats.obsolete_blocks, table_stats.obsolete_bytes),
                format_block_size_pair(table_stats.outdated_blocks, table_stats.outdated_bytes),
                len(table_stats.disk_bytes),
            ]
        )
    return rows


def build_distribution_rows(stats: dict[str, TableStats], top: int) -> list[list[object]]:
    rows: list[list[object]] = []
    sorted_tables = sorted(stats.items(), key=lambda item: item[0])
    for table_key, table_stats in sorted_tables[:top]:
        sorted_disks = sorted(table_stats.disk_bytes.items(), key=lambda item: item[0])
        for disk_path, disk_bytes in sorted_disks:
            percent = 0.0
            if table_stats.bytes_total > 0:
                percent = disk_bytes * 100.0 / table_stats.bytes_total
            rows.append(
                [
                    table_key,
                    disk_path,
                    table_stats.disk_blocks[disk_path],
                    format_bytes(disk_bytes),
                    f"{percent:.2f}%",
                ]
            )
    return rows


def build_candidate_rows(
    candidates: list[BlockRecord],
    include_obsolete: bool,
    include_outdated: bool,
    limit: int,
    now_epoch: int,
) -> list[list[object]]:
    rows: list[list[object]] = []
    visible_candidates = candidates if limit == 0 else candidates[:limit]
    for record in visible_candidates:
        reasons = ",".join(candidate_reasons(record, include_obsolete, include_outdated))
        rows.append(
            [
                record.table_key,
                record.disk_path,
                reasons,
                format_bytes(record.data_size),
                format_expire_duration(record, now_epoch),
                record.path,
            ]
        )
    return rows


def build_untracked_rows(candidates: list[UntrackedBlock], limit: int) -> list[list[object]]:
    rows: list[list[object]] = []
    visible_candidates = candidates if limit == 0 else candidates[:limit]
    for candidate in visible_candidates:
        rows.append(
            [
                candidate.disk_path,
                format_bytes(candidate.data_size),
                candidate.path,
            ]
        )
    return rows


def build_remove_group_rows(remove_plan: dict[str, list[FileRemovalCandidate]]) -> list[list[object]]:
    rows: list[list[object]] = []
    for disk_path, disk_candidates in sorted(remove_plan.items()):
        total_size = sum(candidate.data_size for candidate in disk_candidates)
        rows.append([disk_path, len(disk_candidates), format_bytes(total_size)])
    return rows


def cleanup_requested(args: argparse.Namespace) -> bool:
    return args.delete_obsolete or args.delete_out_of_date or args.delete_untracked


def build_execution_plan(
    args: argparse.Namespace,
    records: list[BlockRecord],
    untracked_blocks: list[UntrackedBlock],
) -> ExecutionPlan:
    requested = cleanup_requested(args)
    dry_run = args.dry_run or not requested
    no_cleanup_filter = not requested
    include_obsolete = args.delete_obsolete or no_cleanup_filter
    include_outdated = args.delete_out_of_date or no_cleanup_filter
    include_untracked = args.delete_untracked or no_cleanup_filter

    selected_records = selected_candidates(records, include_obsolete, include_outdated)
    selected_records = sorted(selected_records, key=lambda record: (record.table_key, record.disk_path, record.path))
    selected_untracked = untracked_blocks if include_untracked else []

    records_for_file_removal = [
        record
        for record in selected_records
        if args.remove_block_files or (args.delete_out_of_date and record.out_of_date)
    ]
    file_removal_candidates = file_candidates_from_records(records_for_file_removal, args.tide_home)
    if args.delete_untracked:
        file_removal_candidates.extend(file_candidates_from_untracked(selected_untracked))

    remove_plan: dict[str, list[FileRemovalCandidate]] = {}
    if file_removal_candidates:
        remove_plan = build_remove_plan(file_removal_candidates, args.tide_home)

    db_delete_plan = build_db_delete_plan(records, args.delete_obsolete, args.delete_out_of_date)

    return ExecutionPlan(
        dry_run=dry_run,
        cleanup_requested=requested,
        selected_records=selected_records,
        selected_untracked=selected_untracked,
        file_removal_candidates=file_removal_candidates,
        remove_plan=remove_plan,
        db_delete_plan=db_delete_plan,
    )


def build_execution_plan_rows(plan: ExecutionPlan, metadata_db_count: int, disk_count: int) -> list[list[object]]:
    phase = "dry-run" if plan.dry_run else "build"
    rows = [
        ["probe", "metadata-db", "load block_records once", metadata_db_count],
        ["probe", "disk", "scan untracked blocks with per-disk path set", disk_count],
        [phase, "disk", "remove block files with HDD work stealing", plan.file_removal_count],
        [phase, "metadata-db", "delete selected metadata rows sequentially by DB", plan.db_delete_count],
    ]
    return rows


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze Tide block distribution and cleanup candidates from fringedb.db metadata.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("-d", "--db", action="append", default=[], help="SQLite fringedb.db path; repeatable")
    parser.add_argument(
        "-t",
        "--tide-home",
        default=os.environ.get("TIDE_HOME", DEFAULT_TIDE_HOME),
        help="TIDE_HOME used to discover DBs and parse logical disk paths",
    )
    parser.add_argument(
        "-n",
        "--dry-run",
        action="store_true",
        default=False,
        help="Print the execution plan without deleting files or metadata rows",
    )
    parser.add_argument(
        "-o",
        "--delete-obsolete",
        action="store_true",
        help="Delete rows whose block path does not exist",
    )
    parser.add_argument(
        "-e",
        "--delete-out-of-date",
        action="store_true",
        help="Delete rows whose partition_time + ttl is smaller than current time",
    )
    parser.add_argument(
        "-u",
        "--delete-untracked",
        action="store_true",
        help="Delete real block paths that exist on disk but are not recorded in SQLite",
    )
    parser.add_argument(
        "-r",
        "--remove-block-files",
        action="store_true",
        help="Also remove existing selected DB-recorded block paths before deleting SQLite rows",
    )
    parser.add_argument("--now", type=int, default=int(dt.datetime.now(dt.timezone.utc).timestamp()), help="Override current epoch seconds")
    parser.add_argument("--trace", action="store_true", help="Print per-resource task traces to stderr")
    parser.add_argument("-p", "--top", type=int, default=50, help="Maximum number of tables to show")
    parser.add_argument(
        "-c",
        "--candidate-limit",
        type=int,
        default=50,
        help="Maximum cleanup candidates to show; 0 means show all",
    )
    return parser.parse_args(argv)


def validate_args(args: argparse.Namespace) -> None:
    if args.top <= 0:
        raise SystemExit("--top must be positive")
    if args.candidate_limit < 0:
        raise SystemExit("--candidate-limit must be zero or positive")


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    validate_args(args)

    db_paths = discover_dbs(args.tide_home, args.db)
    if not db_paths:
        log_error(f"No metadata DB found under {args.tide_home}/node*/metadata/fringedb.db")
        return 1

    try:
        records = load_all_blocks(db_paths, args.tide_home, args.now, args.trace)
    except RuntimeError as exc:
        log_error(str(exc))
        return 1

    stats = aggregate_table_stats(records)
    obsolete_count = sum(1 for record in records if record.obsolete)
    outdated_count = sum(1 for record in records if record.out_of_date)
    total_bytes = sum(record.data_size for record in records)
    known_paths_by_disk = build_known_paths_by_disk(records)
    disk_paths = discover_logical_disk_paths(args.tide_home)
    try:
        untracked_blocks = discover_untracked_blocks(args.tide_home, disk_paths, known_paths_by_disk, args.trace)
    except RuntimeError as exc:
        log_error(str(exc))
        return 1

    try:
        plan = build_execution_plan(args, records, untracked_blocks)
    except RuntimeError as exc:
        log_error(str(exc))
        return 1

    no_cleanup_filter = not plan.cleanup_requested
    include_obsolete = args.delete_obsolete or no_cleanup_filter
    include_outdated = args.delete_out_of_date or no_cleanup_filter
    include_untracked = args.delete_untracked or no_cleanup_filter
    visible_candidate_count = len(plan.selected_records) if args.candidate_limit == 0 else min(
        len(plan.selected_records), args.candidate_limit
    )
    visible_untracked_count = len(plan.selected_untracked) if args.candidate_limit == 0 else min(
        len(plan.selected_untracked), args.candidate_limit
    )

    report_time = dt.datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %Z")
    print(f"Block Layout Analysis - {report_time}")
    print(f"Mode              : {'DRY-RUN' if plan.dry_run else 'BUILD'}")
    print(f"TIDE_HOME         : {args.tide_home}")
    print(f"Metadata DBs      : {len(db_paths)}")
    print(f"Disk Tasks        : {len(disk_paths)}")
    print(f"Remove Block Files: {'yes' if plan.file_removal_candidates else 'no'}")
    print(f"Tables            : {len(stats)}")
    print(f"Blocks            : {len(records)}")
    print(f"Total Size        : {format_bytes(total_bytes)}")
    print(f"Obsolete Blocks   : {obsolete_count}")
    print(f"Out-of-date Blocks: {outdated_count}")
    print(f"Untracked Blocks  : {len(untracked_blocks)}")
    print(f"Cleanup Candidates: {len(plan.selected_records)} total, showing {visible_candidate_count}")
    print(f"Untracked Selected: {len(plan.selected_untracked)} total, showing {visible_untracked_count}")

    print_table(
        "Execution Plan",
        ["PHASE", "RESOURCE", "ACTION", "COUNT"],
        build_execution_plan_rows(plan, len(db_paths), len(disk_paths)),
        group_by=1,
    )

    print_table(
        "Table Distribution Summary",
        ["TABLE", "TTL", "BLOCKS", "SIZE", "OBSOLETE", "OUT-OF-DATE", "DISKS"],
        build_summary_rows(stats, args.top),
        group_by=1,
    )
    print_table(
        "Table Disk Distribution",
        ["TABLE", "DISK", "BLOCKS", "SIZE", "%TABLE"],
        build_distribution_rows(stats, args.top),
        group_by=1,
    )

    show_candidates = include_obsolete or include_outdated
    if show_candidates:
        print_table(
            f"Cleanup Candidates (showing {visible_candidate_count} of {len(plan.selected_records)})",
            ["TABLE", "DISK", "REASON", "SIZE", "TTL / EXPIRE_DURATION", "PATH"],
            build_candidate_rows(plan.selected_records, include_obsolete, include_outdated, args.candidate_limit, args.now),
            group_by=1,
        )
        if visible_candidate_count < len(plan.selected_records):
            print(f"  More candidates hidden. Use --candidate-limit 0 to show all {len(plan.selected_records)} candidates.")
    if include_untracked:
        print_table(
            f"Untracked Block Candidates (showing {visible_untracked_count} of {len(plan.selected_untracked)})",
            ["DISK", "SIZE", "PATH"],
            build_untracked_rows(plan.selected_untracked, args.candidate_limit),
            group_by=1,
        )
        if visible_untracked_count < len(plan.selected_untracked):
            print(f"  More untracked blocks hidden. Use --candidate-limit 0 to show all {len(plan.selected_untracked)} candidates.")
    if plan.file_removal_candidates:
        print_table(
            "Block File Remove Plan",
            ["DISK", "BLOCKS", "SIZE"],
            build_remove_group_rows(plan.remove_plan),
            group_by=1,
        )

    if plan.dry_run:
        if plan.cleanup_requested:
            print()
            print("Dry-run: selected cleanup actions were not applied.")
        else:
            print()
            print("Probe only: pass cleanup options to build an executable mutation plan.")
        return 0

    removed = 0
    if plan.remove_plan:
        try:
            remove_results = remove_block_files(plan.remove_plan, args.trace)
        except RuntimeError as exc:
            log_error(str(exc))
            return 1
        removed = sum(result.removed for result in remove_results)

    try:
        deleted = delete_candidates(plan.db_delete_plan, args.trace)
    except RuntimeError as exc:
        log_error(str(exc))
        return 1
    print()
    print(f"Built block paths removed: {removed}")
    print(f"Built cleanup rows deleted: {deleted}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
