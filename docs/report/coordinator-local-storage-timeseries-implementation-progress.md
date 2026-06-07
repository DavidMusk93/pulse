# Coordinator Local Storage Timeseries Implementation Progress

## 状态

- 时间：2026-06-07
- 最新提交：`db1388c Add bounded metric retention cleanup`
- 部署范围：`cdn_new` 三台 coordinator
- JAR SHA：`2feb51f4455556bc2e91e39773a6988e21f416dd04134e45258057364d11bdab`
- 结论：后端本地时序存储核心链路已从最小闭环推进到可在线验证阶段；前端 Ant Design 时序面板仍未完成。

## 已完成

- SQLite 写入路径：
  - `AsyncLocalMetricStorage` 使用 bounded queue 和单 writer 线程。
  - heartbeat handler 只提交不可变 sample，不同步访问 SQLite。
  - storage health 暴露 `status`、`queue_depth`、`written_commands`、`dropped_commands`、`failed_commands`。

- SQLite schema：
  - `heartbeat_sample`
  - `host_dimension`
  - `tide_worker_sample`
  - `group_leader_sample`
  - `host_event`

- 指标写入：
  - heartbeat 样本写入 `arrival_gap_ms`、`seq_gap`、agent collect/encode/send/thread/rss。
  - heartbeat payload 中的 `tide_workers` 抽取到 `tide_worker_sample`。
  - batch heartbeat 写入 `group_leader_sample`，包含 member/submitted/accepted/missing/stale/arrival/status。
  - host 维度写入 `host_dimension`。

- 查询 API：
  - `/api/metrics/catalog`
  - `/api/metrics/query_range`
  - `/api/metrics/events`
  - `/api/metrics/storage`
  - `/api/metrics/stream`

- 查询能力：
  - heartbeat 指标按 `step_ms` 做服务端 `avg` 聚合。
  - tide worker 指标按 agent/pid 返回 series。
  - group leader 指标按 group 返回 series。
  - host event 支持 time range、agent、severity 和 limit 过滤。

- 保留与清理：
  - `deleteExpiredSamples(cutoffMs, limit)` 已支持 bounded 删除 `heartbeat_sample`、`tide_worker_sample`、`group_leader_sample`、`host_event`。
  - 当前尚未接入 writer 定时调度。

## 测试

- `mvn test`：`61` tests passed。
- 关键新增测试：
  - `AsyncLocalMetricStorageTest`
  - `LocalMetricStorageTest`
  - `CoordinatorServiceTest#batchHeartbeatWritesGroupLeaderMetricSample`
  - `CoordinatorHttpServerTest#metricsStorageAndStreamExposeHealthAndInvalidationEvents`
  - `CoordinatorHttpServerTest#metricsEventsEndpointReturnsHostEvents`

## 线上验证

三台 coordinator rollout：`3/3 ok`。

```text
dc05-p11-t634-n045 STORAGE status=ok queue=0 written=2087 dropped=0 failed=0
TABLE host_dimension rows=471
TABLE heartbeat_sample rows=811111
TABLE tide_worker_sample rows=17760
TABLE group_leader_sample rows=235
TABLE host_event rows=0

dc05-p13-t10c-n040 STORAGE status=ok queue=0 written=2165 dropped=0 failed=0
TABLE host_dimension rows=471
TABLE heartbeat_sample rows=807269
TABLE tide_worker_sample rows=12020
TABLE group_leader_sample rows=160
TABLE host_event rows=0

dc07-p0-t810-n044 STORAGE status=ok queue=0 written=2116 dropped=0 failed=0
TABLE host_dimension rows=471
TABLE heartbeat_sample rows=806929
TABLE tide_worker_sample rows=11705
TABLE group_leader_sample rows=145
TABLE host_event rows=0
```

Coordinator 当前态：

```text
dc05-p11-t634-n045 TOTAL=471 CDN=50 STATUS={'alive': 50}
dc05-p13-t10c-n040 TOTAL=471 CDN=50 STATUS={'alive': 50}
dc07-p0-t810-n044 TOTAL=471 CDN=50 STATUS={'alive': 50}
```

## 与设计差距

- 前端时序面板未完成：
  - 未实现 Ant Design MetricPanel。
  - 未实现 QueryController、SeriesStore、RenderScheduler、ChartAdapter。
  - 未接入 ECharts/uPlot。

- SSE 仍是轻量第一版：
  - 已有 `hello`、`storage.health`、`metric.invalidate`。
  - 尚未实现事件缓存、`Last-Event-ID` 补发和 slow client bounded queue。

- TTL 调度未完成：
  - 已有 bounded delete primitive。
  - 尚未由 writer 定时插入 cleanup/checkpoint command。

- 写入批处理仍可优化：
  - 当前 writer 串行 drain queue，但每条 command 内部仍直接执行 statement。
  - 尚未复用 prepared statement，也未显式包裹 batch transaction。

- 查询预算仍可完善：
  - heartbeat 已支持 `step_ms avg`。
  - tide worker 和 group leader 仍是 raw query。
  - 尚未实现 top N 异常 host、series_limit、自动 suggested_step 增大。

## 下一步

1. 接入 writer 定时 TTL cleanup 和 WAL checkpoint。
2. 为 writer batch 增加显式 transaction 和 prepared statement 复用。
3. 完成 `/api/metrics/query_range` 的统一 query envelope：`query_id`、`from`、`to`、`step`、`unit`、`series_limit`。
4. 恢复前端构建环境或生成稳定静态同步脚本，实现 Ant Design metrics panel。
5. 上线前端后继续用线上 SQLite 分析 group heartbeat 是否达到设计目标。
