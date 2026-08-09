# Coordinator ActiveProcessorCount 16 Canary

## 摘要

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-08-09 |
| 运行模式 | `central-runtime` |
| Inventory | `.tmp/auto-ops/coordinator-runtime-feedback-20260809/scope/canary.hosts` |
| 范围 | `fdbd:dc05:11:634::45` |
| `--max-hosts` | `1` |
| Delivery baseline commit | `da286dc Document coordinator Arthas runtime feedback` |
| Pulse JAR SHA-256 | `53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3` |
| 变更 | `-XX:ActiveProcessorCount=16` |
| 结果 | 短窗口门禁通过，保留 canary-only |

本次为配置单变量 canary，不上传 JAR，不修改 coordinator env，不重启 agent。
`::40`、`::44` 保持原配置作为控制组。

## Baseline

变更前 `::45`：

```text
RSS                  1,308,696,576 bytes
OS threads           128
virtual memory       78,706,368,512 bytes
query p95            82.93 ms
storage status       ok
dropped/failed       0/0
```

Pulse JAR SHA 与目标 SHA 一致。

## 事务化变更

新增：

```text
/etc/systemd/system/pulse-coordinator.service.d/20-active-processor-count.conf
```

内容：

```ini
[Service]
ExecStart=
ExecStart=/data24/otf/pulse/jre/bin/java -XX:ActiveProcessorCount=16 -jar /data24/otf/pulse/bin/pulse.jar
```

备份：

```text
/data24/otf/pulse/rollback/coordinator-apc16-20260809T050221Z
```

执行流程：

1. 核对远端 JAR SHA；
2. 记录 agent start 和原 coordinator PID/cmdline；
3. 备份 systemd unit 和已有 drop-in；
4. 写入新 drop-in；
5. `systemctl daemon-reload`；
6. 只重启 `pulse-coordinator.service`；
7. 从 `/proc/<pid>/cmdline` 验证 APC16；
8. 验证 JAR SHA、agent start 和 storage health。

任一步失败会恢复原 drop-in 并重启旧配置。

## Arthas 验证

使用 Arthas 4.2.0 执行：

```text
dashboard -n 6 -i 5000
jvm
thread -n 5
stop
```

确认：

```text
INPUT-ARGUMENTS        -XX:ActiveProcessorCount=16
PROCESSORS-COUNT       16
```

30 秒窗口：

```text
heap range             39–153 MiB
heap committed         272 MiB
young GC               11 / 29 ms
old GC                 0
live/peak threads      39/43
deadlock               0
```

Arthas `stop` 后无 `3658`/`8563` listener，coordinator PID/start 未变化。

## Feedback 门禁

15 分钟 steady feedback：

| 指标 | Baseline | Steady | 结果 |
| --- | ---: | ---: | --- |
| RSS | 1,308,696,576 B | 469,798,912 B | 通过 |
| OS threads | 128 | 60 | 通过 |
| Virtual memory | 78,706,368,512 B | 43,533,434,880 B | 通过 |
| Query p95 | 82.93 ms | 61.28 ms | 通过 |
| Old GC | 0 | 0 | 通过 |
| dropped/failed | 0/0 | 0/0 | 通过 |

5 分钟 RSS 曾为 1,465,610,240 B，因此延长到 15 分钟后再判定，避免使用冷启动
单点作结论。

门禁文件：

```text
.tmp/auto-ops/coordinator-apc16-canary-20260809/canary-gate.json
```

## 最终范围验证

3 台最终验证：

- `::45` cmdline 含 `-XX:ActiveProcessorCount=16`；
- `::40`、`::44` cmdline 不含 `ActiveProcessorCount`；
- 三台 JAR SHA 一致；
- 三台 coordinator/agent service active；
- 三台 storage health 为 `ok`；
- 三台 dropped/failed 为 0；
- 三台无 Arthas listener。

结果：3/3 成功，failed-hosts 为空。

## 决策

```text
keep ::45 canary
do not roll out to ::40 / ::44
do not change heap settings
```

需要至少 24 小时 canary/control feedback 后再决定扩大。

## 回滚

```bash
rm -f /etc/systemd/system/pulse-coordinator.service.d/20-active-processor-count.conf
systemctl daemon-reload
systemctl restart pulse-coordinator.service
systemctl is-active pulse-coordinator.service
```

回滚必须再次验证 PID/cmdline、agent start、JAR SHA、storage health 和
dropped/failed。

## 原始证据

- `.tmp/auto-ops/coordinator-apc16-canary-20260809/baseline.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/apply.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/post-probe.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/post-arthas.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/steady-probe.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/canary-gate.json`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/final-verify.raw.log`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/final-verify/results.tsv`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/final-verify/failed-hosts.txt`
- `.tmp/auto-ops/coordinator-apc16-canary-20260809/final-verify/summary.json`
