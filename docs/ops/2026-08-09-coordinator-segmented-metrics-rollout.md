# Coordinator Segmented Metrics Rollout

## 摘要

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-08-09 |
| 运行模式 | `central-runtime` |
| Inventory | `docs/ops/coordinators.hosts` |
| 范围 | 3 台 coordinator |
| `--max-hosts` | `3` |
| 实现提交 | `a9fff0c Implement segmented SQLite metrics storage` |
| JAR SHA-256 | `87dc7d3b75f46a0a4d4d19ebe98f767487ff5387845b279e663db3ac7c587b66` |
| 测试 | 88/88 |
| 结果 | 3/3 成功 |

本次将 coordinator metrics 从单个持续增长 SQLite 数据库切换到：

- UTC 日 raw shard；
- 1 分钟 rollup 日 shard；
- 整文件 retention；
- raw/rollup 字节硬上限；
- legacy DB 只读兼容；
- queue、bytes、shard、retention lag 健康指标。

Legacy DB 未删除，作为查询兼容和回滚基础。

## 节点

- `fdbd:dc05:11:634::45`
- `fdbd:dc05:13:10c::40`
- `fdbd:dc07:0:810::44`

## 配置

```text
PULSE_LOCAL_STORAGE_MODE=segmented
PULSE_LOCAL_STORAGE_PATH=/data24/otf/pulse/data/pulse-metrics.db
PULSE_LOCAL_STORAGE_SHARD_DIR=/data24/otf/pulse/data/metrics-v2
PULSE_LOCAL_STORAGE_MAX_BYTES=274877906944
PULSE_LOCAL_STORAGE_RETENTION_DAYS=7
PULSE_LOCAL_STORAGE_ROLLUP_RETENTION_DAYS=30
PULSE_LOCAL_STORAGE_ROLLUP_MAX_BYTES=68719476736
```

Raw managed quota 为 256 GiB，rollup quota 为 64 GiB。

## 发布流程

1. 本地执行前端构建、88 个测试和 shaded JAR 打包。
2. 推送实现提交 `a9fff0c`。
3. 对 3 台 scope 执行 destructive dry-run。
4. 串行 Orthrus demand。
5. 先切换 `::45` canary。
6. 验证 recent query、legacy overlap query、v2 索引、raw/rollup 文件、
   storage health 和 agent 未重启。
7. 等待 canary 完成首个 5 分钟 maintenance。
8. 依次切换 `::40`、`::44`，禁止并发重启。
9. 三台并行等待各自首轮 maintenance 后执行最终强校验。

每台变更前均备份：

- `/data24/otf/pulse/bin/pulse.jar`
- `/data24/otf/pulse/etc/pulse-coordinator.env`

部署失败会自动恢复两文件并重启旧 coordinator。

## 备份

| Coordinator | 备份目录 |
| --- | --- |
| `::45` | `/data24/otf/pulse/rollback/segmented-metrics-20260809T011500Z` |
| `::40` | `/data24/otf/pulse/rollback/segmented-metrics-20260809T012300Z` |
| `::44` | `/data24/otf/pulse/rollback/segmented-metrics-20260809T012500Z` |

## Canary

`fdbd:dc05:11:634::45`：

- recent query：12 points；
- cutover overlap query：13 points；
- v2 时间索引存在；
- raw 与 rollup shard 均持续写入；
- legacy DB mtime 不再变化；
- legacy WAL checkpoint 后为 0；
- agent `ExecMainStartTimestampMonotonic` 未变化；
- 首轮 maintenance：3 ms；
- queue high watermark：33；
- dropped/failed：0。

## 最终验证

| Coordinator | Maintenance | Queue HWM | Managed bytes | Legacy bytes | Legacy WAL |
| --- | ---: | ---: | ---: | ---: | ---: |
| `::45` | 3 ms | 33 | 72,730,400 | 549,739,954,176 | 0 |
| `::40` | 4 ms | 32 | 105,442,328 | 480,782,172,160 | 0 |
| `::44` | 11 ms | 34 | 52,436,936 | 492,557,787,760 | 672,309,872 |

三台均满足：

- coordinator JAR SHA 匹配；
- `pulse-coordinator.service=active`；
- `PULSE_LOCAL_STORAGE_MODE=segmented`；
- raw shard 存在；
- rollup shard 存在；
- `storage.status=ok`；
- queue depth 为 0；
- dropped/failed/capacity dropped 为 0；
- retention lag 为 0；
- managed bytes 小于 quota；
- legacy DB mtime 和 WAL size 在 maintenance 观察窗口内不变；
- recent query 有数据；
- `doubao=15` 且全部 alive；
- 生产静态资源包含新 storage health UI。

旧单库 maintenance 现场耗时 10–30 秒、queue HWM 达 2543。本次 v2 首轮
maintenance 最长 11 ms，未形成可见队列积压。

## Legacy 处理

Legacy DB 继续原位只读保留：

```text
/data24/otf/pulse/data/pulse-metrics.db
```

本次不删除、不改名、不 VACUUM legacy。Legacy 删除必须在 v2 覆盖最长查询
窗口、raw/rollup retention 和容量保护持续验证通过后，作为新的独立生产操作
逐台执行。

## 回滚

以单台 `backup` 目录为例：

```bash
backup=/data24/otf/pulse/rollback/segmented-metrics-<run-id>

cp -p "$backup/pulse.jar" /data24/otf/pulse/bin/pulse.jar
cp -p "$backup/pulse-coordinator.env" /data24/otf/pulse/etc/pulse-coordinator.env
systemctl restart pulse-coordinator.service
systemctl is-active pulse-coordinator.service
```

回滚只恢复 JAR 和 env。Legacy 从未被 v2 修改，不需要反向数据迁移。V2 shard
保留用于排查，确认后再单独清理。

## 原始证据

- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/scope/`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/canary-baseline.raw.log`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/canary.raw.log`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/canary-query-verify.raw.log`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/canary-maintenance.raw.log`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/deploy-40.raw.log`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/deploy-44.raw.log`
- `.tmp/auto-ops/coordinator-segmented-metrics-20260809/final-verify.raw.log`
