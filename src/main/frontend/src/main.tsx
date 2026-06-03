import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
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
  theme
} from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import 'antd/dist/reset.css';
import './style.css';

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
  output_streams?: any[];
};

const loadAverageWindowMs = 5 * 60 * 1000;
const palette = [205, 188, 168, 146, 126, 95, 48, 215, 200, 178];
const loadWindows = new Map<string, { windowStart: number; displayAvg: number; sampledAtMs: number }>();
const clusterCollapseStorageKey = 'pulse.cluster-collapse.v1';

const taskLabels: Record<string, string> = {
  prepare_disk_layout_dry_run: '磁盘布局',
  analyze_block_layout_dry_run: '块分布'
};
const defaultTaskArgs = '--dry-run';

type ActiveClusterRun = { name: string; hosts: HostView[] };

function normalizeAddress(value?: string) {
  const raw = String(value || '').replaceAll('[', '').replaceAll(']', '');
  if (!raw || raw.includes('.')) return '-';
  return raw;
}

function normalizeUrlHost(value?: string) {
  if (!value) return '-';
  try {
    return normalizeAddress(new URL(value).hostname);
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
    alive: '在线', warming: '确认中', expired: '过期'
  } as Record<string, string>)[status || ''] || status || '-';
}

function statusColor(status?: string): 'success' | 'processing' | 'warning' | 'error' | 'default' {
  if (status === 'alive' || status === 'completed') return 'success';
  if (status === 'running' || status === 'accepted' || status === 'delivered') return 'processing';
  if (status === 'warming' || status === 'queued') return 'warning';
  if (status === 'expired' || status === 'failed' || status === 'timeout' || status === 'timed_out') return 'error';
  return 'default';
}

function activeHostTasks(host?: HostView) {
  const tasks = host?.state?.async_tasks;
  return Array.isArray(tasks) ? tasks : [];
}

function groupByCluster(hosts: HostView[]) {
  const groups = new Map<string, HostView[]>();
  hosts.forEach(host => {
    const cluster = host.cluster || 'unknown';
    groups.set(cluster, [...(groups.get(cluster) || []), host]);
  });
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
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
  return [...hosts].sort((left, right) => averageLoad(right) - averageLoad(left) || normalizeAddress(left.ip).localeCompare(normalizeAddress(right.ip)));
}

function taskNeedsAttention(task: any) {
  const status = String(task?.status || '');
  return ['queued', 'delivered', 'accepted', 'running', 'failed', 'timeout', 'timed_out', 'rejected'].includes(status);
}

