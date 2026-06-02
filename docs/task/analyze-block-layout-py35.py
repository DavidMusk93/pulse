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
import argparse
import ctypes
import datetime as dt
import glob
import json
import os
import shutil
import sqlite3
import sys
import threading
import time
from collections import OrderedDict, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

DEFAULT_TIDE_HOME = '/opt/tiger/tide'
MAX_CELL_WIDTH = 72
MIN_CELL_WIDTH = 8
TERMINAL_RIGHT_MARGIN = 2
DELETE_WORK_STEAL_CHUNK_SIZE = 128
MAX_DELETE_WORKERS = 32
DEFAULT_JSON_SAMPLE_LIMIT = 20
LOG_COLOR_RED = '\x1b[0;31m'
LOG_COLOR_YELLOW = '\x1b[1;33m'
LOG_COLOR_BLUE = '\x1b[0;34m'
LOG_COLOR_GREEN = '\x1b[0;32m'
LOG_COLOR_RESET = '\x1b[0m'

class BlockRecord:

    def __init__(self, db_path, tenant, table, path, data_size, layer, event_time, ttl, disk_path, disk_name, node_name, partition_time, obsolete, out_of_date):
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
        self.obsolete = obsolete
        self.out_of_date = out_of_date

    @property
    def table_key(self):
        return '{}.{}'.format(self.tenant, self.table)

class TableStats:

    def __init__(self, blocks=0, bytes_total=0, obsolete_blocks=0, obsolete_bytes=0, outdated_blocks=0, outdated_bytes=0):
        self.blocks = blocks
        self.bytes_total = bytes_total
        self.obsolete_blocks = obsolete_blocks
        self.obsolete_bytes = obsolete_bytes
        self.outdated_blocks = outdated_blocks
        self.outdated_bytes = outdated_bytes
        self.disk_bytes = (lambda : defaultdict(int))()
        self.disk_blocks = (lambda : defaultdict(int))()
        self.ttl_values = set()

class SizeStats:

    def __init__(self, blocks=0, bytes_total=0):
        self.blocks = blocks
        self.bytes_total = bytes_total
        self.disk_bytes = (lambda : defaultdict(int))()
        self.disk_blocks = (lambda : defaultdict(int))()

class LoadResult:

    def __init__(self, db_path, records, duration):
        self.db_path = db_path
        self.records = records
        self.duration = duration

class CleanupResult:

    def __init__(self, db_path, deleted, duration):
        self.db_path = db_path
        self.deleted = deleted
        self.duration = duration

class RemoveResult:

    def __init__(self, disk_path, removed, skipped, duration):
        self.disk_path = disk_path
        self.removed = removed
        self.skipped = skipped
        self.duration = duration

class FileRemovalCandidate:

    def __init__(self, path, disk_path, data_size):
        self.path = path
        self.disk_path = disk_path
        self.data_size = data_size

class RemoveTask:

    def __init__(self, resource_key, disk_path, candidates):
        self.resource_key = resource_key
        self.disk_path = disk_path
        self.candidates = candidates

class UntrackedBlock:

    def __init__(self, path, disk_path, data_size):
        self.path = path
        self.disk_path = disk_path
        self.data_size = data_size

class ExecutionPlan:

    def __init__(self, dry_run, cleanup_requested, selected_records, selected_untracked, file_removal_candidates, remove_plan, db_delete_plan):
        self.dry_run = dry_run
        self.cleanup_requested = cleanup_requested
        self.selected_records = selected_records
        self.selected_untracked = selected_untracked
        self.file_removal_candidates = file_removal_candidates
        self.remove_plan = remove_plan
        self.db_delete_plan = db_delete_plan

    @property
    def file_removal_count(self):
        return sum((len(candidates) for candidates in self.remove_plan.values()))

    @property
    def db_delete_count(self):
        return sum((len(paths) for paths in self.db_delete_plan.values()))

def log_timestamp():
    return dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]

def utc_now_epoch():
    return int(time.time())

def utc_datetime_from_epoch(epoch):
    return dt.datetime.utcfromtimestamp(epoch)

def utc_datetime_to_epoch(value):
    return int((value - dt.datetime(1970, 1, 1)).total_seconds())

def glob_paths(pattern):
    return glob.glob(pattern)

def log_thread_id():
    native_id = getattr(threading, 'get_native_id', None)
    if native_id is not None:
        return int(native_id())
    if sys.platform.startswith('linux'):
        libc = ctypes.CDLL(None, use_errno=True)
        gettid = getattr(libc, 'gettid', None)
        if gettid is not None:
            gettid.restype = ctypes.c_long
            tid = int(gettid())
            if tid > 0:
                return tid
        syscall = getattr(libc, 'syscall', None)
        if syscall is not None:
            syscall.restype = ctypes.c_long
            machine = os.uname().machine.lower()
            sys_gettid_by_machine = {'x86_64': 186, 'amd64': 186, 'i386': 224, 'i686': 224, 'aarch64': 178, 'arm64': 178, 'armv7l': 224, 'armv6l': 224, 'ppc64le': 207, 's390x': 236}
            sys_gettid = sys_gettid_by_machine.get(machine)
            if sys_gettid is not None:
                tid = int(syscall(ctypes.c_long(sys_gettid)))
                if tid > 0:
                    return tid
    return threading.get_ident()

def colorize(value, color):
    if not sys.stderr.isatty():
        return value
    return '{}{}{}'.format(color, value, LOG_COLOR_RESET)

def log_event(level, message, resource_key=None):
    level_colors = {'ERROR': LOG_COLOR_RED, 'WARN': LOG_COLOR_YELLOW, 'TRACE': LOG_COLOR_BLUE, 'OK': LOG_COLOR_GREEN}
    level_text = colorize('{:<5}'.format(level), level_colors.get(level, ''))
    fields = [log_timestamp(), 'pid={}'.format(os.getpid()), 'tid={}'.format(log_thread_id()), level_text]
    if resource_key is not None:
        fields.append('resource={}'.format(resource_key))
    fields.append(message)
    print(' '.join(fields), file=sys.stderr)

def log_error(message):
    log_event('ERROR', message)

