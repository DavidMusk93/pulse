# Coordinator Local Storage Timeseries Implementation Progress

## 状态

- 时间：2026-06-08
- 最新已部署提交：`fc8c7e4 Count group direct fallback metrics`
- 最新本地已测试：writer maintenance、batch transaction、query envelope、query budget、topN series selection、aggregate series、frontend visibility/range pause、frontend render metrics、heartbeat timing instrumentation、heartbeat health presets、group plan mismatch metrics、direct fallback metrics、heartbeat path normalization、unknown cluster direct-only guard、Apple-style Metrics UI、cluster-scoped metric query
- 部署范围：`cdn_new` 50 台 agent 与 3 台 coordinator/group leader 已完成最新版本全量 rollout
- JAR SHA：`35d5d9fa66ca6b2eece9d8dc4b8c616d385dbe798114971a604c2de5fa055e84`
- 结论：后端本地时序存储核心链路已部署并在线验证；前端 Ant Design 时序面板已完成第一版查询与预览；Metrics Panel 已能通过 health presets 反馈心跳架构健壮性、plan 收敛状态和 agent 采集数据实效性。

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
  - `heartbeat_path` 已规范化为 `direct`、`fallback_direct`、`group_leader_batch`、`unknown`；具体 `source_group_id` 保存在 metadata，避免路径维度被 group id 污染。
  - heartbeat payload 中的 `tide_workers` 抽取到 `tide_worker_sample`。
  - batch heartbeat 写入 `group_leader_sample`，包含 member/submitted/accepted/missing/stale/arrival/status。
  - agent outbound 已写入上一轮真实 `agent_encode_ms` / `agent_send_ms`；group leader 已写入 `leader_collect_ms` 并由 coordinator 计算 `group_latency_ms`。
  - `group.arrival_gap_ms` 已作为独立 group 指标暴露，避免把单 coordinator 本地观察间隔误读为 `group.group_latency_ms` 链路延迟。
  - coordinator 已下发稳定 `plan_generation`，agent 已回传 `agent_plan_generation`，group metrics 已暴露 `group.plan_mismatch`；`group.plan_lag` 保留为 0/1 兼容别名，避免 hash generation 相减产生误导值。
  - `group.direct_fallback_count` 已按 group 期望成员的最新 direct source 真实计数，不再恒为 0。
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
  - heartbeat 和 group leader 指标已支持 `cluster` query 参数，Metrics UI 可直接按集群分析健康状态。
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
  - 已支持 `top_n` / `topN`，在未限定具体 series 时按每条 series 的最大观测值返回 Top N，并用 label key 保持稳定排序。
  - 当真实 series 超出预算时，已追加 `series_role=aggregate` / `aggregate=avg` 的服务端聚合线，保留整体趋势。
  - 响应已包含 `query_id`、`from`、`to`、`unit`、`sample_policy`、`truncated`、`suggested_step_ms`、`series_limit`、`point_limit`。
  - 查询默认保持请求 `step_ms`；只有结果被截断时才返回更大的 `suggested_step_ms`，避免稀疏样本被错误合并。
  - heartbeat、tide worker、group leader 已统一按请求 `step_ms` 分桶并使用 `AVG` 聚合。

