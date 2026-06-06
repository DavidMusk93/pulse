# Coordinator 本地存储与时序展示设计

## 结论

每个 Pulse coordinator 增加本地嵌入式时序存储，用于保存本 coordinator 观察到的 heartbeat 与 agent 运行状态历史数据。第一版使用 SQLite 作为本地数据库，按时间分桶、按 host 索引，并通过 coordinator Web API 向前端提供类似 Grafana 的 range query 数据。

第一版落地范围：

- 统计每个 host 的 heartbeat 延迟、到达时间、处理耗时、状态和消息大小。
- 统计每个 host 上 tide_worker 进程的 pid、版本、cpu、rss、线程数、端口、debug 信息和 leader/follower 信息。
- 前端新增 dashboard/time-series 数据层，支持 range、step、series、downsample、auto-refresh。
- 每个 coordinator 只保存本地观察数据，不在第一版做跨 coordinator 全局聚合。

核心约束：

- 不引入外部数据库、Kafka、Prometheus 或远程 TSDB；coordinator 必须单机可运行。
- 本地存储是 coordinator 的可观测性缓存，不是控制面的强一致状态源。
- 写入路径不能阻塞 heartbeat 快路径；必须通过异步队列批量落盘。
- UI 查询不能扫描全表；所有查询必须使用时间范围、agent/cluster/area 过滤和步长下采样。
- 数据必须有 TTL、容量上限、降级策略和损坏恢复策略。

## 非目标

- 不实现跨 coordinator 的全局一致查询。
- 不替代现有 `/api/hosts` 当前态接口。
- 不把本地库作为任务执行、文件下发或 shell 输出的唯一事实源。
- 不保存完整 heartbeat 原始 JSON 长期历史；只保存可查询指标和必要事件摘要。
- 不在第一版引入实时 WebSocket。前端仍使用 HTTP polling，后续可升级 SSE。

## 数据库选择

### 选择 SQLite

第一版使用 SQLite，数据库文件默认位于：

```text
${PULSE_COORDINATOR_DATA_DIR:-${install_root}/coordinator}/pulse-local.db
```

推荐配置：

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA temp_store = MEMORY;
PRAGMA busy_timeout = 5000;
PRAGMA foreign_keys = ON;
```

选择 SQLite 的原因：

- 嵌入式单文件部署，符合当前 coordinator 单 JAR / systemd 部署模式。
- Java 生态成熟，读写路径简单，运维成本低。
- WAL 模式支持一个 writer、多个 reader，适合 coordinator 单进程批量写入 + UI 查询。
- 对 50 至数百 host、秒级 heartbeat、数天保留的规模足够。
- 支持普通 SQL、索引、聚合、窗口查询和在线备份。

### 不选择其他方案

DuckDB：

- 优点是列式分析强，适合离线聚合。
- 缺点是高频小批写入、长驻服务并发查询和 WAL 语义不如 SQLite 简单。
- 可作为后续离线导出或历史归档格式，不作为第一版在线库。

H2：

- 优点是纯 Java、集成简单。
- 缺点是生产运维经验和时序查询生态弱于 SQLite，文件损坏恢复和 CLI 诊断不如 SQLite 直接。

RocksDB：

- 优点是写入强。
- 缺点是查询模型需要自建二级索引和聚合，前端 range query 会复杂化。

Prometheus remote write / VictoriaMetrics / TimescaleDB：

- 优点是时序能力完整。
- 缺点是引入外部依赖，不符合“每个 coordinator 本地存储”的目标。

## 数据模型

### 时间语义

所有时间字段使用 Unix epoch milliseconds：

- `observed_at_ms`：coordinator 收到并开始处理 heartbeat 的本地时间。
- `agent_sent_at_ms`：agent 生成 heartbeat 的时间，由 heartbeat payload 上报。
- `collector_sent_at_ms`：group heartbeat 场景中 leader/collector 转发时间，可选。
- `stored_at_ms`：写入本地库时间。

heartbeat 延迟定义：

```text
heartbeat_latency_ms = observed_at_ms - agent_sent_at_ms
```

如果 agent 没有上报 `agent_sent_at_ms`，延迟为 `NULL`，但仍记录 `arrival_gap_ms`：

```text
arrival_gap_ms = observed_at_ms - previous_observed_at_ms(agent_id)
```

### 维度表：`host_dimension`

保存 host 最新维度信息，供查询时过滤和展示。

```sql
CREATE TABLE IF NOT EXISTS host_dimension (
  agent_id             TEXT PRIMARY KEY,
  ip                   TEXT NOT NULL,
  normalized_ip        TEXT NOT NULL,
  cluster              TEXT NOT NULL DEFAULT 'unknown',
  area                 TEXT NOT NULL DEFAULT 'unknown',
  host_group           TEXT NOT NULL DEFAULT 'unknown',
  mode                 TEXT NOT NULL DEFAULT 'unknown',
  coordinator_id       TEXT NOT NULL,
  first_seen_ms        INTEGER NOT NULL,
  last_seen_ms         INTEGER NOT NULL,
  last_status          TEXT NOT NULL,
  last_heartbeat_seq   INTEGER,
  metadata_json        TEXT
);

