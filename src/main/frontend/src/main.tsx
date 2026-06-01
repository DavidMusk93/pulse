import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Badge,
  Button,
  Card,
  Col,
  ConfigProvider,
  Empty,
  Flex,
  List,
  Modal,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Tabs,
  Tag,
  Typography,
  theme
} from 'antd';
import { PlayCircleOutlined, InboxOutlined } from '@ant-design/icons';
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
  heartbeat_confirmations?: number;
  heartbeatConfirmations?: number;
  status?: string;
  source?: string;
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
};

const loadAverageWindowMs = 5 * 60 * 1000;
const palette = [205, 188, 168, 146, 126, 95, 48, 215, 200, 178];
const loadWindows = new Map<string, { windowStart: number; displayAvg: number; sampledAtMs: number }>();

const taskLabels: Record<string, string> = {
  prepare_disk_layout_dry_run: '磁盘布局 dry-run',
  analyze_block_layout_dry_run: '块分布 dry-run'
};

function normalizeAddress(value?: string) {
  const raw = String(value || '').replaceAll('[', '').replaceAll(']', '');
  if (!raw || raw.includes('.')) return '-';
  return raw;
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
    return new Date(ms).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return '-';
  }
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

function clusterHue(index: number) {
  return palette[index % palette.length];
}

function sortHosts(hosts: HostView[]) {
  return [...hosts].sort((left, right) => averageLoad(right) - averageLoad(left) || normalizeAddress(left.ip).localeCompare(normalizeAddress(right.ip)));
}

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { cache: 'no-store', ...init });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return response.json();
}

function App() {
  const [hosts, setHosts] = useState<HostView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeHost, setActiveHost] = useState<HostView | null>(null);
  const [snapshot, setSnapshot] = useState<TaskSnapshot | null>(null);
  const [output, setOutput] = useState('');
  const [taskType, setTaskType] = useState('prepare_disk_layout_dry_run');
  const viewport = useRef({ left: 0, top: 0 });

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
    if (latest) setOutput(renderCompletion(latest));
  }

  useEffect(() => {
    refreshHosts();
    const timer = window.setInterval(refreshHosts, 5000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!activeHost) return;
    refreshSnapshot(activeHost).catch(err => setOutput(String(err)));
    const timer = window.setInterval(() => refreshSnapshot(activeHost).catch(err => setOutput(String(err))), 2000);
    return () => window.clearInterval(timer);
  }, [activeHost?.ip, activeHost?.agent_id, activeHost?.agentId]);

  const groups = useMemo(() => groupByCluster(hosts), [hosts]);
  const alive = hosts.filter(host => host.status === 'alive').length;
  const avgLoad = hosts.length ? hosts.reduce((sum, host) => sum + averageLoad(host), 0) / hosts.length : 0;

  return <ConfigProvider theme={{ algorithm: theme.defaultAlgorithm, token: { borderRadius: 18, colorPrimary: '#2563eb', fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif' } }}>
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
        <Col xs={12} md={4}><Card><Statistic title="主机" value={hosts.length} suffix="台" loading={loading}/></Card></Col>
        <Col xs={12} md={4}><Card><Statistic title="在线率" value={hosts.length ? Math.round(alive * 100 / hosts.length) : 0} suffix="%"/></Card></Col>
        <Col xs={12} md={4}><Card><Statistic title="5min AVG" value={formatLoad(avgLoad)}/></Card></Col>
        <Col xs={12} md={4}><Card><Statistic title="Coordinator" value={normalizeAddress(window.location.host)}/></Card></Col>
        <Col xs={12} md={4}><Card><Statistic title="刷新" value="5s"/></Card></Col>
        <Col xs={12} md={4}><Card><Statistic title="框架" value="Ant Design"/></Card></Col>
      </Row>

      {error && <Card className="error-card"><Typography.Text type="danger">{error}</Typography.Text></Card>}

      <section id="clusters" className="clusters">
        {groups.map(([cluster, clusterHosts], index) => <ClusterSection key={cluster} cluster={cluster} hosts={clusterHosts} hue={clusterHue(index)} onRun={host => { setActiveHost(host); setSnapshot(null); setOutput(''); }} />)}
      </section>

      <TaskModal
        host={activeHost}
        open={!!activeHost}
        onClose={() => setActiveHost(null)}
        snapshot={snapshot}
        output={output}
        taskType={taskType}
        setTaskType={setTaskType}
        onRun={async () => {
          if (!activeHost) return;
          const id = encodeURIComponent(agentId(activeHost));
          const data = await fetchJson<TaskSnapshot>(`/api/agents/${id}/tasks`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ task_type: taskType }) });
          setSnapshot(data);
          setOutput('任务已下发，等待 agent 心跳反馈执行状态。');
        }}
        onPop={async () => {
          if (!activeHost || !snapshot?.completion_queue?.[0]) return;
          const id = encodeURIComponent(agentId(activeHost));
          const taskId = encodeURIComponent(snapshot.completion_queue[0].task_id);
          await fetchJson(`/api/agents/${id}/tasks/completions/${taskId}/pop`, { method: 'POST' });
          await refreshSnapshot(activeHost);
        }}
      />
    </main>
  </ConfigProvider>;
}