- 前端时序面板：
  - 已恢复本地 Node/npm 构建链路，使用 `.tmp/runtime/node-v22.12.0-darwin-arm64`。
  - 已实现 Ant Design Metrics Panel，包含 storage health、metric selector、agent selector、range selector、query budget 状态和专业时序图。
  - 已接入 `/api/metrics/catalog`、`/api/metrics/storage`、`/api/metrics/query_range`、`/api/metrics/stream`。
  - 已用 Vite 构建同步 `src/main/resources/static/pulse-hosts.js` 和 `pulse-hosts.css`。
  - 已新增 `src/main/frontend/src/metrics.ts`，拆出 `MetricQueryController`、`SeriesStore`、`RenderScheduler` 的数据层骨架。
  - 已实现 `metric.invalidate` 合并、range cache、500ms debounce 补偿查询和 `SeriesStore.merge` 点去重。
  - 已实现页面不可见时暂停补偿查询，以及“暂停窗口/跟随最新”的固定时间窗口查看模式。
  - 已在面板中暴露 `query_ms` 和 `render_ms`，辅助判断查询与渲染是否流畅。
  - 已新增“架构健康 / 计划收敛 / 采集实效 / 发送链路” preset，默认执行全局 TopN + aggregate 查询并用 Tag 给出健康判定；“计划收敛”使用 `group.plan_mismatch`。
  - 已按 Apple 风格重构 Metrics UI：大圆角玻璃卡片、顶部健康概览、统一控件高度、分区对齐、响应式布局和集群分析入口。
  - 已新增“分析范围”集群选择，切换后自动进入当前集群 TopN + aggregate，并将 `cluster` 下推到 metrics query。
  - 已用按需导入的 Apache ECharts 替换手写 SVG sparkline，图表内置 tooltip、legend、时间轴、阈值线、峰值标注，并在图表上方给出状态/当前值/峰值/范围解释。
  - 已为 `group.arrival_gap_ms` 加入单 coordinator 视角说明，避免将 3 coordinator 轮询导致的约 15s 本地间隔误判为发送延迟。

- SSE 重连补偿：
  - `/api/metrics/stream` 已读取 `Last-Event-ID`。
  - `hello` 事件已返回 `resumed`、`last_event_id`、`event_cache_supported=true`、`replayed_events`、`replay_limit` 和 `compensate_from_ms`。
  - 服务端已实现 bounded event cache replay，默认保留最近 256 个 metrics SSE 事件。
  - 缓存未命中时仍通过 `metric.invalidate` 的 `compensate_from_ms` 做 bounded reconnect compensation。
  - 非 `once` stream 已按 `stream_interval_ms` 周期性发送 `metric.invalidate` 和 `ping`。
  - stream 连接由 `stream_max_ms` 限制寿命，避免慢客户端无限占用 HTTP worker。

## 测试

- `mvn test`：`70` tests passed。
- `bash .tmp/build_frontend.sh`：Vite build passed，产出 `pulse-hosts.js/css`。
- 关键新增测试：
  - `AsyncLocalMetricStorageTest`
  - `LocalMetricStorageTest`
  - `CoordinatorServiceTest#batchHeartbeatWritesGroupLeaderMetricSample`
  - `CoordinatorHttpServerTest#metricsStorageAndStreamExposeHealthAndInvalidationEvents`
  - `CoordinatorHttpServerTest#metricsEventsEndpointReturnsHostEvents`
  - `CoordinatorHttpServerTest#metricsRangeQueryAppliesServerSideBudgets`
  - `LocalMetricStorageTest#queryRangeSuggestsLargerStepWhenRequestExceedsPointBudget`
  - `LocalMetricStorageTest#queryRangeAggregatesTideWorkerPointsByRequestedStep`
  - `LocalMetricStorageTest#queryRangeAggregatesGroupLeaderPointsByRequestedStep`
  - `CoordinatorHttpServerTest#metricsStorageAndStreamExposeHealthAndInvalidationEvents` 已覆盖 SSE `retry`、`Last-Event-ID` resume metadata 和 cache replay。
  - `CoordinatorHttpServerTest#metricsStreamProducesBoundedPeriodicInvalidations` 已覆盖 bounded long-running stream。
  - `LocalMetricStorageTest#queryRangeReturnsTopNSeriesByLargestObservedValue` 已覆盖 storage 层 Top N series 选择、aggregate series 追加与 `series_count` metadata。
  - `CoordinatorHttpServerTest#metricsRangeQueryAcceptsTopNSeriesSelection` 已覆盖 HTTP `top_n` 参数和 aggregate series numeric value。
  - `LocalMetricStorageTest#storesAndQueriesGroupLeaderSamples` 已覆盖 `group.stale_member_count`、`group.direct_fallback_count`、`group.status_unhealthy`。
  - `LocalMetricStorageTest#storesAndQueriesGroupLeaderSamples` 已覆盖 `group.plan_generation`、`group.plan_mismatch` 和 `group.plan_lag` 兼容语义。
  - `LocalMetricStorageTest` 已覆盖 heartbeat/group leader metric 的 `cluster` query filter。
  - `CoordinatorServiceTest#batchHeartbeatWritesGroupLeaderMetricSample` 已覆盖 cold-start/unknown `agent_plan_generation` 不产生巨大 lag。
  - `CoordinatorServiceTest#batchHeartbeatWritesGroupLeaderMetricSample` 已覆盖 `group_leader_batch` / `fallback_direct` path metadata。
  - `CoordinatorServiceTest#unknownClusterAgentsStayDirect` 已覆盖 `cluster=unknown` 不进入 group leader 规划。
  - `CoordinatorHttpServerTest#hostsPageRendersFlatSquareChineseHeartbeatConsole` 已断言 Metrics Panel 静态资源、live pause、fixed range、frontend metrics 和 heartbeat health preset markers。

