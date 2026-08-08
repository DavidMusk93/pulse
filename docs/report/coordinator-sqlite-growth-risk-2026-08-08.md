# Coordinator SQLite Metrics 持续增长风险

## 结论

风险等级：**高，且持续增长已经发生**。

三台 coordinator 的 `pulse-metrics.db` 当前合计约 `1.42 TiB`。配置声明
保留 7 天，但 `tide_worker_sample` 实际保留 48–55 天；线上写入速率明显
超过清理上限，因此当前清理机制无法收敛到 7 天窗口。

这不是单纯的“文件删除后未缩小”问题：

- 两台数据库几乎没有 freelist 可复用页，仍在分配新页；
- `tide_worker_sample` 的净新增速度持续为正；
- cleanup 查询需要扫描大索引并使用临时 B-Tree；
- 单 writer 在 maintenance 期间停写约 10–30 秒，队列峰值达到 `2543`；
- 当前 storage health 在队列积压期间仍返回 `status=ok`。

在不修复清理吞吐和容量保护的情况下，数据库会继续增长。

## 范围与方法

- 时间：2026-08-08
- 节点：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- 数据库：`/data24/otf/pulse/data/pulse-metrics.db`
- 配置：
  - retention：7 天
  - maintenance interval：300 秒
  - cleanup limit：每表每轮 10,000 行
- 采样方式：只读 SQLite `mode=ro`，两轮间隔约 44 秒。

为避免对 500 GB 级数据库持续施压，正式采样只读取：

- DB/WAL 文件大小；
- `page_count`、`freelist_count`、journal 参数；
- 每张表首尾 rowid 与时间；
- cleanup SQL 的 `EXPLAIN QUERY PLAN`；
- `/api/metrics/storage` health。

初次尝试的全表 `COUNT(*) + dbstat` 超过 3 分钟，已中止。中止后发现三台
各残留一个远端 probe 进程，已通过精确命令行匹配终止并验证不存在残留。
三个 coordinator 最终均 active，storage 无 dropped/failed command。

## 当前容量

| Coordinator | DB | WAL T1 | 可复用页 | `/data24` 可用 |
| --- | ---: | ---: | ---: | ---: |
| `::45` | 511.99 GiB | 356.37 MiB | 69.04 GiB | 1.19 TiB |
| `::40` | 446.99 GiB | 799.20 MiB | 0 | 1.19 TiB |
| `::44` | 457.82 GiB | 522.58 MiB | 0.002 GiB | 1.16 TiB |

SQLite 参数三台一致：

- `journal_mode=wal`
- `page_size=4096`
- `auto_vacuum=0`
- `wal_autocheckpoint=1000`

`::45` 因历史删除积累了约 69 GiB freelist，短期可复用这些页；另外两台
几乎没有可复用空间，会直接继续扩大主 DB 文件。

## 写入速率

rowid 是插入高水位，不等价于精确行数；在当前顺序写入模型下，两轮 rowid
增量可用于估算短期写入速率。

| Coordinator | Heartbeat | Tide worker | Group leader | 合计 |
| --- | ---: | ---: | ---: | ---: |
| `::45` | 18.7 行/s | 137.6 行/s | 1.7 行/s | 158.0 行/s |
| `::40` | 43.4 行/s | 264.6 行/s | 3.6 行/s | 311.6 行/s |
| `::44` | 31.5 行/s | 163.3 行/s | 2.9 行/s | 197.7 行/s |

清理能力上限为：

```text
10,000 rows / 300s = 33.3 rows/s/table
```

仅 `tide_worker_sample` 的写入就是清理上限的：

- `::45`：4.1 倍
- `::40`：7.9 倍
- `::44`：4.9 倍

即使每轮都完整删除 10,000 条 tide worker 旧样本，其净增长仍约为：

| Coordinator | Tide 净增长 | 每日净增长 |
| --- | ---: | ---: |
| `::45` | 104.3 行/s | 9.0 M 行/day |
| `::40` | 231.3 行/s | 20.0 M 行/day |
| `::44` | 130.0 行/s | 11.2 M 行/day |

`::40` 在 43.6 秒采样窗口内新分配约 10.5 MiB 页面，折算毛增长约
20 GiB/day。其他节点当时主要写入 WAL 或复用 freelist，不能据主 DB
短时不变判断为无增长。

## Retention 失效证据

采样时 7 天 cutoff 约为 `2026-08-01 23:47 CST`。

| Coordinator | Heartbeat 最老样本 | 年龄 | Tide 最老样本 | 年龄 |
| --- | --- | ---: | --- | ---: |
| `::45` | 2026-07-18 08:47 | 21.6 天 | 2026-06-20 05:34 | 49.8 天 |
| `::40` | 2026-07-29 18:58 | 10.2 天 | 2026-06-21 21:23 | 48.1 天 |
| `::44` | 2026-08-01 09:14 | 7.6 天 | 2026-06-14 18:38 | 55.2 天 |

