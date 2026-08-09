# Coordinator Runtime Feedback Rollback And Arthas Snapshot

## 摘要

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-08-09 |
| 运行模式 | `central-runtime` |
| Inventory | `docs/ops/coordinators.hosts` |
| 范围 | 3 台 coordinator |
| `--max-hosts` | `3` |
| Instrumentation commit | `67a37a9 Expose coordinator JVM runtime feedback` |
| Revert delivery commit | `0b8b0d6 Revert "Expose coordinator JVM runtime feedback"` |
| 最终 Pulse JAR SHA-256 | `53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3` |
| Arthas 版本 | `4.2.0` |
| Arthas package SHA-256 | `3252a1531ee3a79910e5602de693297e75f4487a6f5a5c2159e25c3c9d132f88` |
| Revert 测试 | 90/90 |
| 结果 | rollback 3/3；Arthas snapshot 3/3 |

用户认为进程内 runtime instrumentation 侵入性过强，要求改用 Arthas。本次立即
停止 instrumentation 路径，回滚生产与源码，然后使用临时 attach 的 Arthas
采集 JVM feedback。

## Instrumentation 回滚

中断前 instrumentation JAR 已部署到三台：

```text
5c3569bc5495f8491c30fcb169154e0fe4b82d8b33038b90840ae87aab973e0f
```

每台先核对当前 SHA 与 rollback backup SHA，再从以下目录恢复 JAR 和 env：

```text
/data24/otf/pulse/rollback/segmented-metrics-runtime-feedback-20260809T001900Z
```

回滚后逐台验证：

- Pulse JAR SHA 恢复为 `53e732f5...73e0f`；
- `pulse-coordinator.service=active`；
- coordinator 使用新 PID 正常启动；
- `pulse-agent.service` 启动时间未变化；
- `/api/runtime/health` 返回 404；
- storage health 为 `ok`；
- dropped/failed 为 0。

源码使用 `git revert`，提交 `0b8b0d6` 已推送 `origin/main`，全量测试 90/90。

## Arthas 离线安装

远端无法访问 Arthas repository，单独的 148 KB `arthas-boot.jar` 不能完成
attach。使用官方 GitHub release `arthas-all-4.2.0` 的完整
`arthas-bin.zip`，本地和远端均校验 SHA-256。

完整包事务化安装到：

```text
/data24/otf/pulse/tools/arthas
```

包内验证：

- `arthas-boot.jar`；
- `arthas-core.jar`；
- `arthas-agent.jar`。

旧 Arthas 目录回滚点：

```text
/data24/otf/pulse/rollback/arthas-20260809T004000Z
```

工具包安装不重启 coordinator 或 agent。最终 3 台 package marker SHA 一致。

## Attach 方式

Pulse 自带裁剪 JRE 不包含 `jdk.attach`。复用 host 现有 JDK：

```text
/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java
```

每台串行执行：

```text
dashboard -n 6 -i 5000
jvm
thread -n 5
stop
```

参数：

```text
telnet port 3658
HTTP port disabled
session timeout 120s
```

未使用 `trace`、`watch`、`profiler`、heap dump、强制 GC 或热更新。

## 快照结果

| Coordinator | Heap range | Committed | Max | Young GC delta/time | Old GC | Live threads | Deadlock |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `::45` | 70–150 MiB | 272 MiB | 30 GiB | 15 / 25 ms | 0 | 53 | 0 |
| `::40` | 78–156 MiB | 272 MiB | 30 GiB | 18 / 45 ms | 0 | 47 | 0 |
| `::44` | 61–160 MiB | 272 MiB | 30 GiB | 14 / 32 ms | 0 | 43 | 0 |

`::45` 的 JVM 历史线程计数为 peak 550、started 602，快照时已回落到 53。
该反馈支持继续调查 128 CPU host 上的 JVM/thread ergonomics，但当前证据不足以
直接设置 `ActiveProcessorCount` 或 `Xmx`。

## 最终验证

三台最终均满足：

- Pulse JAR SHA 为 `53e732f5...73e0f`；
- `pulse-coordinator.service=active`；
- instrumentation endpoint 为 404；
- Arthas package SHA 为 `3252a153...32f88`；
- `3658`、`8563` 无 listener；
- Arthas 已执行 `stop`；
- snapshot 前后 coordinator PID/start 不变；
- agent start 不变；
- storage status 为 `ok`；
- dropped/failed 为 0。

## 回滚

### Pulse

当前已经回滚，无需进一步操作。必要时仍可从 segmented storage backup 恢复。

### Arthas 工具包

Arthas 当前没有常驻进程或 listener。若需要移除工具文件：

```bash
rm -rf /data24/otf/pulse/tools/arthas
```

`::45` 如需恢复旧 boot-only 目录，可使用：

```bash
mv /data24/otf/pulse/rollback/arthas-20260809T004000Z \
   /data24/otf/pulse/tools/arthas
```

工具包删除或恢复不得重启 Pulse 服务。

## 原始证据

- `.tmp/auto-ops/coordinator-runtime-feedback-rollback-20260809/rollback.raw.log`
- `.tmp/auto-ops/coordinator-runtime-feedback-rollback-20260809/rollback/results.tsv`
- `.tmp/auto-ops/coordinator-runtime-feedback-rollback-20260809/rollback/failed-hosts.txt`
- `.tmp/auto-ops/coordinator-arthas-20260809/preflight-serial.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/jdk-discovery.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/full-deploy-canary.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/full-deploy-all.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/snapshot-canary-retry.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/snapshot-all.raw.log`
- `.tmp/auto-ops/coordinator-arthas-20260809/final-verify.raw.log`