## 线上验证

最新 rollout：

```text
staged coordinator deploy: total=3 ok=3 failed=0
full cdn_new deploy: total=50 ok=50 failed=0 elapsed=170s
verify cdn_new: total=50 ok=50 failed=0
frontend coordinator deploy: total=3 ok=3 failed=0 elapsed=23s
frontend data-layer deploy: total=3 ok=3 failed=0 elapsed=25s
frontend live compensation deploy: total=3 ok=3 failed=0 elapsed=16s
tide/group aggregation deploy: total=3 ok=3 failed=0 elapsed=18s
SSE resume metadata deploy: total=3 ok=3 failed=0 elapsed=18s
SSE event replay cache deploy: total=3 ok=3 failed=0 elapsed=17s
bounded periodic metrics stream deploy: total=3 ok=3 failed=0 elapsed=15s
topN series selection deploy: total=3 ok=3 failed=0 elapsed=15s
aggregate metric series deploy: total=3 ok=3 failed=0 elapsed=19s
metrics panel live pause deploy: total=3 ok=3 failed=0 elapsed=20s
metrics panel render metrics deploy: total=3 ok=3 failed=0 elapsed=19s
full cdn_new latest rollout: total=50 ok=50 failed=0 elapsed=166s
full cdn_new latest verify: total=50 ok=50 failed=0 elapsed=2s
heartbeat timing instrumentation rollout: total=50 ok=50 failed=0 elapsed=187s
heartbeat timing instrumentation verify: total=50 ok=50 failed=0 elapsed=2s
heartbeat health metric presets coordinator deploy: total=3 ok=3 failed=0 elapsed=16s
group plan convergence metrics rollout: total=50 ok=50 failed=0 elapsed=179s
group plan convergence metrics verify: total=50 ok=50 failed=0 elapsed=1s
direct fallback metrics coordinator deploy: total=3 ok=3 failed=0 elapsed=16s
```

最新 heartbeat health metrics 验证：

```text
COORD fdbd:dc05:11:634::45 missing_catalog=[] missing_asset=[] metrics=[group.status_unhealthy series=13 points=195, group.stale_member_count series=13 points=195, group.direct_fallback_count series=13 points=195, group.plan_lag series=13 points=155, heartbeat.agent_collect_ms series=13 points=196, heartbeat.agent_send_ms series=13 points=196]
COORD fdbd:dc05:13:10c::40 missing_catalog=[] missing_asset=[] metrics=[group.status_unhealthy series=13 points=195, group.stale_member_count series=13 points=195, group.direct_fallback_count series=13 points=195, group.plan_lag series=13 points=181, heartbeat.agent_collect_ms series=13 points=200, heartbeat.agent_send_ms series=13 points=206]
COORD fdbd:dc07:0:810::44 missing_catalog=[] missing_asset=[] metrics=[group.status_unhealthy series=13 points=195, group.stale_member_count series=13 points=195, group.direct_fallback_count series=13 points=195, group.plan_lag series=13 points=195, heartbeat.agent_collect_ms series=13 points=198, heartbeat.agent_send_ms series=13 points=202]
```

