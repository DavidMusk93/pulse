# Group Leader 聚合行为运行时分析

分析时间：2026-06-01 19:40-20:05 CST。

## 背景

用户反馈部分机器确认数不符合预期，怀疑与 group leader 转发心跳逻辑有关。本次分析按 `docs/debug/arthas.md` 对 group leader 所在节点做运行时观察，不修改业务逻辑。

## 观测对象

- coordinator：
  - `fdbd:dc05:11:634::45:9966`
  - `fdbd:dc05:13:10c::40:9966`
  - `fdbd:dc07:0:810::44:9966`
- group：
  - `tlblog_stream_olap_separate/lq/000`
- group leader：
  - `dc03-pf-t400-n048.byted.org`
  - IPv6：`fdbd:dc03:f:400::48`
- 低确认样本：
  - `dc03-pf-t418-n048.byted.org`
  - IPv6：`fdbd:dc03:f:418::48`

## 静态行为

当前 coordinator 的 group plan 由 `CoordinatorService#recomputeGroups` 动态维护：

- 只把 `alive` host 纳入 group。
- 按 `cluster + area` 分桶。
- 桶内按 IPv6 排序。
- 每 `PULSE_GROUP_SIZE_LIMIT=7` 台切一个 shard。
- shard 第一个成员是 leader。
- group id 格式为 `cluster/area/%03d`。
- leader URL 使用成员 IPv6 和 `PULSE_GROUP_PORT=9977`。

当前 agent 的 dynamic group 行为：

- 所有 agent 启动后监听 `/group/heartbeat`，端口为 `9977`。
- agent 先 direct 上报 coordinator，拿到 `cmd.group_plan` 后切换模式。
- leader 模式：
  - 接收 follower 的 `/group/heartbeat`。
  - 用 `GroupHeartbeatCollector` 保存每个 follower 最新 heartbeat。
  - 每个 heartbeat 周期把自身 heartbeat 与 collector 中的 follower heartbeat 组成 batch。
  - batch 通过 `/heartbeat` 发送给其中一个 coordinator。
- follower 模式：
  - 向 leader 的 `/group/heartbeat` 上报单机 heartbeat。
  - 如果 leader 请求失败，fallback 到 direct `/heartbeat`。
- coordinator 之间仍依赖 `/heartbeat_fwd` 做 lazy 最终一致同步。

## API 现象

初始 `/api/hosts` 采样中，3 台 coordinator 都返回 `471` 台 host。大部分 group source 都是 `7` 台一组，符合 group size 上限。

在 `fdbd:dc05:13:10c::40` 的采样中，低确认样本为：

| agent | source | status | confirmations | group size | computed leader |
| --- | --- | --- | --- | --- | --- |
| `dc05-p13-t46-n050.byted.org` | `direct` | `warming` | `2` | `0` | `-` |
| `dc02-p11-tc-n043.byted.org` | `direct` | `warming` | `1` | `0` | `-` |
| `dc03-pf-t418-n048.byted.org` | `tlblog_stream_olap_separate/lq/000` | `warming` | `2` | `7` | `dc03-pf-t400-n048.byted.org` |

`tlblog_stream_olap_separate/lq/000` 成员：

| agent | IPv6 | confirmations | status |
| --- | --- | --- | --- |
| `dc03-pf-t400-n048.byted.org` | `fdbd:dc03:f:400::48` | `4` | `alive` |
| `dc03-pf-t402-n048.byted.org` | `fdbd:dc03:f:402::48` | `4` | `alive` |
| `dc03-pf-t406-n048.byted.org` | `fdbd:dc03:f:406::48` | `4` | `alive` |
| `dc03-pf-t418-n048.byted.org` | `fdbd:dc03:f:418::48` | `2` | `warming` |
| `dc03-pf-t41a-n047.byted.org` | `fdbd:dc03:f:41a::47` | `4` | `alive` |
| `dc03-pf-t428-n048.byted.org` | `fdbd:dc03:f:428::48` | `4` | `alive` |
| `dc03-pf-t428-n049.byted.org` | `fdbd:dc03:f:428::49` | `4` | `alive` |

## Arthas 证据

### leader 环境

`dc03-pf-t400-n048.byted.org` 上的 Pulse agent：

