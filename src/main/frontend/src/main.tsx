import React, { memo, useCallback, useDeferredValue, useEffect, useLayoutEffect, useMemo, useRef, useState, useTransition } from 'react';
import { createRoot } from 'react-dom/client';
import { init, use, type EChartsOption } from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, MarkLineComponent, MarkPointComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import {
  Badge,
  Button,
  Card,
  Col,
  ConfigProvider,
  Empty,
  Flex,
  Input,
  List,
  Modal,
  Progress,
  Row,
  Select,
  Segmented,
  Space,
  Statistic,
  Tag,
  Typography,
  message,
  theme
} from 'antd';
import { CopyOutlined, DownloadOutlined, InboxOutlined } from '@ant-design/icons';
import {
  MetricQueryController,
  RenderScheduler,
  SeriesStore,
  mergeInvalidation,
  metricPointTimestamp,
  metricPointValue,
  parseInvalidation,
  type MetricInvalidation,
  type MetricCatalogItem,
  type MetricQueryResultView,
  type MetricStorageHealth
} from './metrics';
import 'antd/dist/reset.css';
import './style.css';

use([LineChart, GridComponent, LegendComponent, MarkLineComponent, MarkPointComponent, TooltipComponent, CanvasRenderer]);

type HostView = {
  agent_id?: string;
  agentId?: string;
  epoch?: number;
  seq?: number;
  ttl_ms?: number;
  observed_at_ms?: number;
  observedAtMs?: number;
  expire_at_ms?: number;
  expireAtMs?: number;
  last_observed_age_ms?: number;
  lastObservedAgeMs?: number;
  heartbeat_confirmations?: number;
  heartbeatConfirmations?: number;
  status?: string;
  source?: string;
  group_id?: string;
  groupId?: string;
  group_mode?: string;
  groupMode?: string;
  leader_agent_id?: string;
  leaderAgentId?: string;
  leader_url?: string;
  leaderUrl?: string;
  group_size?: number;
  groupSize?: number;
  group_size_limit?: number;
  groupSizeLimit?: number;
  host?: string;
  ip?: string;
  cluster?: string;
  area?: string;
  zone?: string;
  role?: string;
  load?: string;
  state?: Record<string, any>;
};

type TaskSnapshot = {
  agent_id?: string;
  execution_queue?: any[];
  completion_queue?: any[];
  traces?: any[];
  task_definitions?: string[];
  file_transfers?: any[];
  output_streams?: any[];
};

type BatchFilePutResponse = {
  ok?: boolean;
  total?: number;
  succeeded?: number;
  failed?: number;
  failed_agents?: string[];
  failedAgents?: string[];
  errors?: Record<string, string>;
  snapshots?: Record<string, TaskSnapshot>;
};

const loadAverageWindowMs = 5 * 60 * 1000;
const maxJsonParseChars = 2 * 1024 * 1024;
const virtualOutputLineHeight = 20;
const virtualOutputOverscan = 12;
const outputPrefixProbeChars = 256;
const virtualOutputCharWidth = 7.2;
const palette = [205, 188, 168, 146, 126, 95, 48, 215, 200, 178];
const loadWindows = new Map<string, { windowStart: number; displayAvg: number; sampledAtMs: number }>();
const clusterCollapseStorageKey = 'pulse.cluster-collapse.v1';

const taskLabels: Record<string, string> = {
  prepare_disk_layout_dry_run: '磁盘布局',
  analyze_block_layout_dry_run: '块分布',
  repair_corrupt_sqlite3_dry_run: '修复 SQLite',
  shell_script: 'Shell'
};
const defaultTaskArgs = '--dry-run';

type ActiveClusterRun = { name: string; hosts: HostView[] };
type ClusterSortMode = 'ip' | 'load-asc' | 'load-desc';
type OutputLog = {
  key: string;
  lines: string[];
  sourceText: string;
  sourceLength: number;
  chunks: string[];
  fullText: string | null;
};

type BatchSubmitSummary = {
  kind: string;
  total: number;
  succeeded: number;
  failed: number;
  failedAgents: string[];
  taskIds: Record<string, string>;
  message: string;
  errors: string[];
  updatedAt: number;
  snapshots: Record<string, TaskSnapshot>;
};

type ClusterExecutionSummary = {
  total: number;
  submitSucceeded: number;
  submitFailed: number;
  executionSucceeded: number;
  executionFailed: number;
  running: number;
  pending: number;
  durationCount: number;
  averageDurationMs: number;
  maxDurationMs: number;
  rows: ClusterExecutionRow[];
};

type ClusterExecutionRow = {
  host: HostView;
  snapshot?: TaskSnapshot;
  status: 'success' | 'failed' | 'running' | 'pending' | 'submit_failed';
  label: string;
  taskId: string;
  taskType: string;
  exitCode: string;
  outputBytes: number;
  message: string;
  outputText: string;
  outputPreview: string;
  outputLineCount: number;
  outputPreviewLineCount: number;
  outputFullUrl?: string;
  durationMs: number;
  durationLabel: string;
  durationKind: 'elapsed' | 'running' | 'none';
};

function normalizeAddress(value?: string) {
  const raw = String(value || '').replaceAll('[', '').replaceAll(']', '');
  if (!raw || raw.includes('.')) return '-';
  return raw;
}

async function copyTextToClipboard(text: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  textarea.style.pointerEvents = 'none';
  document.body.appendChild(textarea);
  textarea.select();

  try {
    if (!document.execCommand('copy')) {
      throw new Error('copy failed');
    }
  } finally {
    document.body.removeChild(textarea);
  }
}

function normalizeUrlHost(value?: string) {
  if (!value) return '-';
  try {
    const parsed = new URL(value);
    const hostWithoutPort = parsed.port ? parsed.host.replace(`:${parsed.port}`, '') : parsed.host;
    return normalizeAddress(hostWithoutPort);
  } catch {
    return normalizeAddress(value);
  }
}

function agentId(host: HostView) {
  return host.agent_id || host.agentId || host.ip || '';
}

function hostKey(host: HostView) {
  return 'ip-' + String(host.ip || agentId(host) || 'unknown').replaceAll(/[^a-zA-Z0-9_-]/g, '_');
}

function hostDisplayName(host: HostView) {
  const normalized = normalizeAddress(host.ip);
  return normalized === '-' ? agentId(host) || '-' : normalized;
}

function loadValue(host: HostView) {
  const parsed = Number.parseFloat(String(host.load || '0'));
  return Number.isFinite(parsed) ? parsed : 0;
}

function recordLoadSamples(hosts: HostView[]) {
  const now = Date.now();
  const windowStart = now - (now % loadAverageWindowMs);
  const active = new Set<string>();
  hosts.forEach(host => {
    const id = agentId(host);
    if (!id) return;
    active.add(id);
    const state = loadWindows.get(id);
    if (!state || state.windowStart !== windowStart) {
      loadWindows.set(id, { windowStart, displayAvg: loadValue(host), sampledAtMs: now });
    }
  });
  [...loadWindows.keys()].forEach(id => {
    if (!active.has(id)) loadWindows.delete(id);
  });
}

function averageLoad(host: HostView) {
  return loadWindows.get(agentId(host))?.displayAvg ?? loadValue(host);
}

function formatLoad(value: number) {
  return value.toFixed(2);
}

function formatTime(ms?: number) {
  if (!ms) return '-';
  try {
    const date = new Date(ms);
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  } catch {
    return '-';
  }
}

function formatSeenTime(ms?: number) {
  if (!ms) return '-';
  try {
    const date = new Date(ms);
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
  } catch {
    return '-';
  }
}

function formatAge(ms?: number) {
  if (ms === undefined || ms === null || !Number.isFinite(ms)) return '-';
  if (ms < 1000) return `${Math.max(0, Math.round(ms))}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m${String(Math.floor((ms % 60_000) / 1000)).padStart(2, '0')}s`;
}

function statusLabel(status?: string) {
  return ({
    queued: '队列中', delivered: '已下发', accepted: '已接收', running: '执行中',
    completed: '已完成', failed: '失败', rejected: '已拒绝', timed_out: '超时', timeout: '超时',
    delivering: '下发中', received: '已回执',
    alive: '在线', warming: '确认中', expired: '过期'
  } as Record<string, string>)[status || ''] || status || '-';
}

function statusColor(status?: string): 'success' | 'processing' | 'warning' | 'error' | 'default' {
  if (status === 'alive' || status === 'completed' || status === 'received') return 'success';
  if (status === 'running' || status === 'accepted' || status === 'delivered' || status === 'delivering') return 'processing';
  if (status === 'warming' || status === 'queued') return 'warning';
  if (status === 'expired' || status === 'failed' || status === 'timeout' || status === 'timed_out') return 'error';
  return 'default';
}

function activeHostTasks(host?: HostView) {
  const tasks = host?.state?.async_tasks;
  return Array.isArray(tasks) ? tasks : [];
}

function snapshotsFromSettledResults(hosts: HostView[], results: PromiseSettledResult<TaskSnapshot>[]) {
  const snapshots: Record<string, TaskSnapshot> = {};
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      snapshots[agentId(hosts[index])] = result.value;
    }
  });
  return snapshots;
}

function submittedTaskId(snapshot: TaskSnapshot, events: string[]) {
  const eventSet = new Set(events);
  const trace = (snapshot.traces || []).find((entry: any) => eventSet.has(entry.event));
  return trace?.task_id || trace?.taskId || '';
}

function taskIdsFromSettledResults(hosts: HostView[], results: PromiseSettledResult<TaskSnapshot>[], events: string[]) {
  const taskIds: Record<string, string> = {};
  results.forEach((result, index) => {
    if (result.status !== 'fulfilled') return;
    const taskId = submittedTaskId(result.value, events);
    if (taskId) taskIds[agentId(hosts[index])] = taskId;
  });
  return taskIds;
}

function fileIdsFromSettledResults(hosts: HostView[], results: PromiseSettledResult<TaskSnapshot>[]) {
  const fileIds: Record<string, string> = {};
  results.forEach((result, index) => {
    if (result.status !== 'fulfilled') return;
    const files = (result.value.file_transfers || []).filter((file: any) => file.file_role !== 'shell_script');
    const latest = files
      .slice()
      .sort((left: any, right: any) => numberField(right, 'created_at_ms', 'createdAtMs') - numberField(left, 'created_at_ms', 'createdAtMs'))[0];
    const fileId = latest?.file_id || latest?.fileId;
    if (fileId) fileIds[agentId(hosts[index])] = fileId;
  });
  return fileIds;
}

function fileIdsFromSnapshots(hosts: HostView[], snapshots: Record<string, TaskSnapshot>) {
  const fileIds: Record<string, string> = {};
  hosts.forEach(host => {
    const id = agentId(host);
    const files = (snapshots[id]?.file_transfers || []).filter((file: any) => file.file_role !== 'shell_script');
    const latest = files
      .slice()
      .sort((left: any, right: any) => numberField(right, 'created_at_ms', 'createdAtMs') - numberField(left, 'created_at_ms', 'createdAtMs'))[0];
    const fileId = latest?.file_id || latest?.fileId;
    if (fileId) fileIds[id] = fileId;
  });
  return fileIds;
}

function clusterExecutionSummary(hosts: HostView[], summary: BatchSubmitSummary | null, snapshots: Record<string, TaskSnapshot>): ClusterExecutionSummary {
  const mergedSnapshots = { ...(summary?.snapshots || {}), ...snapshots };
  const unresolvedSubmitFailures = new Set(summary?.failedAgents || []);
  const rows = hosts.map(host => {
    const id = agentId(host);
    return clusterExecutionRow(host, mergedSnapshots[id], summary?.taskIds?.[id], !!summary, unresolvedSubmitFailures.has(id));
  });
  rows.forEach(row => {
    if (row.status !== 'pending' && row.status !== 'submit_failed') {
      unresolvedSubmitFailures.delete(agentId(row.host));
    }
  });
  const submitFailed = unresolvedSubmitFailures.size;
  const completedDurations = rows
    .filter(row => (row.status === 'success' || row.status === 'failed') && row.durationMs > 0)
    .map(row => row.durationMs);
  return {
    total: hosts.length,
    submitSucceeded: summary ? summary.total - submitFailed : 0,
    submitFailed,
    executionSucceeded: rows.filter(row => row.status === 'success').length,
    executionFailed: rows.filter(row => row.status === 'failed').length + submitFailed,
    running: rows.filter(row => row.status === 'running').length,
    pending: rows.filter(row => row.status === 'pending').length,
    durationCount: completedDurations.length,
    averageDurationMs: completedDurations.length ? Math.round(completedDurations.reduce((total, value) => total + value, 0) / completedDurations.length) : 0,
    maxDurationMs: completedDurations.length ? Math.max(...completedDurations) : 0,
    rows
  };
}

function clusterExecutionRow(host: HostView, snapshot?: TaskSnapshot, expectedTaskId?: string, hasBatch = false, submitFailed = false): ClusterExecutionRow {
  if (submitFailed && !expectedTaskId) {
    return {
      host,
      snapshot,
      status: 'submit_failed',
      label: '提交失败',
      taskId: '-',
      taskType: '-',
      exitCode: '-',
      outputBytes: 0,
      message: '提交请求未成功返回 task_id',
      outputText: '',
      outputPreview: '',
      outputLineCount: 0,
      outputPreviewLineCount: 0,
      outputFullUrl: undefined,
      durationMs: 0,
      durationLabel: '-',
      durationKind: 'none'
    };
  }
  const taskMatches = (item: any) => expectedTaskId
    ? item?.task_id === expectedTaskId || item?.taskId === expectedTaskId || item?.file_id === expectedTaskId || item?.fileId === expectedTaskId
    : !hasBatch;
  const completion = (snapshot?.completion_queue || []).find(taskMatches);
  const execution = (snapshot?.execution_queue || []).find(taskMatches)
    || activeHostTasks(host).find(taskMatches);
  const file = (snapshot?.file_transfers || []).find((entry: any) => taskMatches(entry));
  const stream = streamForTask(snapshot || null, expectedTaskId || completion?.task_id || completion?.taskId || execution?.task_id || execution?.taskId);
  const item = completion || execution || file;
  const outputSource = completion || stream || item;
  const outputText = completion ? completionOutput(completion) : stream ? streamOutput(stream) : '';
  const outputLineCount = Number(outputSource?.output_lines ?? outputSource?.outputLines ?? countLines(outputText));
  const outputFullUrl = completion
    && (completion.output_inline === false || completion.outputInline === false)
    ? `/api/agents/${encodeURIComponent(snapshot?.agent_id || agentId(host))}/tasks/completions/${encodeURIComponent(completion.task_id || completion.taskId)}/output`
    : undefined;
  const rawStatus = String(item?.status || '');
  const exitCode = completion?.exit_code ?? completion?.exitCode;
  const hasFailure = ['failed', 'timeout', 'timed_out', 'rejected'].includes(rawStatus)
    || (exitCode !== undefined && exitCode !== null && Number(exitCode) !== 0)
    || !!item?.runner_error;
  const hasSuccess = !hasFailure && ((!!completion && (rawStatus === 'completed' || exitCode === 0 || exitCode === '0')) || (!!file && rawStatus === 'received'));
  const hasRunning = !!execution && ['accepted', 'running'].includes(rawStatus || String(execution?.status || ''))
    || (!!stream && !completion);
  const status: ClusterExecutionRow['status'] = hasFailure ? 'failed' : hasSuccess ? 'success' : hasRunning ? 'running' : 'pending';
  const duration = taskDuration(item, status);
  return {
    host,
    snapshot,
    status,
    label: hasFailure ? '执行失败' : hasSuccess ? '' : hasRunning ? statusLabel(rawStatus || 'running') : '待回执',
    taskId: item?.task_id || item?.taskId || expectedTaskId || '-',
    taskType: taskLabels[item?.task_type || item?.taskType || stream?.task_type || stream?.taskType || ''] || item?.task_type || item?.taskType || stream?.task_type || stream?.taskType || '-',
    exitCode: exitCode === undefined || exitCode === null ? '-' : String(exitCode),
    outputBytes: Number(outputSource?.output_bytes ?? outputSource?.outputBytes ?? outputSource?.stream_bytes ?? outputSource?.streamBytes ?? 0),
    message: item?.runner_error || item?.error || item?.file_name || '-',
    outputText,
    outputPreview: outputText,
    outputLineCount,
    outputPreviewLineCount: outputLineCount,
    outputFullUrl,
    durationMs: duration.ms,
    durationLabel: duration.label,
    durationKind: duration.kind
  };
}

function taskDuration(item: any, status: ClusterExecutionRow['status']) {
  const durationMs = numberField(item, 'duration_ms', 'durationMs');
  const startedAt = numberField(item, 'started_at_ms', 'startedAtMs');
  const finishedAt = numberField(item, 'finished_at_ms', 'finishedAtMs');
  const runtimeMs = numberField(item, 'runtime_ms', 'runtimeMs');
  const elapsedMs = durationMs > 0 ? durationMs : startedAt > 0 && finishedAt > 0 ? Math.max(0, finishedAt - startedAt) : 0;
  if ((status === 'success' || status === 'failed') && elapsedMs > 0) {
    return { ms: elapsedMs, label: formatDuration(elapsedMs), kind: 'elapsed' as const };
  }
  const runningMs = runtimeMs > 0 ? runtimeMs : status === 'running' && startedAt > 0 ? Math.max(0, Date.now() - startedAt) : 0;
  if (status === 'running' && runningMs > 0) {
    return { ms: runningMs, label: formatDuration(runningMs), kind: 'running' as const };
  }
  return { ms: 0, label: '-', kind: 'none' as const };
}

