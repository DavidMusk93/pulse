# Coordinator Local Storage Timeseries Implementation Progress

## 状态

- 时间：2026-06-07
- 最新已部署提交：`03b033d Add metric query budget controls`
- 最新本地已测试：writer maintenance、batch transaction、query envelope、query budget
- 部署范围：`cdn_new` 50 台 agent + 3 台 coordinator
- JAR SHA：`1359662bddeff263596de4017897c5479461998179061a5602fbbc19388290fa`
- 结论：后端本地时序存储核心链路已部署并在线验证；前端 Ant Design 时序面板仍未完成。

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
  - `AsyncLocalMetricStorage` writer 已定时执行 TTL cleanup 和 WAL checkpoint。
  - storage health 已暴露 `maintenance_commands`、`deleted_samples`、`checkpoint_commands`。

- 写入批处理：
  - writer 已按 batch drain queue，并用显式 transaction 提交。
  - storage health 已暴露 `transaction_batches`。

- 查询预算：
  - `/api/metrics/query_range` 已支持 `series_limit`，默认 50，最大 200。
  - `point_limit` 已服务端钳制到最大 20000。
  - 响应已包含 `query_id`、`from`、`to`、`unit`、`sample_policy`、`truncated`、`suggested_step_ms`、`series_limit`、`point_limit`。
  - 查询默认保持请求 `step_ms`；只有结果被截断时才返回更大的 `suggested_step_ms`，避免稀疏样本被错误合并。

## 测试

- `mvn test`：`64` tests passed。
- 关键新增测试：
  - `AsyncLocalMetricStorageTest`
  - `LocalMetricStorageTest`
  - `CoordinatorServiceTest#batchHeartbeatWritesGroupLeaderMetricSample`
  - `CoordinatorHttpServerTest#metricsStorageAndStreamExposeHealthAndInvalidationEvents`
  - `CoordinatorHttpServerTest#metricsEventsEndpointReturnsHostEvents`
  - `CoordinatorHttpServerTest#metricsRangeQueryAppliesServerSideBudgets`
  - `LocalMetricStorageTest#queryRangeSuggestsLargerStepWhenRequestExceedsPointBudget`

## 线上验证

最新 rollout：

```text
staged coordinator deploy: total=3 ok=3 failed=0
full cdn_new deploy: total=50 ok=50 failed=0 elapsed=170s
verify cdn_new: total=50 ok=50 failed=0
```

最新 query budget 和 storage health 验证：

```text
COORD fdbd:dc05:11:634::45
STORAGE status=ok queue=0 written=6219 dropped=0 failed=0 maintenance=0 deleted=0 checkpoint=0 batches=1610
QUERY query_id=q-0-1-1086903931 metric=agent.thread_count from=0 to=1 unit=threads policy=avg truncated=False suggested_step_ms=1 series_limit=2 point_limit=20000

COORD fdbd:dc05:13:10c::40
STORAGE status=ok queue=0 written=4417 dropped=0 failed=0 maintenance=0 deleted=0 checkpoint=0 batches=1052
QUERY query_id=q-0-1-1086903931 metric=agent.thread_count from=0 to=1 unit=threads policy=avg truncated=False suggested_step_ms=1 series_limit=2 point_limit=20000

COORD fdbd:dc07:0:810::44
STORAGE status=ok queue=0 written=2260 dropped=0 failed=0 maintenance=0 deleted=0 checkpoint=0 batches=443
QUERY query_id=q-0-1-1086903931 metric=agent.thread_count from=0 to=1 unit=threads policy=avg truncated=False suggested_step_ms=1 series_limit=2 point_limit=20000
```

历史 coordinator rollout：`3/3 ok`。

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

- 部署脚本经验沉淀：
  - 一次错误 full rollout 使用同一行环境变量赋值并传 `"$COORDINATORS"`，导致参数展开为空，引发远端 `$6: unbound variable`。
  - 已停止错误 rollout、改用 `.tmp/deploy_query_budget_full.sh` 落盘脚本重跑并成功。
  - 已记录到 `docs/debug/auto-ops-argument-passing.md`。
  - 已记录禁止 heredoc/inline script 作为验证证据到 `docs/debug/shell-heredoc-and-inline-script-notes.md`。

- 查询预算仍可完善：
  - heartbeat 已支持 `step_ms avg`。
  - tide worker 和 group leader 仍是 raw query。
  - 已有 `series_limit`、`point_limit` 和截断后的 `suggested_step_ms`。
  - 尚未实现 top N 异常 host、服务端聚合线和 tide/group 的 step 聚合。

- 前端构建环境阻塞：
  - 当前 agent shell 找不到 `node`/`npm`，无法运行 `src/main/frontend` 的 Vite build。
  - 已记录到 `docs/debug/frontend-build-environment.md`。

## 下一步

1. 恢复前端构建环境或生成稳定静态同步脚本，实现 Ant Design metrics panel。
2. 为 tide worker 和 group leader query 补齐 step 聚合、series budget 和 topN。
3. 为 SSE 增加 `Last-Event-ID`、事件缓存和 slow client bounded queue。
4. 上线前端后继续用线上 SQLite 分析 group heartbeat 是否达到设计目标。