```text
PULSE_AGENT_ID=dc03-pf-t400-n048.byted.org
PULSE_AGENT_IP=fdbd:dc03:f:400::48
PULSE_AGENT_CLUSTER=tlblog_stream_olap_separate
PULSE_AGENT_AREA=lq
PULSE_GROUP_MODE=dynamic
PULSE_GROUP_PORT=9977
PULSE_GROUP_SIZE_LIMIT=7
PULSE_COORDINATOR_URLS=http://[fdbd:dc05:11:634::45]:9966,http://[fdbd:dc05:13:10c::40]:9966,http://[fdbd:dc07:0:810::44]:9966
```

### leader collector

Arthas watch `GroupHeartbeatCollector#record`：

```text
ts=2026-06-01 19:50:53.394 dc03-pf-t402-n048.byted.org seq=3440
ts=2026-06-01 19:50:53.490 dc03-pf-t418-n048.byted.org seq=3440
ts=2026-06-01 19:50:53.778 dc03-pf-t400-n048.byted.org seq=3441
ts=2026-06-01 19:50:54.759 dc03-pf-t406-n048.byted.org seq=3443
ts=2026-06-01 19:50:55.086 dc03-pf-t428-n048.byted.org seq=3441
ts=2026-06-01 19:50:56.029 dc03-pf-t428-n049.byted.org seq=3440
ts=2026-06-01 19:50:56.545 dc03-pf-t41a-n047.byted.org seq=3442
ts=2026-06-01 19:50:58.534 dc03-pf-t418-n048.byted.org seq=3441
```

结论：

- leader 能收到 `dc03-pf-t418-n048` 的 follower heartbeat。
- follower seq 以 5 秒节奏递增。
- `leader 未收到 follower` 这个假设不成立。

### leader batch

Arthas watch `GroupHeartbeatCollector#batch`：

```text
ts=2026-06-01 19:50:18.341 group=tlblog_stream_olap_separate/lq/000 size_limit=7 agents=7
ts=2026-06-01 19:50:23.416 group=tlblog_stream_olap_separate/lq/000 size_limit=7 agents=7
ts=2026-06-01 19:50:28.478 group=tlblog_stream_olap_separate/lq/000 size_limit=7 agents=7
ts=2026-06-01 19:50:33.544 group=tlblog_stream_olap_separate/lq/000 size_limit=7 agents=7
ts=2026-06-01 19:50:38.595 group=tlblog_stream_olap_separate/lq/000 size_limit=7 agents=7
```

结论：

- leader 每轮 batch 都带满 7 个 agent。
- `leader batch 不完整` 这个假设不成立。

### coordinator handleHeartbeat

在 `fdbd:dc05:13:10c::40` 上 Arthas watch `CoordinatorService#handleHeartbeat`，只观察 `tlblog_stream_olap_separate/lq/000`：

```text
ts=2026-06-01 19:57:07.761 group=tlblog_stream_olap_separate/lq/000 request_agents=7 response_agents=7
ts=2026-06-01 19:57:22.941 group=tlblog_stream_olap_separate/lq/000 request_agents=7 response_agents=7
ts=2026-06-01 19:57:38.071 group=tlblog_stream_olap_separate/lq/000 request_agents=7 response_agents=7
```

结论：

- coordinator 能接收到该 group 的 batch。
- coordinator response 也包含 7 个 agent 的 `acceptedSeq`。
- `coordinator 未处理 group batch` 这个假设不成立。

### leader 上报目标

leader systemd journal 显示 batch 以 5 秒节奏轮询 3 个 coordinator：

```text
19:56:57 target=http://[fdbd:dc07:0:810::44]:9966 group=tlblog_stream_olap_separate/lq/000 agents=7
19:57:02 target=http://[fdbd:dc05:11:634::45]:9966 group=tlblog_stream_olap_separate/lq/000 agents=7
19:57:07 target=http://[fdbd:dc05:13:10c::40]:9966 group=tlblog_stream_olap_separate/lq/000 agents=7
19:57:12 target=http://[fdbd:dc07:0:810::44]:9966 group=tlblog_stream_olap_separate/lq/000 agents=7
19:57:17 target=http://[fdbd:dc05:11:634::45]:9966 group=tlblog_stream_olap_separate/lq/000 agents=7
19:57:22 target=http://[fdbd:dc05:13:10c::40]:9966 group=tlblog_stream_olap_separate/lq/000 agents=7
```

结论：