最新 SQLite 心跳链路分析：

- 报告：`docs/report/heartbeat-chain-sqlite-analysis-2026-06-08.md`
- `cdn2` agents：3 台 coordinator 均观测到 `50/50`。
- `cdn2` arrival p95：`5025-5036ms`；arrival p99：`8488-9007ms`；低于 `30000ms` TTL。
- `cdn2` `seq_gap > 1`：每台 coordinator 过去 1 小时仅 `1-2` 条。
- `cdn2` group status：`ok` 约 `98.3%-98.6%`，仍有低量 `partial` / `stale_plan`。
- post-fix：`agent_encode_ms` p99 `1ms`，`agent_send_ms` p95 `3ms`，`leader_collect_ms` p95 `1ms`，`group_latency_ms` p95 `2ms`。
- post-fix：`cdn2` 短窗口 3 台 coordinator 均为 `50/50` agents，`seq_gap=0`。
- 2026-06-08 运行时复查：过去 24h/7d 三台 coordinator 的 `group.group_latency_ms` p99 为 `2-3ms`，max 小于 `314ms`；约 15s 来自 `group.arrival_gap_ms`，根因是 agent 成功后轮询 3 个 coordinator。
- 已修复 agent coordinator 选择策略：成功路径改为 stable sticky target，失败时才 failover；跨 coordinator 最终一致性继续由 `/heartbeat_fwd` 负责。

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

最新 frontend asset 验证：

```text
COORD fdbd:dc05:11:634::45
ASSET /assets/pulse-hosts.js bytes=837724 missing=[]
ASSET /assets/pulse-hosts.css bytes=23316 missing=[]
STORAGE bytes=230 status_ok=True

COORD fdbd:dc05:13:10c::40
ASSET /assets/pulse-hosts.js bytes=837724 missing=[]
ASSET /assets/pulse-hosts.css bytes=23316 missing=[]
STORAGE bytes=230 status_ok=True

COORD fdbd:dc07:0:810::44
ASSET /assets/pulse-hosts.js bytes=837724 missing=[]
ASSET /assets/pulse-hosts.css bytes=23316 missing=[]
STORAGE bytes=230 status_ok=True
```

最新 tide/group 聚合验证：

```text
COORD fdbd:dc05:11:634::45
QUERY metric=tide_worker.rss_kb unit=KiB policy=avg truncated=True suggested_step_ms=60000 series_limit=12 point_limit=20000 series=12 points=612
QUERY metric=group.submitted_agent_count unit=count policy=avg truncated=True suggested_step_ms=60000 series_limit=12 point_limit=20000 series=12 points=513

COORD fdbd:dc05:13:10c::40
QUERY metric=tide_worker.rss_kb unit=KiB policy=avg truncated=True suggested_step_ms=60000 series_limit=12 point_limit=20000 series=12 points=600
QUERY metric=group.submitted_agent_count unit=count policy=avg truncated=True suggested_step_ms=60000 series_limit=12 point_limit=20000 series=12 points=502

COORD fdbd:dc07:0:810::44
QUERY metric=tide_worker.rss_kb unit=KiB policy=avg truncated=True suggested_step_ms=60000 series_limit=12 point_limit=20000 series=12 points=600
QUERY metric=group.submitted_agent_count unit=count policy=avg truncated=True suggested_step_ms=60000 series_limit=12 point_limit=20000 series=12 points=453
```

最新 SSE resume 验证：

```text
COORD fdbd:dc05:11:634::45 bytes=1259 missing=[]
COORD fdbd:dc05:13:10c::40 bytes=1257 missing=[]
COORD fdbd:dc07:0:810::44 bytes=1254 missing=[]
```