function clusterNeedsAttention(hosts: HostView[]) {
  return hosts.some(host => {
    if (host.status === 'warming' || host.status === 'expired') return true;
    return activeHostTasks(host).some(taskNeedsAttention);
  });
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

function App() {
  const [hosts, setHosts] = useState<HostView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeHost, setActiveHost] = useState<HostView | null>(null);
  const [activeCluster, setActiveCluster] = useState<ActiveClusterRun | null>(null);
  const [snapshot, setSnapshot] = useState<TaskSnapshot | null>(null);
  const [output, setOutput] = useState('');
  const [taskType, setTaskType] = useState('prepare_disk_layout_dry_run');
  const [taskArgs, setTaskArgs] = useState(defaultTaskArgs);
  const [collapsedClusters, setCollapsedClusters] = useState<Record<string, boolean>>(() => loadCollapsedClusters());
  const viewport = useRef({ left: 0, top: 0 });
  const activeTargetHost = activeHost || activeCluster?.hosts[0] || null;

  async function refreshHosts() {
    viewport.current = { left: window.scrollX, top: window.scrollY };
    try {
      const data = await fetchJson<HostView[]>('/api/hosts');
      recordLoadSamples(data);
      setHosts(data);
      setError('');
      requestAnimationFrame(() => window.scrollTo(viewport.current.left, viewport.current.top));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  async function refreshSnapshot(host: HostView) {
    const id = encodeURIComponent(agentId(host));
    const data = await fetchJson<TaskSnapshot>(`/api/agents/${id}/tasks`);
    setSnapshot(data);
    const latest = data.completion_queue?.[0];
    setOutput(latest ? completionOutput(latest) : '');
  }

  useEffect(() => {
    refreshHosts();
    const timer = window.setInterval(refreshHosts, 5000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!activeTargetHost) return;
    refreshSnapshot(activeTargetHost).catch(err => setOutput(String(err)));
    const timer = window.setInterval(() => refreshSnapshot(activeTargetHost).catch(err => setOutput(String(err))), 2000);
    return () => window.clearInterval(timer);
  }, [activeTargetHost?.ip, activeTargetHost?.agent_id, activeTargetHost?.agentId]);

  const groups = useMemo(() => groupByCluster(hosts), [hosts]);
  const attentionClusters = useMemo(() => new Set(groups.filter(([, clusterHosts]) => clusterNeedsAttention(clusterHosts)).map(([cluster]) => cluster)), [groups]);
  const alive = hosts.filter(host => host.status === 'alive').length;
  const avgLoad = hosts.length ? hosts.reduce((sum, host) => sum + averageLoad(host), 0) / hosts.length : 0;

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

  return <ConfigProvider autoInsertSpaceInButton={false} theme={{ algorithm: theme.defaultAlgorithm, token: { borderRadius: 18, colorPrimary: '#2563eb', fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif' } }}>
    <main className="pulse-page">
      <section className="pulse-hero">
        <Card className="hero-main" variant="outlined">
          <Typography.Text className="hero-eyebrow">Pulse 心跳平台</Typography.Text>
          <Typography.Title level={1}>心跳平台，连接运维现场</Typography.Title>
          <Typography.Paragraph className="hero-subtitle">任务、资源、监控与告警，沿一条消息链自然流动。</Typography.Paragraph>
          <Space size="middle" wrap>
            <Button type="primary" shape="round" size="large" href="#clusters">主机</Button>
            <Button shape="round" size="large" href="#capability">能力</Button>
          </Space>
        </Card>
        <Space direction="vertical" size={16} className="hero-side">
          <Card id="capability" title="平台能力" variant="outlined">
            <Row gutter={[12, 12]}>
              {[
                ['任务', '下发、执行、回执。'], ['集群', '分组、编排、收敛。'],
                ['资源', '采集、聚合、判断。'], ['告警', '识别、定位、闭环。']
              ].map(([title, text]) => <Col span={12} key={title}><Card className="cap-card" variant="borderless"><b>{title}</b><span>{text}</span></Card></Col>)}
            </Row>
          </Card>
          <Card title="平台协调" variant="outlined"><Typography.Text type="secondary">心跳不只是存活信号，也是轻量控制面。</Typography.Text><br/><Typography.Text strong>Pulse Coordinator</Typography.Text></Card>
        </Space>
      </section>

      <Row gutter={[14, 14]} className="metric-row">
        <Col xs={12} md={6} xl={4}><Card><Statistic title="主机" value={hosts.length} suffix="台" loading={loading}/></Card></Col>
        <Col xs={12} md={6} xl={4}><Card><Statistic title="在线率" value={hosts.length ? Math.round(alive * 100 / hosts.length) : 0} suffix="%"/></Card></Col>
        <Col xs={12} md={6} xl={4}><Card><Statistic title="5min AVG" value={formatLoad(avgLoad)}/></Card></Col>
        <Col xs={24} md={12} xl={8}><Card><Statistic title="Coordinator" valueRender={() => <AutoFitText className="metric-fit-value" text={normalizeAddress(window.location.host)} minFontSize={14} maxFontSize={24} />} /></Card></Col>
        <Col xs={12} md={6} xl={4}><Card><Statistic title="刷新" value="5s"/></Card></Col>
      </Row>

      {error && <Card className="error-card"><Typography.Text type="danger">{error}</Typography.Text></Card>}

      <section id="clusters" className="clusters">
        {groups.map(([cluster, clusterHosts], index) => <ClusterSection
          key={cluster}
          cluster={cluster}
          hosts={clusterHosts}
          hue={clusterHue(index)}
          collapsed={!!collapsedClusters[cluster] && !attentionClusters.has(cluster)}
          needsAttention={attentionClusters.has(cluster)}
          onToggle={() => setCollapsedClusters(prev => {
            const next = { ...prev };
            if (attentionClusters.has(cluster)) {
              delete next[cluster];
              return next;
            }
            if (next[cluster]) delete next[cluster];
            else next[cluster] = true;
            return next;
          })}
          onRun={host => { setActiveHost(host); setActiveCluster(null); setSnapshot(null); setOutput(''); }}
          onClusterRun={() => {
            setActiveHost(null);
            setActiveCluster({ name: cluster, hosts: sortHosts(clusterHosts) });
            setSnapshot(null);
            setOutput('');
          }}
        />)}
      </section>

      <TaskModal
        host={activeTargetHost}
        clusterName={activeCluster?.name || ''}
        clusterHosts={activeCluster?.hosts || []}
        open={!!activeTargetHost}
        onClose={() => { setActiveHost(null); setActiveCluster(null); }}
        snapshot={snapshot}
        output={output}
        taskType={taskType}
        setTaskType={setTaskType}
        taskArgs={taskArgs}
        setTaskArgs={setTaskArgs}
        onRun={async args => {
          const targets = activeCluster?.hosts || (activeHost ? [activeHost] : []);
          if (!targets.length) return;
          const results = await Promise.allSettled(targets.map(async target => {
            const id = encodeURIComponent(agentId(target));
            return fetchJson<TaskSnapshot>(`/api/agents/${id}/tasks`, {
              method: 'POST',
              headers: { 'content-type': 'application/json' },
              body: JSON.stringify({ task_type: taskType, args })
            });
          }));
          const first = results.find((result): result is PromiseFulfilledResult<TaskSnapshot> => result.status === 'fulfilled');
          if (first) setSnapshot(first.value);
          setOutput('');
          const failed = results.filter(result => result.status === 'rejected');
          if (failed.length) {
            setOutput(`集群下发部分失败: ${failed.length}/${targets.length}\n${failed.map(result => String((result as PromiseRejectedResult).reason)).join('\n')}`);
          }
        }}
        onPop={async () => {
          if (!activeTargetHost || !snapshot?.completion_queue?.[0]) {
            if (activeTargetHost) await refreshSnapshot(activeTargetHost);
            return;
          }
          const id = encodeURIComponent(agentId(activeTargetHost));
          const taskId = encodeURIComponent(snapshot.completion_queue[0].task_id);
          await fetchJson(`/api/agents/${id}/tasks/completions/${taskId}/pop`, { method: 'POST' });
          await refreshSnapshot(activeTargetHost);
        }}
      />
    </main>
  </ConfigProvider>;
}

function ClusterSection({
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
  onToggle: () => void;
  onRun: (host: HostView) => void;
  onClusterRun: () => void;
}) {
  const sorted = sortHosts(hosts);
  return <Card
    className={`cluster-section ${collapsed ? 'cluster-section-collapsed' : ''}`.trim()}
    style={{ ['--cluster-hue' as any]: hue }}
    title={<Space size={8}><span>{cluster}</span><Tag>{hosts.length} 台</Tag>{needsAttention && <Tag color="warning">需关注</Tag>}</Space>}
    extra={<Space size={6}>
      <Button size="small" className="cluster-run-button" onClick={onClusterRun}>Run UI</Button>
      <Button size="small" type="text" className="cluster-toggle-button" onClick={onToggle} disabled={needsAttention}>{needsAttention ? '异常展开' : (collapsed ? '展开' : '折叠')}</Button>
    </Space>}
    variant="outlined"
  >
    {!collapsed && <div className="tile-grid">
      {sorted.map(host => <HostTile host={host} key={hostKey(host)} onRun={() => onRun(host)} />)}
    </div>}
  </Card>;
}

function HostTile({ host, onRun }: { host: HostView; onRun: () => void }) {
  const avg = averageLoad(host);
  const level = Math.min(1, avg / 400);
  const confirmations = host.heartbeat_confirmations ?? host.heartbeatConfirmations ?? 0;
  const workers = Array.isArray(host.state?.workers) ? host.state?.workers : Array.isArray(host.state?.tide_workers) ? host.state?.tide_workers : [];
  const observedAt = host.observed_at_ms || host.observedAtMs;
  const lastObservedAge = hostDebugValue(host, 'last_observed_age_ms', 'lastObservedAgeMs', undefined) as number | undefined;
  const groupId = String(hostDebugValue(host, 'group_id', 'groupId'));
  const groupMode = String(hostDebugValue(host, 'group_mode', 'groupMode'));
  const leaderUrl = String(hostDebugValue(host, 'leader_url', 'leaderUrl'));
  const groupSize = hostDebugValue(host, 'group_size', 'groupSize', '-');
  const groupSizeLimit = hostDebugValue(host, 'group_size_limit', 'groupSizeLimit', '-');
  return <Card className="host-tile" style={{ ['--load-level' as any]: level }} data-agent-key={hostKey(host)} variant="borderless">
    <Flex className="tile-header" justify="space-between" align="center" gap={10}>
      <AutoFitText className="seen" title={formatTime(observedAt)} text={formatSeenTime(observedAt)} minFontSize={9} maxFontSize={11} />
      <Button className="run-button" data-status={statusColor(host.status)} type="primary" size="small" onClick={onRun} disabled={confirmations < 3 || host.status !== 'alive'}>任务</Button>
    </Flex>
    <div className="tile-scroll">
      <Typography.Title level={4} className="ip-title" data-field="ip_title">{normalizeAddress(host.ip)}</Typography.Title>
      <Row gutter={[8, 8]}>
        <Col span={12}><Statistic title="Area" value={host.area || '-'} /></Col>
        <Col span={12}><Statistic title="5min AVG" value={formatLoad(avg)} valueStyle={{ fontSize: 18 }} /></Col>
        <Col span={24}><Statistic title="20s确认" value={confirmations} /></Col>
      </Row>
      <div className="debug-panel">
        <Typography.Text className="debug-title">调试</Typography.Text>
        <div className="debug-grid">
          <span>age {formatAge(lastObservedAge)}</span>
          <span>mode {groupMode}</span>
          <span>group {groupId}</span>
          <span>size {groupSize}/{groupSizeLimit}</span>
          <span>leader {normalizeUrlHost(leaderUrl)}</span>
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
            <span>cpu {workerValue(worker, 'cpu_percent')}</span>
            <span>usr {workerValue(worker, 'user_cpu_percent')}</span>
            <span>sys {workerValue(worker, 'sys_cpu_percent')}</span>
            <span>rss {formatRssMb(worker)}</span>
            <span>mem {workerValue(worker, 'mem_percent')}</span>
            <span>thr {workerValue(worker, 'threads')}</span>
            {worker.port1 && <span>port {workerValue(worker, 'port1')}</span>}
          </div>
        </div>)}
      </div>}
    </div>
  </Card>;
}

function TaskModal(props: {
  host: HostView | null;
  clusterName: string;
  clusterHosts: HostView[];
  open: boolean;
  onClose: () => void;
  snapshot: TaskSnapshot | null;
  output: string;
  taskType: string;
  setTaskType: (value: string) => void;
  taskArgs: string;
  setTaskArgs: (value: string) => void;
  onRun: (args: string[]) => Promise<void>;
  onPop: () => Promise<void>;
}) {
  const [argsUnlocked, setArgsUnlocked] = useState(false);
  const unlockClicks = useRef<number[]>([]);
  const tasks = activeHostTasks(props.host || undefined);
  const agentTask = tasks[0];
  const completions = props.snapshot?.completion_queue || [];
  const executions = props.snapshot?.execution_queue || [];
  const latestCompletion = completions[0];
  const streamLog = latestCompletion ? null : streamForTask(props.snapshot, agentTask?.task_id || executions[0]?.task_id);
  const visibleTraces = (props.snapshot?.traces || []).slice(0, 4);
  const completionText = props.output || (latestCompletion ? completionOutput(latestCompletion) : (streamLog ? streamOutput(streamLog) : ''));
  const asyncTask = agentTask || executions[0];
  const currentTaskId = latestCompletion?.task_id || asyncTask?.task_id || props.snapshot?.traces?.[0]?.task_id || '';
  const outputMeta = latestCompletion || streamLog || asyncTask;
  const outputRunning = !latestCompletion && !!asyncTask;
  const outputNotice = outputStatusNotice(completionText, outputMeta, outputRunning);
  const isClusterRun = props.clusterHosts.length > 0;
  const targetTitle = isClusterRun ? props.clusterName : normalizeAddress(props.host?.ip);
  const targetDescription = isClusterRun ? `${props.clusterHosts.length} 台 host，将逐台下发作业` : '单节点作业';
  function handleResultTitleClick() {
    const now = Date.now();
    const next = [...unlockClicks.current.filter(value => now - value <= 5000), now];
    unlockClicks.current = next;
    if (next.length >= 3) {
      setArgsUnlocked(true);
      if (!props.taskArgs.trim()) {
        props.setTaskArgs(defaultTaskArgs);
      }
    }
  }
  return <Modal open={props.open} onCancel={props.onClose} footer={null} width="min(1320px, calc(100vw - 44px))" className="task-modal" title={null} closeIcon={<span className="mac-close" />}>
    <div className="task-shell">
      <div className="task-sidebar">
        <Card className="task-hero" variant="outlined">
          <Flex gap={8} align="center">
            <Select value={props.taskType} onChange={props.setTaskType} className="task-select" options={Object.entries(taskLabels).map(([value, label]) => ({ value, label }))}/>
            <Button type="primary" onClick={() => props.onRun(parseTaskArgs(props.taskArgs || defaultTaskArgs))}>执行</Button>
            <Button onClick={props.onPop} icon={<InboxOutlined />}>弹出结果</Button>
          </Flex>
          {argsUnlocked && <div className="task-args-panel">
            <Typography.Text className="task-args-title">自定义参数</Typography.Text>
            <Input.TextArea
              value={props.taskArgs}
              onChange={event => props.setTaskArgs(event.target.value)}
              autoSize={{ minRows: 1, maxRows: 3 }}
              placeholder={defaultTaskArgs}
            />
            <Typography.Text type="secondary">默认参数为 --dry-run。非 dry-run 操作会真实修改线上机器，执行前必须确认目标范围。</Typography.Text>
          </div>}
        </Card>
        <Card title={isClusterRun ? '目标集群' : '目标节点'}>
          <Space direction="vertical" size={4}>
            <Typography.Text strong>{targetTitle}</Typography.Text>
            <Typography.Text type="secondary">{targetDescription}</Typography.Text>
          </Space>
        </Card>
        <Card title="当前任务">
          <Space direction="vertical" size={6} className="task-state-card">
            <Badge status={statusColor(agentTask?.status || executions[0]?.status)} text={statusLabel(agentTask?.status || executions[0]?.status || '空闲')} />
            <Typography.Text type="secondary">{taskLabels[(latestCompletion?.task_type || asyncTask?.task_type || '')] || latestCompletion?.task_type || asyncTask?.task_type || '当前没有任务。'}</Typography.Text>
            {currentTaskId && <Typography.Text className="task-id-text" copyable={{ text: currentTaskId }}>task_id: {currentTaskId}</Typography.Text>}
          </Space>
        </Card>
        <Card title="结果队列"><Statistic value={completions.length} /></Card>
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
      </div>
      <Card
        className="task-workspace"
        title={<OutputPanelTitle meta={outputMeta} notice={outputNotice} value={completionText} onUnlock={handleResultTitleClick} />}
        variant="outlined"
      >
        <div className="completion-pane">
          <CompletionViewer value={completionText} meta={outputMeta} />
        </div>
      </Card>
    </div>
  </Modal>;
}

function OutputPanelTitle({ meta, notice, value, onUnlock }: { meta?: any; notice: OutputNotice | null; value: string; onUnlock?: () => void }) {
  const lines = Number(meta?.output_lines ?? meta?.stream_lines ?? countLines(value));
  const bytes = Number(meta?.output_bytes ?? meta?.stream_bytes ?? new Blob([value]).size);
  return <div className="output-panel-title">
    <button type="button" className="output-title-main output-title-trigger" onClick={onUnlock}>结果查看</button>
    <span className="output-title-spacer" />
    {notice && <OutputStatusNotice notice={notice} compact />}
    {meta?.status && <span className={`output-title-pill output-title-${statusColor(meta.status)}`}>{statusLabel(meta.status)}</span>}
    {meta?.exit_code !== undefined && meta?.exit_code !== null && <span className="output-title-pill">exit {meta.exit_code}</span>}
    <span className="output-title-pill">{lines} 行</span>
    <span className="output-title-pill">{formatBytes(bytes)}</span>
  </div>;
}

function CompletionViewer({ value, meta }: { value: string; meta?: any }) {
  const [mode, setMode] = useState<'log' | 'json' | 'markdown' | 'raw'>('log');
  const [query, setQuery] = useState('');
  const [wrap, setWrap] = useState(true);
  const parsed = useMemo(() => parseJsonOutput(value), [value]);
  const display = mode === 'json' && parsed.ok ? parsed.formatted : value;
  const outputType = String(meta?.output_type ?? meta?.outputType ?? meta?.stream_id ?? '').toLowerCase();
  const markdownHint = outputType === 'markdown' || looksLikeMarkdown(value);
  const matches = query ? countMatches(display, query) : 0;
  return <div className="completion-viewer">
    <Flex className="completion-toolbar" justify="space-between" align="center" gap={8}>
      <Space size={8}>
        <Segmented
          size="small"
          value={mode}
          onChange={next => setMode(next as 'log' | 'json' | 'markdown' | 'raw')}
          options={[
            { label: '日志', value: 'log' },
            { label: 'JSON', value: 'json', disabled: !parsed.ok },
            { label: 'Markdown', value: 'markdown' },
            { label: '原始', value: 'raw' }
          ]}
        />
        {parsed.ok && <Tag color="blue">JSON</Tag>}
        {markdownHint && <Tag color="purple">Markdown</Tag>}
        {query && <Tag color={matches > 0 ? 'green' : 'red'}>{matches} 匹配</Tag>}
      </Space>
      <Space size={8}>
        <Input.Search
          size="small"
          allowClear
          className="output-search"
          placeholder="搜索输出"
          value={query}
          onChange={event => setQuery(event.target.value)}
        />
        <Button size="small" onClick={() => setWrap(next => !next)}>{wrap ? '不换行' : '自动换行'}</Button>
        <Button size="small" onClick={() => navigator.clipboard?.writeText(display)}>拷贝</Button>
      </Space>
    </Flex>
    {mode === 'markdown'
      ? value
        ? <div className="task-output markdown-output" dangerouslySetInnerHTML={{ __html: renderMarkdown(value) }} />
        : <Empty className="output-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无命令输出" />
      : <LineNumberedOutput
          value={display}
          mode={mode}
          query={query}
          wrap={wrap}
          json={mode === 'json' && parsed.ok}
        />}
  </div>;
}

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

function LineNumberedOutput({
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
  if (!value) {
    return <Empty className="output-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无命令输出" />;
  }
  const rows = value.length > 0 ? value.split('\n') : [''];
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
}

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
  const output = result.output || result.stdout_tail || result.stdout || '';
  const stderr = result.stderr_tail || result.stderr || '';
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

function formatBytes(value: any) {
  const bytes = Number(value || 0);
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${bytes} B`;
}

function formatDuration(value: any) {
  const ms = Number(value || 0);
  if (ms <= 0) return '-';
  if (ms >= 60_000) return `${Math.floor(ms / 60_000)}m ${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 1000)}s`;
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