CREATE INDEX IF NOT EXISTS idx_host_dimension_cluster
  ON host_dimension(cluster, area, host_group);

CREATE INDEX IF NOT EXISTS idx_host_dimension_ip
  ON host_dimension(normalized_ip);
```

说明：

- `agent_id` 是稳定主键。
- `normalized_ip` 用于排序和前端展示稳定性。
- `metadata_json` 只保存低频变更字段，不保存每次 heartbeat 全量。

### 时序表：`heartbeat_sample`

保存 per host heartbeat 观测样本。

```sql
CREATE TABLE IF NOT EXISTS heartbeat_sample (
  bucket_ms             INTEGER NOT NULL,
  observed_at_ms        INTEGER NOT NULL,
  agent_id              TEXT NOT NULL,
  heartbeat_seq         INTEGER,
  status                TEXT NOT NULL,
  agent_sent_at_ms      INTEGER,
  latency_ms            INTEGER,
  arrival_gap_ms        INTEGER,
  coordinator_process_ms INTEGER,
  request_bytes         INTEGER,
  response_bytes        INTEGER,
  message_count         INTEGER,
  error_code            TEXT,
  stored_at_ms          INTEGER NOT NULL,
  PRIMARY KEY (agent_id, observed_at_ms)
);

CREATE INDEX IF NOT EXISTS idx_heartbeat_sample_time
  ON heartbeat_sample(bucket_ms, observed_at_ms);

CREATE INDEX IF NOT EXISTS idx_heartbeat_sample_agent_time
  ON heartbeat_sample(agent_id, observed_at_ms);

CREATE INDEX IF NOT EXISTS idx_heartbeat_sample_status_time
  ON heartbeat_sample(status, observed_at_ms);
```

字段说明：

- `bucket_ms` 是按写入配置对 `observed_at_ms` 取整后的时间桶，如 10s 或 60s，用于快速 group by。
- `latency_ms` 可能为 `NULL`，前端需按 missing point 处理。
- `arrival_gap_ms` 用于识别 heartbeat 间隔异常、agent 卡顿或链路抖动。
- `coordinator_process_ms` 是 coordinator 处理 heartbeat 的本地耗时。

### 时序表：`tide_worker_sample`

保存 tide_worker 进程样本。一个 host 上可能有多个 tide_worker，因此主键包含 pid。

```sql
CREATE TABLE IF NOT EXISTS tide_worker_sample (
  bucket_ms             INTEGER NOT NULL,
  observed_at_ms        INTEGER NOT NULL,
  agent_id              TEXT NOT NULL,
  pid                   INTEGER NOT NULL,
  version               TEXT,
  role                  TEXT,
  leader                TEXT,
  area                  TEXT,
  group_name            TEXT,
  cpu_pct               REAL,
  usr_pct               REAL,
  sys_pct               REAL,
  mem_pct               REAL,
  rss_kb                INTEGER,
  thread_count          INTEGER,
  port                  INTEGER,
  age_seconds           INTEGER,
  mode                  TEXT,
  size_current          INTEGER,
  size_total            INTEGER,
  debug_json            TEXT,
  stored_at_ms          INTEGER NOT NULL,
  PRIMARY KEY (agent_id, observed_at_ms, pid)
);

CREATE INDEX IF NOT EXISTS idx_tide_worker_time
  ON tide_worker_sample(bucket_ms, observed_at_ms);

