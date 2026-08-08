# Coordinator Storage Feedback Optimization

## 摘要

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-08-09 |
| 运行模式 | `central-runtime` |
| Inventory | `docs/ops/coordinators.hosts` |
| 范围 | 3 台 coordinator |
| 单次部署 `--max-hosts` | `1` |
| 最终验证 `--max-hosts` | `3` |
| Delivery commit | `2df72ce Optimize coordinator metric storage layout` |
| JAR SHA-256 | `53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3` |
| 前一版本 SHA-256 | `87dc7d3b75f46a0a4d4d19ebe98f767487ff5387845b279e663db3ac7c587b66` |
| 测试 | 90/90 |
| 结果 | 3/3 成功 |

本次按线上 metrics feedback 执行单变量 P0 优化：

- rollup 使用 normalized catalog/series 和 `WITHOUT ROWID` 数据主表；
- 新写入切换到 `metrics-rollup-v2-*.db`，保留 generic rollup 只读兼容；
- heartbeat raw JSON 不再重复保存 `tide_workers`；
- tide worker 不再重复保存结构化字段的完整 debug JSON；
- group `plan_mismatch`、`plan_lag` 改为结构化列；
- 旧 raw shard 和旧 generic rollup 继续兼容查询。

本次未同时调整 JVM 或 writer batching，确保容量和延迟反馈可归因于 storage layout。

## Canary

Canary 为 `fdbd:dc05:11:634::45`。先部署并暖机 5 分钟，再使用
639.809 秒纯增量窗口测量 t0/t1：

| 门禁 | 结果 | 阈值 |
| --- | ---: | ---: |
| Raw physical delta | 31,888,152 bytes | - |
| JSON delta | 4,206,010 bytes | - |
| JSON/raw ratio | 13.19% | <= 20% |
| Rollup delta | 2,698,600 bytes / 30,723 rows | - |
| Projected rollup 30d | 10.18 GiB | <= 51.2 GiB |
| Normalized query p95 | 3.02 ms | <= 300 ms |
| API historical p95 | 72.01 ms | <= 300 ms |
| dropped/failed/capacity dropped | 0/0/0 | 0/0/0 |

Canary 同时满足：

- `metric_rollup_1m` 为 `WITHOUT ROWID`；
- `metric_catalog` 有 29 个 metric；
- raw group schema 含 `plan_mismatch`、`plan_lag`；
- API 查询合同非空；
- coordinator health 为 `ok`；
- agent `ExecMainStartTimestampMonotonic` 未变化。

所有门禁通过后才继续处理另外两台。

## 差分发布

每台发布前均读取远端 SHA。三台远端均为前一版本 SHA，因此只上传并替换 JAR；
coordinator env 已是目标配置，不修改 task script，不重启 `pulse-agent.service`。

发布严格串行：

1. `::45` canary；
2. `::40`；
3. `::44`。

每台更新前均备份 JAR 和 coordinator env：

```text
/data24/otf/pulse/rollback/segmented-metrics-storage-p0-20260809T232720Z
```

## 最终验证

| Coordinator | Maintenance | Queue HWM | V2 rollup bytes | V2 rows | Query p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 8 ms | 25 | 6,104,248 | 83,859 | 3.28 ms |
| `fdbd:dc05:13:10c::40` | 10 ms | 31 | 2,620,136 | 35,087 | 1.62 ms |
| `fdbd:dc07:0:810::44` | 38 ms | 27 | 2,010,376 | 27,686 | 1.30 ms |

三台均满足：

- coordinator JAR SHA 为目标 SHA；
- `pulse-coordinator.service=active`；
- `pulse-agent.service` 启动时间未变化；
- storage health 为 `ok`；
- queue depth 为 0；
- dropped/failed/capacity dropped 为 0；
- retention lag 为 0；
- `metrics-rollup-v2-*.db` 存在；
- normalized rollup 为 `WITHOUT ROWID`；
- metric catalog 为 29 项；
- raw group plan 字段已结构化；
- API historical query 非空且 p95 为 106.93–129.40 ms。

Legacy DB 和 generic rollup 未删除，继续作为查询兼容与回滚基础。

## 回滚

任一节点可独立回滚：

```bash
backup=/data24/otf/pulse/rollback/segmented-metrics-storage-p0-20260809T232720Z

cp -p "$backup/pulse.jar" /data24/otf/pulse/bin/pulse.jar
cp -p "$backup/pulse-coordinator.env" /data24/otf/pulse/etc/pulse-coordinator.env
systemctl restart pulse-coordinator.service
systemctl is-active pulse-coordinator.service
```

回滚恢复前一 JAR 和 env。新 v2 rollup shard 保留用于问题分析；再次启用新版本时，
`rollup-v2-cutover-ms` 会根据 generic rollup mtime 推进。

## 原始证据

- `.tmp/auto-ops/coordinator-storage-p0-20260809/pre-deploy-baseline-retry.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/canary-deploy.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/canary-t0.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/canary-t1.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/canary-query.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/canary-gate.json`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/deploy-40.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/deploy-44.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/final-verify.raw.log`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/*/results.tsv`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/*/failed-hosts.txt`
- `.tmp/auto-ops/coordinator-storage-p0-20260809/*/summary.json`
