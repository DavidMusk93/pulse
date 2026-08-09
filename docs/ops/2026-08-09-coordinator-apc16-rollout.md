# Coordinator ActiveProcessorCount 16 Rollout

## 摘要

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-08-09 |
| 运行模式 | `central-runtime` |
| Inventory | `docs/ops/coordinators.hosts` |
| 范围 | 3 台 coordinator |
| `--max-hosts` | `3` |
| 执行并发 | `1`，串行重启 |
| Delivery baseline commit | `8474473 Record coordinator APC16 canary feedback` |
| Pulse JAR SHA-256 | `53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3` |
| 变更 | `-XX:ActiveProcessorCount=16` |
| 结果 | 3/3 成功，保留全量发布 |

用户明确要求将已通过短窗口门禁的 APC16 canary 扩到全部 coordinator。

本次不上传 JAR、不修改 coordinator env、不修改 heap、不重启 agent。只通过
systemd drop-in 修改 coordinator `ExecStart`。

## 变更前提

`::45` canary 已证明：

- RSS steady 下降约 800 MiB；
- OS threads 从 128 降到 60；
- virtual memory 减少约 32.8 GiB；
- query p95 无回归；
- old GC、deadlock、storage drop/failure 均为 0。

Canary 原始门禁：

```text
.tmp/auto-ops/coordinator-apc16-canary-20260809/canary-gate.json
```

## 发布范围

| Coordinator | 发布前 | 本次结果 |
| --- | --- | --- |
| `fdbd:dc05:11:634::45` | APC16 canary | unchanged |
| `fdbd:dc05:13:10c::40` | default 128 processors | updated |
| `fdbd:dc07:0:810::44` | default 128 processors | updated |

发布严格串行，禁止同时重启多个 coordinator。

## 配置

三台最终均存在：

```text
/etc/systemd/system/pulse-coordinator.service.d/20-active-processor-count.conf
```

内容：

```ini
[Service]
ExecStart=
ExecStart=/data24/otf/pulse/jre/bin/java -XX:ActiveProcessorCount=16 -jar /data24/otf/pulse/bin/pulse.jar
```

## 回滚点

| Coordinator | Backup |
| --- | --- |
| `::45` | `/data24/otf/pulse/rollback/coordinator-apc16-20260809T050221Z` |
| `::40` | `/data24/otf/pulse/rollback/coordinator-apc16-20260809T052731Z` |
| `::44` | `/data24/otf/pulse/rollback/coordinator-apc16-20260809T052731Z` |

每台执行前均核对 Pulse JAR SHA，备份主 unit 和已有 drop-in。失败会自动恢复
原 drop-in 并重启旧配置。

## 5 分钟检查

| Coordinator | RSS | OS threads | VMS | Query p95 | Storage |
| --- | ---: | ---: | ---: | ---: | --- |
| `::45` | 503,037,952 B | 62 | 43,529,236,480 B | 59.47 ms | ok |
| `::40` | 1,469,894,656 B | 71 | 39,744,839,680 B | 59.50 ms | ok |
| `::44` | 1,485,377,536 B | 65 | 39,396,081,664 B | 62.11 ms | ok |

`::40`、`::44` 的 RSS 仍处于约 1.4 GiB 冷启动阶段，因此没有在 5 分钟时宣告
内存收益，继续等待到 15 分钟。

## 15 分钟反馈

| Coordinator | RSS | OS threads | VMS | Query p95 | Maintenance | Queue HWM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `::45` | 506,519,552 B | 61 | 43,529,236,480 B | 55.16 ms | 5 ms | 34 |
| `::40` | 1,491,542,016 B | 63 | 39,744,839,680 B | 55.33 ms | 6 ms | 33 |
| `::44` | 1,503,477,760 B | 62 | 39,396,081,664 B | 60.64 ms | 4 ms | 80 |

三台：

- storage status 为 `ok`；
- queue depth 为 0；
- dropped/failed/capacity dropped 为 0；
- retention lag 为 0；
- query 非空且 p95 低于 300 ms。

RSS 结论必须区分：

- `::45` 明确复现显著下降；
- `::40`、`::44` 相对历史约 1.48 GiB 基线无回归，但 15 分钟内未显著下降。

因此全量发布保留的主要一致收益是 OS thread 和 VMS 收缩；RSS 的跨节点收益仍需
长窗口继续观察。

## Arthas

`::40`、`::44` 串行执行：

```text
dashboard -n 6 -i 5000
jvm
thread -n 5
stop
```

| Coordinator | Heap range | Young GC delta/time | Old GC | Live/peak threads | Deadlock |
| --- | ---: | ---: | ---: | ---: | ---: |
| `::40` | 38–168 MiB | 15 / 33 ms | 0 | 42/43 | 0 |
| `::44` | 44–153 MiB | 17 / 49 ms | 0 | 47/48 | 0 |

两台均确认：

```text
INPUT-ARGUMENTS        -XX:ActiveProcessorCount=16
PROCESSORS-COUNT       16
```

Arthas 执行后立即 `stop`，PID/start/JAR/agent 均未变化，无 `3658`/`8563`
listener 残留。

## 最终门禁

原始结果：

```text
.tmp/auto-ops/coordinator-apc16-rollout-20260809/rollout-gate.json
```

全部通过：

```text
all 3 hosts run APC16
RSS has no regression
OS threads <= 80
virtual memory <= 50 GiB
query p95 <= 300 ms
old GC = 0
deadlock = 0
storage status = ok
dropped/failed/capacity dropped = 0
Arthas stopped
```

最终决策：

```text
keep_full_rollout
```

## 最终验证

三台最终均满足：

- cmdline 含 `-XX:ActiveProcessorCount=16`；
- Pulse JAR SHA 匹配；
- coordinator/agent service active；
- queue depth 为 0；
- drop/failure/capacity drop 为 0；
- storage health 为 `ok`；
- 无 Arthas listener。

最终验证 3/3，failed-hosts 为空。

## 回滚

任一节点可独立回滚：

```bash
rm -f /etc/systemd/system/pulse-coordinator.service.d/20-active-processor-count.conf
systemctl daemon-reload
systemctl restart pulse-coordinator.service
systemctl is-active pulse-coordinator.service
```

回滚后必须验证：

- cmdline 不含 `ActiveProcessorCount`；
- agent start 未变化；
- JAR SHA 未变化；
- storage status 为 `ok`；
- dropped/failed 为 0。

## 原始证据

- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/rollout.raw.log`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/rollout/results.tsv`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/rollout/failed-hosts.txt`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/probe-5m.raw.log`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/probe-steady.raw.log`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/arthas.raw.log`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/rollout-gate.json`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/final-verify.raw.log`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/final-verify/results.tsv`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/final-verify/failed-hosts.txt`
- `.tmp/auto-ops/coordinator-apc16-rollout-20260809/final-verify/summary.json`