CREATE INDEX IF NOT EXISTS idx_tide_worker_agent_time
  ON tide_worker_sample(agent_id, observed_at_ms);

CREATE INDEX IF NOT EXISTS idx_tide_worker_role_time
  ON tide_worker_sample(role, observed_at_ms);
```

说明：

- `debug_json` 保存低频调试字段，如 leader、mode、group、size 等未结构化扩展。
- 高频可画图字段必须结构化，例如 `cpu_pct`、`rss_kb`、`thread_count`。
- 如果某次 heartbeat 没有 tide_worker，写入一条 host 级状态事件，而不是写入 pid=0 的伪样本。

### 事件表：`host_event`

保存稀疏事件，避免将异常文本塞进样本表。

```sql
CREATE TABLE IF NOT EXISTS host_event (
  event_id          TEXT PRIMARY KEY,
  observed_at_ms    INTEGER NOT NULL,
  agent_id          TEXT NOT NULL,
  severity          TEXT NOT NULL,
  event_type        TEXT NOT NULL,
  message           TEXT NOT NULL,
  details_json      TEXT,
  stored_at_ms      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_host_event_agent_time
  ON host_event(agent_id, observed_at_ms);

CREATE INDEX IF NOT EXISTS idx_host_event_type_time
  ON host_event(event_type, observed_at_ms);
```

事件类型示例：

- `heartbeat.missing_agent_sent_time`
- `heartbeat.latency_spike`
- `heartbeat.arrival_gap_spike`
- `tide_worker.disappeared`
- `tide_worker.pid_changed`
- `tide_worker.leader_changed`
- `storage.write_dropped`

### 聚合表：`metric_rollup_1m`

第一版可先不实现 rollup 表，直接基于 sample 按 bucket 聚合。规模扩大后增加 1m rollup：

```sql
CREATE TABLE IF NOT EXISTS metric_rollup_1m (
  metric_name       TEXT NOT NULL,
  bucket_ms         INTEGER NOT NULL,
  agent_id          TEXT NOT NULL,
  min_value         REAL,
  max_value         REAL,
  avg_value         REAL,
  p50_value         REAL,
  p95_value         REAL,
  count_value       INTEGER NOT NULL,
  missing_count     INTEGER NOT NULL DEFAULT 0,
  stored_at_ms      INTEGER NOT NULL,
  PRIMARY KEY (metric_name, bucket_ms, agent_id)
);

CREATE INDEX IF NOT EXISTS idx_metric_rollup_1m_metric_time
  ON metric_rollup_1m(metric_name, bucket_ms);
```

## 写入架构

### 数据流

```text
agent heartbeat
  -> CoordinatorHttpServer
  -> Heartbeat handler parses current state
  -> Current in-memory host view update
  -> LocalMetricSample created
  -> Bounded write queue
  -> Batch writer thread
  -> SQLite WAL
  -> Query API
  -> Frontend chart store
  -> Grafana-like panels
```

关键原则：

- heartbeat handler 只负责构造轻量 sample 并放入队列。
- SQLite 写入由单独 writer 线程批量事务完成。
- 查询 API 使用独立 read connection，不与 writer 共用 statement。
- 当 write queue 满时，丢弃最老或最新样本必须可配置，并写入 `storage.write_dropped` 事件或内存计数。

### 写入队列

配置项：

```text
PULSE_LOCAL_STORAGE_ENABLED=true
PULSE_LOCAL_STORAGE_PATH=/data24/otf/pulse/coordinator/pulse-local.db
PULSE_LOCAL_STORAGE_QUEUE_SIZE=20000
PULSE_LOCAL_STORAGE_BATCH_SIZE=500
PULSE_LOCAL_STORAGE_FLUSH_MS=1000
PULSE_LOCAL_STORAGE_RETENTION_DAYS=7
PULSE_LOCAL_STORAGE_MAX_BYTES=10737418240
```

队列策略：

- 默认 `drop_oldest`，保证最新图表可用。
- 如果连续丢弃超过阈值，在 UI 上显示 coordinator storage degraded。
- 单次 batch 写入包含 host dimension upsert、heartbeat sample、tide worker sample 和事件。

### 批量写入事务

伪代码：

```text
while running:
  samples = queue.drain(max=batch_size, timeout=flush_ms)
  begin transaction
    upsert host_dimension
    insert heartbeat_sample
    insert tide_worker_sample
    insert host_event
  commit
```

写入幂等：

- `heartbeat_sample` 以 `(agent_id, observed_at_ms)` 去重。
- `tide_worker_sample` 以 `(agent_id, observed_at_ms, pid)` 去重。
- 如果同一 heartbeat 被重复处理，使用 `INSERT OR IGNORE` 或 `ON CONFLICT DO UPDATE`。

## 保留与清理

### TTL

默认保留 7 天原始样本：

```sql
DELETE FROM heartbeat_sample WHERE observed_at_ms < :cutoff_ms;
DELETE FROM tide_worker_sample WHERE observed_at_ms < :cutoff_ms;
DELETE FROM host_event WHERE observed_at_ms < :cutoff_ms;
```

清理频率：

- 每 10 分钟检查一次。
- 每次删除限制最大行数，避免长事务影响查询。
- 删除后按需触发 `PRAGMA wal_checkpoint(PASSIVE)`。

### 容量上限

如果数据库文件超过 `PULSE_LOCAL_STORAGE_MAX_BYTES`：

1. 优先降低保留天数。
2. 删除最老的 raw sample。
3. 如果仍超限，停止写入 raw sample，只保留当前态和事件。
4. 前端显示 storage degraded。

## 查询 API

### 查询指标列表

```http
GET /api/metrics/catalog
```

返回：

```json
{
  "metrics": [
    {
      "name": "heartbeat.latency_ms",
      "unit": "ms",
      "type": "gauge",
      "source": "heartbeat_sample"
    },
    {
      "name": "heartbeat.arrival_gap_ms",
      "unit": "ms",
      "type": "gauge",
      "source": "heartbeat_sample"
    },
    {
      "name": "tide_worker.cpu_pct",
      "unit": "%",
      "type": "gauge",
      "source": "tide_worker_sample"
    },
    {
      "name": "tide_worker.rss_kb",
      "unit": "bytes",
      "type": "gauge",
      "source": "tide_worker_sample"
    }
  ]
}
```

### 查询时序

```http
GET /api/metrics/query_range?metric=heartbeat.latency_ms&from=1710000000000&to=1710003600000&step=10000&cluster=cdn_new
```

返回：

```json
{
  "metric": "heartbeat.latency_ms",
  "unit": "ms",
  "from": 1710000000000,
  "to": 1710003600000,
  "step": 10000,
  "series": [
    {
      "agent_id": "fdbd:dc05:11:634::45",
      "ip": "fdbd:dc05:11:634::45",
      "cluster": "cdn_new",
      "points": [
        [1710000000000, 12],
        [1710000010000, 15],
        [1710000020000, null]
      ]
    }
  ]
}
```

规则：

- `from/to` 必填，最大查询窗口默认 24h。
- `step` 必填或由服务端自动计算。
- 最大返回点数默认 20000，超过则服务端自动增大 step。
- host 数过多时，默认返回 top N 异常 host，并提供聚合线。

### 查询 tide_worker 进程时序

```http
GET /api/metrics/query_range?metric=tide_worker.cpu_pct&from=...&to=...&step=10000&agent_id=...
```

返回 series label 包含 `pid`：

```json
{
  "metric": "tide_worker.cpu_pct",
  "unit": "%",
  "series": [
    {
      "agent_id": "agent-a",
      "pid": 3338619,
      "labels": {
        "version": "1.1.0.6396",
        "role": "follower"
      },
      "points": [[1710000000000, 1.78]]
    }
  ]
}
```

### 查询事件

```http
GET /api/metrics/events?from=...&to=...&agent_id=...&severity=warn,error
```

用于图表 annotation 和异常列表。

## 前端展示机制

### 目标体验

前端采用类似 Grafana 的 state-of-art 面板模型：

- 顶部全局 time range：最近 15m、1h、6h、24h、自定义。
- 自动刷新：5s、10s、30s、off。
- 面板支持多 series、legend、tooltip、brush zoom、异常 annotation。
- 支持 host/cluster/area/group 过滤。
- 支持图表与 host 卡片联动：点击 host 卡片可打开该 host 的 heartbeat latency 和 tide_worker 面板。

### 前端状态模型

```ts
type TimeRange = {
  from: number;
  to: number;
  mode: 'relative' | 'absolute';
  refreshMs: number;
};

type MetricQuery = {
  metric: string;
  from: number;
  to: number;
  step: number;
  filters: {
    cluster?: string;
    area?: string;
    group?: string;
    agentId?: string;
  };
  aggregation?: 'avg' | 'max' | 'p95' | 'raw';
};

type MetricSeries = {
  id: string;
  labels: Record<string, string>;
  points: Array<[number, number | null]>;
};

type MetricPanelState = {
  query: MetricQuery;
  loading: boolean;
  error?: string;
  series: MetricSeries[];
  lastUpdatedAt: number;
};
```

### 图表组件选择

推荐第一版使用 Apache ECharts：

- 支持大点数折线、tooltip、legend、dataZoom、markLine/markArea。
- 比手写 SVG 成本低，比重型 Grafana embed 更容易内嵌当前 React 页面。
- 支持后续热力图、散点图、堆叠图和异常标记。

备选：

- uPlot：性能极好，适合大量时序点，但交互和扩展需要更多自定义。
- Recharts：React 友好，但大数据量和复杂交互弱于 ECharts/uPlot。

第一版建议：

- Cluster overview 使用 ECharts。
- Host detail 小图可以使用 ECharts sparkline 或 uPlot。
- 避免引入完整 Grafana iframe 或外部服务。

### 下采样策略

前端根据容器宽度和 time range 自动计算 step：

```text
target_points = min(panel_width_px * 1.5, 1200)
step = ceil((to - from) / target_points)
step = round_to_nice_step(step)
```

服务端必须再次校验 step：

- 如果返回点数过大，服务端自动增大 step。
- 对 gauge 指标默认返回 avg/max/p95 三种可选聚合。
- 对 `heartbeat.latency_ms` 默认展示 p95 + avg。
- 对 `tide_worker.cpu_pct` 默认展示 max 或 per-pid raw。

### 图表布局

新增 `MetricsDashboard` 区域：

```text
Time range bar
  - last 15m / 1h / 6h / 24h
  - auto refresh
  - cluster / area / host filter

Panels
  - Heartbeat latency p95 by host
  - Heartbeat arrival gap by host
  - Tide worker CPU by pid
  - Tide worker RSS by pid
  - Event annotations
```

与现有 UI 的关系：

- 首页 host tiles 继续展示当前态。
- 新图表区域展示历史趋势。
- Cluster 批任务 UI 不直接读取本地时序库，但可在任务结果旁增加 host history link。

### 空值与异常表达

- `null` point 表示该时间桶无数据，图表断线。
- latency 超过阈值使用 markArea 高亮。
- host offline 或 heartbeat gap 事件作为 annotation。
- tide_worker pid 改变时添加 `tide_worker.pid_changed` annotation。

## 数据流架构

### 写入流

```text
Agent
  heartbeat {
    agent_sent_at_ms,
    host status,
    tide_worker processes
  }
    |
    v
Coordinator heartbeat handler
  - update current HostView
  - compute latency/gap
  - extract tide_worker samples
  - enqueue LocalMetricSample
    |
    v
LocalStorageWriter
  - batch transaction
  - upsert host_dimension
  - insert samples/events
    |
    v
SQLite WAL
```

### 查询流

```text
React dashboard panel
  -> /api/metrics/query_range
  -> Coordinator metric query service
  -> SQL range query + aggregation
  -> JSON series
  -> chart store
  -> ECharts render
```

### 降级流

```text
SQLite unavailable / queue full / query too expensive
  -> storage health state = degraded
  -> current /api/hosts remains available
  -> metric panels show degraded notice
  -> heartbeat control path continues
```

## SQL 查询示例

heartbeat latency 按 step 聚合：

```sql
SELECT
  ((observed_at_ms / :step_ms) * :step_ms) AS ts,
  agent_id,
  AVG(latency_ms) AS avg_value,
  MAX(latency_ms) AS max_value,
  COUNT(latency_ms) AS count_value
FROM heartbeat_sample
WHERE observed_at_ms BETWEEN :from_ms AND :to_ms
  AND latency_ms IS NOT NULL
  AND agent_id IN (:agent_ids)
GROUP BY agent_id, ts
ORDER BY ts ASC;
```

tide_worker CPU per pid：

```sql
SELECT
  ((observed_at_ms / :step_ms) * :step_ms) AS ts,
  agent_id,
  pid,
  AVG(cpu_pct) AS avg_cpu,
  MAX(cpu_pct) AS max_cpu
FROM tide_worker_sample
WHERE observed_at_ms BETWEEN :from_ms AND :to_ms
  AND cpu_pct IS NOT NULL
  AND agent_id = :agent_id
GROUP BY agent_id, pid, ts
ORDER BY ts ASC;
```

## 采样与性能估算

假设：

- 500 hosts。
- heartbeat 间隔 5s。
- 每 host 平均 2 个 tide_worker。
- 保留 7 天。

数据量估算：

```text
heartbeat_sample = 500 * 12/min * 60 * 24 * 7 ~= 6,048,000 rows
tide_worker_sample = 500 * 2 * 12/min * 60 * 24 * 7 ~= 12,096,000 rows
```

优化策略：

- 第一版默认只保留 3 到 7 天 raw sample。
- 超过 24h 查询默认使用 1m 或更大 step。
- 后续加入 `metric_rollup_1m` 后，超过 6h 的查询默认读 rollup。
- 进程 debug JSON 控制大小，默认不超过 2KB。

## 安全与隐私

- 不保存敏感环境变量。
- `debug_json` 必须经过字段白名单过滤。
- 本地数据库文件权限建议 `0600`，目录权限 `0700`。
- API 查询沿用 coordinator 的访问控制策略；后续权限系统需要限制下载和长窗口查询。
- 下载或导出时必须标注 coordinator id、时间范围和过滤条件。

## 运维与可观测性

新增 coordinator storage health：

```json
{
  "enabled": true,
  "path": "/data24/otf/pulse/coordinator/pulse-local.db",
  "queue_depth": 120,
  "dropped_samples": 0,
  "last_write_ms": 1710000000000,
  "last_error": "",
  "db_bytes": 123456789,
  "retention_days": 7
}
```

建议 API：

```http
GET /api/metrics/storage
```

前端展示：

- 正常：不打扰用户。
- degraded：顶部或图表面板显示轻量黄色提示。
- disabled：图表区域显示“本 coordinator 未启用本地历史存储”。

## 迁移策略

数据库使用 `schema_version` 表：

```sql
CREATE TABLE IF NOT EXISTS schema_version (
  version       INTEGER PRIMARY KEY,
  applied_at_ms INTEGER NOT NULL,
  description  TEXT NOT NULL
);
```

启动流程：

1. 打开 SQLite。
2. 设置 PRAGMA。
3. 获取当前 schema version。
4. 在事务内按顺序执行 migration。
5. migration 失败则关闭本地存储，但 coordinator 主服务继续启动。

## 实施阶段

### Phase 1：本地库与写入

- 引入 SQLite JDBC。
- 增加 `LocalMetricStorage`、`LocalMetricWriter`、`MetricSampleExtractor`。
- 写入 `host_dimension`、`heartbeat_sample`、`tide_worker_sample`。
- 增加 TTL 清理和 storage health。

### Phase 2：查询 API

- 增加 `/api/metrics/catalog`。
- 增加 `/api/metrics/query_range`。
- 增加 `/api/metrics/events`。
- 增加服务端 step 校验和 top N 限制。

### Phase 3：前端图表

- 引入 ECharts。
- 增加 time range bar 和 metric panel。
- 支持 heartbeat latency、arrival gap、tide_worker CPU/RSS。
- 支持 host 卡片联动和 annotation。

### Phase 4：聚合与优化

- 增加 `metric_rollup_1m`。
- 增加后台 rollup worker。
- 增加长窗口查询自动切换 rollup。
- 增加本地数据库备份、导出和诊断命令。

## 开放问题

- heartbeat payload 中是否已经有可靠的 `agent_sent_at_ms`；如果没有，需要在 agent 侧补充。
- tide_worker 进程信息当前是否完全来自 heartbeat，还是需要 agent 侧增加采集插件。
- 多 coordinator 之间是否需要后续合并视图；如果需要，可设计 coordinator federation 或导出到远端 TSDB。
- 前端默认展示 top N 异常 host 还是全部 host，需要根据实际 host 数和图表可读性确定。
