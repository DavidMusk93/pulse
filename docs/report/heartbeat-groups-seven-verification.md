# Heartbeat Groups 7 验证报告

## 摘要

- 验证时间：2026-05-30
- 目标：将 heartbeat group size 从 13 调整为 7，并按规划开发、部署、验证。
- 结论：符合预期。
- 核心结果：3 个 coordinator 均显示 63 台 host alive，`direct=0`，全部 host 都通过 group heartbeat 上报，group 数为 11，最大 group size 为 7。

## 设计与计划

- 设计文档：`docs/design/group-heartbeat-cluster-metadata.md`
- 执行计划：`docs/plan/group-heartbeat-cluster-metadata-plan.md`

已更新内容：

- group size 从 13 改为 7。
- 请求量估算从 `ceil(N / 13)` 改为 `ceil(N / 7)`。
- 分组实现范围确定为部署侧静态分组 + agent leader/follower 模式。

## 本地验证

已执行：

```bash
bash -n docs/script/pulse-cdn-new-deploy.sh docs/script/pulse-cdn-new-verify.sh
docs/script/pulse-generate-group-plan.py --cluster cdn_new --group-size 7
mvn test
mvn package
```

结果：

- `mvn test`：10 个测试全部通过。
- `mvn package`：构建成功。
- group plan 生成脚本：确认每 7 台切组，首台为 leader，其余为 follower。

## 实现内容

### Agent 模式

`PulseAgentApp` 支持三种模式：

| 模式 | 行为 |
| --- | --- |
| `direct` | 单机直接向 coordinator 上报 `/heartbeat` |
| `leader` | 启动本地 `/group/heartbeat`，收集 follower 心跳，并向 coordinator 批量上报 `agents[]` |
| `follower` | 不直接上报 coordinator，只向 leader 的 `/group/heartbeat` 上报本机心跳 |

### Group Collector

`GroupHeartbeatCollector` 行为：

- 保存 group 内每个 `agent_id` 的最新 heartbeat。
- 按 `epoch + seq` 选择新状态。
- leader 自身 heartbeat 始终进入 batch。
- 每次 batch 最多包含 7 个 agent。

### 静态 Group Plan

新增脚本：

```bash
docs/script/pulse-generate-group-plan.py
```

输出 CSV：

```text
host,group_id,group_mode,leader_url,group_members
```

部署脚本会将 plan 写入 agent 环境变量：

- `PULSE_GROUP_ID`
- `PULSE_GROUP_MODE`
- `PULSE_GROUP_LEADER_URL`
- `PULSE_GROUP_MEMBERS`
- `PULSE_GROUP_SIZE_LIMIT=7`
- `PULSE_GROUP_PORT=9977`

## Group Plan 结果

| 集群 | Agent 数 | Group 数 | Leader 数 | Follower 数 |
| --- | ---: | ---: | ---: | ---: |
| `cdn_new` | 50 | 8 | 8 | 42 |
| `doubao` | 8 | 2 | 2 | 6 |
| `tlbmirror` | 5 | 1 | 1 | 4 |
| 合计 | 63 | 11 | 11 | 52 |

预期 coordinator heartbeat 请求数：

```text
每轮请求数 ~= 11
原始 direct 请求数 = 63
降幅 ~= 82.5%
```

## 部署结果

### 部署

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

说明：

- 一次部署命令最终退出码为 1，原因是本地 `tee` 目标目录最初不存在。
- auto-ops 部署本身完成，三个集群均有 `summary ok=total failed=0`。
- 后续已创建目录并完成 verify。

### Verify

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

verify 观察：

- agent 均为 `active`。
- `PULSE_GROUP_SIZE_LIMIT=7` 已写入。
- leader/follower 模式已写入。
- Java 11 机器的 systemd `ExecStart` 使用 bundled JRE：`/data24/otf/pulse/jre/bin/java`。

## Coordinator 验证

访问方式：

```bash
curl -g -sS --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/api/hosts"
```

三台 coordinator 结果一致：

| Coordinator | Total | Alive | Direct | Group Source Count | Grouped Hosts |
| --- | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 11 | 63 |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 11 | 63 |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 11 | 63 |

source 分布：

| Source | Host Count |
| --- | ---: |
| `cdn_new/unknown/000` | 7 |
| `cdn_new/unknown/001` | 7 |
| `cdn_new/unknown/002` | 7 |
| `cdn_new/unknown/003` | 7 |
| `cdn_new/unknown/004` | 7 |
| `cdn_new/unknown/005` | 7 |
| `cdn_new/unknown/006` | 7 |
| `cdn_new/unknown/007` | 1 |
| `doubao/unknown/000` | 7 |
| `doubao/unknown/001` | 1 |
| `tlbmirror/unknown/000` | 5 |

## 判断

分组功能已经从“协议可用”推进到“线上 agent 实际按 group 批量上报”：

- coordinator 不再看到 `direct` heartbeat。
- 所有 host 的 `source` 都是 group id。
- 最大 group size 为 7。
- `cdn_new`、`doubao`、`tlbmirror` 都已纳入 group heartbeat。
- host 可观测性未丢失，coordinator 仍按单个 `agent_id` 保存状态。

## 当前限制

- 静态 group plan 当前按 auto-ops tag 生成，`group_id` 的 area 使用 `unknown`。
- host state 仍包含 tide 上报的真实 `cluster` 和 `area`，Web/API 展示不受影响。
- 若后续希望 group id 也体现 tide area，需要先批量采集 tide metadata，再按 `(cluster, area)` 生成 plan。
- 当前 leader/follower 是静态模式，leader 故障时需要重新生成 plan 或引入 coordinator group plan。

## 后续建议

- 增加 `/api/groups` 展示 group、leader、members、batch size、last seen。
- leader 上报 batch 时增加 `group_size`、`leader_agent_id` 指标。
- 部署前增加端口 `9977` 连通性检查。
- 下一阶段将 group plan 从 `cluster/unknown/shard` 升级为 `cluster/area/shard`。