function numberField(item: any, snakeKey: string, camelKey: string) {
  const value = Number(item?.[snakeKey] ?? item?.[camelKey] ?? 0);
  return Number.isFinite(value) ? value : 0;
}

function downloadFileName(summary: BatchSubmitSummary | null) {
  const kind = (summary?.kind || 'cluster-run').replace(/[^\w\u4e00-\u9fa5-]+/g, '-');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  return `pulse-${kind}-${stamp}.txt`;
}

function clusterExecutionText(execution: ClusterExecutionSummary, summary: BatchSubmitSummary | null) {
  const lines: string[] = [
    `Pulse Cluster Run Result`,
    `Generated: ${new Date().toISOString()}`,
    `Kind: ${summary?.kind || '-'}`,
    `Targets: ${execution.total}`,
    `Submit: success=${execution.submitSucceeded} failed=${execution.submitFailed}`,
    `Execution: success=${execution.executionSucceeded} failed=${execution.executionFailed} running=${execution.running} pending=${execution.pending}`,
    ''
  ];
  execution.rows.forEach((row, index) => {
    lines.push(`===== #${index + 1} ${normalizeAddress(row.host.ip)} =====`);
    lines.push(`status: ${row.label || row.status}`);
    lines.push(`task_id: ${row.taskId}`);
    lines.push(`task_type: ${row.taskType}`);
    lines.push(`exit_code: ${row.exitCode}`);
    lines.push(`duration: ${row.durationLabel}`);
    lines.push(`output_bytes: ${row.outputBytes}`);
    if (row.message !== '-') lines.push(`message: ${row.message}`);
    lines.push(`----- output -----`);
    lines.push(row.outputText || '(empty)');
    lines.push('');
  });
  return lines.join('\n');
}

async function saveTextFile(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const savePicker = (window as any).showSaveFilePicker;
  if (typeof savePicker === 'function') {
    const handle = await savePicker({
      suggestedName: filename,
      types: [{ description: 'Text file', accept: { 'text/plain': ['.txt'] } }]
    });
    const writable = await handle.createWritable();
    await writable.write(blob);
    await writable.close();
    return;
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function friendlyErrorText(error: unknown) {
  const raw = error instanceof Error ? error.message : String(error || '未知错误');
  if (/failed to fetch/i.test(raw)) {
    return '请求 coordinator 失败：网络不可达、页面连接断开，或 coordinator 正在重启。请稍后刷新后重试。';
  }
  if (/networkerror/i.test(raw)) {
    return '网络请求失败：请确认 coordinator 服务可访问。';
  }
  if (/^5\d\d\b/.test(raw)) return `coordinator 服务异常：${raw}`;
  if (/^4\d\d\b/.test(raw)) return `请求被 coordinator 拒绝：${raw}`;
  return raw;
}

function failedAgentsFromSettledResults(hosts: HostView[], results: PromiseSettledResult<unknown>[]) {
  return hosts
    .filter((_, index) => results[index]?.status === 'rejected')
    .map(host => agentId(host));
}

function sleep(ms: number) {
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

function isRetryableSubmitError(error: unknown) {
  const text = error instanceof Error ? error.message : String(error || '');
  return /failed to fetch|networkerror|load failed|timeout|timed out|network/i.test(text);
}

async function fetchJsonWithRetry<T>(url: string, init: RequestInit, retries = 2): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    try {
      return await fetchJson<T>(url, init);
    } catch (error) {
      lastError = error;
      if (attempt >= retries || !isRetryableSubmitError(error)) {
        throw error;
      }
      await sleep(350 * (attempt + 1));
    }
  }
  throw lastError;
}

async function settleWithConcurrency<T, R>(items: T[], concurrency: number, worker: (item: T, index: number) => Promise<R>): Promise<PromiseSettledResult<R>[]> {
  const results: PromiseSettledResult<R>[] = new Array(items.length);
  let nextIndex = 0;
  async function runNext() {
    while (nextIndex < items.length) {
      const index = nextIndex;
      nextIndex += 1;
      try {
        results[index] = { status: 'fulfilled', value: await worker(items[index], index) };
      } catch (reason) {
        results[index] = { status: 'rejected', reason };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, runNext));
  return results;
}

function submitToTargets(targets: HostView[], bodyForTarget: (target: HostView) => any) {
  return settleWithConcurrency(targets, 6, async target => {
    const id = encodeURIComponent(agentId(target));
    return fetchJsonWithRetry<TaskSnapshot>(`/api/agents/${id}/tasks`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(bodyForTarget(target))
    });
  });
}

function groupByCluster(hosts: HostView[]) {
  const groups = new Map<string, HostView[]>();
  hosts.forEach(host => {
    const cluster = host.cluster || 'unknown';
    groups.set(cluster, [...(groups.get(cluster) || []), host]);
  });
  return [...groups.entries()]
    .map(([cluster, clusterHosts]) => [cluster, sortHosts(clusterHosts)] as const)
    .sort(([a], [b]) => a.localeCompare(b));
}

function loadCollapsedClusters() {
  try {
    const raw = window.localStorage.getItem(clusterCollapseStorageKey);
    const parsed = raw ? JSON.parse(raw) : {};
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    return Object.fromEntries(Object.entries(parsed).filter(([, value]) => value === true));
  } catch {
    return {};
  }
}

function persistCollapsedClusters(value: Record<string, boolean>) {
  try {
    window.localStorage.setItem(clusterCollapseStorageKey, JSON.stringify(value));
  } catch {
    // Ignore storage failures and keep UI usable.
  }
}

function clusterHue(index: number) {
  return palette[index % palette.length];
}

function sortHosts(hosts: HostView[]) {
  return [...hosts].sort((left, right) =>
    normalizeAddress(left.ip).localeCompare(normalizeAddress(right.ip))
    || agentId(left).localeCompare(agentId(right)));
}

function sortClusterHosts(hosts: HostView[], mode: ClusterSortMode) {
  if (mode === 'ip') return sortHosts(hosts);
  return [...hosts].sort((left, right) => {
    const loadDelta = averageLoad(left) - averageLoad(right);
    if (loadDelta !== 0) return mode === 'load-asc' ? loadDelta : -loadDelta;
    return normalizeAddress(left.ip).localeCompare(normalizeAddress(right.ip))
      || agentId(left).localeCompare(agentId(right));
  });
}

function nextClusterSortMode(mode: ClusterSortMode): ClusterSortMode {
  if (mode === 'ip') return 'load-asc';
  if (mode === 'load-asc') return 'load-desc';
  return 'ip';
}

function clusterSortLabel(mode: ClusterSortMode) {
  if (mode === 'load-asc') return 'Load ↑';
  if (mode === 'load-desc') return 'Load ↓';
  return 'IP';
}

function taskNeedsAttention(task: any) {
  const status = String(task?.status || '');
  return ['queued', 'delivered', 'accepted', 'running', 'failed', 'timeout', 'timed_out', 'rejected'].includes(status);
}

function hostHealthNeedsAttention(host: HostView) {
  return host.status === 'warming' || host.status === 'expired';
}

function hostTaskNeedsAttention(host: HostView) {
  return activeHostTasks(host).some(taskNeedsAttention);
}

function hostNeedsAttention(host: HostView) {
  return hostHealthNeedsAttention(host) || hostTaskNeedsAttention(host);
}

function clusterAttentionHosts(hosts: HostView[]) {
  return hosts.filter(hostNeedsAttention);
}

function clusterAttentionReason(host: HostView) {
  const reasons: string[] = [];
  if (hostHealthNeedsAttention(host)) {
    reasons.push(statusLabel(host.status));
  }
  const taskStatuses = [...new Set(
    activeHostTasks(host)
      .filter(taskNeedsAttention)
      .map(task => statusLabel(String(task.status || '')))
  )];
  if (taskStatuses.length > 0) reasons.push(`任务 ${taskStatuses.join('、')}`);
  return reasons.join('；') || '需关注';
}

function clusterNeedsAttention(hosts: HostView[]) {
  return clusterAttentionHosts(hosts).length > 0;
}

function workerValue(worker: any, key: string, fallback = '-') {
  const value = worker?.[key];
  return value === undefined || value === null || value === '' ? fallback : String(value);
}

function hostDebugValue(host: HostView, snakeKey: keyof HostView, camelKey: keyof HostView, fallback: any = '-') {
  const value = host[snakeKey] ?? host[camelKey];
  return value === undefined || value === null || value === '' ? fallback : value;
}

function formatRssMb(worker: any) {
  const value = Number.parseFloat(workerValue(worker, 'rss_kb', '0'));
  return Number.isFinite(value) && value > 0 ? `${(value / 1024).toFixed(1)}MB` : '-';
}

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { cache: 'no-store', ...init });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return response.json();
}

function parseTaskArgs(input: string) {
  return input.split(/\s+/).map(part => part.trim()).filter(Boolean);
}

function taskVersion(task: any) {
  if (!task) return '-';
  return [
    task.task_id || task.taskId || '-',
    task.task_type || task.taskType || '-',
    task.status || '-',
    task.exit_code ?? task.exitCode ?? '-',
    task.output_sha256 || task.outputSha256 || '-',
    task.output_bytes ?? task.outputBytes ?? task.output?.length ?? '-',
    task.output_lines ?? task.outputLines ?? '-',
    task.stream_seq ?? task.streamSeq ?? '-',
    task.stream_bytes ?? task.streamBytes ?? '-',
    task.stream_lines ?? task.streamLines ?? '-'
  ].join(':');
}

function completionStack(snapshot: TaskSnapshot | null) {
  return snapshot?.completion_queue || [];
}

function newestCompletion(snapshot: TaskSnapshot | null) {
  const completions = completionStack(snapshot);
  return completions[0] || null;
}

function matchesTaskId(item: any, taskId?: string) {
  return !!taskId && (item?.task_id === taskId || item?.taskId === taskId);
}

function completionForTask(snapshot: TaskSnapshot | null, taskId?: string) {
  return taskId ? completionStack(snapshot).find(item => matchesTaskId(item, taskId)) || null : null;
}

function outputLines(value: string) {
  return value ? value.split('\n') : [];
}

function outputLogText(log: OutputLog) {
  return log.fullText ?? log.chunks.join('');
}

function outputLogExtends(log: OutputLog, value: string) {
  if (value.length < log.sourceLength) return false;
  const probeLength = Math.min(outputPrefixProbeChars, log.sourceLength);
  if (!probeLength) return true;
  const suffixStart = log.sourceLength - probeLength;
  return value.slice(0, probeLength) === log.sourceText.slice(0, probeLength)
    && value.slice(suffixStart, log.sourceLength) === log.sourceText.slice(suffixStart);
}

function snapshotVersion(snapshot: TaskSnapshot | null) {
  if (!snapshot) return '-';
  const executions = (snapshot.execution_queue || []).map(taskVersion).join('|');
  const completions = (snapshot.completion_queue || []).map(taskVersion).join('|');
  const streams = (snapshot.output_streams || []).map(taskVersion).join('|');
  const traces = (snapshot.traces || []).slice(0, 4).map(trace => [trace.task_id || '-', trace.event || trace.status || '-', trace.observed_at_ms || trace.observedAtMs || '-'].join(':')).join('|');
  return [snapshot.agent_id || '-', executions, completions, streams, traces].join('||');
}

