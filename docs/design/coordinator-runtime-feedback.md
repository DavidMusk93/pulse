# Coordinator Runtime Feedback

## 目标

Coordinator JVM 运行在 128 CPU host 上，默认 ergonomics 会扩展 GC、JIT 和
公共线程池。限制 CPU 或 heap 前必须先获得至少 24 小时 runtime feedback，
禁止仅根据一次 `ps` 快照直接设置 `-Xmx` 或 `ActiveProcessorCount`。

本阶段只增加可观测性，不修改 JVM 参数，也不同时引入 writer micro-batching。

## API

```http
GET /api/runtime/health
```

响应包含：

```json
{
  "status": "ok",
  "observed_at_ms": 1710000000000,
  "uptime_ms": 3600000,
  "available_processors": 128,
  "heap_used_bytes": 268435456,
  "heap_committed_bytes": 1073741824,
  "heap_max_bytes": 34359738368,
  "non_heap_used_bytes": 125829120,
  "non_heap_committed_bytes": 134217728,
  "gc_collection_count": 42,
  "gc_collection_time_ms": 310,
  "gc_pause_events": 42,
  "gc_pause_last_ms": 8,
  "gc_pause_max_ms": 31,
  "gc_pause_tracking_since_ms": 1710000000000,
  "live_threads": 101,
  "peak_threads": 105,
  "daemon_threads": 96,
  "total_started_threads": 110,
  "process_cpu_load": 0.06,
  "process_cpu_time_ns": 120000000000,
  "process_rss_bytes": 1585446912,
  "process_virtual_memory_bytes": 42627538944,
  "committed_virtual_memory_bytes": 42627538944,
  "allocation_rate_bytes_per_second": 25165824,
  "allocation_sample_interval_ms": 5000,
  "allocation_tracking_supported": true,
  "gc_collectors": []
}
```

## 采样语义

- heap、non-heap、线程和 GC 累计值来自标准 MXBean；
- process CPU 和 committed virtual memory 来自
  `com.sun.management.OperatingSystemMXBean`；
- Linux RSS/virtual memory 来自 `/proc/self/status`；
- GC pause 通过 JVM GC notification 记录，max 从 HTTP server 初始化时开始累计；
- allocation rate 使用 live thread allocated bytes 的相邻样本差值；
- allocation 首个样本 rate 和 interval 为 0；
- 新出现或已经退出的线程不把完整 lifetime allocation 注入当前窗口；
- 不启动后台采样线程，采样频率由调用方控制。

非 Linux 或不支持 thread allocation tracking 的 JVM 返回 `status=partial`，
对应字段使用 `-1`，不得伪造为 0。

## 安全

API 不暴露：

- 环境变量；
- JVM 完整命令行；
- system properties；
- thread stack；
- host 文件内容。

因此不会把 credential 或启动参数中的敏感值带入响应。

## 24 小时基线

以 5–15 秒间隔采样并保存原始响应。每台 coordinator 独立计算：

- heap used p50/p95/p99/max；
- RSS p50/p95/p99/max；
- allocation rate p50/p95/p99；
- process CPU p50/p95/p99；
- GC pause p50/p95/p99/max；
- live/peak threads；
- heartbeat 和 query latency；
- storage dropped/failed/queue HWM。

采样本身的 HTTP latency 和 CPU 增量也必须记录。若 instrumentation 导致
heartbeat/query latency 或 CPU 可见回归，应回滚 instrumentation，而不是继续调参。

## 调参门禁

只有 24 小时数据完整且无采样缺口时，才允许单机 canary：

```text
-XX:ActiveProcessorCount=8 or 16
-Xms/-Xmx = heap-used p99 + explicit headroom
```

canary 必须满足：

```text
RSS p95 decreases
GC pause p99 does not regress
process CPU p95 does not regress
allocation rate does not regress unexpectedly
heartbeat/query latency does not regress
storage dropped/failed = 0
```

CPU 限制和 heap 限制必须分开 canary，禁止一次同时修改两个变量。