function ClusterSection({ cluster, hosts, hue, onRun }: { cluster: string; hosts: HostView[]; hue: number; onRun: (host: HostView) => void }) {
  const sorted = sortHosts(hosts);
  return <Card className="cluster-section" style={{ ['--cluster-hue' as any]: hue }} title={<Space><span>{cluster}</span><Tag>{hosts.length} 台</Tag></Space>} variant="outlined">
    <div className="tile-grid">
      {sorted.map(host => <HostTile host={host} key={hostKey(host)} onRun={() => onRun(host)} />)}
    </div>
  </Card>;
}

function HostTile({ host, onRun }: { host: HostView; onRun: () => void }) {
  const avg = averageLoad(host);
  const level = Math.min(1, avg / 400);
  const confirmations = host.heartbeat_confirmations ?? host.heartbeatConfirmations ?? 0;
  const workers = Array.isArray(host.state?.workers) ? host.state?.workers : Array.isArray(host.state?.tide_workers) ? host.state?.tide_workers : [];
  return <Card className="host-tile" style={{ ['--load-level' as any]: level }} data-agent-key={hostKey(host)} variant="borderless">
    <Flex className="tile-header" justify="space-between" align="start" gap={8}>
      <Typography.Text className="seen">{formatTime(host.observed_at_ms || host.observedAtMs)}</Typography.Text>
      <Space size={8} className="tile-actions">
        <Badge className="status-dot" status={statusColor(host.status) as any} />
        <Button className="run-button" type="primary" size="small" icon={<PlayCircleOutlined />} onClick={onRun} disabled={confirmations < 3 || host.status !== 'alive'}>任务</Button>
      </Space>
    </Flex>
    <div className="tile-scroll">
      <Typography.Title level={4} className="ip-title" data-field="ip_title">{normalizeAddress(host.ip)}</Typography.Title>
      <Row gutter={[8, 8]}>
        <Col span={12}><Statistic title="Area" value={host.area || '-'} /></Col>
        <Col span={12}><Statistic title="5min AVG" value={formatLoad(avg)} valueStyle={{ fontSize: 18 }} /></Col>
        <Col span={24}><Statistic title="Confirm" value={confirmations} /></Col>
      </Row>
      <Progress percent={Math.round(level * 100)} showInfo={false} strokeColor="hsl(var(--cluster-hue) 48% 24%)" trailColor="rgba(15,23,42,.24)" />
      {workers.length > 0 && <List className="worker-list" size="small" dataSource={workers.slice(0, 4)} renderItem={(worker: any) => <List.Item>
        <Typography.Text>pid {String(worker.pid || '')}</Typography.Text>
        <Typography.Text type="secondary">cpu {String(worker.cpu_percent || '')}</Typography.Text>
      </List.Item>} />}
    </div>
  </Card>;
}

