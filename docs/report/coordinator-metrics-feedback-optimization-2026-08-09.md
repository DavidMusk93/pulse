# Coordinator Metrics Feedback Optimization

## 结论

Coordinator 有明确优化空间，但当前没有吞吐危机。

现状：

- writer queue 长期为 0；
- dropped/failed 为 0；
- maintenance 为 1–9 ms；
- coordinator 平均 CPU 约为单核的 5–10%；
- 单 agent 15 分钟查询为 18–44 ms；
- fleet 15 分钟查询为 209–354 ms。

因此不应优先扩大 queue 或提高并发。当前最高价值问题是：

1. rollup 空间放大导致 30 天 retention 无法在 64 GiB quota 内实现；
2. raw SQLite 重复保存大量 JSON；
3. JVM 根据 128 CPU host 自动扩展，RSS 和 GC 线程明显偏大；
4. batch=500 但实际每事务只有 5.0–6.4 个 command。

## 线上范围

- `fdbd:dc05:11:634::45`
- `fdbd:dc05:13:10c::40`
- `fdbd:dc07:0:810::44`

采样时间约为 segmented storage 上线后 5.5 小时。

所有探测均只读，只访问 v2 raw/rollup shard；未扫描 legacy 大库。

## 当前负载

| Coordinator | Commands/s | Commands/tx | CPU | RSS | Threads |
| --- | ---: | ---: | ---: | ---: | ---: |
| `::45` | 30.9 | 6.41 | 5.18% | 1.48 GiB | 101 |
| `::40` | 46.5 | 5.34 | 9.99% | 1.48 GiB | 100 |
| `::44` | 27.4 | 5.04 | 6.03% | 1.49 GiB | 100 |

CPU 百分比以单核 100% 计。当前 coordinator CPU 不是瓶颈。

Queue：

- depth：3 台均为 0；
- high watermark：55 / 68 / 104；
- capacity：20,000；
- dropped/failed/capacity dropped：均为 0。

Batch 上限为 500，但 writer 在取得首个 command 后立即 `drainTo`。由于队列
通常为空，实际上每事务仅合并 5.0–6.4 个 command。

## API 延迟

| Coordinator | Health | Hosts | 单 agent 15m | Fleet 15m |
| --- | ---: | ---: | ---: | ---: |
| `::45` | 2.5 ms | 7.6 ms | 18.0 ms | 221 ms |
| `::40` | 2.9 ms | 8.6 ms | 21.7 ms | 209 ms |
| `::44` | 2.6 ms | 7.7 ms | 43.9 ms | 354 ms |

单 agent 15 分钟查询只有 1 条 series，但响应约 196,525 bytes。当前每个
heartbeat metric point 都合并完整 `state_json` 到 metadata，造成重复序列化和
网络放大。Raw JSON 白名单化应同时缩小历史查询响应。

Fleet query 已有优化空间，但仍低于当前最紧迫的容量问题。

## 容量反馈

### 当前增长斜率

| Coordinator | Raw/day | Rollup/day | Raw 7d | Rollup 30d |
| --- | ---: | ---: | ---: | ---: |
| `::45` | 14.2 GiB | 2.6 GiB | 99 GiB | 78 GiB |
| `::40` | 21.2 GiB | 3.9 GiB | 149 GiB | 118 GiB |
| `::44` | 9.4 GiB | 1.9 GiB | 66 GiB | 57 GiB |

Raw 7 天可以落在 256 GiB quota 内。

Rollup 30 天：

- `::44` 可以落在 64 GiB 附近；
- `::45` 预计只能保留约 25 天；
- `::40` 预计只能保留约 16 天。

所以当前 rollup schema 无法满足声明的 30 天 retention。

## P0：Rollup 宽表化

`::40` 当前 rollup：

```text
rows             1,980,910
distinct series      2,231
metrics                 29
tide rows         1,361,286
heartbeat rows      360,875
agent rows          144,350
group rows          114,399
```

对象空间：

```text
metric_rollup_1m table       556.7 MB
primary-key autoindex        289.7 MB
metric/time index             80.7 MB
repeated labels JSON         210.6 MB
```

当前通用 schema 每个 `metric/series/minute` 一行，重复保存：

- metric string；
- series key；
- labels JSON；
- rowid table；
- primary-key index；
- time index。

建议替换为 3 张 `WITHOUT ROWID` 宽表：

