# Coordinator JVM Arthas Feedback

## 结论

三台 coordinator 当前没有 heap pressure：

- 30 秒窗口 heap used 为 61–160 MiB；
- heap committed 均为 272 MiB；
- default heap max 均为 30 GiB；
- G1 old collection 均为 0；
- young GC 平均耗时约 1.7–2.5 ms/次；
- 无 Java deadlock。

当前更值得验证的是 128 CPU host 上的 JVM/thread ergonomics，而不是立即设置
heap 上限。`::45` 的 JVM 历史计数出现 peak threads 550、started threads 602，
虽然快照时 live threads 已回落到 53，但证明存在显著瞬态线程扩展。

本轮只有 30 秒快照，不足以直接决定 `ActiveProcessorCount` 或 `Xmx`。下一步应
使用同一低侵入 Arthas 命令做 24 小时稀疏采样，再逐个变量 canary。

## 方法

用户拒绝进程内 runtime instrumentation 后，已执行：

1. 三台回滚 instrumentation JAR；
2. 源码 revert 并推送；
3. 离线安装 Arthas 4.2.0 完整包；
4. 每台串行 attach；
5. 执行 6 次、5 秒间隔 `dashboard`；
6. 执行 `jvm` 和 `thread -n 5`；
7. 立即执行 `stop`；
8. 验证 PID/start/SHA/health 和端口清理。

未使用：

- `trace`；
- `watch`；
- `profiler`；
- heap dump；
- 强制 GC；
- 热更新。

## 快照

| Coordinator | Heap range | Heap committed | Heap max | Young GC delta | Young GC time delta | Old GC |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `::45` | 70–150 MiB | 272 MiB | 30 GiB | 15 | 25 ms | 0 |
| `::40` | 78–156 MiB | 272 MiB | 30 GiB | 18 | 45 ms | 0 |
| `::44` | 61–160 MiB | 272 MiB | 30 GiB | 14 | 32 ms | 0 |

`jvm` 命令结束时：

| Coordinator | Heap used | Non-heap used | Live threads | Peak threads | Started threads | Deadlock |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `::45` | 107.2 MiB | 77.2 MiB | 53 | 550 | 602 | 0 |
| `::40` | 157.8 MiB | 57.2 MiB | 47 | 48 | 72 | 0 |
| `::44` | 110.8 MiB | 56.4 MiB | 43 | 45 | 62 | 0 |

三台均为：

```text
JRE             17.0.19
processors      128
input arguments []
collector       G1
heap init       2 GiB
heap max        30 GiB
```

## CPU 与线程

30 秒 dashboard 中：

- `pulse-sqlite-segment-writer` 通常约 0.9–1.4% 单核 CPU；
- HTTP pool worker 单线程峰值约 1–2.6%；
- Arthas 自身 timer/command thread 通常低于 0.4%；
- top threads 主要处于 `TIMED_WAITING`；
- 未发现锁竞争或 deadlock 证据。

`ThreadMXBean` 的 live thread 不包含全部 GC/JIT native workers，因此它低于
`/proc/<pid>/status` 中约 100 个 OS thread 是预期现象。

`::45` 的 peak 550 是 JVM 启动后的历史峰值，不代表快照时存在 550 个 live
thread。它与此前观测到的默认 `HttpClient`/JVM 根据 128 CPU 扩展相符，需要在
24 小时采样中确认触发条件和持续时间。

## 风险判断

### Heap

30 GiB max 与 272 MiB committed、低于 160 MiB used 明显不匹配，但单个 30 秒
窗口不能覆盖任务输出、文件分发或异常积压场景。

暂不设置 `Xmx`。需要先得到：

- heap used p99/max；
- old generation p99/max；
- old GC count/time；
- task/file workload 峰值。

### CPU/Thread Ergonomics

三台均看到 128 processors，且无显式 JVM 参数。`ActiveProcessorCount` canary
具有明确优化空间，但必须先确认：

- peak threads 是否重复；
- young GC pause 是否受限；
- query/heartbeat latency 是否受限；
- coordinator CPU p95 是否回归。

建议先单独 canary `-XX:ActiveProcessorCount=16`，不要同时修改 heap。

## 后续门禁

24 小时内每 30–60 分钟执行一次同样的 Arthas 快照，快照后立即 `stop`。

满足以下条件后才能开始 CPU canary：

```text
all snapshots stop successfully
coordinator/agent start timestamps unchanged
storage dropped/failed = 0
old GC count remains 0 or pause remains stable
heap used p99 has explicit headroom
query/heartbeat latency baseline complete
```

CPU canary 通过后，再基于 heap p99 独立评估 `Xmx`。

## 原始证据

- `.tmp/auto-ops/coordinator-runtime-feedback-rollback-20260809/rollback.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/full-deploy-all.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/snapshot-canary-retry.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/snapshot-all.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/final-verify.raw.log`