function App() {
  const [hosts, setHosts] = useState<HostView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeHost, setActiveHost] = useState<HostView | null>(null);
  const [activeCluster, setActiveCluster] = useState<ActiveClusterRun | null>(null);
  const [snapshot, setSnapshot] = useState<TaskSnapshot | null>(null);
  const [output, setOutput] = useState('');
  const [outputLogRevision, setOutputLogRevision] = useState(0);
  const [focusedTaskId, setFocusedTaskId] = useState('');
  const [batchSummary, setBatchSummary] = useState<BatchSubmitSummary | null>(null);
  const [clusterSnapshots, setClusterSnapshots] = useState<Record<string, TaskSnapshot>>({});
  const [taskType, setTaskType] = useState('prepare_disk_layout_dry_run');
  const [collapsedClusters, setCollapsedClusters] = useState<Record<string, boolean>>(() => loadCollapsedClusters());
  const viewport = useRef({ left: 0, top: 0 });
  const snapshotVersionRef = useRef('');
  const snapshotRevisionRef = useRef(0);
  const outputRequestRef = useRef('');
  const outputSourceRef = useRef<EventSource | null>(null);
  const outputCacheRef = useRef<Record<string, string>>({});
  const focusedTaskIdRef = useRef('');
  const outputLogRef = useRef<OutputLog | null>(null);
  const outputLogFrameRef = useRef<number | null>(null);
  const scrollingRef = useRef(false);
  const scrollIdleTimerRef = useRef<number | null>(null);
  const pendingHostsRef = useRef<HostView[] | null>(null);
  const activeTargetHost = activeHost || activeCluster?.hosts[0] || null;
  const clusterAgentKey = useMemo(() => (activeCluster?.hosts || []).map(agentId).join(','), [activeCluster?.name, activeCluster?.hosts]);

  function cancelOutputLogRender() {
    if (outputLogFrameRef.current !== null) {
      window.cancelAnimationFrame(outputLogFrameRef.current);
      outputLogFrameRef.current = null;
    }
  }

  function closeOutputStream() {
    outputSourceRef.current?.close();
    outputSourceRef.current = null;
    cancelOutputLogRender();
  }

  function publishOutputLog() {
    setOutputLogRevision(previous => previous + 1);
  }

  function clearOutputLog() {
    cancelOutputLogRender();
    outputLogRef.current = null;
    publishOutputLog();
  }

  function replaceOutputLog(key: string, value: string, chunks: string[] = []) {
    cancelOutputLogRender();
    outputLogRef.current = {
      key,
      lines: outputLines(value),
      sourceText: value,
      sourceLength: value.length,
      chunks,
      fullText: chunks.length ? null : value
    };
    setOutput('');
    publishOutputLog();
  }

  function appendOutputLogLines(log: OutputLog, chunk: string) {
    if (!chunk) return;
    const partial = log.lines.pop() || '';
    for (const line of `${partial}${chunk}`.split('\n')) {
      log.lines.push(line);
    }
  }

  function scheduleOutputLogRender(key: string) {
    if (outputLogFrameRef.current !== null) return;
    outputLogFrameRef.current = window.requestAnimationFrame(() => {
      outputLogFrameRef.current = null;
      if (outputLogRef.current?.key === key) {
        publishOutputLog();
      }
    });
  }

  function syncLiveOutputLog(key: string, value: string) {
    let log = outputLogRef.current;
    if (!log || log.key !== key) {
      replaceOutputLog(key, value);
      return;
    }
    if (value.length === log.sourceLength) return;
    if (outputLogExtends(log, value)) {
      appendOutputLogLines(log, value.slice(log.sourceText.length));
    } else {
      log.lines = outputLines(value);
    }
    log.sourceText = value;
    log.sourceLength = value.length;
    log.fullText = value;
    scheduleOutputLogRender(key);
  }

  function appendCompletionOutputLog(key: string, chunk: string, originalChunk = chunk) {
    const log = outputLogRef.current;
    if (!log || log.key !== key) return;
    log.fullText = null;
    log.chunks.push(originalChunk);
    if (!chunk) return;
    appendOutputLogLines(log, chunk);
    log.sourceLength += chunk.length;
    scheduleOutputLogRender(key);
  }

  function finishCompletionOutputLog(key: string, fullOutput: string) {
    cancelOutputLogRender();
    const log = outputLogRef.current;
    if (!log || log.key !== key) {
      replaceOutputLog(key, fullOutput);
      return;
    }
    if (log.sourceLength !== fullOutput.length) {
      log.lines = outputLines(fullOutput);
    }
    log.sourceText = fullOutput;
    log.sourceLength = fullOutput.length;
    log.fullText = fullOutput;
    log.chunks = [];
    publishOutputLog();
  }

  function showCompletedOutput(key: string, fullOutput: string) {
    replaceOutputLog(key, fullOutput);
  }

  function showCompletedOutputLog(key: string, fullOutput: string) {
    replaceOutputLog(key, fullOutput);
  }

  function focusOutputTask(taskId: string) {
    focusedTaskIdRef.current = taskId;
    setFocusedTaskId(taskId);
  }

  function beginTaskSubmission() {
    const revision = snapshotRevisionRef.current;
    focusOutputTask('');
    outputRequestRef.current = '';
    closeOutputStream();
    clearOutputLog();
    setOutput('任务已提交，等待 agent 实时输出...');
    return revision;
  }

  function applySubmittedSnapshot(data: TaskSnapshot, submissionRevision: number) {
    if (snapshotRevisionRef.current === submissionRevision) {
      applyTaskSnapshot(data);
    }
  }

  async function loadCompletionOutputFallback(agentIdValue: string, result: any, key: string) {
    try {
      const taskId = result?.task_id || result?.taskId;
      const url = `/api/agents/${encodeURIComponent(agentIdValue)}/tasks/completions/${encodeURIComponent(taskId)}/output`;
      const full = await fetchJson<any>(url);
      const fullOutput = completionOutput(full);
      outputCacheRef.current[key] = fullOutput;
      if (outputRequestRef.current === key) {
        showCompletedOutputLog(key, fullOutput);
      }
    } catch (err) {
      if (outputRequestRef.current === key) {
        const preview = completionOutput(result);
        clearOutputLog();
        setOutput(`${preview}${preview ? '\n\n' : ''}[完整输出加载失败: ${err instanceof Error ? err.message : String(err)}]`);
      }
    }
  }

  function streamCompletionOutput(agentIdValue: string, result: any) {
    const taskId = result?.task_id || result?.taskId;
    if (!agentIdValue || !taskId) return;
    const key = `${agentIdValue}:${taskId}:${result.output_sha256 || result.outputSha256 || ''}`;
    if (outputCacheRef.current[key]) {
      outputRequestRef.current = key;
      closeOutputStream();
      showCompletedOutputLog(key, outputCacheRef.current[key]);
      return;
    }
    if (outputRequestRef.current === key && outputSourceRef.current) {
      return;
    }
    outputRequestRef.current = key;
    closeOutputStream();
    if (!('EventSource' in window)) {
      void loadCompletionOutputFallback(agentIdValue, result, key);
      return;
    }
    const preview = completionOutput(result);
    const chunks: string[] = [];
    let previewCharsRemaining = preview.length;
    replaceOutputLog(key, preview);
    const streamUrl = result.output_stream_url || result.outputStreamUrl
      || `/api/agents/${encodeURIComponent(agentIdValue)}/tasks/completions/${encodeURIComponent(taskId)}/output_stream`;
    const source = new EventSource(streamUrl);
    outputSourceRef.current = source;
    const finish = () => {
      const fullOutput = chunks.join('');
      outputCacheRef.current[key] = fullOutput;
      if (outputRequestRef.current === key) {
        finishCompletionOutputLog(key, fullOutput);
      }
      source.close();
      if (outputSourceRef.current === source) {
        outputSourceRef.current = null;
      }
    };
    source.addEventListener('completion.output_start', (event: MessageEvent<string>) => {
      if (outputRequestRef.current === key) {
        const offset = Number(JSON.parse(event.data).offset || 0);
        if (offset === 0) {
          previewCharsRemaining = preview.length;
          replaceOutputLog(key, preview);
        } else {
          previewCharsRemaining = 0;
        }
      }
    });
    source.addEventListener('completion.output_chunk', (event: MessageEvent<string>) => {
      if (outputRequestRef.current !== key) return;
      const payload = JSON.parse(event.data);
      const chunk = String(payload.chunk || '');
      chunks.push(chunk);
      const skipped = Math.min(previewCharsRemaining, chunk.length);
      previewCharsRemaining -= skipped;
      appendCompletionOutputLog(key, chunk.slice(skipped), chunk);
    });
    source.addEventListener('completion.output_end', finish);
    source.onerror = () => {
      // EventSource reconnects automatically and sends Last-Event-ID, which is the next output offset.
    };
  }

  function applyTaskSnapshot(data: TaskSnapshot) {
    const version = snapshotVersion(data);
    if (snapshotVersionRef.current !== version) {
      snapshotVersionRef.current = version;
      snapshotRevisionRef.current += 1;
      setSnapshot(data);
    }
    const selectedTaskId = focusedTaskIdRef.current;
    const selectedCompletion = completionForTask(data, selectedTaskId);
    const selectedStream = selectedTaskId ? streamForTask(data, selectedTaskId) : null;
    if (selectedTaskId && !selectedCompletion) {
      if (outputRequestRef.current) {
        outputRequestRef.current = '';
        closeOutputStream();
      }
      if (selectedStream) {
        const selectedAgent = data.agent_id || data.agentId || '';
        syncLiveOutputLog(`${selectedAgent}:${selectedTaskId}:live`, streamOutput(selectedStream));
      } else {
        const waiting = '任务已提交，等待 agent 实时输出...';
        clearOutputLog();
        setOutput(current => current === waiting ? current : waiting);
      }
      return;
    }
    const latest = selectedCompletion || newestCompletion(data);
    if (!latest) {
      outputRequestRef.current = '';
      closeOutputStream();
      clearOutputLog();
      setOutput(current => current === '' ? current : '');
      return;
    }
    const latestOutput = completionOutput(latest);
    const latestAgent = data.agent_id || data.agentId || latest.agent_id || latest.agentId || '';
    const latestTaskId = latest.task_id || latest.taskId || '';
    const outputKey = `${latestAgent}:${latestTaskId}:${latest.output_sha256 || latest.outputSha256 || ''}`;
    if (latest.output_inline === false || latest.outputInline === false) {
      if (outputCacheRef.current[outputKey]) {
        outputRequestRef.current = outputKey;
        closeOutputStream();
        showCompletedOutputLog(outputKey, outputCacheRef.current[outputKey]);
      } else {
        streamCompletionOutput(latestAgent, latest);
      }
      return;
    }
    outputRequestRef.current = outputKey;
    closeOutputStream();
    showCompletedOutput(outputKey, latestOutput);
  }

  async function refreshHosts() {
    viewport.current = { left: window.scrollX, top: window.scrollY };
    try {
      const data = await fetchJson<HostView[]>('/api/hosts');
      if (scrollingRef.current) {
        pendingHostsRef.current = data;
      } else {
        applyHosts(data);
      }
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  function applyHosts(data: HostView[]) {
    recordLoadSamples(data);
    setHosts(data);
    requestAnimationFrame(() => window.scrollTo(viewport.current.left, viewport.current.top));
  }

  async function refreshSnapshot(host: HostView) {
    const id = encodeURIComponent(agentId(host));
    const data = await fetchJson<TaskSnapshot>(`/api/agents/${id}/tasks`);
    applyTaskSnapshot(data);
  }

  useEffect(() => {
    refreshHosts();
    const timer = window.setInterval(refreshHosts, 5000);
    return () => {
      window.clearInterval(timer);
      closeOutputStream();
    };
  }, []);

  useEffect(() => {
    const flushPendingHosts = () => {
      scrollingRef.current = false;
      if (!pendingHostsRef.current) return;
      const pendingHosts = pendingHostsRef.current;
      pendingHostsRef.current = null;
      applyHosts(pendingHosts);
    };
    const onScroll = () => {
      scrollingRef.current = true;
      if (scrollIdleTimerRef.current) {
        window.clearTimeout(scrollIdleTimerRef.current);
      }
      scrollIdleTimerRef.current = window.setTimeout(flushPendingHosts, 350);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      window.removeEventListener('scroll', onScroll);
      if (scrollIdleTimerRef.current) {
        window.clearTimeout(scrollIdleTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!activeTargetHost || activeCluster) return;
    if (!('EventSource' in window)) {
      refreshSnapshot(activeTargetHost).catch(err => setOutput(String(err)));
      const timer = window.setInterval(() => refreshSnapshot(activeTargetHost).catch(err => setOutput(String(err))), 2000);
      return () => window.clearInterval(timer);
    }

    const id = encodeURIComponent(agentId(activeTargetHost));
    const events = new EventSource(`/api/agents/${id}/tasks/stream`);
    const handleSnapshot = (event: MessageEvent<string>) => {
      try {
        applyTaskSnapshot(JSON.parse(event.data) as TaskSnapshot);
      } catch (err) {
        setOutput(String(err));
      }
    };
    events.addEventListener('task.snapshot', handleSnapshot as EventListener);
    events.onerror = () => {
      // EventSource reconnects automatically; keep current output visible.
    };
    return () => events.close();
  }, [activeTargetHost?.ip, activeTargetHost?.agent_id, activeTargetHost?.agentId, activeCluster?.name]);

  useEffect(() => {
    if (!activeCluster || !batchSummary) return;
    const cluster = activeCluster;
    const clusterAgents = new Set(cluster.hosts.map(agentId));
    let disposed = false;
    async function refreshClusterSnapshots() {
      const entries = await Promise.allSettled(cluster.hosts.map(async host => {
        const id = agentId(host);
        const data = await fetchJson<TaskSnapshot>(`/api/agents/${encodeURIComponent(id)}/tasks`);
        return [id, data] as const;
      }));
      if (disposed) return;
      setClusterSnapshots(prev => {
        const next = { ...prev };
        entries.forEach(entry => {
          if (entry.status === 'fulfilled') {
            next[entry.value[0]] = entry.value[1];
          }
        });
        return next;
      });
    }
    if (!('EventSource' in window)) {
      refreshClusterSnapshots().catch(err => setOutput(String(err)));
      const timer = window.setInterval(() => refreshClusterSnapshots().catch(err => setOutput(String(err))), 3000);
      return () => {
        disposed = true;
        window.clearInterval(timer);
      };
    }

    const agents = cluster.hosts.map(host => encodeURIComponent(agentId(host))).join(',');
    const events = new EventSource(`/api/tasks/stream?agents=${agents}`);
    const handleSnapshot = (event: MessageEvent<string>) => {
      try {
        const data = JSON.parse(event.data) as TaskSnapshot;
        const id = data.agent_id || (data as any).agentId || '';
        if (!id || !clusterAgents.has(id) || disposed) return;
        setClusterSnapshots(prev => {
          const version = snapshotVersion(data);
          if (snapshotVersion(prev[id] || null) === version) return prev;
          return { ...prev, [id]: data };
        });
      } catch (err) {
        setOutput(String(err));
      }
    };
    events.addEventListener('task.snapshot', handleSnapshot as EventListener);
    events.onerror = () => {
      // EventSource reconnects automatically; keep the current cluster result visible.
    };
    return () => {
      disposed = true;
      events.close();
    };
  }, [activeCluster?.name, clusterAgentKey, batchSummary?.updatedAt]);

  const groups = useMemo(() => groupByCluster(hosts), [hosts]);
  const attentionClusters = useMemo(() => new Set(groups.filter(([, clusterHosts]) => clusterNeedsAttention(clusterHosts)).map(([cluster]) => cluster)), [groups]);
  const alive = hosts.filter(host => host.status === 'alive').length;
  const avgLoad = hosts.length ? hosts.reduce((sum, host) => sum + averageLoad(host), 0) / hosts.length : 0;
  const handleHostRun = useCallback((host: HostView) => {
    snapshotVersionRef.current = '';
    snapshotRevisionRef.current += 1;
    closeOutputStream();
    setActiveHost(host);
    setActiveCluster(null);
    setSnapshot(null);
    setClusterSnapshots({});
    focusOutputTask('');
    clearOutputLog();
    setOutput('');
    setBatchSummary(null);
  }, []);
  const handleClusterRun = useCallback((cluster: string, clusterHosts: HostView[]) => {
    snapshotVersionRef.current = '';
    snapshotRevisionRef.current += 1;
    closeOutputStream();
    setActiveHost(null);
    setActiveCluster({ name: cluster, hosts: sortHosts(clusterHosts) });
    setSnapshot(null);
    setClusterSnapshots({});
    focusOutputTask('');
    clearOutputLog();
    setOutput('');
    setBatchSummary(null);
  }, []);
  const handleClusterToggle = useCallback((cluster: string) => {
    setCollapsedClusters(prev => {
      const next = { ...prev };
      if (attentionClusters.has(cluster)) {
        delete next[cluster];
        return next;
      }
      if (next[cluster]) delete next[cluster];
      else next[cluster] = true;
      return next;
    });
  }, [attentionClusters]);

  useEffect(() => {
    setCollapsedClusters(prev => {
      let changed = false;
      const next = { ...prev };
      attentionClusters.forEach(cluster => {
        if (next[cluster]) {
          delete next[cluster];
          changed = true;
        }
      });
      return changed ? next : prev;
    });
  }, [attentionClusters]);

  useEffect(() => {
    persistCollapsedClusters(collapsedClusters);
  }, [collapsedClusters]);

  return <ConfigProvider autoInsertSpaceInButton={false} theme={{ algorithm: theme.defaultAlgorithm, token: { borderRadius: 20, colorPrimary: '#2563eb', fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", Inter, "Segoe UI", sans-serif' } }}>
    <main className="pulse-page">
      <section className="pulse-hero">
        <Card className="hero-main" variant="outlined">
          <Typography.Text className="hero-eyebrow">Pulse 心跳平台</Typography.Text>
          <Typography.Title level={1}>心跳平台，连接运维现场</Typography.Title>
          <Typography.Paragraph className="hero-subtitle">任务、资源、监控与告警，沿一条消息链自然流动。</Typography.Paragraph>
          <Space size="middle" wrap>
            <Button type="primary" shape="round" size="large" href="#clusters">主机</Button>
            <Button shape="round" size="large" href="#metrics">时序</Button>
            <Button shape="round" size="large" href="#capability">能力</Button>
          </Space>
        </Card>
        <div className="hero-side">
          <Card id="capability" className="hero-capability-card" variant="outlined">
            <Typography.Text className="capability-title">平台能力</Typography.Text>
            <div className="hero-cap-grid">
              {[
                ['任务', '下发、执行、回执。'], ['集群', '分组、编排、收敛。'],
                ['资源', '采集、聚合、判断。'], ['告警', '识别、定位、闭环。']
              ].map(([title, text]) => <Card className="cap-card" variant="borderless" key={title}><b>{title}</b><span>{text}</span></Card>)}
            </div>
          </Card>
        </div>
        <div className="hero-metrics">
          <Card><Statistic title="主机" value={hosts.length} suffix="台" loading={loading}/></Card>
          <Card><Statistic title="在线率" value={hosts.length ? Math.round(alive * 100 / hosts.length) : 0} suffix="%"/></Card>
          <Card><Statistic title="5min AVG" value={formatLoad(avgLoad)}/></Card>
          <Card><Statistic title="刷新" value="5s"/></Card>
        </div>
      </section>

      {error && <Card className="error-card"><Typography.Text type="danger">{error}</Typography.Text></Card>}

      <MetricsPanel hosts={hosts} />

      <section id="clusters" className="clusters">
        {groups.map(([cluster, clusterHosts], index) => <ClusterSection
          key={cluster}
          cluster={cluster}
          hosts={clusterHosts}
          hue={clusterHue(index)}
          collapsed={!!collapsedClusters[cluster] && !attentionClusters.has(cluster)}
          needsAttention={attentionClusters.has(cluster)}
          onToggle={handleClusterToggle}
          onRun={handleHostRun}
          onClusterRun={handleClusterRun}
        />)}
      </section>

      <TaskModal
        host={activeTargetHost}
        clusterName={activeCluster?.name || ''}
        clusterHosts={activeCluster?.hosts || []}
        open={!!activeTargetHost}
        onClose={() => { setActiveHost(null); setActiveCluster(null); setClusterSnapshots({}); }}
        snapshot={snapshot}
        batchSummary={batchSummary}
        clusterSnapshots={clusterSnapshots}
        output={output}
        outputLog={outputLogRef.current}
        outputLogRevision={outputLogRevision}
        focusedTaskId={focusedTaskId}
        taskType={taskType}
        setTaskType={setTaskType}
        onRun={async args => {
          const targets = activeCluster?.hosts || (activeHost ? [activeHost] : []);
          if (!targets.length) return;
          const submissionRevision = beginTaskSubmission();
          const results = await submitToTargets(targets, () => ({ task_type: taskType, args }));
          const first = results.find((result): result is PromiseFulfilledResult<TaskSnapshot> => result.status === 'fulfilled');
          if (first && !activeCluster) {
            focusOutputTask(submittedTaskId(first.value, ['task.enqueued']));
          }
          if (first) applySubmittedSnapshot(first.value, submissionRevision);
          const snapshots = snapshotsFromSettledResults(targets, results);
          setClusterSnapshots(snapshots);
          const failed = results.filter(result => result.status === 'rejected');
          setBatchSummary({
            kind: '预定义任务',
            total: targets.length,
            succeeded: targets.length - failed.length,
            failed: failed.length,
            failedAgents: failedAgentsFromSettledResults(targets, results),
            taskIds: taskIdsFromSettledResults(targets, results, ['task.enqueued']),
            message: failed.length ? `任务提交部分失败：${targets.length - failed.length}/${targets.length}` : `任务已提交：${targets.length}/${targets.length}`,
            errors: failed.map(result => friendlyErrorText((result as PromiseRejectedResult).reason)).slice(0, 8),
            updatedAt: Date.now(),
            snapshots
          });
          if (failed.length) {
            setOutput(`集群下发部分失败: ${failed.length}/${targets.length}\n${failed.map(result => friendlyErrorText((result as PromiseRejectedResult).reason)).join('\n')}`);
          }
        }}
        onFilePut={async payload => {
          const targets = activeCluster?.hosts || (activeHost ? [activeHost] : []);
          if (!targets.length) return;
          const submissionRevision = snapshotRevisionRef.current;
          const response = await fetchJsonWithRetry<BatchFilePutResponse>('/api/files/batch_put', {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({
              agent_ids: targets.map(agentId),
              file_role: 'generic_file',
              target_dir: payload.target_dir || 'files',
              ...payload
            })
          });
          const snapshots = response.snapshots || {};
          const firstSnapshot = Object.values(snapshots)[0];
          if (firstSnapshot) applySubmittedSnapshot(firstSnapshot, submissionRevision);
          setClusterSnapshots(snapshots);
          const failedAgents = response.failed_agents || response.failedAgents || [];
          const failed = failedAgents.length;
          const errors = response.errors || {};
          setBatchSummary({
            kind: '文件上传',
            total: targets.length,
            succeeded: targets.length - failed,
            failed,
            failedAgents,
            taskIds: fileIdsFromSnapshots(targets, snapshots),
            message: failed ? `文件上传提交部分失败：${targets.length - failed}/${targets.length}` : `文件上传已提交：${targets.length}/${targets.length}`,
            errors: Object.entries(errors).map(([agent, error]) => `${agent}: ${error}`).slice(0, 8),
            updatedAt: Date.now(),
            snapshots
          });
          setOutput(failed ? `文件上传提交部分失败: ${failed}/${targets.length}` : '文件上传已批量入队，等待 agent 心跳确认。');
        }}
        onShellRun={async (payload, args) => {
          const targets = activeCluster?.hosts || (activeHost ? [activeHost] : []);
          if (!targets.length) return;
          const submissionRevision = beginTaskSubmission();
          const results = await submitToTargets(targets, () => ({ operation: 'shell_script', args, ...payload }));
          const first = results.find((result): result is PromiseFulfilledResult<TaskSnapshot> => result.status === 'fulfilled');
          if (first && !activeCluster) {
            focusOutputTask(submittedTaskId(first.value, ['shell.enqueued']));
          }
          if (first) applySubmittedSnapshot(first.value, submissionRevision);
          const snapshots = snapshotsFromSettledResults(targets, results);
          setClusterSnapshots(snapshots);
          const failed = results.filter(result => result.status === 'rejected');
          setBatchSummary({
            kind: 'Shell 执行',
            total: targets.length,
            succeeded: targets.length - failed.length,
            failed: failed.length,
            failedAgents: failedAgentsFromSettledResults(targets, results),
            taskIds: taskIdsFromSettledResults(targets, results, ['shell.enqueued']),
            message: failed.length ? `Shell 执行提交部分失败：${targets.length - failed.length}/${targets.length}` : `Shell 执行已提交：${targets.length}/${targets.length}`,
            errors: failed.map(result => friendlyErrorText((result as PromiseRejectedResult).reason)).slice(0, 8),
            updatedAt: Date.now(),
            snapshots
          });
          if (failed.length) {
            setOutput(`Shell 执行提交部分失败: ${failed.length}/${targets.length}`);
          }
        }}
        onPop={async () => {
          const running = activeTargetHost
            ? activeHostTasks(activeTargetHost).length > 0 || (snapshot?.execution_queue || []).length > 0
            : false;
          if (!activeTargetHost || running || !snapshot?.completion_queue?.[0]) {
            if (activeTargetHost) await refreshSnapshot(activeTargetHost);
            return;
          }
          const id = encodeURIComponent(agentId(activeTargetHost));
          const completedTaskId = snapshot.completion_queue[0].task_id;
          await fetchJson(`/api/agents/${id}/tasks/completions/${encodeURIComponent(completedTaskId)}/pop`, { method: 'POST' });
          if (focusedTaskIdRef.current === completedTaskId) {
            focusOutputTask('');
          }
          await refreshSnapshot(activeTargetHost);
        }}
      />
    </main>
  </ConfigProvider>;
}

const MetricsPanel = memo(function MetricsPanel({ hosts }: { hosts: HostView[] }) {
  const [catalog, setCatalog] = useState<MetricCatalogItem[]>([]);
  const [storage, setStorage] = useState<MetricStorageHealth | null>(null);
  const [metric, setMetric] = useState('agent.thread_count');
  const [selectedAgents, setSelectedAgents] = useState<string[]>([]);
  const [draftAgents, setDraftAgents] = useState<string[]>([]);
  const [selectedCluster, setSelectedCluster] = useState('all');
  const [fleetMode, setFleetMode] = useState(false);
  const [rangeMinutes, setRangeMinutes] = useState(30);
  const [result, setResult] = useState<MetricQueryResultView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [liveStatus, setLiveStatus] = useState('connecting');
  const [lastInvalidateAt, setLastInvalidateAt] = useState<number | null>(null);
  const [invalidatedRange, setInvalidatedRange] = useState<MetricInvalidation | null>(null);
  const [pageVisible, setPageVisible] = useState(() => document.visibilityState !== 'hidden');
  const [fixedRangeEndMs, setFixedRangeEndMs] = useState<number | null>(null);
  const [frontendMetrics, setFrontendMetrics] = useState({ queryMs: 0, renderMs: 0 });
  const [isApplyingHostSelection, startHostSelectionTransition] = useTransition();
  const queryController = useMemo(() => new MetricQueryController(fetchJson), []);
  const renderScheduler = useMemo(() => new RenderScheduler(), []);
  const deferredHosts = useDeferredValue(hosts);
  const agentOptions = useMemo(() => sortHosts(deferredHosts)
    .filter(host => host.status === 'alive')
    .map(host => ({
      value: agentId(host),
      label: `${normalizeAddress(host.ip) === '-' ? agentId(host) : normalizeAddress(host.ip)} · ${host.cluster || 'unknown'}`
    }))
    .filter(option => option.value), [deferredHosts]);
  const metricOptions = useMemo(() => catalog.map(item => ({
    value: item.metric,
    label: `${item.title} (${item.unit || '-'})`
  })), [catalog]);
  const clusterOptions = useMemo(() => {
    const counts = new Map<string, { total: number; alive: number }>();
    hosts.forEach(host => {
      const cluster = host.cluster && host.cluster !== '-' ? host.cluster : 'unknown';
      const current = counts.get(cluster) || { total: 0, alive: 0 };
      current.total++;
      if (host.status === 'alive') current.alive++;
      counts.set(cluster, current);
    });
    return [
      { value: 'all', label: `全部集群 (${hosts.filter(host => host.status === 'alive').length}/${hosts.length})` },
      ...[...counts.entries()]
        .sort((left, right) => right[1].alive - left[1].alive || left[0].localeCompare(right[0]))
        .map(([cluster, count]) => ({
          value: cluster,
          label: `${cluster} (${count.alive}/${count.total})`
        }))
    ];
  }, [hosts]);
  const clusterHosts = useMemo(() => selectedCluster === 'all'
    ? hosts
    : hosts.filter(host => (host.cluster && host.cluster !== '-' ? host.cluster : 'unknown') === selectedCluster), [hosts, selectedCluster]);
  const clusterAgentSet = useMemo(() => new Set(clusterHosts.map(agentId)), [clusterHosts]);
  const scopedAgentOptions = useMemo(() => selectedCluster === 'all'
    ? agentOptions
    : agentOptions.filter(option => clusterAgentSet.has(option.value)), [agentOptions, clusterAgentSet, selectedCluster]);
  const clusterAliveCount = clusterHosts.filter(host => host.status === 'alive').length;
  const clusterLeaderCount = clusterHosts.filter(host => (host.groupMode || host.group_mode) === 'leader').length;
  const clusterDirectCount = clusterHosts.filter(host => (host.groupMode || host.group_mode) === 'direct').length;
  const activeMetric = catalog.find(item => item.metric === metric);
  const visibleAgents = fleetMode ? [] : selectedAgents.length ? selectedAgents : scopedAgentOptions.slice(0, 3).map(option => option.value);
  const activeCluster = selectedCluster === 'all' ? undefined : selectedCluster;
  const rangePaused = fixedRangeEndMs !== null;
  const livePaused = rangePaused || !pageVisible;
  const storageStatus = storage?.status || 'unknown';
  const assessment = metricAssessment(metric, result, storageStatus);
  const selectedAgentKey = selectedAgents.join(',');
  const draftAgentKey = draftAgents.join(',');
  const hostSelectionDirty = draftAgentKey !== selectedAgentKey || (fleetMode && draftAgents.length > 0);

  async function loadMetrics(nextMetric = metric, nextAgents = visibleAgents, nextRangeMinutes = rangeMinutes, nextNowMs = fixedRangeEndMs ?? undefined, nextCluster = activeCluster) {
    const queryStart = performance.now();
    setLoading(true);
    setError('');
    try {
      const data = await queryController.queryRange({
        metric: nextMetric,
        agents: nextAgents,
        cluster: nextCluster,
        rangeMinutes: nextRangeMinutes,
        nowMs: nextNowMs,
        topN: fleetMode ? 12 : undefined,
        seriesLimit: 12
      });
      const queryMs = Math.round(performance.now() - queryStart);
      const renderStart = performance.now();
      renderScheduler.schedule(() => {
        setResult(data);
        setFrontendMetrics({
          queryMs,
          renderMs: Math.round(performance.now() - renderStart)
        });
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    queryController.catalog().then(items => {
      setCatalog(items);
      if (items.length && !items.some(item => item.metric === metric)) {
        setMetric(items[0].metric);
      }
    }).catch(err => setError(err instanceof Error ? err.message : String(err)));
    queryController.storage().then(setStorage).catch(err => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  useEffect(() => {
    if (fleetMode || selectedAgents.length || scopedAgentOptions.length === 0) return;
    const defaults = scopedAgentOptions.slice(0, 3).map(option => option.value);
    setSelectedAgents(defaults);
    setDraftAgents(defaults);
  }, [scopedAgentOptions, selectedAgents.length, fleetMode]);

  useEffect(() => {
    setDraftAgents(selectedAgents);
  }, [selectedAgentKey]);

  useEffect(() => {
    if (!metric) return;
    if (!pageVisible) {
      setLiveStatus('paused-hidden');
      return;
    }
    loadMetrics(metric, visibleAgents, rangeMinutes, fixedRangeEndMs ?? undefined, activeCluster);
  }, [metric, selectedAgents.join(','), rangeMinutes, pageVisible, fixedRangeEndMs, fleetMode, selectedCluster]);

  useEffect(() => {
    const onVisibilityChange = () => setPageVisible(document.visibilityState !== 'hidden');
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, []);

  useEffect(() => {
    if (!('EventSource' in window)) {
      setLiveStatus('fallback');
      return;
    }
    const events = new EventSource('/api/metrics/stream');
    events.onopen = () => setLiveStatus('connected');
    events.addEventListener('storage.health', (event: MessageEvent<string>) => {
      try {
        setStorage(JSON.parse(event.data) as MetricStorageHealth);
      } catch {
        setLiveStatus('degraded');
      }
    });
    events.addEventListener('metric.invalidate', (event: MessageEvent<string>) => {
      const invalidation = parseInvalidation(event.data);
      setLastInvalidateAt(Date.now());
      if (!invalidation || (invalidation.metrics.length && !invalidation.metrics.includes(metric))) {
        return;
      }
      if (rangePaused) {
        setLiveStatus('paused-range');
        return;
      }
      queryController.invalidate();
      setInvalidatedRange(current => mergeInvalidation(current, invalidation));
    });
    events.onerror = () => setLiveStatus('reconnecting');
    return () => events.close();
  }, [metric, queryController, rangePaused]);

  useEffect(() => () => renderScheduler.cancel(), [renderScheduler]);

  useEffect(() => {
    if (!invalidatedRange || !metric) return;
    if (livePaused) return;
    const timer = window.setTimeout(async () => {
      const now = Date.now();
      const visibleFrom = now - rangeMinutes * 60_000;
      const startMs = Math.max(visibleFrom, invalidatedRange.from);
      const endMs = Math.max(startMs, invalidatedRange.to);
      try {
        const patch = await queryController.queryRange({
          metric,
          agents: visibleAgents,
          cluster: activeCluster,
          startMs,
          endMs,
          stepMs: 10_000,
          pointLimit: 20_000,
          seriesLimit: 12,
          topN: fleetMode ? 12 : undefined,
          cache: false
        });
        renderScheduler.schedule(() => setResult(current => SeriesStore.merge(current, patch)));
        setInvalidatedRange(null);
      } catch (err) {
        setLiveStatus('stale');
        setError(err instanceof Error ? err.message : String(err));
      }
    }, 500);
    return () => window.clearTimeout(timer);
  }, [invalidatedRange, metric, selectedAgents.join(','), rangeMinutes, livePaused, fleetMode, selectedCluster]);

  const seriesStore = useMemo(() => new SeriesStore(result), [result]);
  const seriesCount = seriesStore.seriesCount();
  const pointCount = seriesStore.pointCount();
  const storageTone = storageStatus === 'ok' ? 'success' : storageStatus === 'disabled' ? 'default' : 'warning';

  return <Card id="metrics" className="metrics-panel metrics-panel-apple" variant="outlined">
    <div className="metrics-layout">
      <div className="metrics-hero">
        <div>
          <span className="metrics-eyebrow">METRICS OVERVIEW</span>
          <Typography.Title level={2}>集群健康洞察</Typography.Title>
          <Typography.Text type="secondary">选择一个集群，直接观察 heartbeat 架构、plan 收敛、采集实效和发送链路。</Typography.Text>
        </div>
        <Space size={8} wrap className="metrics-status-strip">
          <Tag color={storageTone}>{storageStatus}</Tag>
          <Tag>{liveStatus}</Tag>
          {livePaused && <Tag color="gold">live paused</Tag>}
          {fleetMode && <Tag color="cyan">TopN + aggregate</Tag>}
        </Space>
      </div>
      <div className="metrics-topline">
        <div className="metrics-scope-card metrics-control-card">
          <span className="metrics-field-label">分析范围</span>
          <Select
            className="metrics-control"
            value={selectedCluster}
            options={clusterOptions}
            showSearch
            optionFilterProp="label"
            onChange={value => {
              setSelectedCluster(value);
              setSelectedAgents([]);
              setDraftAgents([]);
              setFleetMode(true);
            }}
          />
        </div>
        <div className="metrics-scope-stats">
          <div className="metrics-scope-card"><span>在线</span><b>{clusterAliveCount}</b><em>/ {clusterHosts.length}</em></div>
          <div className="metrics-scope-card"><span>Leader</span><b>{clusterLeaderCount}</b><em>nodes</em></div>
          <div className="metrics-scope-card"><span>Direct</span><b>{clusterDirectCount}</b><em>nodes</em></div>
          <div className="metrics-scope-card"><span>写入队列</span><b>{storage?.queue_depth ?? 0}</b><em>pending</em></div>
          <div className="metrics-scope-card"><span>失败</span><b>{storage?.failed_commands ?? 0}</b><em>commands</em></div>
          <div className="metrics-scope-card"><span>事务批次</span><b>{storage?.transaction_batches ?? 0}</b><em>batches</em></div>
        </div>
      </div>
      <div className="metrics-control-grid">
        <div className="metrics-control-card metrics-preset-card">
          <span className="metrics-field-label">健康视角</span>
          <Segmented
            value={metricPresetValue(metric)}
            options={[
              { label: '手动', value: 'manual' },
              { label: '架构', value: 'heartbeat-architecture' },
              { label: '计划', value: 'plan-convergence' },
              { label: '采集', value: 'agent-freshness' },
              { label: '发送', value: 'send-path' }
            ]}
            onChange={value => {
              if (value === 'manual') {
                setFleetMode(false);
                return;
              }
              const preset = metricPreset(String(value));
              if (!preset) return;
              setMetric(preset.metric);
              setRangeMinutes(preset.rangeMinutes);
              setSelectedAgents([]);
              setDraftAgents([]);
              setFleetMode(true);
            }}
          />
        </div>
        <div className="metrics-control-card">
          <span className="metrics-field-label">指标</span>
          <Select
            className="metrics-control"
            value={metric}
            options={metricOptions}
            loading={!catalog.length}
            showSearch
            optionFilterProp="label"
            onChange={value => {
              setMetric(value);
              setFleetMode(false);
            }}
          />
        </div>
        <div className="metrics-control-card">
          <span className="metrics-field-label">Host 明细</span>
          <Select
            mode="multiple"
            className="metrics-control"
            maxTagCount={0}
            maxTagTextLength={16}
            maxTagPlaceholder={() => `已选 ${draftAgents.length}`}
            value={draftAgents}
            options={scopedAgentOptions}
            showSearch
            optionFilterProp="label"
            virtual
            listHeight={320}
            allowClear
            placeholder={fleetMode ? '当前范围 TopN + aggregate' : '默认选择前 3 台在线 host'}
            onChange={setDraftAgents}
          />
          <Space.Compact className="metrics-host-apply">
            <Button
              type="primary"
              size="small"
              disabled={!hostSelectionDirty || !draftAgents.length}
              loading={isApplyingHostSelection}
              onClick={() => startHostSelectionTransition(() => {
                setSelectedAgents(draftAgents);
                setFleetMode(false);
              })}
            >
              应用 Host
            </Button>
            <Button
              size="small"
              disabled={fleetMode && !draftAgents.length && !selectedAgents.length}
              onClick={() => startHostSelectionTransition(() => {
                setDraftAgents([]);
                setSelectedAgents([]);
                setFleetMode(true);
              })}
            >
              TopN
            </Button>
          </Space.Compact>
        </div>
        <div className="metrics-control-card metrics-actions-card">
          <span className="metrics-field-label">时间窗口</span>
          <Segmented
            value={rangeMinutes}
            options={[
              { label: '15m', value: 15 },
              { label: '30m', value: 30 },
              { label: '1h', value: 60 },
              { label: '6h', value: 360 }
            ]}
            onChange={value => setRangeMinutes(Number(value))}
          />
          <Space.Compact>
            <Button onClick={() => setFixedRangeEndMs(current => current === null ? Date.now() : null)}>
              {rangePaused ? '跟随最新' : '暂停窗口'}
            </Button>
            <Button type="primary" loading={loading} onClick={() => loadMetrics()}>刷新时序</Button>
          </Space.Compact>
        </div>
      </div>
      {error && <Typography.Text type="danger">{error}</Typography.Text>}
      <div className="metrics-chart-card">
        <div className="metrics-chart-head">
          <Space size={8} wrap>
            <Typography.Text strong>{activeMetric?.title || metric}</Typography.Text>
            <Tag color={assessment.tone}>{assessment.label}</Tag>
            {fleetMode && <Tag color="cyan">全局 TopN</Tag>}
            <Tag>{activeMetric?.unit || result?.unit || '-'}</Tag>
            <Tag>{seriesCount} series</Tag>
            <Tag>{pointCount} points</Tag>
            <Tag>query_ms {frontendMetrics.queryMs}</Tag>
            <Tag>render_ms {frontendMetrics.renderMs}</Tag>
            {result?.truncated && <Tag color="warning">已截断，建议 step {result.suggested_step_ms ?? result.suggestedStepMs}ms</Tag>}
            {invalidatedRange && <Tag color="gold">补偿中 {formatSeenTime(invalidatedRange.to)}</Tag>}
            {lastInvalidateAt && <Tag color="blue">live {formatSeenTime(lastInvalidateAt)}</Tag>}
            {rangePaused && <Tag color="purple">窗口固定 {formatSeenTime(fixedRangeEndMs ?? undefined)}</Tag>}
          </Space>
          <Typography.Text type="secondary">{result ? `已更新 ${formatSeenTime(result.to ?? undefined)}` : '尚未查询'}</Typography.Text>
        </div>
        {seriesCount ? <MetricInsightChart metric={metric} result={result} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无时序数据" />}
      </div>
    </div>
  </Card>;
});

type MetricPreset = {
  key: string;
  metric: string;
  rangeMinutes: number;
};

const metricPresets: MetricPreset[] = [
  { key: 'heartbeat-architecture', metric: 'group.status_unhealthy', rangeMinutes: 15 },
  { key: 'plan-convergence', metric: 'group.plan_mismatch', rangeMinutes: 15 },
  { key: 'agent-freshness', metric: 'heartbeat.agent_collect_ms', rangeMinutes: 15 },
  { key: 'send-path', metric: 'heartbeat.agent_send_ms', rangeMinutes: 15 }
];

function metricPreset(key: string) {
  return metricPresets.find(preset => preset.key === key);
}

function metricPresetValue(metric: string) {
  return metricPresets.find(preset => preset.metric === metric)?.key || 'manual';
}

function metricAssessment(metric: string, result: MetricQueryResultView | null, storageStatus: string) {
  if (storageStatus !== 'ok') {
    return { label: '存储降级', tone: 'warning' as const };
  }
  const max = maxMetricValue(result);
  if (max === null) {
    return { label: '等待样本', tone: 'default' as const };
  }
  if (metric === 'group.status_unhealthy') {
    return max > 0 ? { label: '架构退化', tone: 'error' as const } : { label: '架构健康', tone: 'success' as const };
  }
  if (metric === 'group.plan_mismatch' || metric === 'group.plan_lag') {
    return max > 0 ? { label: '计划不一致', tone: 'warning' as const } : { label: '计划收敛', tone: 'success' as const };
  }
  if (metric === 'group.missing_member_count' || metric === 'group.stale_member_count' || metric === 'group.direct_fallback_count') {
    return max > 0 ? { label: 'group 有尾部', tone: 'warning' as const } : { label: 'group 稳定', tone: 'success' as const };
  }
  if (metric === 'heartbeat.arrival_gap_ms') {
    return max > 30_000 ? { label: '超过 TTL', tone: 'error' as const } : max > 10_000 ? { label: '到达抖动', tone: 'warning' as const } : { label: '到达稳定', tone: 'success' as const };
  }
  if (metric === 'group.arrival_gap_ms') {
    return max > 20_000 ? { label: 'group 到达稀疏', tone: 'warning' as const } : { label: 'sticky 到达正常', tone: 'success' as const };
  }
  if (metric === 'heartbeat.seq_gap') {
    return max > 0 ? { label: '序列缺口', tone: 'warning' as const } : { label: '序列连续', tone: 'success' as const };
  }
  if (metric === 'heartbeat.agent_collect_ms') {
    return max > 100 ? { label: '采集偏慢', tone: 'warning' as const } : { label: '采集新鲜', tone: 'success' as const };
  }
  if (metric === 'heartbeat.agent_encode_ms' || metric === 'heartbeat.agent_send_ms' || metric === 'group.group_latency_ms') {
    return max > 100 ? { label: '链路偏慢', tone: 'warning' as const } : { label: '链路轻量', tone: 'success' as const };
  }
  return { label: '可观测', tone: 'processing' as const };
}

function maxMetricValue(result: MetricQueryResultView | null) {
  const values = (result?.series || [])
    .flatMap(series => series.points || [])
    .map(point => Number(point.value))
    .filter(Number.isFinite);
  return values.length ? Math.max(...values) : null;
}

type MetricChartPoint = {
  seriesName: string;
  timestamp: number;
  value: number;
};

type MetricThreshold = {
  value: number;
  label: string;
  severity: 'warning' | 'error';
};

const MetricInsightChart = memo(function MetricInsightChart({ metric, result }: { metric: string; result: MetricQueryResultView | null }) {
  const chartRef = useRef<HTMLDivElement | null>(null);
  const unit = result?.unit || '';
  const threshold = metricThreshold(metric);
  const points = useMemo(() => metricChartPoints(result), [result]);
  const summary = useMemo(() => metricChartSummary(points, threshold), [points, threshold]);
  const option = useMemo(() => metricChartOption(metric, result, threshold), [metric, result, threshold]);

  useEffect(() => {
    if (!chartRef.current || !option) return;
    const chart = init(chartRef.current, undefined, { renderer: 'canvas' });
    chart.setOption(option);
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(chartRef.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [option]);

  if (!points.length || !summary || !option) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可解释的数据点" />;
  }

  return <div className="metrics-insight-chart">
    <div className="metrics-insight-grid">
      <div className={`metrics-insight-card metrics-insight-card-${summary.tone}`}>
        <span>状态</span>
        <b>{summary.label}</b>
        <em>{metricThresholdHint(metric, threshold, unit)}</em>
      </div>
      <div className="metrics-insight-card">
        <span>当前</span>
        <b>{formatMetricValue(summary.latest.value, unit)}</b>
        <em>{summary.latest.seriesName}</em>
      </div>
      <div className="metrics-insight-card">
        <span>峰值</span>
        <b>{formatMetricValue(summary.max.value, unit)}</b>
        <em>{formatChartTime(summary.max.timestamp)}</em>
      </div>
      <div className="metrics-insight-card">
        <span>范围</span>
        <b>{summary.seriesCount}</b>
        <em>{summary.pointCount} points</em>
      </div>
    </div>
    <div ref={chartRef} className="metrics-echart" role="img" aria-label="metrics insight chart" />
  </div>;
});

function metricChartPoints(result: MetricQueryResultView | null): MetricChartPoint[] {
  return (result?.series || [])
    .flatMap(series => {
      const name = metricSeriesName(series.labels || {});
      return (series.points || []).map(point => ({
        seriesName: name,
        timestamp: metricPointTimestamp(point),
        value: metricPointValue(point)
      }));
    })
    .filter(point => point.timestamp > 0 && Number.isFinite(point.value));
}

function metricChartSummary(points: MetricChartPoint[], threshold: MetricThreshold | null) {
  if (!points.length) return null;
  const sorted = [...points].sort((left, right) => left.timestamp - right.timestamp);
  const latest = sorted[sorted.length - 1];
  const max = points.reduce((current, point) => point.value > current.value ? point : current, points[0]);
  const seriesCount = new Set(points.map(point => point.seriesName)).size;
  const breached = threshold ? max.value > threshold.value : false;
  return {
    latest,
    max,
    seriesCount,
    pointCount: points.length,
    label: breached ? (threshold?.severity === 'error' ? '异常' : '需关注') : '正常',
    tone: breached ? (threshold?.severity === 'error' ? 'error' : 'warning') : 'success'
  };
}

function metricChartOption(metric: string, result: MetricQueryResultView | null, threshold: MetricThreshold | null): EChartsOption | null {
  const series = result?.series || [];
  if (!series.some(item => item.points?.length)) return null;
  const unit = result?.unit || '';
  const palette = ['#2563eb', '#0891b2', '#7c3aed', '#dc2626', '#ea580c', '#16a34a', '#475569'];
  return {
    animation: false,
    color: palette,
    grid: { left: 54, right: 24, top: 58, bottom: 42 },
    legend: {
      type: 'scroll',
      top: 8,
      left: 8,
      right: 8,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { color: '#475569', fontSize: 12 }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      valueFormatter: (value: number) => formatMetricValue(Number(value), unit)
    },
    xAxis: {
      type: 'time',
      axisLabel: { color: '#64748b', formatter: (value: number) => formatChartTime(value) },
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: unit || metric,
      nameTextStyle: { color: '#64748b', padding: [0, 0, 0, 6] },
      axisLabel: { color: '#64748b', formatter: (value: number) => shortMetricValue(value) },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    series: series.slice(0, 12).map((item, index) => {
      const data = (item.points || [])
        .map(point => [metricPointTimestamp(point), metricPointValue(point)])
        .filter(([timestamp, value]) => Number(timestamp) > 0 && Number.isFinite(Number(value)));
      const maxPoint = data.reduce<[number, number] | null>((current, point) => {
        if (!current) return point as [number, number];
        return Number(point[1]) > current[1] ? point as [number, number] : current;
      }, null);
      return {
        name: metricSeriesName(item.labels || {}),
        type: 'line',
        data,
        showSymbol: false,
        smooth: false,
        sampling: 'lttb',
        lineStyle: { width: index === 0 ? 2.8 : 2 },
        emphasis: { focus: 'series' },
        markPoint: maxPoint ? {
          symbolSize: 42,
          label: { formatter: '峰值', fontSize: 11 },
          data: [{ coord: maxPoint, value: maxPoint[1] }]
        } : undefined,
        markLine: threshold && index === 0 ? {
          symbol: 'none',
          lineStyle: { color: threshold.severity === 'error' ? '#dc2626' : '#d97706', type: 'dashed', width: 1.4 },
          label: { formatter: threshold.label, color: threshold.severity === 'error' ? '#dc2626' : '#d97706' },
          data: [{ yAxis: threshold.value }]
        } : undefined
      };
    })
  };
}

function metricSeriesName(labels: Record<string, string>) {
  if (labels.series_role === 'aggregate') return '整体平均';
  return labels.agent_id || labels.group_id || labels.pid || labels.cluster || Object.values(labels).filter(Boolean).slice(0, 2).join(' / ') || 'series';
}

function metricThreshold(metric: string): MetricThreshold | null {
  if (metric === 'group.status_unhealthy' || metric === 'group.plan_mismatch' || metric === 'group.plan_lag' || metric === 'heartbeat.seq_gap') {
    return { value: 0, label: '必须为 0', severity: 'warning' };
  }
  if (metric === 'group.missing_member_count' || metric === 'group.stale_member_count' || metric === 'group.direct_fallback_count') {
    return { value: 0, label: '存在尾部', severity: 'warning' };
  }
  if (metric === 'heartbeat.arrival_gap_ms') {
    return { value: 10_000, label: '到达抖动', severity: 'warning' };
  }
  if (metric === 'group.arrival_gap_ms') {
    return { value: 20_000, label: '本地间隔过大', severity: 'warning' };
  }
  if (metric === 'heartbeat.agent_collect_ms' || metric === 'heartbeat.agent_encode_ms' || metric === 'heartbeat.agent_send_ms' || metric === 'group.group_latency_ms') {
    return { value: 100, label: '链路偏慢', severity: 'warning' };
  }
  return null;
}

function metricThresholdHint(metric: string, threshold: MetricThreshold | null, unit: string) {
  if (metric === 'group.arrival_gap_ms') {
    return '单 coordinator 视角；sticky 后应接近心跳间隔';
  }
  return threshold ? `阈值 ${formatMetricValue(threshold.value, unit)}` : '观察趋势';
}

function formatMetricValue(value: number, unit: string) {
  const formatted = Math.abs(value) >= 1000 ? Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(value) : Number(value.toFixed(value % 1 === 0 ? 0 : 1)).toString();
  return unit ? `${formatted} ${unit}` : formatted;
}

function shortMetricValue(value: number) {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}m`;
  if (Math.abs(value) >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return Number(value.toFixed(value % 1 === 0 ? 0 : 1)).toString();
}

function formatChartTime(timestamp: number) {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

const ClusterSection = memo(function ClusterSection({
  cluster,
  hosts,
  hue,
  collapsed,
  needsAttention,
  onToggle,
  onRun,
  onClusterRun
}: {
  cluster: string;
  hosts: HostView[];
  hue: number;
  collapsed: boolean;
  needsAttention: boolean;
  onToggle: (cluster: string) => void;
  onRun: (host: HostView) => void;
  onClusterRun: (cluster: string, hosts: HostView[]) => void;
}) {
  const [sortMode, setSortMode] = useState<ClusterSortMode>('ip');
  const sorted = useMemo(() => sortClusterHosts(hosts, sortMode), [hosts, sortMode]);
  const attentionHosts = useMemo(() => clusterAttentionHosts(hosts), [hosts]);
  const healthAttentionHosts = useMemo(() => attentionHosts.filter(hostHealthNeedsAttention), [attentionHosts]);
  const taskAttentionHosts = useMemo(
    () => attentionHosts.filter(host => !hostHealthNeedsAttention(host) && hostTaskNeedsAttention(host)),
    [attentionHosts]
  );
  const attentionNames = useMemo(() => attentionHosts.map(hostDisplayName), [attentionHosts]);
  const attentionTitle = useMemo(
    () => attentionHosts.map(host => `${hostDisplayName(host)}（${clusterAttentionReason(host)}）`).join('、'),
    [attentionHosts]
  );
  const onlineCount = useMemo(() => hosts.filter(host => host.status === 'alive').length, [hosts]);
  const visibleAttentionNames = attentionNames.slice(0, 4);
  const remainingAttentionCount = Math.max(0, attentionNames.length - visibleAttentionNames.length);
  const attentionLabel = healthAttentionHosts.length > 0
    ? `异常节点 ${healthAttentionHosts.length}${taskAttentionHosts.length > 0 ? ` · 任务执行中 ${taskAttentionHosts.length}` : ''}`
    : `任务执行中 ${taskAttentionHosts.length}`;
  return <Card
    className={`cluster-section ${collapsed ? 'cluster-section-collapsed' : ''}`.trim()}
    style={{ ['--cluster-hue' as any]: hue }}
    title={
      <div className="cluster-title-block">
        <div className="cluster-title-line">
          <Space size={8}><span>{cluster}</span><Tag>{hosts.length} 台</Tag>{needsAttention && <Tag color={healthAttentionHosts.length > 0 ? 'warning' : 'processing'}>{healthAttentionHosts.length > 0 ? '需关注' : '任务执行中'}</Tag>}</Space>
        </div>
        <div
          className={`cluster-status-bar ${needsAttention ? 'cluster-status-attention' : 'cluster-status-healthy'}`}
          role="status"
          aria-label={needsAttention ? `${attentionLabel}：${attentionNames.join('、')}` : `状态正常，${onlineCount}/${hosts.length} 台在线`}
        >
          <span className="cluster-status-dot" aria-hidden="true" />
          {needsAttention ? <>
            <span className="cluster-status-label">{attentionLabel}</span>
            <span className="cluster-status-hosts" title={attentionTitle}>
              {visibleAttentionNames.join('、')}{remainingAttentionCount > 0 ? ` +${remainingAttentionCount}` : ''}
            </span>
          </> : (
            <span>状态正常 · {onlineCount}/{hosts.length} 在线</span>
          )}
        </div>
      </div>
    }
    extra={<Space size={6}>
      <Button
        className="cluster-sort-control"
        size="small"
        onClick={() => setSortMode(mode => nextClusterSortMode(mode))}
        title="点击切换排序：IP → Load 升序 → Load 降序"
        aria-label={`${cluster} 主机排序`}
      >
        排序：{clusterSortLabel(sortMode)}
      </Button>
      <Button size="small" className="cluster-run-button" onClick={() => onClusterRun(cluster, hosts)}>批任务</Button>
      <Button size="small" type="text" className="cluster-toggle-button" onClick={() => onToggle(cluster)} disabled={healthAttentionHosts.length > 0}>{healthAttentionHosts.length > 0 ? '异常展开' : (needsAttention ? '任务执行中' : (collapsed ? '展开' : '折叠'))}</Button>
    </Space>}
    variant="outlined"
  >
    {!collapsed && <div className="tile-grid">
      {sorted.map(host => <HostTile host={host} key={hostKey(host)} onRun={onRun} />)}
    </div>}
  </Card>;
});

const HostTile = memo(function HostTile({ host, onRun }: { host: HostView; onRun: (host: HostView) => void }) {
  const avg = averageLoad(host);
  const level = Math.min(1, avg / 400);
  const confirmations = host.heartbeat_confirmations ?? host.heartbeatConfirmations ?? 0;
  const displayIp = normalizeAddress(host.ip);
  const workers = Array.isArray(host.state?.workers) ? host.state?.workers : Array.isArray(host.state?.tide_workers) ? host.state?.tide_workers : [];
  const observedAt = host.observed_at_ms || host.observedAtMs;
  const lastObservedAge = hostDebugValue(host, 'last_observed_age_ms', 'lastObservedAgeMs', undefined) as number | undefined;
  const groupId = String(hostDebugValue(host, 'group_id', 'groupId'));
  const groupMode = String(hostDebugValue(host, 'group_mode', 'groupMode'));
  const leaderUrl = String(hostDebugValue(host, 'leader_url', 'leaderUrl'));
  const groupSize = hostDebugValue(host, 'group_size', 'groupSize', '-');
  const groupSizeLimit = hostDebugValue(host, 'group_size_limit', 'groupSizeLimit', '-');
  const handleCopyIp = useCallback(async (event: { stopPropagation: () => void }) => {
    event.stopPropagation();
    if (displayIp === '-') return;

    try {
      await copyTextToClipboard(displayIp);
      message.success({ content: `已复制 ${displayIp}`, key: `copy-ip-${displayIp}`, duration: 1.4 });
    } catch {
      message.error({ content: '复制失败，请手动复制', key: `copy-ip-${displayIp}`, duration: 1.8 });
    }
  }, [displayIp]);

  return <Card className="host-tile" style={{ ['--load-level' as any]: level }} data-agent-key={hostKey(host)} variant="borderless">
    <Flex className="tile-header" justify="space-between" align="flex-start" gap={10}>
      <div className="tile-id-block">
        <div className="ip-title-row">
          <Typography.Text className="ip-title" data-field="ip_title" title={displayIp}>{displayIp}</Typography.Text>
          <Button
            aria-label="复制 IP"
            className="ip-copy-button"
            icon={<CopyOutlined />}
            size="small"
            title="复制 IP"
            type="text"
            disabled={displayIp === '-'}
            onClick={handleCopyIp}
          />
        </div>
        <AutoFitText className="seen" title={formatTime(observedAt)} text={formatSeenTime(observedAt)} minFontSize={9} maxFontSize={11} />
      </div>
      <Button className="run-button" data-status={statusColor(host.status)} type="primary" size="small" onClick={() => onRun(host)} disabled={confirmations < 3 || host.status !== 'alive'}>任务</Button>
    </Flex>
    <div className="tile-scroll">
      <div className="tile-metrics">
        <div className="tile-metric">
          <span className="metric-label">Area</span>
          <span className="metric-value">{host.area || '-'}</span>
        </div>
        <div className="tile-metric">
          <span className="metric-label">5min AVG</span>
          <span className="metric-value metric-value-strong">{formatLoad(avg)}</span>
        </div>
      </div>
      <div className="debug-panel">
        <Typography.Text className="debug-title">调试</Typography.Text>
        <div className="debug-grid">
          <span><b>20s确认</b><em>{confirmations}</em></span>
          <span><b>age</b><em>{formatAge(lastObservedAge)}</em></span>
          <span><b>mode</b><em>{groupMode}</em></span>
          <span><b>group</b><em>{groupId}</em></span>
          <span><b>size</b><em>{groupSize}/{groupSizeLimit}</em></span>
          <span><b>leader</b><em>{normalizeUrlHost(leaderUrl)}</em></span>
        </div>
      </div>
      <Progress percent={Math.round(level * 100)} showInfo={false} strokeColor="hsl(var(--cluster-hue) 48% 24%)" trailColor="rgba(15,23,42,.24)" />
      {workers.length > 0 && <div className="worker-list">
        {workers.slice(0, 8).map((worker: any, index: number) => <div className="worker-card" key={`${worker.pid || 'worker'}-${index}`}>
          <Flex className="worker-card-head" justify="space-between" align="center" gap={6}>
            <Typography.Text className="worker-pid">pid {workerValue(worker, 'pid')}</Typography.Text>
            <Typography.Text className="worker-version">{workerValue(worker, 'component_version')}</Typography.Text>
          </Flex>
          <div className="worker-metrics">
            <span><b>cpu</b><em>{workerValue(worker, 'cpu_percent')}</em></span>
            <span><b>usr</b><em>{workerValue(worker, 'user_cpu_percent')}</em></span>
            <span><b>sys</b><em>{workerValue(worker, 'sys_cpu_percent')}</em></span>
            <span><b>rss</b><em>{formatRssMb(worker)}</em></span>
            <span><b>mem</b><em>{workerValue(worker, 'mem_percent')}</em></span>
            <span><b>thr</b><em>{workerValue(worker, 'threads')}</em></span>
            {worker.port1 && <span><b>port</b><em>{workerValue(worker, 'port1')}</em></span>}
          </div>
        </div>)}
      </div>}
    </div>
  </Card>;
});

function TaskModal(props: {
  host: HostView | null;
  clusterName: string;
  clusterHosts: HostView[];
  open: boolean;
  onClose: () => void;
  snapshot: TaskSnapshot | null;
  batchSummary: BatchSubmitSummary | null;
  clusterSnapshots: Record<string, TaskSnapshot>;
  output: string;
  outputLog: OutputLog | null;
  outputLogRevision: number;
  focusedTaskId: string;
  taskType: string;
  setTaskType: (value: string) => void;
  onRun: (args: string[]) => Promise<void>;
  onFilePut: (payload: any) => Promise<void>;
  onShellRun: (payload: any, args: string[]) => Promise<void>;
  onPop: () => Promise<void>;
}) {
  const [argsUnlocked, setArgsUnlocked] = useState(false);
  const unlockClicks = useRef<number[]>([]);
  const tasks = activeHostTasks(props.host || undefined);
  const agentTask = tasks[0];
  const completions = completionStack(props.snapshot);
  const executions = props.snapshot?.execution_queue || [];
  const focusedCompletion = completionForTask(props.snapshot, props.focusedTaskId);
  const latestCompletion = props.focusedTaskId ? focusedCompletion : newestCompletion(props.snapshot);
  const hasRunningTask = tasks.length > 0 || executions.length > 0;
  const canPop = completions.length > 0 && !hasRunningTask;
  const asyncTask = (props.focusedTaskId
    ? tasks.find(task => matchesTaskId(task, props.focusedTaskId)) || executions.find(task => matchesTaskId(task, props.focusedTaskId))
    : null) || agentTask || executions[0];
  const streamLog = latestCompletion ? null : streamForTask(props.snapshot, props.focusedTaskId || asyncTask?.task_id || asyncTask?.taskId);
  const visibleTraces = (props.snapshot?.traces || []).slice(0, 4);
  const completionText = props.outputLog ? '' : (props.output || (latestCompletion ? completionOutput(latestCompletion) : (streamLog ? streamOutput(streamLog) : '')));
  const outputDisplayValue = props.outputLog ? 'streaming' : completionText;
  const currentTaskId = latestCompletion?.task_id || asyncTask?.task_id || props.focusedTaskId || props.snapshot?.traces?.[0]?.task_id || '';
  const outputMeta = latestCompletion || streamLog || asyncTask;
  const outputRunning = !latestCompletion && !!(asyncTask || props.focusedTaskId);
  const outputNotice = outputStatusNotice(outputDisplayValue, outputMeta, outputRunning);
  const isClusterRun = props.clusterHosts.length > 0;
  const targetTitle = isClusterRun ? props.clusterName : normalizeAddress(props.host?.ip);
  const targetDescription = isClusterRun ? `${props.clusterHosts.length} 台 host，将逐台下发作业` : '单节点作业';
  const fileTransfers = (props.snapshot?.file_transfers || []).filter((file: any) => file.file_role !== 'shell_script');
  const shellTransfers = (props.snapshot?.file_transfers || []).filter((file: any) => file.file_role === 'shell_script');
  function handleResultTitleClick() {
    if (argsUnlocked) return;
    const now = Date.now();
    const next = [...unlockClicks.current.filter(value => now - value <= 5000), now];
    unlockClicks.current = next;
    if (next.length >= 3) {
      setArgsUnlocked(true);
      unlockClicks.current = [];
      message.success({ content: '已显示预定义任务参数输入', key: 'task-args-unlocked', duration: 1.6 });
    }
  }
  return <Modal centered open={props.open} onCancel={props.onClose} footer={null} width="min(61.8vw, calc(100vw - 32px))" className={`task-modal ${isClusterRun ? 'cluster-run-modal' : ''}`} title={null} closeIcon={<span className="mac-close" />}>
    <div className="task-shell">
      <div className="task-sidebar">
        <Card className="task-hero" variant="outlined">
          <TaskCommandPanel
            taskType={props.taskType}
            setTaskType={props.setTaskType}
            argsUnlocked={argsUnlocked}
            onRun={props.onRun}
            onFilePut={props.onFilePut}
            onShellRun={props.onShellRun}
            onPop={props.onPop}
            stackSize={completions.length}
            canPop={canPop}
          />
        </Card>
        <Card title={isClusterRun ? '目标集群' : '目标节点'}>
          <Space direction="vertical" size={4}>
            <Typography.Text strong>{targetTitle}</Typography.Text>
            <Typography.Text type="secondary">{targetDescription}</Typography.Text>
          </Space>
        </Card>
        {isClusterRun ? null : <>
        <Card title="当前任务">
          <Space direction="vertical" size={6} className="task-state-card">
            <Badge status={statusColor(asyncTask?.status)} text={statusLabel(asyncTask?.status || '空闲')} />
            <Typography.Text type="secondary">{taskLabels[(latestCompletion?.task_type || asyncTask?.task_type || '')] || latestCompletion?.task_type || asyncTask?.task_type || '当前没有任务。'}</Typography.Text>
            {currentTaskId && <Typography.Text className="task-id-text" copyable={{ text: currentTaskId }}>task_id: {currentTaskId}</Typography.Text>}
          </Space>
        </Card>
        <Card title="结果栈">
          <Space direction="vertical" size={8} className="task-state-card">
            <Statistic title="stack size" value={completions.length} />
            {completions.length > 0 ? <List
              size="small"
              dataSource={completions.slice(0, 5)}
              renderItem={(item: any, index) => <List.Item>
                <Space direction="vertical" size={2}>
                  <Space><Tag color={index === 0 ? 'blue' : 'default'}>{index === 0 ? 'top' : `#${index + 1}`}</Tag><Tag color={statusColor(item.status)}>{statusLabel(item.status)}</Tag></Space>
                  <Typography.Text className="task-id-text">{taskLabels[item.task_type] || item.task_type || '-'}</Typography.Text>
                  <Typography.Text className="task-id-text">task_id: {item.task_id || '-'}</Typography.Text>
                </Space>
              </List.Item>}
            /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无完成结果" />}
          </Space>
        </Card>
        <Card title="文件上传" data-testid="file-upload-status-card">
          {fileTransfers.length > 0 ? <List
            dataSource={fileTransfers}
            renderItem={(file: any) => <List.Item>
              <Space direction="vertical" size={2}>
                <Space><Typography.Text strong>{file.file_name || '-'}</Typography.Text><Tag color={statusColor(file.status)}>{statusLabel(file.status)}</Tag></Space>
                <Typography.Text type="secondary">{file.target_dir || 'files'} · {formatBytes(file.content_bytes)}</Typography.Text>
                {file.runner_error && <Typography.Text type="danger">{file.runner_error}</Typography.Text>}
              </Space>
            </List.Item>}
          /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无文件上传记录" />}
        </Card>
        {shellTransfers.length > 0 && <Card title="脚本投递">
          <List
            dataSource={shellTransfers}
            renderItem={(file: any) => <List.Item>
              <Space direction="vertical" size={2}>
                <Space><Typography.Text strong>{file.file_name || 'script.sh'}</Typography.Text><Tag color={statusColor(file.status)}>{statusLabel(file.status)}</Tag></Space>
                <Typography.Text type="secondary">Shell 执行内部投递 · {formatBytes(file.content_bytes)}</Typography.Text>
                {file.runner_error && <Typography.Text type="danger">{file.runner_error}</Typography.Text>}
              </Space>
            </List.Item>}
          />
        </Card>}
        <Card title="执行队列">
          {tasks.length > 0 ? <List dataSource={tasks} renderItem={(task: any) => <List.Item><Space direction="vertical" size={2}><Space><Typography.Text strong>agent 执行中</Typography.Text><Tag color="blue">{statusLabel(task.status)}</Tag></Space><Typography.Text type="secondary">{taskLabels[task.task_type] || task.task_type}</Typography.Text><Typography.Text className="task-id-text">task_id: {task.task_id || '-'}</Typography.Text><Progress percent={task.status === 'running' ? 68 : 38} showInfo={false}/></Space></List.Item>} /> : executions.length > 0 ? <List dataSource={executions} renderItem={(task: any) => <List.Item><Space direction="vertical" size={2}><Typography.Text>{statusLabel(task.status)} · {taskLabels[task.task_type] || task.task_type}</Typography.Text><Typography.Text className="task-id-text">task_id: {task.task_id || '-'}</Typography.Text></Space></List.Item>} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有待执行任务。来自 agent 心跳的任务会显示在上方。" />}
        </Card>
        <Card title="Trace" className="task-trace-card">
          <List
            className="trace-list"
            dataSource={visibleTraces}
            locale={{ emptyText: '当前没有 trace。' }}
            renderItem={(trace: any) => <List.Item>
              <Space direction="vertical" size={2} className="trace-item">
                <Typography.Text>{formatTime(trace.observed_at_ms || trace.observedAtMs)} · {trace.event || trace.status || '-'}</Typography.Text>
                <Typography.Text className="task-id-text">task_id: {trace.task_id || '-'}</Typography.Text>
              </Space>
            </List.Item>}
          />
        </Card>
        </>}
      </div>
      <Card
        className="task-workspace"
          title={<OutputPanelTitle meta={outputMeta} notice={outputNotice} value={completionText} onUnlock={handleResultTitleClick} showMeta={!isClusterRun} />}
        variant="outlined"
      >
        <div className="completion-pane">
          {isClusterRun
            ? <ClusterRunSummary summary={props.batchSummary} hosts={props.clusterHosts} snapshots={props.clusterSnapshots} />
            : <CompletionViewer key={props.focusedTaskId || currentTaskId || props.outputLog?.key || 'task-output'} value={completionText} outputLog={props.outputLog} outputLogRevision={props.outputLogRevision} meta={outputMeta} />}
        </div>
      </Card>
    </div>
  </Modal>;
}

const TaskCommandPanel = memo(function TaskCommandPanel({
  taskType,
  setTaskType,
  argsUnlocked,
  onRun,
  onFilePut,
  onShellRun,
  onPop,
  stackSize,
  canPop
}: {
  taskType: string;
  setTaskType: (value: string) => void;
  argsUnlocked: boolean;
  onRun: (args: string[]) => Promise<void>;
  onFilePut: (payload: any) => Promise<void>;
  onShellRun: (payload: any, args: string[]) => Promise<void>;
  onPop: () => Promise<void>;
  stackSize: number;
  canPop: boolean;
}) {
  const [taskArgs, setTaskArgs] = useState(defaultTaskArgs);
  const [file, setFile] = useState<File | null>(null);
  const [fileTargetDir, setFileTargetDir] = useState<'files' | 'workspace'>('files');
  const [scriptText, setScriptText] = useState('#!/usr/bin/env bash\nset -euo pipefail\necho \"pulse shell ok args=$*\"\n');
  const [scriptTitle, setScriptTitle] = useState('');
  const [actionMessage, setActionMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const parsedArgs = useMemo(() => parseTaskArgs(taskArgs || defaultTaskArgs), [taskArgs]);
  const scriptLines = useMemo(() => countLines(scriptText), [scriptText]);
  const scriptFileName = useMemo(() => shellFileName(scriptTitle, scriptText), [scriptTitle, scriptText]);
  async function submitFile() {
    if (!file) return;
    setBusy(true);
    setActionMessage('正在提交文件上传...');
    try {
      await onFilePut({ ...(await filePayload(file)), target_dir: fileTargetDir });
      setActionMessage(`文件上传已提交：${file.name}`);
    } catch (error) {
      setActionMessage(`文件上传提交失败：${friendlyErrorText(error)}`);
    } finally {
      setBusy(false);
    }
  }
  async function submitShell() {
    setBusy(true);
    setActionMessage('正在提交 Shell 执行...');
    try {
      await onShellRun(await textPayload(scriptFileName, scriptText), argsUnlocked ? parsedArgs : []);
      setActionMessage(`Shell 执行已提交：${scriptTitle.trim() || '临时脚本'} · ${scriptLines} 行`);
    } catch (error) {
      setActionMessage(`Shell 执行提交失败：${friendlyErrorText(error)}`);
    } finally {
      setBusy(false);
    }
  }
  return <>
    <div className="run-section">
      <Typography.Text className="task-args-title">预定义任务</Typography.Text>
      <Flex gap={8} align="center">
      <Select value={taskType} onChange={setTaskType} className="task-select" options={Object.entries(taskLabels).map(([value, label]) => ({ value, label }))}/>
      <Button type="primary" onClick={() => onRun(parsedArgs)}>执行</Button>
      <Button onClick={onPop} icon={<InboxOutlined />} disabled={!canPop}>弹出结果 · {stackSize}</Button>
      </Flex>
      {argsUnlocked && <div className="task-args-panel">
        <Typography.Text className="task-args-title">自定义参数</Typography.Text>
        <Input.TextArea
          value={taskArgs}
          onChange={event => setTaskArgs(event.target.value)}
          autoSize={{ minRows: 1, maxRows: 3 }}
          placeholder={defaultTaskArgs}
        />
        <Typography.Text type="secondary">默认参数为 --dry-run。非 dry-run 操作会真实修改线上机器，执行前必须确认目标范围。</Typography.Text>
      </div>}
    </div>
    <div className="file-shell-panel">
      <Typography.Text className="task-args-title">文件上传</Typography.Text>
      <Space direction="vertical" size={8} className="file-shell-stack">
        <input data-testid="task-file-input" type="file" onChange={event => setFile(event.target.files?.[0] || null)} />
        <Select
          value={fileTargetDir}
          onChange={setFileTargetDir}
          options={[
            { value: 'files', label: '上传到 $agent_work_dir/files' },
            { value: 'workspace', label: '上传到 $agent_work_dir/workspace/files' }
          ]}
        />
        <Button data-testid="task-file-upload-submit" size="small" disabled={!file || busy} loading={busy && actionMessage.includes('文件上传')} onClick={submitFile}>仅上传文件</Button>
        <Typography.Text type="secondary">文件上传只负责投递文件，不会执行脚本或触发任务。</Typography.Text>
      </Space>
    </div>
    <div className="file-shell-panel shell-execute-panel">
      <Typography.Text className="task-args-title">Shell 执行</Typography.Text>
      <Space direction="vertical" size={8} className="file-shell-stack">
        <Input value={scriptTitle} onChange={event => setScriptTitle(event.target.value)} placeholder="运行标题（可选，例如：查看 Tide worker 日志）" />
        <Flex justify="space-between" align="center" gap={8} wrap>
          <Typography.Text type="secondary">自动脚本名：{scriptFileName}</Typography.Text>
          <Tag color="blue">{scriptLines} 行</Tag>
        </Flex>
        <ShellScriptEditor value={scriptText} onChange={setScriptText} />
        <Button danger type="primary" disabled={!scriptText.trim() || busy} loading={busy && actionMessage.includes('Shell')} onClick={submitShell}>执行 Shell 脚本</Button>
        <Typography.Text type="secondary">Shell 执行使用这里的脚本内容；它和上面的文件上传是两个独立功能。</Typography.Text>
      </Space>
    </div>
    {actionMessage && <Typography.Text className="action-message" data-testid="task-action-message" type={actionMessage.includes('失败') ? 'danger' : 'secondary'}>{actionMessage}</Typography.Text>}
  </>;
});

const ShellScriptEditor = memo(function ShellScriptEditor({
  value,
  onChange
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const highlightRef = useRef<HTMLPreElement | null>(null);
  const lineRef = useRef<HTMLDivElement | null>(null);
  const lineCount = useMemo(() => countLines(value), [value]);
  const handleScroll = useCallback((event: React.UIEvent<HTMLTextAreaElement>) => {
    if (!highlightRef.current) return;
    highlightRef.current.scrollTop = event.currentTarget.scrollTop;
    highlightRef.current.scrollLeft = event.currentTarget.scrollLeft;
    if (lineRef.current) {
      lineRef.current.scrollTop = event.currentTarget.scrollTop;
    }
  }, []);
  return <div className="shell-script-editor" data-renderer="shell">
    <div ref={lineRef} className="shell-script-lines" aria-hidden="true">
      {Array.from({ length: lineCount }, (_, index) => <span key={index}>{index + 1}</span>)}
    </div>
    <pre ref={highlightRef} className="shell-script-highlight" aria-hidden="true">
      <code dangerouslySetInnerHTML={{ __html: highlightShell(value) }} />
    </pre>
    <textarea
      className="shell-script-input"
      spellCheck={false}
      value={value}
      onChange={event => onChange(event.target.value)}
      onScroll={handleScroll}
      aria-label="Shell 脚本内容"
    />
  </div>;
});

const ClusterRunSummary = memo(function ClusterRunSummary({
  summary,
  hosts,
  snapshots
}: {
  summary: BatchSubmitSummary | null;
  hosts: HostView[];
  snapshots: Record<string, TaskSnapshot>;
}) {
  const execution = useMemo(() => clusterExecutionSummary(hosts, summary, snapshots), [hosts, summary, snapshots]);
  const [fullOutputs, setFullOutputs] = useState<Record<string, string>>({});
  const requestedOutputUrlsRef = useRef<Set<string>>(new Set());
  const mountedRef = useRef(true);
  const displayExecution = useMemo(() => ({
    ...execution,
    rows: execution.rows.map(row => {
      const fullOutput = fullOutputs[`${agentId(row.host)}:${row.taskId}`];
      if (fullOutput === undefined) return row;
      const lineCount = countLines(fullOutput);
      return {
        ...row,
        outputText: fullOutput,
        outputPreview: fullOutput,
        outputLineCount: lineCount,
        outputPreviewLineCount: lineCount
      };
    })
  }), [execution, fullOutputs]);
  useEffect(() => () => {
    mountedRef.current = false;
  }, []);
  useEffect(() => {
    execution.rows.forEach(row => {
      if (!row.outputFullUrl) return;
      const key = `${agentId(row.host)}:${row.taskId}`;
      if (fullOutputs[key] !== undefined || requestedOutputUrlsRef.current.has(row.outputFullUrl)) return;
      requestedOutputUrlsRef.current.add(row.outputFullUrl);
      fetchJson<any>(row.outputFullUrl)
        .then(result => {
          if (!mountedRef.current) return;
          setFullOutputs(previous => ({ ...previous, [key]: completionOutput(result) }));
        })
        .catch(() => {
          requestedOutputUrlsRef.current.delete(row.outputFullUrl as string);
        });
    });
  }, [execution, fullOutputs]);
  const completionPercent = Math.round(displayExecution.executionSucceeded * 100 / Math.max(1, displayExecution.total));
  const visibleErrors = useMemo(() => execution.submitFailed ? [...new Set(summary?.errors || [])].slice(0, 5) : [], [summary, execution.submitFailed]);
  const downloadResults = useCallback(() => {
    void saveTextFile(downloadFileName(summary), clusterExecutionText(displayExecution, summary));
  }, [displayExecution, summary]);
  return <div className="cluster-run-summary">
    <Flex className="cluster-summary-heading" justify="space-between" align="center" gap={12}>
      <Typography.Title level={4}>集群批量操作</Typography.Title>
      <Button icon={<DownloadOutlined />} disabled={!summary} onClick={downloadResults}>下载全部输出</Button>
    </Flex>
    {summary ? <Space direction="vertical" size={12} className="cluster-run-summary-body">
      <Space wrap>
        <Tag color="blue">{summary.kind}</Tag>
        <Tag color="default">目标 {summary.total}</Tag>
        <Tag color="green">提交成功 {displayExecution.submitSucceeded}</Tag>
        <Tag color={displayExecution.submitFailed ? 'red' : 'default'}>提交失败 {displayExecution.submitFailed}</Tag>
      </Space>
      <Row gutter={[12, 12]} className="cluster-exec-stats">
        <Col xs={12} md={6}><Card><Statistic title="执行成功" value={execution.executionSucceeded} suffix={`/ ${execution.total}`} /></Card></Col>
        <Col xs={12} md={6}><Card><Statistic title="执行失败" value={execution.executionFailed} valueStyle={{ color: execution.executionFailed ? '#dc2626' : undefined }} /></Card></Col>
        <Col xs={12} md={6}><Card><Statistic title="执行中" value={execution.running} /></Card></Col>
        <Col xs={12} md={6}><Card><Statistic title="待回执" value={execution.pending} /></Card></Col>
        <Col xs={12} md={6}><Card><Statistic title="平均耗时" value={execution.durationCount ? formatDuration(execution.averageDurationMs) : '-'} /></Card></Col>
        <Col xs={12} md={6}><Card><Statistic title="最长耗时" value={execution.durationCount ? formatDuration(execution.maxDurationMs) : '-'} /></Card></Col>
      </Row>
      <Progress percent={completionPercent} status={displayExecution.executionFailed ? 'exception' : displayExecution.executionSucceeded === displayExecution.total ? 'success' : 'active'} />
      <Typography.Paragraph>{summary.failed && !displayExecution.submitFailed ? '提交阶段曾出现临时失败，已被后续执行结果确认完成。' : summary.message}</Typography.Paragraph>
      {visibleErrors.length > 0 && <div className="cluster-run-errors">
        {visibleErrors.map((error, index) => <Typography.Text key={`${index}-${error}`} type="danger">{error}</Typography.Text>)}
      </div>}
    </Space> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未提交批量操作" />}
    <Typography.Paragraph type="secondary">这里聚合所有目标 host 的执行状态、completion、exit code 和错误摘要；无需逐台打开批任务详情。</Typography.Paragraph>
    <List
      size="small"
      className="cluster-exec-list"
      dataSource={displayExecution.rows}
      renderItem={(row, index) => <List.Item>
        <div className="cluster-exec-row">
          <div className="cluster-exec-header">
            <Badge status={row.status === 'success' ? 'success' : row.status === 'failed' || row.status === 'submit_failed' ? 'error' : row.status === 'running' ? 'processing' : 'default'} text={row.label || undefined} />
            <Typography.Text className="cluster-host-index">#{index + 1}</Typography.Text>
            <Typography.Text strong>{normalizeAddress(row.host.ip)}</Typography.Text>
            {row.taskType !== '-' && row.taskType !== 'Shell' && <Tag>{row.taskType}</Tag>}
            <Tag>exit {row.exitCode}</Tag>
            {row.durationKind !== 'none' && <Tag color={row.durationKind === 'running' ? 'blue' : 'purple'}>{row.durationKind === 'running' ? '已运行' : '耗时'} {row.durationLabel}</Tag>}
            <Typography.Text type="secondary">
              {row.outputText ? `${row.outputLineCount} 行 · ` : ''}{formatBytes(row.outputBytes)}
            </Typography.Text>
            {row.taskId !== '-' && <Typography.Text className="task-id-text cluster-task-id" copyable={{ text: row.taskId }}>{row.taskId}</Typography.Text>}
            {row.message !== '-' && <Typography.Text type={row.status === 'failed' ? 'danger' : 'secondary'}>{row.message}</Typography.Text>}
          </div>
          {row.outputText && <ClusterOutputViewer row={row} />}
        </div>
      </List.Item>}
    />
  </div>;
});

const ClusterOutputViewer = memo(function ClusterOutputViewer({ row }: { row: ClusterExecutionRow }) {
  const log = useMemo<OutputLog>(() => ({
    key: `${agentId(row.host)}:${row.taskId}`,
    lines: outputLines(row.outputText),
    sourceText: row.outputText,
    sourceLength: row.outputText.length,
    chunks: [],
    fullText: row.outputText
  }), [row.host, row.taskId, row.outputText]);
  return <div className="cluster-exec-output cluster-exec-output-virtual">
    <VirtualLineOutput log={log} revision={log.sourceLength} query="" wrap={false} />
  </div>;
});

const OutputPanelTitle = memo(function OutputPanelTitle({
  meta,
  notice,
  value,
  onUnlock,
  showMeta = true
}: {
  meta?: any;
  notice: OutputNotice | null;
  value: string;
  onUnlock?: () => void;
  showMeta?: boolean;
}) {
  const lines = showMeta ? Number(meta?.output_lines ?? meta?.stream_lines ?? countLines(value)) : 0;
  const bytes = showMeta ? Number(meta?.output_bytes ?? meta?.stream_bytes ?? new Blob([value]).size) : 0;
  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (!onUnlock) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onUnlock();
    }
  };
  return <div
    className={`output-panel-title ${onUnlock ? 'output-title-trigger' : ''}`.trim()}
    onClick={onUnlock}
    onKeyDown={handleKeyDown}
    role={onUnlock ? 'button' : undefined}
    tabIndex={onUnlock ? 0 : undefined}
    title={onUnlock ? '连续点击 3 次显示预定义任务参数输入' : undefined}
  >
    <span className="output-title-main">结果查看</span>
    <span className="output-title-spacer" />
    {showMeta && <div className="output-title-status-stack">
      {notice && <OutputStatusNotice notice={notice} compact />}
      {meta?.status && <span className={`output-title-pill output-title-${statusColor(meta.status)}`}>{statusLabel(meta.status)}</span>}
      {meta?.exit_code !== undefined && meta?.exit_code !== null && <span className="output-title-pill">exit {meta.exit_code}</span>}
      <span className="output-title-pill">{lines} 行</span>
      <span className="output-title-pill">{formatBytes(bytes)}</span>
    </div>}
  </div>;
});

const CompletionViewer = memo(function CompletionViewer({ value, outputLog, outputLogRevision, meta }: { value: string; outputLog: OutputLog | null; outputLogRevision: number; meta?: any }) {
  const [mode, setMode] = useState<'log' | 'json' | 'markdown' | 'raw'>('log');
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const [wrap, setWrap] = useState(true);
  const virtual = !!outputLog;
  const canParseJson = !virtual && value.length <= maxJsonParseChars;
  const parsed = useMemo(() => canParseJson ? parseJsonOutput(value) : { ok: false, formatted: value }, [canParseJson, value]);
  const display = mode === 'json' && parsed.ok ? parsed.formatted : value;
  const renderDisplay = display;
  const outputType = String(meta?.output_type ?? meta?.outputType ?? meta?.stream_id ?? '').toLowerCase();
  const markdownHint = useMemo(() => !virtual && (outputType === 'markdown' || looksLikeMarkdown(value)), [outputType, value, virtual]);
  const matches = useMemo(() => virtual && outputLog
    ? deferredQuery
      ? outputLog.lines.reduce((count, line) => count + (line.toLowerCase().includes(deferredQuery.toLowerCase()) ? 1 : 0), 0)
      : 0
    : deferredQuery ? countMatches(renderDisplay, deferredQuery) : 0, [deferredQuery, outputLog, outputLogRevision, renderDisplay, virtual]);
  return <div className="completion-viewer">
    <Flex className="completion-toolbar" justify="space-between" align="center" gap={8}>
      <Space size={8}>
        <Segmented
          size="small"
          value={mode}
          onChange={next => setMode(next as 'log' | 'json' | 'markdown' | 'raw')}
          options={[
            { label: '日志', value: 'log' },
            { label: 'JSON', value: 'json', disabled: virtual || !parsed.ok },
            { label: 'Markdown', value: 'markdown', disabled: virtual },
            { label: '原始', value: 'raw' }
          ]}
        />
        {parsed.ok && <Tag color="blue">JSON</Tag>}
        {virtual && <Tag color="cyan">完整日志</Tag>}
        {markdownHint && <Tag color="purple">Markdown</Tag>}
        {deferredQuery && <Tag color={matches > 0 ? 'green' : 'red'}>{matches} 匹配</Tag>}
      </Space>
      <Space size={8}>
        <OutputSearch value={query} onCommit={setQuery} />
        <Button type={wrap ? 'primary' : 'default'} size="small" onClick={() => setWrap(next => !next)}>换行</Button>
        <Button size="small" onClick={() => navigator.clipboard?.writeText(outputLog ? outputLogText(outputLog) : display)}>复制</Button>
      </Space>
    </Flex>
    {virtual && outputLog
      ? <VirtualLineOutput log={outputLog} revision={outputLogRevision} query={deferredQuery} wrap={wrap} />
      : mode === 'markdown'
      ? renderDisplay
        ? <div className="task-output markdown-output" dangerouslySetInnerHTML={{ __html: renderMarkdown(renderDisplay) }} />
        : <Empty className="output-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无命令输出" />
      : <LineNumberedOutput
          value={renderDisplay}
          mode={mode}
          query={deferredQuery}
          wrap={wrap}
          json={mode === 'json' && parsed.ok}
        />}
  </div>;
});

const VirtualLineOutput = memo(function VirtualLineOutput({ log, revision, query, wrap }: { log: OutputLog; revision: number; query: string; wrap: boolean }) {
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const stickToBottomRef = useRef(true);
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportWidth, setViewportWidth] = useState(0);
  const [measuredHeights, setMeasuredHeights] = useState<Record<number, number>>({});
  const lineIndexes = useMemo(() => {
    if (!query) return null;
    const needle = query.toLowerCase();
    return log.lines.reduce<number[]>((matches, line, index) => {
      if (line.toLowerCase().includes(needle)) matches.push(index);
      return matches;
    }, []);
  }, [log, query, revision]);
  const rowCount = lineIndexes ? lineIndexes.length : log.lines.length;
  const lineNumberWidth = Math.max(2, String(log.lines.length || 1).length);
  const indexedLines = useMemo(
    () => lineIndexes || log.lines.map((_, index) => index),
    [lineIndexes, log, revision]
  );
  const charsPerLine = Math.max(
    1,
    Math.floor((Math.max(240, viewportWidth) - lineNumberWidth * virtualOutputCharWidth - 46) / virtualOutputCharWidth)
  );
  const estimatedRowHeights = useMemo(() => {
    if (!wrap) return null;
    return indexedLines.map(lineIndex => {
      const length = stripAnsi(log.lines[lineIndex] || '').length;
      return Math.max(1, Math.ceil(Math.max(1, length) / charsPerLine)) * virtualOutputLineHeight;
    });
  }, [charsPerLine, indexedLines, log, revision, wrap]);
  const rowHeights = useMemo(() => {
    if (!estimatedRowHeights) return null;
    return estimatedRowHeights.map((height, rowIndex) => {
      const lineIndex = indexedLines[rowIndex];
      return Math.max(height, measuredHeights[lineIndex] || 0);
    });
  }, [estimatedRowHeights, indexedLines, measuredHeights]);
  const rowOffsets = useMemo(() => {
    if (!rowHeights) return null;
    const offsets = [0];
    rowHeights.forEach(height => offsets.push(offsets[offsets.length - 1] + height));
    return offsets;
  }, [rowHeights]);
  const totalHeight = rowOffsets ? rowOffsets[rowOffsets.length - 1] : rowCount * virtualOutputLineHeight;
  const rowAtOffset = (offset: number) => {
    if (!rowOffsets) return Math.max(0, Math.min(rowCount - 1, Math.floor(offset / virtualOutputLineHeight)));
    let low = 0;
    let high = rowCount;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      if (rowOffsets[middle + 1] <= offset) low = middle + 1;
      else high = middle;
    }
    return Math.min(rowCount - 1, low);
  };
  const firstVisible = rowAtOffset(scrollTop);
  const lastVisible = rowAtOffset(scrollTop + 640);
  const start = Math.max(0, firstVisible - virtualOutputOverscan);
  const end = Math.min(rowCount, lastVisible + virtualOutputOverscan + 1);

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const updateWidth = () => setViewportWidth(viewport.clientWidth);
    updateWidth();
    const observer = new ResizeObserver(updateWidth);
    observer.observe(viewport);
    return () => observer.disconnect();
  }, []);

  useLayoutEffect(() => {
    setMeasuredHeights({});
  }, [log.key, wrap]);

  useLayoutEffect(() => {
    if (!wrap) return;
    const viewport = viewportRef.current;
    if (!viewport) return;
    const observer = new ResizeObserver(entries => {
      const updates: Record<number, number> = {};
      entries.forEach(entry => {
        const lineIndex = Number((entry.target as HTMLElement).dataset.lineIndex);
        const height = entry.target.getBoundingClientRect().height;
        if (Number.isFinite(lineIndex) && height > 0) {
          updates[lineIndex] = height;
        }
      });
      if (!Object.keys(updates).length) return;
      setMeasuredHeights(previous => {
        let changed = false;
        const next = { ...previous };
        Object.entries(updates).forEach(([key, height]) => {
          const lineIndex = Number(key);
          if (Math.abs((next[lineIndex] || 0) - height) > 0.5) {
            next[lineIndex] = height;
            changed = true;
          }
        });
        return changed ? next : previous;
      });
    });
    viewport.querySelectorAll<HTMLElement>('.output-line[data-line-index]').forEach(line => observer.observe(line));
    return () => observer.disconnect();
  }, [end, revision, start, wrap]);

  useLayoutEffect(() => {
    if (!stickToBottomRef.current) return;
    const currentViewport = viewportRef.current;
    if (currentViewport) {
      const nextScrollTop = Math.max(0, currentViewport.scrollHeight - currentViewport.clientHeight);
      currentViewport.scrollTop = nextScrollTop;
      setScrollTop(current => current === nextScrollTop ? current : nextScrollTop);
    }
    let innerFrame: number | null = null;
    const frame = window.requestAnimationFrame(() => {
      innerFrame = window.requestAnimationFrame(() => {
        const nextViewport = viewportRef.current;
        if (nextViewport && stickToBottomRef.current) {
          const nextScrollTop = Math.max(0, nextViewport.scrollHeight - nextViewport.clientHeight);
          nextViewport.scrollTop = nextScrollTop;
          setScrollTop(current => current === nextScrollTop ? current : nextScrollTop);
        }
      });
    });
    return () => {
      window.cancelAnimationFrame(frame);
      if (innerFrame !== null) window.cancelAnimationFrame(innerFrame);
    };
  }, [revision, rowCount, totalHeight, wrap]);

  if (!rowCount) {
    return <Empty className="output-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无命令输出" />;
  }

  return <div
    ref={viewportRef}
    className={`task-output output-lines ${wrap ? 'output-wrap' : 'output-nowrap'} output-virtual`}
    style={{ '--line-number-width': `${lineNumberWidth}ch` } as React.CSSProperties}
    onScroll={event => {
      const viewport = event.currentTarget;
      stickToBottomRef.current = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= 40;
      setScrollTop(viewport.scrollTop);
    }}
  >
    <div style={{ height: totalHeight, position: 'relative' }}>
      <div style={{ left: 0, position: 'absolute', right: 0, top: rowOffsets ? rowOffsets[start] : start * virtualOutputLineHeight }}>
        {Array.from({ length: end - start }, (_, offset) => {
          const rowIndex = start + offset;
          const lineIndex = indexedLines[rowIndex];
          const line = log.lines[lineIndex] || '';
          const normalized = stripAnsi(line);
          const height = rowHeights ? rowHeights[rowIndex] : virtualOutputLineHeight;
          return <div
            className={`output-line ${logLevelClass(normalized)}`}
            data-line-index={lineIndex}
            key={`${lineIndex}-${line.length}`}
            style={wrap ? { minHeight: height } : { height }}
          >
            <span className="output-line-number">{lineIndex + 1}</span>
            <span className="output-line-content" dangerouslySetInnerHTML={{ __html: highlightSearch(escapeHtml(normalized), query) }} />
          </div>;
        })}
      </div>
    </div>
  </div>;
});

const OutputSearch = memo(function OutputSearch({ value, onCommit }: { value: string; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  useEffect(() => {
    const timer = window.setTimeout(() => onCommit(draft), 160);
    return () => window.clearTimeout(timer);
  }, [draft, onCommit]);
  return <input
    type="search"
    className="output-search"
    placeholder="搜索输出"
    value={draft}
    onChange={event => setDraft(event.target.value)}
  />;
});

type OutputNotice = { tone: 'running' | 'empty' | 'done'; text: string };

function OutputStatusNotice({ notice, compact }: { notice: OutputNotice; compact?: boolean }) {
  return <div className={`output-status-notice output-status-${notice.tone} ${compact ? 'output-status-compact' : ''}`}>
    <span className="output-status-dot" />
    <span>{notice.text}</span>
  </div>;
}

function outputStatusNotice(value: string, meta: any, running: boolean) {
  const taskName = taskLabels[meta?.task_type] || meta?.task_type || '任务';
  if (running && value) {
    return { tone: 'running' as const, text: `${taskName}运行中，正在展示实时命令输出` };
  }
  if (running) {
    return { tone: 'empty' as const, text: `${taskName}运行中，尚未收到命令输出` };
  }
  if (!value && meta?.status) {
    return { tone: 'done' as const, text: `${taskName}已结束，无命令输出` };
  }
  return null;
}

const LineNumberedOutput = memo(function LineNumberedOutput({
  value,
  mode,
  query,
  wrap,
  json
}: {
  value: string;
  mode: 'log' | 'json' | 'raw';
  query: string;
  wrap: boolean;
  json: boolean;
}) {
  const rows = useMemo(() => value.length > 0 ? value.split('\n') : [''], [value]);
  if (!value) {
    return <Empty className="output-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无命令输出" />;
  }
  const lineNumberWidth = Math.max(2, String(rows.length).length);
  return <div
    className={`task-output output-lines ${wrap ? 'output-wrap' : 'output-nowrap'} ${json ? 'json-output' : ''}`}
    data-mode={mode}
    style={{ '--line-number-width': `${lineNumberWidth}ch` } as React.CSSProperties}
  >
    {rows.map((line, index) => {
      const normalized = mode === 'log' ? stripAnsi(line) : line;
      return <div className={`output-line ${logLevelClass(normalized)}`} key={`${index}-${line.length}`}>
        <span className="output-line-number">{index + 1}</span>
        <span
          className="output-line-content"
          dangerouslySetInnerHTML={{ __html: json ? highlightJsonLine(normalized, query) : highlightSearch(escapeHtml(normalized), query) }}
        />
      </div>;
    })}
  </div>;
});

function AutoFitText({
  text,
  className,
  title,
  minFontSize = 10,
  maxFontSize = 24
}: {
  text: string;
  className?: string;
  title?: string;
  minFontSize?: number;
  maxFontSize?: number;
}) {
  const wrapperRef = useRef<HTMLSpanElement | null>(null);
  const textRef = useRef<HTMLSpanElement | null>(null);

  useLayoutEffect(() => {
    const wrapper = wrapperRef.current;
    const node = textRef.current;
    if (!wrapper || !node) return;

    const fit = () => {
      let size = maxFontSize;
      node.style.fontSize = `${size}px`;
      while (size > minFontSize && (node.scrollWidth > wrapper.clientWidth || node.scrollHeight > wrapper.clientHeight)) {
        size -= 1;
        node.style.fontSize = `${size}px`;
      }
    };

    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(wrapper);
    return () => observer.disconnect();
  }, [text, minFontSize, maxFontSize]);

  return <span className={`auto-fit ${className || ''}`.trim()} ref={wrapperRef} title={title || text}>
    <span className="auto-fit-content" ref={textRef}>{text}</span>
  </span>;
}

function renderCompletion(result: any) {
  const output = result.output || result.stdout || '';
  const stderr = result.stderr || '';
  return [output, stderr].filter(Boolean).join('\n');
}

function completionOutput(result: any) {
  return result.output || renderCompletion(result);
}

function streamForTask(snapshot: TaskSnapshot | null, taskId?: string) {
  const streams = snapshot?.output_streams || [];
  if (!streams.length) return null;
  if (taskId) {
    return streams.find(stream => stream.task_id === taskId || stream.taskId === taskId) || null;
  }
  return streams[0] || null;
}

function streamOutput(stream: any) {
  return stream.output || [
    '任务执行中，正在展示运行中输出',
    `task_id: ${stream.task_id || stream.taskId || '-'}`,
    `lines: ${stream.stream_lines ?? stream.streamLines ?? 0}`,
    `bytes: ${stream.stream_bytes ?? stream.streamBytes ?? 0}`,
    '',
    stream.output || ''
  ].join('\n');
}

function countLines(value: string) {
  if (!value) return 0;
  return value.endsWith('\n') ? value.split('\n').length - 1 : value.split('\n').length;
}

function shellFileName(title: string, content: string) {
  const base = title.trim() || firstMeaningfulShellLine(content) || 'shell-script';
  const slug = base
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40) || 'shell-script';
  return `${slug}.sh`;
}

function firstMeaningfulShellLine(content: string) {
  return content
    .split('\n')
    .map(line => line.trim())
    .find(line => line && !line.startsWith('#!') && !line.startsWith('#') && !line.startsWith('set ')) || '';
}

function formatBytes(value: any) {
  const bytes = Number(value || 0);
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${bytes} B`;
}

async function filePayload(file: File) {
  const buffer = await file.arrayBuffer();
  return {
    file_name: file.name,
    content_base64: arrayBufferToBase64(buffer),
    content_sha256: await sha256Hex(buffer),
    content_bytes: buffer.byteLength
  };
}

async function textPayload(fileName: string, content: string) {
  const buffer = new TextEncoder().encode(content).buffer;
  return {
    file_name: fileName || 'script.sh',
    content_base64: arrayBufferToBase64(buffer),
    content_sha256: await sha256Hex(buffer),
    content_bytes: buffer.byteLength
  };
}

function arrayBufferToBase64(buffer: ArrayBuffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const chunkSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
  }
  return btoa(binary);
}

async function sha256Hex(buffer: ArrayBuffer) {
  if (globalThis.crypto?.subtle?.digest) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', buffer);
    return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
  }
  return sha256HexFallback(buffer);
}

function sha256HexFallback(buffer: ArrayBuffer) {
  const bytes = new Uint8Array(buffer);
  const words: number[] = [];
  const bitLength = bytes.length * 8;
  for (let index = 0; index < bytes.length; index += 1) {
    words[index >> 2] = (words[index >> 2] || 0) | (bytes[index] << (24 - (index % 4) * 8));
  }
  words[bytes.length >> 2] = (words[bytes.length >> 2] || 0) | (0x80 << (24 - (bytes.length % 4) * 8));
  words[(((bytes.length + 8) >> 6) << 4) + 15] = bitLength;

  let h0 = 0x6a09e667;
  let h1 = 0xbb67ae85;
  let h2 = 0x3c6ef372;
  let h3 = 0xa54ff53a;
  let h4 = 0x510e527f;
  let h5 = 0x9b05688c;
  let h6 = 0x1f83d9ab;
  let h7 = 0x5be0cd19;
  const k = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];
  const w = new Array<number>(64);

  for (let offset = 0; offset < words.length; offset += 16) {
    for (let index = 0; index < 16; index += 1) {
      w[index] = words[offset + index] || 0;
    }
    for (let index = 16; index < 64; index += 1) {
      const s0 = rotateRight(w[index - 15], 7) ^ rotateRight(w[index - 15], 18) ^ (w[index - 15] >>> 3);
      const s1 = rotateRight(w[index - 2], 17) ^ rotateRight(w[index - 2], 19) ^ (w[index - 2] >>> 10);
      w[index] = add32(w[index - 16], s0, w[index - 7], s1);
    }

    let a = h0;
    let b = h1;
    let c = h2;
    let d = h3;
    let e = h4;
    let f = h5;
    let g = h6;
    let h = h7;

    for (let index = 0; index < 64; index += 1) {
      const s1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = add32(h, s1, ch, k[index], w[index]);
      const s0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = add32(s0, maj);
      h = g;
      g = f;
      f = e;
      e = add32(d, temp1);
      d = c;
      c = b;
      b = a;
      a = add32(temp1, temp2);
    }

    h0 = add32(h0, a);
    h1 = add32(h1, b);
    h2 = add32(h2, c);
    h3 = add32(h3, d);
    h4 = add32(h4, e);
    h5 = add32(h5, f);
    h6 = add32(h6, g);
    h7 = add32(h7, h);
  }

  return [h0, h1, h2, h3, h4, h5, h6, h7]
    .map(value => value.toString(16).padStart(8, '0'))
    .join('');
}

function rotateRight(value: number, bits: number) {
  return (value >>> bits) | (value << (32 - bits));
}

function add32(...values: number[]) {
  return values.reduce((sum, value) => (sum + value) >>> 0, 0);
}

function highlightShell(value: string) {
  return (value || ' ').split('\n').map(line => {
    const commentIndex = line.indexOf('#');
    const code = commentIndex >= 0 ? line.slice(0, commentIndex) : line;
    const comment = commentIndex >= 0 ? line.slice(commentIndex) : '';
    const highlighted = code.replace(
      /("[^"\n]*"|'[^'\n]*'|\b(?:set|if|then|else|elif|fi|for|while|do|done|case|esac|function|return|exit|export|local|readonly|trap|source)\b|&&|\|\||[;|]|\$\?|\$\*|\$@|\$\{|\}|\$[A-Za-z_][A-Za-z0-9_]*)/g,
      token => {
        const escaped = escapeHtml(token);
        if (token.startsWith('"') || token.startsWith("'")) return `<span class="shell-token-string">${escaped}</span>`;
        if (/^(set|if|then|else|elif|fi|for|while|do|done|case|esac|function|return|exit|export|local|readonly|trap|source)$/.test(token)) {
          return `<span class="shell-token-keyword">${escaped}</span>`;
        }
        return `<span class="shell-token-symbol">${escaped}</span>`;
      });
    return highlighted + (comment ? `<span class="shell-token-comment">${escapeHtml(comment)}</span>` : '');
  }).join('\n');
}

function formatDuration(value: any) {
  const ms = Number(value || 0);
  if (ms <= 0) return '-';
  if (ms >= 60_000) return `${Math.floor(ms / 60_000)}m ${Math.floor((ms % 60_000) / 1000)}s`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(ms >= 10_000 ? 0 : 1)}s`;
  return `${Math.round(ms)}ms`;
}

function parseJsonOutput(value: string) {
  const trimmed = value.trim();
  const candidates = [trimmed];
  const firstObject = trimmed.search(/[\[{]/);
  if (firstObject > 0) {
    candidates.push(trimmed.slice(firstObject));
  }
  for (const candidate of candidates) {
    try {
      return { ok: true, formatted: JSON.stringify(JSON.parse(candidate), null, 2) };
    } catch {
      // try the next candidate
    }
  }
  return { ok: false, formatted: value };
}

function highlightJson(value: string) {
  return escapeHtml(value).replace(
    /(&quot;(?:\\.|[^&])*?&quot;)(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
    (match, stringValue, colon, literal) => {
      if (stringValue) {
        return colon ? `<span class="json-key">${stringValue}</span>${colon}` : `<span class="json-string">${stringValue}</span>`;
      }
      if (literal) {
        return `<span class="json-literal">${literal}</span>`;
      }
      return `<span class="json-number">${match}</span>`;
    });
}

function highlightJsonLine(value: string, query: string) {
  return query ? highlightSearch(escapeHtml(value), query) : highlightJson(value);
}

function highlightSearch(escapedHtml: string, query: string) {
  if (!query) return escapedHtml;
  const escapedQuery = escapeHtml(query);
  if (!escapedQuery) return escapedHtml;
  return escapedHtml.replace(new RegExp(escapeRegExp(escapedQuery), 'gi'), match => `<mark>${match}</mark>`);
}

function countMatches(value: string, query: string) {
  if (!query) return 0;
  return Array.from(value.matchAll(new RegExp(escapeRegExp(query), 'gi'))).length;
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function stripAnsi(value: string) {
  return value.replace(/\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])/g, '');
}

function logLevelClass(line: string) {
  const lower = line.toLowerCase();
  if (/\b(error|failed|failure|exception|fatal|panic)\b/.test(lower)) return 'output-line-error';
  if (/\b(warn|warning|retry|timeout|backpressure)\b/.test(lower)) return 'output-line-warn';
  if (/\b(success|ok|done|completed|active)\b/.test(lower)) return 'output-line-ok';
  if (/\b(info|start|running|progress)\b/.test(lower)) return 'output-line-info';
  return '';
}

function looksLikeMarkdown(value: string) {
  const sample = value.slice(0, 4096);
  return /(^|\n)#{1,3}\s+\S/.test(sample)
    || /(^|\n)\s*[-*]\s+\S/.test(sample)
    || /(^|\n)>\s+\S/.test(sample)
    || /```/.test(sample)
    || /\[[^\]]+\]\(https?:\/\/[^)]+\)/.test(sample);
}

function renderMarkdown(value: string) {
  const rows = value.split('\n');
  const html: string[] = [];
  const code: string[] = [];
  let inCode = false;
  let listOpen = false;

  const closeList = () => {
    if (listOpen) {
      html.push('</ul>');
      listOpen = false;
    }
  };

  for (const row of rows) {
    const fence = row.trim().match(/^```/);
    if (fence) {
      if (inCode) {
        html.push(`<pre class="markdown-code"><code>${escapeHtml(code.join('\n'))}</code></pre>`);
        code.length = 0;
        inCode = false;
      } else {
        closeList();
        inCode = true;
      }
      continue;
    }
    if (inCode) {
      code.push(row);
      continue;
    }

    const heading = row.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = heading[1].length;
      html.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`);
      continue;
    }

    const bullet = row.match(/^\s*[-*]\s+(.+)$/);
    if (bullet) {
      if (!listOpen) {
        html.push('<ul>');
        listOpen = true;
      }
      html.push(`<li>${inlineMarkdown(bullet[1])}</li>`);
      continue;
    }

    const quote = row.match(/^\s*>\s+(.+)$/);
    if (quote) {
      closeList();
      html.push(`<blockquote>${inlineMarkdown(quote[1])}</blockquote>`);
      continue;
    }

    if (!row.trim()) {
      closeList();
      html.push('<div class="markdown-gap"></div>');
      continue;
    }

    closeList();
    html.push(`<p>${inlineMarkdown(row)}</p>`);
  }
  closeList();
  if (inCode) {
    html.push(`<pre class="markdown-code"><code>${escapeHtml(code.join('\n'))}</code></pre>`);
  }
  return html.join('');
}

function inlineMarkdown(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, (_match, text, url) => {
      return `<a href="${escapeAttribute(url)}" target="_blank" rel="noreferrer">${text}</a>`;
    });
}

function escapeAttribute(value: string) {
  return escapeHtml(value).replace(/`/g, '&#96;');
}

function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

createRoot(document.getElementById('root')!).render(<App />);
