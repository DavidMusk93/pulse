# Segmented Metric Storage

## 目标

Coordinator metrics 使用嵌入式 SQLite，但不能再依赖单个无限增长数据库和
大表逐行 retention。

终态采用：

```text
single async writer
  -> daily raw SQLite shards
  -> in-memory 1m aggregation
  -> daily rollup SQLite shards

query
  -> recent range: federated raw shards
  -> historical range: rollup shards
  -> migration overlap: read-only legacy DB + raw shards
```

核心目标：

- retention 通过删除整个 sealed shard 完成，复杂度与表行数无关；
- raw 和 rollup 都有字节硬上限；
- 当前写入永远只有一个 writer thread；
- 旧 500 GB 数据库不原地建索引、不 VACUUM、不继续写入；
- HTTP metrics contract 和 UI 查询接口保持不变。

## 文件布局

默认目录：

```text
/data24/otf/pulse/data/
  pulse-metrics.db                 # legacy，只读
  pulse-metrics.db-wal
  pulse-metrics.db-shm
  metrics-v2/
    legacy-cutover-ms
    metrics-raw-2026-08-09.db
    metrics-rollup-2026-08-09.db
```

Raw 和 rollup 都使用 UTC 日期命名。

Raw shard：

- 保存完整 heartbeat、tide worker、group leader 和 host event schema；
- 当前 shard 使用 WAL；
- 日切后 checkpoint、TRUNCATE WAL 并关闭；
- 新 shard 从空库创建时间索引，不对 legacy DB 执行 schema migration。

Rollup shard：

- 粒度固定 1 分钟；
- 通用主键为 `(metric, series_key, bucket_ms)`；
- writer 内存聚合同一分钟样本，分钟闭合后批量 UPSERT；
- 保存 `sum/count`，查询时保持 `sample_policy=avg`。

## 写入路径

写入仍使用 bounded queue 和单 writer：

```text
heartbeat handler
  -> queue.offer(command)
  -> writer batch
  -> transaction(raw shard)
  -> update in-memory minute accumulator
  -> flush completed minute(rollup shard)
```

硬约束：

- heartbeat handler 不等待 SQLite；
- queue 满时丢弃最新 metrics command，不阻塞 heartbeat；
- raw transaction 成功后才更新 rollup；
- rollup 失败不回滚已提交 raw，但 storage health 必须 degraded；
- 日切、retention、checkpoint 和容量处理均在 writer thread 串行执行。

## Retention

### Raw

默认保留 7 个 UTC 日 shard。

```text
today = D
keep D ... D-6
delete shard < D-6
```

删除顺序：

1. 关闭 shard writer；
2. 删除 `-wal`；
3. 删除 `-shm`；
4. 删除 `.db`。

禁止对 raw shard 执行逐行 TTL DELETE。

### Rollup

默认保留 30 个 UTC 日 shard。删除策略与 raw 相同。

### 容量上限

默认：

- raw：256 GiB；
- rollup：64 GiB。

超过配额时：

1. 删除最老 sealed shard；
2. 不删除当前 active shard；
3. 如果 active shard 自身仍超过配额，进入 capacity degraded；
4. capacity degraded 时停止接受新的 metrics command，但 heartbeat 主链继续。

Legacy DB 单独计量并展示，不计入 v2 managed quota。Legacy 退出由迁移流程
控制，不能被后台自动删除。

## 查询路由

### Recent

查询起点位于 raw retention 窗口内时：

1. 只选择与 `[start_ms, end_ms]` 日期范围相交的 raw shard；
2. 使用只读内存 SQLite connection；
3. `ATTACH` 目标 shard；
4. 创建 TEMP `UNION ALL` views；
5. 复用现有 LocalMetricStorage SQL 和全局 point/series budget。

查询不会在前端拼接，也不会按 shard 分别截断。

### Historical

查询起点早于 raw retention 且 legacy 不覆盖时，读取 1m rollup shard。

Rollup query：

- 最小 step 为 60 秒；
- 跨 shard 合并同一 series；
- 保持 TopN、aggregate、point limit 和 series limit；
- point metadata 标记 `rollup=1m`。

### Migration overlap

首次启用 v2 时写入 `legacy-cutover-ms`：

- 新写入只进入 v2；
- 查询起点早于 cutover 时，legacy DB 以只读方式参与 federated query；
- 查询起点晚于 cutover 时，不 ATTACH legacy；
- legacy 保留窗口结束后，由运维逐台移除。

这样不需要双写，也不需要在 500 GB legacy DB 上建新索引。

## Health Contract

`/api/metrics/storage` 增加：

| 字段 | 说明 |
| --- | --- |
| `storage_bytes` | v2 raw + rollup bytes |
| `legacy_bytes` | legacy DB/WAL/SHM bytes |
| `max_bytes` | raw + rollup quota |
| `shard_count` | raw + rollup shard 数 |
| `deleted_shards` | 整文件 retention/capacity 删除数 |
| `capacity_dropped_commands` | 容量保护丢弃命令数 |
| `queue_high_watermark` | writer queue 历史峰值 |
| `maintenance_duration_ms` | 最近 maintenance 耗时 |
| `retention_lag_ms` | 最老 raw shard 超出窗口的时间 |

以下任一条件必须返回 `degraded`：

- failed/dropped command 非零；
- capacity exceeded；
- queue depth 达到容量一半。

UI 必须展示 queue、queue high watermark、shard count、v2 bytes、legacy bytes
和 retention lag。

## 索引

Legacy schema 不改变。

V2 raw shard 从空库创建：

- `tide_worker_sample(observed_at_ms)`；
- `group_leader_sample(observed_at_ms)`；
- `host_event(observed_at_ms)`。

这些索引用于 shard 内时间查询。由于 shard 初始为空，不存在大库在线建索引
的额外空间和长锁风险。

## 发布与迁移

逐台 coordinator 执行：

1. dry-run 和权限刷新；
2. 记录 legacy DB/JAR/service 基线；
3. 更新 coordinator env，启用 `PULSE_LOCAL_STORAGE_MODE=segmented`；
4. 部署 JAR并重启 coordinator；
5. 验证当前 raw/rollup shard、cutover marker 和 health；
6. 验证 recent query、legacy overlap query 和写入增长；
7. 观察至少一个 maintenance 周期；
8. canary 通过后再处理下一台。

禁止三台同时切换。

## 回滚

回滚只需要：

1. 恢复旧 JAR；
2. 将 `PULSE_LOCAL_STORAGE_MODE` 改回 `legacy`；
3. 重启 coordinator。

Legacy DB 在迁移观察期保持原位且不被 v2 修改，因此回滚不需要数据反向迁移。
V2 shard 保留用于问题分析，确认后再清理。

## Legacy 退出

满足全部条件后才能逐台删除 legacy：

- v2 连续运行覆盖最长在线查询窗口；
- raw retention、rollup retention 和 quota 均通过；
- queue 无持续积压，dropped/failed 为 0；
- recent 与 historical 查询结果抽样一致；
- rollback 观察期结束。

Legacy 删除必须作为独立生产操作记录，不得与首次切换隐式捆绑。