```text
heartbeat_rollup_1m(bucket_ms, agent_id, ...7 metric sums..., sample_count)
tide_rollup_1m(bucket_ms, agent_id, pid, ...3 metric sums..., sample_count)
group_rollup_1m(bucket_ms, group_id, ...19 metric sums..., sample_count)
```

维度字段只存一次。主键以 `(bucket_ms, series identity)` 开头，时间查询直接
使用主表，不再保留通用 rowid + PK autoindex + time index 三份结构。

预期：

- 行数降低约 3–7 倍；
- labels JSON 基本消除；
- rollup bytes/day 降低到 64 GiB/30d 预算以内；
- 历史查询减少 JSON extraction 和索引页访问。

验收门槛：

```text
projected_rollup_30d <= 51.2 GiB   # quota 80%
historical_query_p95 <= 300 ms
rollup_write_failed = 0
```

## P0：Raw JSON 去重

Raw JSON 占 raw shard 的约 47–50%。

| Coordinator | Heartbeat state JSON | Tide debug JSON |
| --- | ---: | ---: |
| `::45` | 0.99 GB | 0.70 GB |
| `::40` | 1.46 GB | 1.03 GB |
| `::44` | 0.71 GB | 0.46 GB |

平均：

- heartbeat `state_json`：1.4–1.7 KB/row；
- tide `debug_json`：约 191 B/row；
- group `debug_json`：约 350 B/row。

主要重复：

- heartbeat state 内的 `tide_workers` 已再次结构化写入
  `tide_worker_sample`；
- tide `debug_json` 重复结构化列；
- group plan mismatch/lag 仍通过 debug JSON 查询。

建议：

1. heartbeat `state_json` 删除 `tide_workers` 和高频结构化字段；
2. tide 默认不保存完整 worker debug JSON，只保存显式白名单；
3. group 增加 `plan_mismatch`、`plan_lag` 列；
4. debug JSON 设置严格字节上限和采样策略；
5. current host state 继续保留在内存，不要求 raw history 重复完整 state。

验收门槛：

```text
json_bytes / raw_bytes <= 20%
projected_raw_7d <= 204.8 GiB      # quota 80%
metric query contract unchanged
```

## P1：JVM Runtime Feedback

`::40`：

```text
nproc        128
VmSize       39.7 GiB
RSS/PSS      1.48 GiB
anonymous    1.47 GiB
threads      100
GC workers    50
```

HTTP pool core thread 只有 8，约 100 个线程主要来自 JVM 根据 128 CPU 自动
选择的 GC/JIT/ForkJoin/HttpClient 线程，而不是 heartbeat handler 排队。

不要直接设置 `-Xmx`。先在 storage health 或独立 runtime health 中暴露：

- heap used/committed/max；
- non-heap used；
- GC count/time/pause max；
- live thread/peak thread；
- process CPU；
- allocation rate。

得到至少 24 小时数据后再 canary：

```text
-XX:ActiveProcessorCount=8 or 16
-Xms / -Xmx based on heap-used p99 + explicit headroom
```

验收门槛：

```text
RSS p95 decreases
GC pause p99 does not regress
CPU does not regress
heartbeat/query latency does not regress
```

## P1：Micro-batching

当前 transaction rate 为 4.8–8.7/s，不构成瓶颈，但 batch=500 没有实际生效。

可以在首个 command 后增加 5–20 ms bounded coalescing window，使 transaction
内包含更多 command。必须用指标验证：

```text
commands_per_transaction >= 20
queue_wait_p99 <= 50 ms
heartbeat handler latency unchanged
transaction rate decreases
```

这是低于 JSON/rollup 的优化，不应先实施。

## 优先级

1. **P0：rollup 宽表 + WITHOUT ROWID。**
2. **P0：raw JSON 去重、字段结构化和 debug 白名单。**
3. **P1：增加 JVM heap/GC/runtime feedback。**
4. **P1：基于 feedback 限制 ActiveProcessorCount 和 heap。**
5. **P1：5–20 ms micro-batching canary。**
6. **P2：fleet query profiling/caching。**

## 原始证据

- `.tmp/auto-ops/coordinator-feedback-20260809/probe.raw.log`
- `.tmp/auto-ops/coordinator-feedback-20260809/memory-threads.raw.log`
- `.tmp/auto-ops/coordinator-feedback-20260809/rollup-layout.raw.log`