最新周期性 metrics stream 验证：

```text
COORD fdbd:dc05:11:634::45 bytes=1923 invalidations=5 pings=4 missing=[]
COORD fdbd:dc05:13:10c::40 bytes=1923 invalidations=5 pings=4 missing=[]
COORD fdbd:dc07:0:810::44 bytes=1920 invalidations=5 pings=4 missing=[]
```

最新 Top N + aggregate query 验证：

```text
COORD fdbd:dc05:11:634::45 series=4 points=122 aggregate=1 truncated=True missing=[] labels=[{'agent_id': 'dc05-p13-t46-n050.byted.org'}, {'agent_id': 'dc05-p13-t46-n047.byted.org'}, {'agent_id': 'dc05-p13-t46-n049.byted.org'}, {'aggregate': 'avg', 'series_role': 'aggregate'}]
COORD fdbd:dc05:13:10c::40 series=4 points=124 aggregate=1 truncated=True missing=[] labels=[{'agent_id': 'dc05-p13-t46-n050.byted.org'}, {'agent_id': 'dc05-p13-t46-n047.byted.org'}, {'agent_id': 'dc05-p13-t46-n049.byted.org'}, {'aggregate': 'avg', 'series_role': 'aggregate'}]
COORD fdbd:dc07:0:810::44 series=4 points=124 aggregate=1 truncated=True missing=[] labels=[{'agent_id': 'dc05-p13-t46-n050.byted.org'}, {'agent_id': 'dc05-p13-t46-n049.byted.org'}, {'agent_id': 'dc05-p13-t46-n047.byted.org'}, {'series_role': 'aggregate', 'aggregate': 'avg'}]
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

- 前端时序面板仍需增强：
  - 已实现 Ant Design Metrics Panel 和按需 ECharts 专业图表。
  - 已有 QueryController、SeriesStore、RenderScheduler 数据层。
  - 已有 live invalidation merge、range cache 和补偿查询第一版。
  - 已有可见性暂停和 fixed range pause。
  - 已有 `query_ms` / `render_ms` 前端性能指标。
  - 仍需把断线全窗口补偿补齐。

- SSE 仍是轻量第一版：
  - 已有 `hello`、`storage.health`、`metric.invalidate`。
  - 已实现 bounded event cache replay 和 `Last-Event-ID` 补发。
  - 已实现 long-running stream 的周期性 invalidate 生产和 bounded max stream duration。
  - 尚未实现多订阅者独立 bounded outbound queue；当前依赖同步写 + max duration 限制慢客户端资源占用。

- 部署脚本经验沉淀：
  - 一次错误 full rollout 使用同一行环境变量赋值并传 `"$COORDINATORS"`，导致参数展开为空，引发远端 `$6: unbound variable`。
  - 已停止错误 rollout、改用 `.tmp/deploy_query_budget_full.sh` 落盘脚本重跑并成功。
  - 已记录到 `docs/debug/auto-ops-argument-passing.md`。
  - 已记录禁止 heredoc/inline script 作为验证证据到 `docs/debug/shell-heredoc-and-inline-script-notes.md`。

- 查询预算仍可完善：
  - heartbeat、tide worker、group leader 已支持 `step_ms avg`。
  - 已有 `series_limit`、`point_limit`、`top_n` 和截断后的 `suggested_step_ms`。
  - 已有超预算时的服务端 `avg` 聚合线。

- 前端构建环境：
  - 当前 agent shell 全局仍找不到 `node`/`npm`。
  - 已通过 `.tmp/runtime` 下载 Node `v22.12.0` 恢复构建。
  - 已记录到 `docs/debug/frontend-build-environment.md`。

## 下一步

1. 为 Metrics Panel 增加断线全窗口补偿。
2. 为 SSE 增加多订阅者独立 bounded outbound queue。
3. 上线前端后继续用线上 SQLite 分析 group heartbeat 是否达到设计目标。