def log_warn(message):
    log_event('WARN', message)

def log_trace(enabled, resource_key, message):
    if enabled:
        log_event('TRACE', message, resource_key)

def format_bytes(size):
    units = ('B', 'KiB', 'MiB', 'GiB', 'TiB')
    value = float(size)
    for unit in units:
        if abs(value) < 1024.0 or unit == units[-1]:
            if unit == 'B':
                return '{} {}'.format(int(value), unit)
            return '{:.2f} {}'.format(value, unit)
        value /= 1024.0
    return '{} B'.format(size)

def format_ttl_values(ttl_values):
    if not ttl_values:
        return '-'
    formatted = []
    for ttl in sorted(ttl_values):
        if ttl % 86400 == 0:
            formatted.append('{}s/{}d'.format(ttl, ttl // 86400))
        elif ttl % 3600 == 0:
            formatted.append('{}s/{}h'.format(ttl, ttl // 3600))
        else:
            formatted.append('{}s'.format(ttl))
    return ','.join(formatted)

def format_duration(seconds):
    seconds = max(0, seconds)
    (days, remainder) = divmod(seconds, 86400)
    (hours, remainder) = divmod(remainder, 3600)
    (minutes, seconds) = divmod(remainder, 60)
    parts = []
    if days:
        parts.append('{}d'.format(days))
    if hours:
        parts.append('{}h'.format(hours))
    if minutes and (not days):
        parts.append('{}m'.format(minutes))
    if not parts:
        parts.append('{}s'.format(seconds))
    return ' '.join(parts)

def format_ttl_value(ttl):
    if ttl is None:
        return '-'
    return format_ttl_values({ttl})

def format_block_size_pair(blocks, size):
    return '{} blocks\n{}'.format(blocks, format_bytes(size))

def format_expire_duration(record, now_epoch):
    if record.ttl is None or record.partition_time is None:
        return '-'
    expire_at = record.partition_time + record.ttl
    if expire_at < now_epoch:
        return 'ttl {}\nexpired {} ago'.format(format_ttl_value(record.ttl), format_duration(now_epoch - expire_at))
    return 'ttl {}\nexpires in {}'.format(format_ttl_value(record.ttl), format_duration(expire_at - now_epoch))

def wrap_cell(value, width):
    text = str(value)
    if width <= 0:
        return [text]
    if text == '':
        return ['']
    wrapped_lines = []
    for segment in text.splitlines():
        wrapped_lines.extend(wrap_cell_segment(segment, width))
    return wrapped_lines or ['']

def wrap_cell_segment(text, width):
    lines = []
    remaining = text
    while len(remaining) > width:
        cut = remaining.rfind('/', 1, width + 1)
        if cut <= 0 or cut < width // 2:
            cut = width
        else:
            cut += 1
        lines.append(remaining[:cut])
        remaining = remaining[cut:]
    if remaining:
        lines.append(remaining)
    return lines

def output_width():
    columns = shutil.get_terminal_size((120, 24)).columns
    return max(60, columns - TERMINAL_RIGHT_MARGIN)

def table_border_width(column_count):
    return 3 * column_count + 1

def fit_column_widths(headers, rows, max_width):
    widths = [len(header) for header in headers]
    for row in rows:
        for (index, cell) in enumerate(row):
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

def print_table(title, headers, rows, group_by=0):
    print()
    print(title)
    if not rows:
        print('  <none>')
        return
    max_width = output_width()
    string_rows = [[str(cell) for cell in row] for row in rows]
    widths = fit_column_widths(headers, string_rows, max_width)
    border = '+-' + '-+-'.join(('-' * width for width in widths)) + '-+'
    header_line = '| ' + ' | '.join((header.ljust(widths[index]) for (index, header) in enumerate(headers))) + ' |'
    print(border)
    print(header_line)
    print(border)
    previous_group = None
    for (row_index, row) in enumerate(string_rows):
        current_group = tuple(row[:group_by]) if group_by > 0 else (row_index,)
        if row_index > 0 and current_group != previous_group:
            print(border)
        wrapped_cells = [wrap_cell(cell, widths[index]) for (index, cell) in enumerate(row)]
        row_height = max((len(cell_lines) for cell_lines in wrapped_cells))
        for line_index in range(row_height):
            line_cells = []
            for (column_index, cell_lines) in enumerate(wrapped_cells):
                cell = cell_lines[line_index] if line_index < len(cell_lines) else ''
                line_cells.append(cell.ljust(widths[column_index]))
            print('| ' + ' | '.join(line_cells) + ' |')
        previous_group = current_group
    print(border)

def discover_dbs(tide_home, explicit_dbs):
    if explicit_dbs:
        return [os.path.abspath(db) for db in explicit_dbs]
    pattern = os.path.join(tide_home, 'node*', 'metadata', 'fringedb.db')
    return sorted(path for path in glob_paths(pattern) if os.path.isfile(path))

def discover_logical_disk_paths(tide_home):
    disk_paths = []
    for node_path in sorted(glob_paths(os.path.join(tide_home, 'node*'))):
        if not os.path.isdir(node_path):
            continue
        for disk_path in sorted(glob_paths(os.path.join(node_path, 'disk[0-9][0-9]'))):
            if os.path.basename(disk_path) == 'disk00':
                continue
            if os.path.isdir(disk_path):
                disk_paths.append(disk_path)
        for disk_path in sorted(glob_paths(os.path.join(node_path, 'ssd_disks_[0-9][0-9]'))):
            if os.path.isdir(disk_path):
                disk_paths.append(disk_path)
    return disk_paths

def ensure_schema(conn, db_path):
    rows = conn.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tables', 'block_records')").fetchall()
    names = set(row[0] for row in rows)
    missing = {'tables', 'block_records'} - names
    if missing:
        raise RuntimeError('{} missing required tables: {}'.format(db_path, ', '.join(sorted(missing))))

def load_table_ttls(conn):
    ttl_by_table = {}
    for (tenant, table_name, ttl) in conn.execute('SELECT tenant, "table", ttl FROM tables'):
        ttl_by_table[tenant, table_name] = int(ttl)
    return ttl_by_table

def parse_disk_path(block_path, tide_home):
    normalized_home = tide_home.rstrip('/')
    prefix = '{}/'.format(normalized_home)
    if not block_path.startswith(prefix):
        return ('<outside-tide-home>', '<unknown>', '<unknown>')
    parts = block_path[len(prefix):].split('/')
    if len(parts) < 4:
        return ('<unparsed>', '<unknown>', '<unknown>')
    node_name = parts[0]
    disk_name = parts[1]
    if not (node_name.startswith('node') and (disk_name.startswith('disk') or disk_name.startswith('ssd_disks_'))):
        return ('<unparsed>', '<unknown>', '<unknown>')
    return ('{}/{}/{}'.format(normalized_home, node_name, disk_name), disk_name, node_name)

def parse_partition_time(block_path):
    block_id = os.path.basename(block_path.rstrip('/'))
    head = block_id.rsplit('_', 3)[0]
    if '_' not in head:
        return None
    (_partition_id, partition_text) = head.split('_', 1)
    try:
        parsed = dt.datetime.strptime(partition_text, '%Y-%m-%d_%H:%M:%S')
    except ValueError:
        return None
    return utc_datetime_to_epoch(parsed)

def load_blocks(db_path, tide_home, now_epoch):
    conn = sqlite3.connect(db_path)
    try:
        ensure_schema(conn, db_path)
        ttl_by_table = load_table_ttls(conn)
        rows = conn.execute('SELECT tenant, "table", path, data_size, layer, event_time FROM block_records').fetchall()
    finally:
        conn.close()
    records = []
    for (tenant, table_name, path, data_size, layer, event_time) in rows:
        ttl = ttl_by_table.get((tenant, table_name))
        (disk_path, disk_name, node_name) = parse_disk_path(path, tide_home)
        partition_time = parse_partition_time(path)
        obsolete = not os.path.exists(path)
        out_of_date = False
        if ttl is not None and partition_time is not None:
            out_of_date = partition_time + ttl < now_epoch
        records.append(BlockRecord(db_path=db_path, tenant=tenant, table=table_name, path=path, data_size=int(data_size or 0), layer=int(layer or 0), event_time=int(event_time or 0), ttl=ttl, disk_path=disk_path, disk_name=disk_name, node_name=node_name, partition_time=partition_time, obsolete=obsolete, out_of_date=out_of_date))
    return records

def load_blocks_task(db_path, tide_home, now_epoch, trace):
    started_at = time.monotonic()
    log_trace(trace, db_path, 'start load block metadata')
    records = load_blocks(db_path, tide_home, now_epoch)
    duration = time.monotonic() - started_at
    log_trace(trace, db_path, 'done load blocks={} duration={:.2f}s'.format(len(records), duration))
    return LoadResult(db_path=db_path, records=records, duration=duration)

def load_all_blocks(db_paths, tide_home, now_epoch, trace):
    valid_db_paths = []
    for db_path in db_paths:
        if not os.path.isfile(db_path):
            log_warn('Skip missing DB: {}'.format(db_path))
            continue
        valid_db_paths.append(db_path)
    if not valid_db_paths:
        return []
    records = []
    for db_path in sorted(valid_db_paths):
        try:
            result = load_blocks_task(db_path, tide_home, now_epoch, trace)
        except Exception as exc:
            raise RuntimeError('{} failed to load: {}'.format(db_path, exc))
        records.extend(result.records)
    return records

def aggregate_table_stats(records):
    stats = defaultdict(TableStats)
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

def previous_utc_day_window(now_epoch):
    current_day = utc_datetime_from_epoch(now_epoch).date()
    previous_day = current_day - dt.timedelta(days=1)
    start = dt.datetime.combine(previous_day, dt.time.min)
    end = start + dt.timedelta(days=1)
    return (previous_day.isoformat(), utc_datetime_to_epoch(start), utc_datetime_to_epoch(end))

def aggregate_previous_day_stats(records, start_epoch, end_epoch):
    stats = defaultdict(SizeStats)
    for record in records:
        if record.partition_time is None:
            continue
        if not start_epoch <= record.partition_time < end_epoch:
            continue
        table_stats = stats[record.table_key]
        table_stats.blocks += 1
        table_stats.bytes_total += record.data_size
        table_stats.disk_bytes[record.disk_path] += record.data_size
        table_stats.disk_blocks[record.disk_path] += 1
    return stats

def candidate_reasons(record, include_obsolete, include_outdated):
    reasons = []
    if include_obsolete and record.obsolete:
        reasons.append('obsolete')
    if include_outdated and record.out_of_date:
        reasons.append('out-of-date')
    return reasons

def selected_candidates(records, include_obsolete, include_outdated):
    return [record for record in records if candidate_reasons(record, include_obsolete, include_outdated)]

def normalize_path(path):
    return path.rstrip('/')

def path_size(path):
    if os.path.isfile(path) or os.path.islink(path):
        try:
            return os.path.getsize(path)
        except OSError:
            return 0
    total = 0
    for (root, _dirs, files) in os.walk(path):
        for name in files:
            file_path = os.path.join(root, name)
            try:
                total += os.path.getsize(file_path)
            except OSError:
                continue
    return total

def is_safe_block_path(record, tide_home):
    normalized_home = tide_home.rstrip('/')
    if not record.path.startswith('{}/node'.format(normalized_home)):
        return False
    if record.disk_path in ('<outside-tide-home>', '<unparsed>'):
        return False
    if not record.path.startswith('{}/'.format(record.disk_path)):
        return False
    if record.path in ('', '/', normalized_home, record.disk_path):
        return False
    return True

def is_safe_path_under_disk(path, disk_path, tide_home):
    normalized_home = tide_home.rstrip('/')
    if not path.startswith('{}/node'.format(normalized_home)):
        return False
    if disk_path in ('<outside-tide-home>', '<unparsed>'):
        return False
    if not path.startswith('{}/'.format(disk_path)):
        return False
    if path in ('', '/', normalized_home, disk_path):
        return False
    return True

def build_known_paths_by_disk(records):
    known_paths_by_disk = defaultdict(set)
    for record in records:
        if record.disk_path in ('<outside-tide-home>', '<unparsed>'):
            continue
        known_paths_by_disk[record.disk_path].add(normalize_path(record.path))
    return known_paths_by_disk

def discover_block_table_paths_for_disk(disk_path):
    table_paths = []
    if not os.path.isdir(disk_path):
        return table_paths
    try:
        tenant_paths = sorted(os.path.join(disk_path, name) for name in os.listdir(disk_path))
    except OSError:
        return table_paths
    for tenant_path in tenant_paths:
        if not os.path.isdir(tenant_path):
            continue
        try:
            children = sorted(os.path.join(tenant_path, name) for name in os.listdir(tenant_path))
        except OSError:
            continue
        for table_path in children:
            if os.path.isdir(table_path):
                table_paths.append(table_path)
    return table_paths

def scan_table_untracked_blocks(disk_path, table_path, known_paths, tide_home, trace):
    log_trace(trace, table_path, 'start scan untracked blocks')
    untracked = []
    if not os.path.isdir(table_path):
        return untracked
    try:
        block_paths = sorted(os.path.join(table_path, name) for name in os.listdir(table_path))
    except OSError:
        return untracked
    for block_path in block_paths:
        if not os.path.exists(block_path):
            continue
        block_path_text = normalize_path(block_path)
        if parse_partition_time(block_path_text) is None:
            continue
        if block_path_text in known_paths:
            continue
        if not is_safe_path_under_disk(block_path_text, disk_path, tide_home):
            raise RuntimeError('Unsafe untracked block path for removal: {}'.format(block_path_text))
        untracked.append(UntrackedBlock(path=block_path_text, disk_path=disk_path, data_size=path_size(block_path_text)))
    log_trace(trace, table_path, 'done scan untracked blocks={}'.format(len(untracked)))
    return untracked

def scan_disk_untracked_blocks(disk_path, known_paths, tide_home, trace):
    started_at = time.monotonic()
    table_paths = discover_block_table_paths_for_disk(disk_path)
    log_trace(trace, disk_path, 'start scan untracked tables={} known_paths={}'.format(len(table_paths), len(known_paths)))
    untracked = []
    for table_path in table_paths:
        untracked.extend(scan_table_untracked_blocks(disk_path, table_path, known_paths, tide_home, trace))
    duration = time.monotonic() - started_at
    log_trace(trace, disk_path, 'done scan untracked blocks={} duration={:.2f}s'.format(len(untracked), duration))
    return untracked

def discover_untracked_blocks(tide_home, disk_paths, known_paths_by_disk, trace):
    if not disk_paths:
        return []
    log_trace(trace, 'untracked', 'resource=disk tasks={}'.format(len(disk_paths)))
    results = []
    executor = ThreadPoolExecutor(max_workers=len(disk_paths))
    try:
        futures = {executor.submit(scan_disk_untracked_blocks, disk_path, known_paths_by_disk.get(disk_path, set()), tide_home, trace): disk_path for disk_path in disk_paths}
        for future in as_completed(futures):
            disk_path = futures[future]
            try:
                results.extend(future.result())
            except Exception as exc:
                raise RuntimeError('{} failed to scan untracked blocks: {}'.format(disk_path, exc))
    finally:
        executor.shutdown(wait=True)
    return sorted(results, key=lambda item: (item.disk_path, item.path))

def file_candidates_from_records(records, tide_home):
    candidates = []
    for record in records:
        if not os.path.exists(record.path):
            continue
        if not is_safe_block_path(record, tide_home):
            raise RuntimeError('Unsafe block path for removal: {}'.format(record.path))
        candidates.append(FileRemovalCandidate(path=record.path, disk_path=record.disk_path, data_size=record.data_size))
    return candidates

def file_candidates_from_untracked(blocks):
    return [FileRemovalCandidate(path=block.path, disk_path=block.disk_path, data_size=block.data_size) for block in blocks]

def build_remove_plan(candidates, tide_home):
    plan = defaultdict(list)
    for candidate in candidates:
        if not os.path.exists(candidate.path):
            continue
        if not is_safe_path_under_disk(candidate.path, candidate.disk_path, tide_home):
            raise RuntimeError('Unsafe block path for removal: {}'.format(candidate.path))
        plan[candidate.disk_path].append(candidate)
    return plan

def is_hdd_disk_path(disk_path):
    return os.path.basename(disk_path).startswith('disk')

def chunked_candidates(candidates, chunk_size=DELETE_WORK_STEAL_CHUNK_SIZE):
    for index in range(0, len(candidates), chunk_size):
        yield candidates[index:index + chunk_size]

def build_remove_tasks(remove_plan):
    tasks = []
    for (disk_path, disk_candidates) in sorted(remove_plan.items()):
        sorted_candidates = sorted(disk_candidates, key=lambda item: item.path)
        if is_hdd_disk_path(disk_path) and len(sorted_candidates) > DELETE_WORK_STEAL_CHUNK_SIZE:
            for (index, chunk) in enumerate(chunked_candidates(sorted_candidates), start=1):
                tasks.append(RemoveTask(resource_key='{}#delete-{}'.format(disk_path, index), disk_path=disk_path, candidates=chunk))
            continue
        tasks.append(RemoveTask(resource_key=disk_path, disk_path=disk_path, candidates=sorted_candidates))
    return tasks

def resolve_delete_workers(tasks, disk_count):
    if not tasks:
        return 1
    cpu_count_func = getattr(os, 'cpu_count', None)
    cpu_count = cpu_count_func() if cpu_count_func is not None else 1
    cpu_count = cpu_count or 1
    natural_workers = max(disk_count, cpu_count)
    return min(len(tasks), natural_workers, MAX_DELETE_WORKERS)

def aggregate_remove_results(results):
    by_disk = {}
    for result in results:
        current = by_disk.get(result.disk_path)
        if current is None:
            by_disk[result.disk_path] = RemoveResult(disk_path=result.disk_path, removed=result.removed, skipped=result.skipped, duration=result.duration)
            continue
        current.removed += result.removed
        current.skipped += result.skipped
        current.duration += result.duration
    return sorted(by_disk.values(), key=lambda item: item.disk_path)

def remove_path(path):
    if os.path.isdir(path) and (not os.path.islink(path)):
        shutil.rmtree(path)
        return
    os.unlink(path)

def remove_task_candidates(task, trace):
    started_at = time.monotonic()
    log_trace(trace, task.resource_key, 'start rm disk={} candidates={}'.format(task.disk_path, len(task.candidates)))
    removed = 0
    skipped = 0
    for candidate in task.candidates:
        if not os.path.exists(candidate.path):
            skipped += 1
            continue
        remove_path(candidate.path)
        removed += 1
    duration = time.monotonic() - started_at
    log_trace(trace, task.resource_key, 'done rm disk={} removed={} skipped={} duration={:.2f}s'.format(task.disk_path, removed, skipped, duration))
    return RemoveResult(disk_path=task.disk_path, removed=removed, skipped=skipped, duration=duration)

def remove_block_files(remove_plan, trace):
    if not remove_plan:
        return []
    tasks = build_remove_tasks(remove_plan)
    worker_count = resolve_delete_workers(tasks, len(remove_plan))
    steal_tasks = sum((1 for task in tasks if '#' in task.resource_key))
    log_trace(trace, 'rm', 'tasks={} disks={} workers={} work_steal_tasks={}'.format(len(tasks), len(remove_plan), worker_count, steal_tasks))
    results = []
    executor = ThreadPoolExecutor(max_workers=worker_count)
    try:
        futures = {executor.submit(remove_task_candidates, task, trace): task.resource_key for task in tasks}
        for future in as_completed(futures):
            resource_key = futures[future]
            try:
                results.append(future.result())
            except Exception as exc:
                raise RuntimeError('{} failed to remove block files: {}'.format(resource_key, exc))
    finally:
        executor.shutdown(wait=True)
    return aggregate_remove_results(results)

def delete_db_candidates(db_path, paths, trace):
    started_at = time.monotonic()
    log_trace(trace, db_path, 'start cleanup candidates={}'.format(len(paths)))
    deleted = 0
    conn = sqlite3.connect(db_path)
    try:
        with conn:
            for path in sorted(paths):
                cursor = conn.execute('DELETE FROM block_records WHERE path = ?', (path,))
                deleted += cursor.rowcount
        conn.execute('VACUUM')
    finally:
        conn.close()
    duration = time.monotonic() - started_at
    log_trace(trace, db_path, 'done cleanup deleted={} duration={:.2f}s'.format(deleted, duration))
    return CleanupResult(db_path=db_path, deleted=deleted, duration=duration)

def build_db_delete_plan(records, include_obsolete, include_outdated):
    paths_by_db = defaultdict(set)
    for record in selected_candidates(records, include_obsolete, include_outdated):
        paths_by_db[record.db_path].add(record.path)
    return paths_by_db

def delete_candidates(db_delete_plan, trace):
    paths_by_db = db_delete_plan
    if not paths_by_db:
        return 0
    results = []
    for (db_path, paths) in sorted(paths_by_db.items()):
        try:
            results.append(delete_db_candidates(db_path, paths, trace))
        except Exception as exc:
            raise RuntimeError('{} failed to cleanup: {}'.format(db_path, exc))
    deleted = 0
    for result in sorted(results, key=lambda item: item.db_path):
        deleted += result.deleted
    return deleted

def build_summary_rows(stats, top):
    rows = []
    sorted_items = sorted(stats.items(), key=lambda item: item[0])
    for (table_key, table_stats) in sorted_items[:top]:
        rows.append([table_key, format_ttl_values(table_stats.ttl_values), table_stats.blocks, format_bytes(table_stats.bytes_total), format_block_size_pair(table_stats.obsolete_blocks, table_stats.obsolete_bytes), format_block_size_pair(table_stats.outdated_blocks, table_stats.outdated_bytes), len(table_stats.disk_bytes)])
    return rows

def build_distribution_rows(stats, top):
    rows = []
    sorted_tables = sorted(stats.items(), key=lambda item: item[0])
    for (table_key, table_stats) in sorted_tables[:top]:
        sorted_disks = sorted(table_stats.disk_bytes.items(), key=lambda item: item[0])
        for (disk_path, disk_bytes) in sorted_disks:
            percent = 0.0
            if table_stats.bytes_total > 0:
                percent = disk_bytes * 100.0 / table_stats.bytes_total
            rows.append([table_key, disk_path, table_stats.disk_blocks[disk_path], format_bytes(disk_bytes), '{:.2f}%'.format(percent)])
    return rows

def build_candidate_rows(candidates, include_obsolete, include_outdated, limit, now_epoch):
    rows = []
    visible_candidates = candidates if limit == 0 else candidates[:limit]
    for record in visible_candidates:
        reasons = ','.join(candidate_reasons(record, include_obsolete, include_outdated))
        rows.append([record.table_key, record.disk_path, reasons, format_bytes(record.data_size), format_expire_duration(record, now_epoch), record.path])
    return rows

def build_untracked_rows(candidates, limit):
    rows = []
    visible_candidates = candidates if limit == 0 else candidates[:limit]
    for candidate in visible_candidates:
        rows.append([candidate.disk_path, format_bytes(candidate.data_size), candidate.path])
    return rows

def build_remove_group_rows(remove_plan):
    rows = []
    for (disk_path, disk_candidates) in sorted(remove_plan.items()):
        total_size = sum((candidate.data_size for candidate in disk_candidates))
        rows.append([disk_path, len(disk_candidates), format_bytes(total_size)])
    return rows

def cleanup_requested(args):
    return args.delete_obsolete or args.delete_out_of_date or args.delete_untracked

def build_execution_plan(args, records, untracked_blocks):
    requested = cleanup_requested(args)
    dry_run = args.dry_run or not requested
    no_cleanup_filter = not requested
    include_obsolete = args.delete_obsolete or no_cleanup_filter
    include_outdated = args.delete_out_of_date or no_cleanup_filter
    include_untracked = args.delete_untracked or no_cleanup_filter
    selected_records = selected_candidates(records, include_obsolete, include_outdated)
    selected_records = sorted(selected_records, key=lambda record: (record.table_key, record.disk_path, record.path))
    selected_untracked = untracked_blocks if include_untracked else []
    records_for_file_removal = [record for record in selected_records if args.remove_block_files or (args.delete_out_of_date and record.out_of_date)]
    file_removal_candidates = file_candidates_from_records(records_for_file_removal, args.tide_home)
    if args.delete_untracked:
        file_removal_candidates.extend(file_candidates_from_untracked(selected_untracked))
    remove_plan = {}
    if file_removal_candidates:
        remove_plan = build_remove_plan(file_removal_candidates, args.tide_home)
    db_delete_plan = build_db_delete_plan(records, args.delete_obsolete, args.delete_out_of_date)
    return ExecutionPlan(dry_run=dry_run, cleanup_requested=requested, selected_records=selected_records, selected_untracked=selected_untracked, file_removal_candidates=file_removal_candidates, remove_plan=remove_plan, db_delete_plan=db_delete_plan)

def build_execution_plan_rows(plan, metadata_db_count, disk_count):
    phase = 'dry-run' if plan.dry_run else 'build'
    rows = [['probe', 'metadata-db', 'load block_records once', metadata_db_count], ['probe', 'disk', 'scan untracked blocks with per-disk path set', disk_count], [phase, 'disk', 'remove block files with HDD work stealing', plan.file_removal_count], [phase, 'metadata-db', 'delete selected metadata rows sequentially by DB', plan.db_delete_count]]
    return rows

def block_record_to_json(record, include_obsolete, include_outdated, now_epoch):
    reasons = candidate_reasons(record, include_obsolete, include_outdated)
    expire_at = None
    expired_seconds = None
    expires_in_seconds = None
    if record.ttl is not None and record.partition_time is not None:
        expire_at = record.partition_time + record.ttl
        if expire_at < now_epoch:
            expired_seconds = now_epoch - expire_at
        else:
            expires_in_seconds = expire_at - now_epoch
    return {'db_path': record.db_path, 'tenant': record.tenant, 'table': record.table, 'table_key': record.table_key, 'path': record.path, 'disk_path': record.disk_path, 'disk_name': record.disk_name, 'node_name': record.node_name, 'data_size_bytes': record.data_size, 'data_size': format_bytes(record.data_size), 'layer': record.layer, 'event_time': record.event_time, 'ttl_seconds': record.ttl, 'partition_time': record.partition_time, 'expire_at': expire_at, 'expired_seconds': expired_seconds, 'expires_in_seconds': expires_in_seconds, 'obsolete': record.obsolete, 'out_of_date': record.out_of_date, 'reasons': reasons}

def untracked_block_to_json(block):
    return {'path': block.path, 'disk_path': block.disk_path, 'data_size_bytes': block.data_size, 'data_size': format_bytes(block.data_size)}

def remove_plan_to_json(remove_plan):
    rows = []
    for (disk_path, disk_candidates) in sorted(remove_plan.items()):
        total_size = sum((candidate.data_size for candidate in disk_candidates))
        rows.append({'disk_path': disk_path, 'blocks': len(disk_candidates), 'size_bytes': total_size, 'size': format_bytes(total_size), 'paths': [candidate.path for candidate in sorted(disk_candidates, key=lambda item: item.path)]})
    return rows

def limited_items(items, limit):
    if limit == 0:
        return (items, 0)
    visible = items[:limit]
    return (visible, max(0, len(items) - len(visible)))

def disk_distribution_to_json(table_stats, previous_day_stats):
    rows = []
    for (disk_path, disk_bytes) in sorted(table_stats.disk_bytes.items(), key=lambda item: item[0]):
        table_percent = 0.0
        if table_stats.bytes_total > 0:
            table_percent = disk_bytes * 100.0 / table_stats.bytes_total
        previous_day_bytes = 0
        previous_day_blocks = 0
        if previous_day_stats is not None:
            previous_day_bytes = previous_day_stats.disk_bytes.get(disk_path, 0)
            previous_day_blocks = previous_day_stats.disk_blocks.get(disk_path, 0)
        rows.append({'disk_path': disk_path, 'blocks': table_stats.disk_blocks[disk_path], 'size_bytes': disk_bytes, 'size': format_bytes(disk_bytes), 'table_percent': round(table_percent, 2), 'previous_day_blocks': previous_day_blocks, 'previous_day_size_bytes': previous_day_bytes, 'previous_day_size': format_bytes(previous_day_bytes)})
    return rows

def table_layout_to_json(stats, previous_day_stats, top):
    rows = []
    sorted_tables = sorted(stats.items(), key=lambda item: (-item[1].bytes_total, item[0]))
    for (table_key, table_stats) in sorted_tables[:top]:
        previous_stats = previous_day_stats.get(table_key)
        previous_day_blocks = previous_stats.blocks if previous_stats is not None else 0
        previous_day_bytes = previous_stats.bytes_total if previous_stats is not None else 0
        max_disk_bytes = max(table_stats.disk_bytes.values()) if table_stats.disk_bytes else 0
        max_disk_percent = 0.0
        if table_stats.bytes_total > 0:
            max_disk_percent = max_disk_bytes * 100.0 / table_stats.bytes_total
        rows.append({'table': table_key, 'blocks': table_stats.blocks, 'size_bytes': table_stats.bytes_total, 'size': format_bytes(table_stats.bytes_total), 'disk_count': len(table_stats.disk_bytes), 'max_disk_percent': round(max_disk_percent, 2), 'ttl_seconds': sorted(table_stats.ttl_values), 'ttl': format_ttl_values(table_stats.ttl_values), 'previous_day': {'blocks': previous_day_blocks, 'size_bytes': previous_day_bytes, 'size': format_bytes(previous_day_bytes)}, 'disk_distribution': disk_distribution_to_json(table_stats, previous_stats)})
    return rows

def block_layout_to_json(stats, previous_day_stats, records, db_paths, disk_paths, total_bytes, top, previous_day_label, previous_day_start, previous_day_end):
    previous_day_bytes = sum((table_stats.bytes_total for table_stats in previous_day_stats.values()))
    previous_day_blocks = sum((table_stats.blocks for table_stats in previous_day_stats.values()))
    return OrderedDict([('summary', OrderedDict([('metadata_dbs', len(db_paths)), ('disk_tasks', len(disk_paths)), ('tables', len(stats)), ('blocks', len(records)), ('total_size_bytes', total_bytes), ('total_size', format_bytes(total_bytes)), ('previous_day', OrderedDict([('date_utc', previous_day_label), ('start_epoch', previous_day_start), ('end_epoch', previous_day_end), ('blocks', previous_day_blocks), ('size_bytes', previous_day_bytes), ('size', format_bytes(previous_day_bytes))]))])), ('tables_order', 'size_bytes_desc'), ('table_limit', top), ('tables', table_layout_to_json(stats, previous_day_stats, top))])

def table_summary_to_json(stats, top):
    rows = []
    for (table_key, table_stats) in sorted(stats.items(), key=lambda item: item[0])[:top]:
        rows.append({'table': table_key, 'ttl_seconds': sorted(table_stats.ttl_values), 'ttl': format_ttl_values(table_stats.ttl_values), 'blocks': table_stats.blocks, 'size_bytes': table_stats.bytes_total, 'size': format_bytes(table_stats.bytes_total), 'obsolete_blocks': table_stats.obsolete_blocks, 'obsolete_bytes': table_stats.obsolete_bytes, 'obsolete_size': format_bytes(table_stats.obsolete_bytes), 'out_of_date_blocks': table_stats.outdated_blocks, 'out_of_date_bytes': table_stats.outdated_bytes, 'out_of_date_size': format_bytes(table_stats.outdated_bytes), 'disk_count': len(table_stats.disk_bytes)})
    return rows

def table_distribution_to_json(stats, top):
    rows = []
    for (table_key, table_stats) in sorted(stats.items(), key=lambda item: item[0])[:top]:
        for (disk_path, disk_bytes) in sorted(table_stats.disk_bytes.items(), key=lambda item: item[0]):
            percent = 0.0
            if table_stats.bytes_total > 0:
                percent = disk_bytes * 100.0 / table_stats.bytes_total
            rows.append({'table': table_key, 'disk_path': disk_path, 'blocks': table_stats.disk_blocks[disk_path], 'size_bytes': disk_bytes, 'size': format_bytes(disk_bytes), 'table_percent': round(percent, 2)})
    return rows

def output_dry_run_json(args, plan, report_time, db_paths, disk_paths, records, untracked_blocks, stats, total_bytes, obsolete_count, outdated_count, include_obsolete, include_outdated, include_untracked):
    sample_limit = args.json_sample_limit
    (visible_records, hidden_records) = limited_items(plan.selected_records, sample_limit)
    (visible_untracked, hidden_untracked) = limited_items(plan.selected_untracked, sample_limit)
    execution_plan = [{'phase': phase, 'resource': resource, 'action': action, 'count': count} for (phase, resource, action, count) in build_execution_plan_rows(plan, len(db_paths), len(disk_paths))]
    (previous_day_label, previous_day_start, previous_day_end) = previous_utc_day_window(args.now)
    previous_day_stats = aggregate_previous_day_stats(records, previous_day_start, previous_day_end)
    payload = OrderedDict([('report_type', 'block_layout_analysis'), ('report_time', report_time), ('mode', 'dry-run'), ('tide_home', args.tide_home), ('block_layout', block_layout_to_json(stats, previous_day_stats, records, db_paths, disk_paths, total_bytes, args.top, previous_day_label, previous_day_start, previous_day_end)), ('cleanup_overview', OrderedDict([('summary', OrderedDict([('cleanup_requested', plan.cleanup_requested), ('obsolete_blocks', obsolete_count), ('out_of_date_blocks', outdated_count), ('untracked_blocks', len(untracked_blocks)), ('selected_db_record_candidates', len(plan.selected_records)), ('selected_untracked_candidates', len(plan.selected_untracked)), ('file_removal_candidates', len(plan.file_removal_candidates)), ('file_removal_groups', len(plan.remove_plan)), ('db_delete_candidates', plan.db_delete_count)])), ('samples', OrderedDict([('limit', sample_limit), ('db_record_candidates_hidden', hidden_records), ('untracked_candidates_hidden', hidden_untracked), ('db_record_candidates', [block_record_to_json(record, include_obsolete, include_outdated, args.now) for record in visible_records]), ('untracked_candidates', [untracked_block_to_json(block) for block in visible_untracked]), ('file_removal_groups', remove_plan_to_json(plan.remove_plan)[:sample_limit] if sample_limit else remove_plan_to_json(plan.remove_plan))]))])), ('execution_plan', execution_plan), ('cleanup_requested', plan.cleanup_requested), ('filters', OrderedDict([('include_obsolete', include_obsolete), ('include_out_of_date', include_outdated), ('include_untracked', include_untracked), ('remove_block_files', args.remove_block_files)])), ('limits', OrderedDict([('top', args.top), ('candidate_limit', args.candidate_limit), ('json_sample_limit', sample_limit)])), ('metadata_dbs', db_paths), ('disk_paths', disk_paths), ('message', 'selected cleanup actions were not applied' if plan.cleanup_requested else 'probe only; pass cleanup options to build an executable mutation plan')])
    json.dump(payload, sys.stdout, indent=2)
    print()

def parse_args(argv):
    parser = argparse.ArgumentParser(description='Analyze Tide block distribution and cleanup candidates from fringedb.db metadata.', formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('-d', '--db', action='append', default=[], help='SQLite fringedb.db path; repeatable')
    parser.add_argument('-t', '--tide-home', default=os.environ.get('TIDE_HOME', DEFAULT_TIDE_HOME), help='TIDE_HOME used to discover DBs and parse logical disk paths')
    parser.add_argument('-n', '--dry-run', action='store_true', default=False, help='Print the execution plan without deleting files or metadata rows')
    parser.add_argument('-o', '--delete-obsolete', action='store_true', help='Delete rows whose block path does not exist')
    parser.add_argument('-e', '--delete-out-of-date', action='store_true', help='Delete rows whose partition_time + ttl is smaller than current time')
    parser.add_argument('-u', '--delete-untracked', action='store_true', help='Delete real block paths that exist on disk but are not recorded in SQLite')
    parser.add_argument('-r', '--remove-block-files', action='store_true', help='Also remove existing selected DB-recorded block paths before deleting SQLite rows')
    parser.add_argument('--now', type=int, default=utc_now_epoch(), help='Override current epoch seconds')
    parser.add_argument('--trace', action='store_true', help='Print per-resource task traces to stderr')
    parser.add_argument('-p', '--top', type=int, default=50, help='Maximum number of tables to show')
    parser.add_argument('-c', '--candidate-limit', type=int, default=50, help='Maximum cleanup candidates to show; 0 means show all')
    parser.add_argument('--json-sample-limit', type=int, default=DEFAULT_JSON_SAMPLE_LIMIT, help='Maximum non-layout samples in dry-run JSON; 0 means show all')
    return parser.parse_args(argv)

def validate_args(args):
    if args.top <= 0:
        raise SystemExit('--top must be positive')
    if args.candidate_limit < 0:
        raise SystemExit('--candidate-limit must be zero or positive')
    if args.json_sample_limit < 0:
        raise SystemExit('--json-sample-limit must be zero or positive')

def main(argv):
    args = parse_args(argv)
    validate_args(args)
    db_paths = discover_dbs(args.tide_home, args.db)
    if not db_paths:
        log_error('No metadata DB found under {}/node*/metadata/fringedb.db'.format(args.tide_home))
        return 1
    try:
        records = load_all_blocks(db_paths, args.tide_home, args.now, args.trace)
    except RuntimeError as exc:
        log_error(str(exc))
        return 1
    stats = aggregate_table_stats(records)
    obsolete_count = sum((1 for record in records if record.obsolete))
    outdated_count = sum((1 for record in records if record.out_of_date))
    total_bytes = sum((record.data_size for record in records))
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
    visible_candidate_count = len(plan.selected_records) if args.candidate_limit == 0 else min(len(plan.selected_records), args.candidate_limit)
    visible_untracked_count = len(plan.selected_untracked) if args.candidate_limit == 0 else min(len(plan.selected_untracked), args.candidate_limit)
    report_time = dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    if plan.dry_run:
        output_dry_run_json(args, plan, report_time, db_paths, disk_paths, records, untracked_blocks, stats, total_bytes, obsolete_count, outdated_count, include_obsolete, include_outdated, include_untracked)
        return 0
    print('Block Layout Analysis - {}'.format(report_time))
    print('Mode              : {}'.format('DRY-RUN' if plan.dry_run else 'BUILD'))
    print('TIDE_HOME         : {}'.format(args.tide_home))
    print('Metadata DBs      : {}'.format(len(db_paths)))
    print('Disk Tasks        : {}'.format(len(disk_paths)))
    print('Remove Block Files: {}'.format('yes' if plan.file_removal_candidates else 'no'))
    print('Tables            : {}'.format(len(stats)))
    print('Blocks            : {}'.format(len(records)))
    print('Total Size        : {}'.format(format_bytes(total_bytes)))
    print('Obsolete Blocks   : {}'.format(obsolete_count))
    print('Out-of-date Blocks: {}'.format(outdated_count))
    print('Untracked Blocks  : {}'.format(len(untracked_blocks)))
    print('Cleanup Candidates: {} total, showing {}'.format(len(plan.selected_records), visible_candidate_count))
    print('Untracked Selected: {} total, showing {}'.format(len(plan.selected_untracked), visible_untracked_count))
    print_table('Execution Plan', ['PHASE', 'RESOURCE', 'ACTION', 'COUNT'], build_execution_plan_rows(plan, len(db_paths), len(disk_paths)), group_by=1)
    print_table('Table Distribution Summary', ['TABLE', 'TTL', 'BLOCKS', 'SIZE', 'OBSOLETE', 'OUT-OF-DATE', 'DISKS'], build_summary_rows(stats, args.top), group_by=1)
    print_table('Table Disk Distribution', ['TABLE', 'DISK', 'BLOCKS', 'SIZE', '%TABLE'], build_distribution_rows(stats, args.top), group_by=1)
    show_candidates = include_obsolete or include_outdated
    if show_candidates:
        print_table('Cleanup Candidates (showing {} of {})'.format(visible_candidate_count, len(plan.selected_records)), ['TABLE', 'DISK', 'REASON', 'SIZE', 'TTL / EXPIRE_DURATION', 'PATH'], build_candidate_rows(plan.selected_records, include_obsolete, include_outdated, args.candidate_limit, args.now), group_by=1)
        if visible_candidate_count < len(plan.selected_records):
            print('  More candidates hidden. Use --candidate-limit 0 to show all {} candidates.'.format(len(plan.selected_records)))
    if include_untracked:
        print_table('Untracked Block Candidates (showing {} of {})'.format(visible_untracked_count, len(plan.selected_untracked)), ['DISK', 'SIZE', 'PATH'], build_untracked_rows(plan.selected_untracked, args.candidate_limit), group_by=1)
        if visible_untracked_count < len(plan.selected_untracked):
            print('  More untracked blocks hidden. Use --candidate-limit 0 to show all {} candidates.'.format(len(plan.selected_untracked)))
    if plan.file_removal_candidates:
        print_table('Block File Remove Plan', ['DISK', 'BLOCKS', 'SIZE'], build_remove_group_rows(plan.remove_plan), group_by=1)
    removed = 0
    if plan.remove_plan:
        try:
            remove_results = remove_block_files(plan.remove_plan, args.trace)
        except RuntimeError as exc:
            log_error(str(exc))
            return 1
        removed = sum((result.removed for result in remove_results))
    try:
        deleted = delete_candidates(plan.db_delete_plan, args.trace)
    except RuntimeError as exc:
        log_error(str(exc))
        return 1
    print()
    print('Built block paths removed: {}'.format(removed))
    print('Built cleanup rows deleted: {}'.format(deleted))
    return 0
if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
