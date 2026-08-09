# Coordinator ActiveProcessorCount Canary

## 结论

`fdbd:dc05:11:634::45` 的单变量
`-XX:ActiveProcessorCount=16` canary 短窗口门禁全部通过，当前决策为：

```text
keep canary only
do not roll out to ::40 / ::44 yet
do not change Xmx
```

15 分钟稳态反馈：

- RSS 从 1.219 GiB 降到 448 MiB；
- OS threads 从 128 降到 60；
- virtual memory 从 73.3 GiB 降到 40.5 GiB；
- query p95 从 82.9 ms 降到 61.3 ms；
- storage dropped/failed 保持 0；
- G1 old GC 为 0；
- Java deadlock 为 0。

## 变更范围

只修改一台 coordinator：

```text
fdbd:dc05:11:634::45
```

只增加一个 JVM 参数：

```text
-XX:ActiveProcessorCount=16
```

未修改：

- Pulse JAR；
- coordinator env；
- `Xms` / `Xmx`；
- GC algorithm；
- HTTP thread pool；
- SQLite writer batching；
- pulse-agent；
- 另外两台 coordinator。

## 实施方式

使用独立 systemd drop-in 覆盖 coordinator `ExecStart`：

```ini
[Service]
ExecStart=
ExecStart=/data24/otf/pulse/jre/bin/java -XX:ActiveProcessorCount=16 -jar /data24/otf/pulse/bin/pulse.jar
```

路径：

```text
/etc/systemd/system/pulse-coordinator.service.d/20-active-processor-count.conf
```

回滚点：

```text
/data24/otf/pulse/rollback/coordinator-apc16-20260809T050221Z
```

## Feedback

### 进程和 API

| 指标 | Baseline | 5 min | 15 min steady | Steady delta |
| --- | ---: | ---: | ---: | ---: |
| RSS | 1,308,696,576 B | 1,465,610,240 B | 469,798,912 B | -838,897,664 B |
| OS threads | 128 | 62 | 60 | -68 |
| Virtual memory | 78,706,368,512 B | 39,266,656,256 B | 43,533,434,880 B | -35,172,933,632 B |
| Query p95 | 82.93 ms | 58.51 ms | 61.28 ms | 0.739x |

5 分钟 RSS 高于 baseline，说明冷启动单点不能作为结论。继续观察到 15 分钟后，
RSS 显著下降，因此 canary 判定使用 steady sample，而不是选择性忽略启动噪声。

### Arthas

Arthas 明确确认：

```text
INPUT-ARGUMENTS        -XX:ActiveProcessorCount=16
PROCESSORS-COUNT       16
```

30 秒窗口：

| 指标 | APC16 |
| --- | ---: |
| Heap used range | 39–153 MiB |
| Heap committed | 272 MiB |
| Heap max | 30 GiB |
| Young GC delta | 11 |
| Young GC time delta | 29 ms |
| Old GC | 0 |
| Live Java threads | 39 |
| Peak Java threads | 43 |
| Deadlock | 0 |
| SQLite writer CPU | 约 0.5–0.7% |

对照快照中 `::45` 使用 128 processors 时：

- live Java threads 为 53；
- JVM 历史 peak threads 为 550；
- 30 秒 young GC 为 15 次 / 25 ms；
- SQLite writer 通常约 0.9–1.4% CPU。

短窗口内 APC16 没有造成 GC、writer 或 query 回归。

## 门禁

以下门禁全部通过：

```text
Arthas confirms processors=16
RSS steady decreases
OS threads decrease
virtual memory decreases
query p95 does not regress
old GC remains 0
deadlock remains 0
storage status=ok
dropped/failed/capacity dropped=0
agent start unchanged
Pulse JAR unchanged
```

原始门禁结果：

```text
.tmp/auto-ops/coordinator-apc16-canary-20260809/canary-gate.json
```

## 控制组

最终验证保持：

| Coordinator | Role | JVM flag |
| --- | --- | --- |
| `::45` | canary | `ActiveProcessorCount=16` |
| `::40` | control | none |
| `::44` | control | none |

三台均运行相同 Pulse JAR SHA：

```text
53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3
```

## 回滚

```bash
rm -f /etc/systemd/system/pulse-coordinator.service.d/20-active-processor-count.conf
systemctl daemon-reload
systemctl restart pulse-coordinator.service
systemctl is-active pulse-coordinator.service
```

回滚后必须确认：

- `/proc/<pid>/cmdline` 不含 `ActiveProcessorCount`；
- agent start 未变化；
- JAR SHA 未变化；
- storage dropped/failed 为 0。

## 下一步

保留 `::45` canary 24 小时，并与 `::40`、`::44` 比较：

- RSS p50/p95/max；
- OS thread p50/p95/max；
- query/heartbeat latency；
- maintenance duration；
- queue HWM；
- dropped/failed；
- Arthas heap/GC/thread 稀疏快照。

24 小时门禁通过后，才允许逐台扩到另外两台。Heap tuning 继续保持独立，不与
APC16 同批发布。

## 原始证据

- `.tmp/auto-ops/coordinator-apc16-canary-20260809/baseline.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/apply.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/post-probe.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/post-arthas.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/steady-probe.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/canary-gate.json`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/final-verify.raw.log`