`group_leader_sample` 最老数据约 7.002 天，说明低写入速率表可以基本追上
retention；高写入速率的 heartbeat/tide 表无法追上。

## 源码根因

### 删除吞吐有硬上限

`AsyncLocalMetricStorage` 每 300 秒执行一次 maintenance。
`LocalMetricStorage.deleteExpiredSamples()` 对四张表分别最多删除 10,000
行。这个限额适合控制事务大小，但没有根据 backlog 连续 drain，也没有根据
写入速率调整。

### Cleanup 索引不匹配

cleanup 条件是：

```sql
WHERE observed_at_ms < ?
ORDER BY observed_at_ms ASC
LIMIT 10000
```

线上 query plan：

- `heartbeat_sample`：可使用主键首列 `observed_at_ms` 搜索；
- `tide_worker_sample`：扫描 `idx_tide_worker_time` 并创建临时 B-Tree；
- `group_leader_sample`：扫描 `idx_group_leader_time` 并创建临时 B-Tree；
- `host_event`：扫描索引并创建临时 B-Tree。

现有 tide/group 索引以 `bucket_ms` 为首列，不能高效服务只按
`observed_at_ms` 的删除。

### Maintenance 阻塞单 writer

SQLite 写入、retention 删除和 WAL checkpoint 共用同一个 writer thread。
现场观察到 maintenance 期间：

- queue 从 0 增长到峰值 2543；
- 两台约 10 秒后恢复，一台约 30 秒后恢复；
- maintenance 完成后 queue 回到 0；
- 未发生 drop/failure。

随着数据库继续增大，扫描时间会继续变长；queue 上限为 20,000，最终存在
触发 dropped command 的风险。

### 缺少容量硬保护

设计文档提到 `PULSE_LOCAL_STORAGE_MAX_BYTES`，当前代码没有实现：

- 没有 DB 最大字节数；
- 没有超限降级或停止 raw sample；
- health 状态不考虑 DB 大小、retention lag 或 queue depth；
- `auto_vacuum=0`，代码也不执行 VACUUM；
- `PRAGMA wal_checkpoint(PASSIVE)` 不保证缩小 WAL 文件。

## 风险判断

1. **容量风险：确定。** 当前单库已达 447–512 GiB，且 tide 净增长持续为正。
2. **Retention 风险：已发生。** 7 天配置实际保留最长 55 天。
3. **写入丢失风险：中高。** maintenance 已造成数千队列积压；库越大，
   单轮扫描越慢，可能最终打满 20k queue。
4. **查询风险：高。** 无约束全表统计在三台并发执行超过 3 分钟，并明显影响
   writer 队列。
5. **磁盘耗尽时间：月级。** 按当前毛增长和约 1.2 TiB 可用空间估算，
   风险窗口是数月而非数年；实际时间还受同盘其他业务写入影响。

## 建议顺序

### P0：先修清理算法，再做一次性压缩

1. 为 cleanup 提供以 `observed_at_ms` 为首列的可用访问路径，或改成可证明
   单调安全的 rowid/bucket 分批清理，避免每轮全扫描和临时排序。
2. maintenance 在单次时间预算内连续 drain backlog，而不是每 5 分钟每表
   只删一次 10k；同时限制每个小事务大小。
3. health 增加 retention lag、DB/WAL bytes、queue high-watermark 和
   maintenance duration；queue depth 持续非零时不得继续报告 `ok`。
4. 实现 `PULSE_LOCAL_STORAGE_MAX_BYTES` 硬保护和明确降级策略。

不要直接在 500 GB 线上库并发创建大索引或执行 VACUUM。这会产生大量额外
I/O、临时空间和长时间锁竞争，必须先做单机 canary 与空间预算。

### P1：修复上线后轮转历史大库

清理算法修复后，再逐台 coordinator 事务化处理：

1. 停止单台 coordinator；
2. checkpoint 并保留旧 DB 回滚副本；
3. 启动新 DB 或迁移最近 7 天数据；
4. 验证 service、metrics API、写入速率和 retention；
5. 三台依次执行，禁止并发处理；
6. 观察期结束后再删除旧库。

仅提高 `PULSE_LOCAL_STORAGE_CLEANUP_LIMIT` 不能解决索引扫描问题，可能反而
扩大 writer 停顿。

## 证据

- `.tmp/auto-ops/coordinator-sqlite-growth-20260808/t0-light.raw.log`
- `.tmp/auto-ops/coordinator-sqlite-growth-20260808/t1-light.raw.log`
- `.tmp/auto-ops/coordinator-sqlite-growth-20260808/maintenance-recovery.raw.log`
- `.tmp/auto-ops/coordinator-sqlite-growth-20260808/probe-cleanup.raw.log`
- `.tmp/auto-ops/coordinator-sqlite-growth-20260808/post-cleanup-verify.raw.log`
- `.tmp/reports/coordinator-sqlite-growth-summary.tsv`