- 发送方没有广播到所有 coordinator，符合设计内核。
- 单个 coordinator 直接接收该 group batch 的间隔约为 `15s`。
- 其余 coordinator 的新状态依赖 `/heartbeat_fwd` lazy 同步。

## 确认数行为

`CoordinatorService.NodeState#recentConfirmations` 的确认数含义是：

- 在最近 `20s` 窗口内，按 `epoch/seq` 去重后的确认数。
- 不是 “coordinator 数量”。
- 也不是 “group 内成员数量”。
- 对 5 秒 heartbeat 来说，稳定状态通常是 `4` 左右。

对 `dc03-pf-t418-n048.byted.org` 做 8 轮、每 5 秒一次的线上采样：

| sample | seq | confirmations | status | observed_at_ms |
| --- | --- | --- | --- | --- |
| `0` | `3523` | `4` | `alive` | `1780315073217/3218` |
| `1` | `3524` | `4` | `alive` | `1780315078265` |
| `2` | `3525` | `4` | `alive` | `1780315083306` |
| `3` | `3526` | `4` | `alive` | `1780315088367` |
| `4` | `3527` | `4` | `alive` | `1780315093415` |
| `5` | `3528` | `4` | `alive` | `1780315098469/8470` |
| `6` | `3529` | `4` | `alive` | `1780315103511` |
| `7` | `3530` | `4` | `alive` | `1780315108563` |

结论：

- 初始看到的 `confirmations=2` 是 transient warming 状态，不是该 group 稳态。
- 稳定后 3 个 coordinator 上该 agent 的 `seq`、`source`、`observed_at_ms` 和 `confirmations` 收敛一致。

## 假设判定

| 假设 | 判定 | 证据 |
| --- | --- | --- |
| leader 未收到部分 follower | 否定 | `GroupHeartbeatCollector#record` 观察到 `dc03-pf-t418-n048` 连续上报 |
| leader batch 不完整 | 否定 | `GroupHeartbeatCollector#batch` 连续 5 次 `agents=7` |
| coordinator 未处理 group batch | 否定 | `CoordinatorService#handleHeartbeat` 观察到 `request_agents=7`、`response_agents=7` |
| group plan 抖动导致该样本确认数低 | 未发现持续证据 | 样本稳定后连续 8 轮 `confirmations=4` |
| lazy sync 导致短时观测差异 | 部分成立 | leader 每 5 秒只发一个 coordinator；单个 coordinator 直接接收间隔约 15 秒，依赖 `/heartbeat_fwd` 最终一致 |

## 当前结论

当前运行时聚合行为符合设计：

- group leader 实际接收 follower heartbeat。
- group leader batch 按 `size_limit=7` 聚合。
- coordinator 接收 batch 并返回每个 agent 的 response。
- agent 没有向所有 coordinator 广播，leader 只轮询其中一个 coordinator。
- coordinator 之间通过 lazy forward 保持最终一致。

确认数“不符合预期”的主要解释是语义误读和 transient 状态：

- UI 中的 `confirmations` 是最近 20 秒内 distinct heartbeat seq 数。
- 5 秒 heartbeat 在稳态下常见值是 `4`，不是固定等于 `3`、`7` 或 coordinator 数量。
- 刚进入 group、刚重启、刚发生 plan 切换、或者 peer lazy sync 尚未完全收敛时，会短暂看到 `1/2/3`。
- 这类节点会显示 `warming`，稳定后恢复到 `alive`。

## 风险与后续建议

- 当前 UI 文案如果只展示数字，容易让人误解为“确认 coordinator 数”或“group 成员数”；建议改名为 `20s确认` 或增加 tooltip。
- 若希望快速区分 transient 和真实异常，可以在 host view 增加 `last_observed_age_ms`、`source`、`group_id`、`leader_agent_id` 等调试字段。
- 若业务希望每个 coordinator 的确认数都不依赖 lazy sync，应违背当前“只发一个 coordinator”的设计；不建议这么改。
- 更合理的后续改造是补充 `/api/groups` 或内部诊断视图，只读展示 group plan、leader、members、last batch size、last direct coordinator。

## 采集产物

临时采集产物保存在 `.tmp/analysis/group-leader-aggregation/`：

- `api-summary.json`
- `arthas-leader-batch.txt`
- `arthas-leader-record.txt`
- `arthas-coordinator-handleheartbeat.txt`
- `leader-journal-heartbeat-targets.txt`
- `target-confirmations-timeseries.jsonl`