function TaskModal(props: {
  host: HostView | null;
  open: boolean;
  onClose: () => void;
  snapshot: TaskSnapshot | null;
  output: string;
  taskType: string;
  setTaskType: (value: string) => void;
  onRun: () => Promise<void>;
  onPop: () => Promise<void>;
}) {
  const tasks = activeHostTasks(props.host || undefined);
  const agentTask = tasks[0];
  const completions = props.snapshot?.completion_queue || [];
  const executions = props.snapshot?.execution_queue || [];
  return <Modal open={props.open} onCancel={props.onClose} footer={null} width="min(1320px, calc(100vw - 44px))" className="task-modal" title={null} closeIcon={<span className="mac-close" />}>
    <div className="task-shell">
      <Space direction="vertical" size={12} className="task-sidebar">
        <Card className="task-hero" variant="outlined">
          <Flex gap={8} align="center">
            <Select value={props.taskType} onChange={props.setTaskType} className="task-select" options={Object.entries(taskLabels).map(([value, label]) => ({ value, label }))}/>
            <Button type="primary" onClick={props.onRun}>执行</Button>
            <Button onClick={props.onPop} icon={<InboxOutlined />}>弹出结果</Button>
          </Flex>
        </Card>
        <Card title="目标节点"><Typography.Text strong>{normalizeAddress(props.host?.ip)}</Typography.Text></Card>
        <Card title="当前任务"><Badge status={statusColor(agentTask?.status || executions[0]?.status)} text={statusLabel(agentTask?.status || executions[0]?.status || '空闲')} /></Card>
        <Card title="结果队列"><Statistic value={completions.length} /></Card>
        <Card title="执行队列">
          {tasks.length > 0 ? <List dataSource={tasks} renderItem={(task: any) => <List.Item><Space direction="vertical" size={2}><Space><Typography.Text strong>agent 执行中</Typography.Text><Tag color="blue">{statusLabel(task.status)}</Tag></Space><Typography.Text type="secondary">{taskLabels[task.task_type] || task.task_type}</Typography.Text><Progress percent={task.status === 'running' ? 68 : 38} showInfo={false}/></Space></List.Item>} /> : executions.length > 0 ? <List dataSource={executions} renderItem={(task: any) => <List.Item>{statusLabel(task.status)} · {taskLabels[task.task_type] || task.task_type}</List.Item>} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有待执行任务。来自 agent 心跳的任务会显示在上方。" />}
        </Card>
      </Space>
      <Card className="task-workspace" title="结果查看" variant="outlined">
        <Tabs items={[{ key: 'output', label: 'Completion', children: <pre className="task-output">{props.output || (agentTask ? runningTaskText(agentTask) : '')}</pre> }, { key: 'trace', label: 'Trace', children: <List dataSource={props.snapshot?.traces || []} renderItem={(trace: any) => <List.Item><Typography.Text>{formatTime(trace.observed_at_ms || trace.observedAtMs)} · {trace.event || trace.status || '-'}</Typography.Text></List.Item>} /> }]} />
      </Card>
    </div>
  </Modal>;
}

function runningTaskText(task: any) {
  return [
    'agent 已反馈执行状态',
    '状态: ' + statusLabel(task.status),
    '任务: ' + (taskLabels[task.task_type] || task.task_type || ''),
    'task: ' + (task.task_id || ''),
    'trace: ' + (task.trace_id || ''),
    '接收: ' + formatTime(task.accepted_at_ms),
    '开始: ' + formatTime(task.started_at_ms)
  ].join('\n');
}

function renderCompletion(result: any) {
  return [
    `status: ${result.status || '-'}`,
    `exit_code: ${result.exit_code ?? '-'}`,
    `task_id: ${result.task_id || '-'}`,
    `trace_id: ${result.trace_id || '-'}`,
    '',
    result.stdout_tail || result.stdout || '',
    result.stderr_tail || result.stderr || ''
  ].join('\n');
}

createRoot(document.getElementById('root')!).render(<App />);
