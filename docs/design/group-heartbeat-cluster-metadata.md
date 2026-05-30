# Group Heartbeat 与集群元数据设计

## 背景

Pulse coordinator 已支持单机 agent 心跳和批量 `agents[]` group heartbeat。当前新需求要求：

- 在 coordinator 中验证 group heartbeat 功能是否正常。
- 使用 Arthas 作为 Java 线上调试手段，并沉淀部署与使用文档。
- agent 从本机 `tide_worker` 进程环境变量中采集集群元数据。
- 前端展示按集群名称分组。
- 将 auto-ops 中的 `doubao`、`tlbmirror` 集群也接入 agent 列表。
- 后续每个新需求都必须先补充 `docs/design` 与 `docs/plan` 文档，再进入开发、部署、验证。

## 目标

- Group heartbeat 协议可用：一次请求可上报多个 agent，coordinator 返回每个 agent 的 `accepted_seq`。
- Host 视图具备集群维度：`cluster`、`area` 作为一等字段返回给 API 与 Web。
- Web 页面按 `cluster` 分组展示 host 磁贴。
- 部署脚本自动采集 `tide_worker` 环境变量：
  - `_TIDELET_AREA` 映射为 `PULSE_AGENT_AREA`
  - `_TIDELET_CLUSTER_ID` 映射为 `PULSE_AGENT_CLUSTER`
- 如果 `tide_worker` 不存在或变量为空：
  - `cluster` 使用部署参数兜底，最终兜底为 `unknown`
  - `area` 兜底为 `unknown`
- `cdn_new`、`doubao`、`tlbmirror` agent 共用同一组三台 coordinator。
- Java 运行环境必须满足 Java 17+，低版本系统 Java 自动切换到 `/data24/otf/pulse/jre/bin/java`。

## 非目标

- 不在本阶段实现 coordinator 间强一致复制。
- 不在本阶段实现长期持久化存储。
- 不在本阶段把 Arthas 做成常驻 systemd 服务。
- 当前已上线版本不包含减压型 agent 分组编排，只完成了 group heartbeat 协议验证和 Web 展示分组。

## 当前分组现状

当前系统里“分组”有两层含义，需要明确区分：

| 分组类型 | 当前状态 | 目的 | 是否能降低 coordinator 压力 |
| --- | --- | --- | --- |
| Web 展示分组 | 已实现 | 按 `cluster` 展示 host 磁贴 | 否 |
| Group heartbeat 协议 | 已实现并验证 | 单次 `/heartbeat` 接收多个 `agents[]` | 仅协议具备能力，线上 agent 尚未使用 |
| Agent 减压分组策略 | 需要迁移到 coordinator | 将同集群 agent 编排为小组，批量上报心跳 | 是 |

因此，静态 group plan 只能作为验证手段，不能作为最终设计。最终分组逻辑必须位于 coordinator 的心跳处理逻辑中，由 coordinator 基于内存中的 `NodeState` 维护 group assignment，并通过 API 下发给 agent。agent 不应该依赖部署脚本生成的静态 CSV 来决定 leader/follower。

## 分组目标

分组的核心目标不是 UI 展示，而是降低 coordinator 处理心跳的请求放大：

- 将同一集群内的 agent 切分为多个 heartbeat group。
- 每个 group 至多包含 7 个 agent。
- 每个 group 内只由一个 group leader 向 coordinator 批量上报 `agents[]`。
- follower agent 将本机 heartbeat state 交给 group leader，由 leader 聚合后批量提交。
- coordinator 从每台机器一个请求，收敛为每个 group 一个请求。
- coordinator 继续以单个 `agent_id` 为状态维度保存 NodeState，避免牺牲单机可观测性。

### 规模估算

假设一个集群有 `N` 台 agent，group size 上限为 `7`：

```text
group_count = ceil(N / 7)
coordinator_requests_per_interval = group_count
```

示例：

| Agent 数 | 不分组请求数 | 分组后请求数 | 降幅 |
| ---: | ---: | ---: | ---: |
| 50 | 50 | 8 | 84% |
| 500 | 500 | 72 | 85.6% |
| 5000 | 5000 | 715 | 85.7% |

## 分组策略设计

### 分组输入

coordinator 或部署侧进行分组时使用以下输入：

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `cluster` | `_TIDELET_CLUSTER_ID` 或部署参数 | 一级分组边界，不跨 cluster 组队 |
| `area` | `_TIDELET_AREA` | 二级分组边界，优先同 area 组队 |
| `ip` | agent 心跳或部署探测 | 同 area 内排序与稳定分片 |
| `agent_id` | hostname 或配置 | 稳定排序兜底 |

### 分组边界

分组必须遵守：

- 不跨 `cluster`。
- 优先不跨 `area`。
- 单组 agent 数不超过 `7`。
- `cluster=unknown` 的 agent 单独进入 `unknown` 集合，不与已知 cluster 混组。
- `area=unknown` 的 agent 可以在同 cluster 内组成 `unknown` area group。

### 稳定排序

为了避免频繁换组，分组前对同一 `(cluster, area)` 内的 agent 做稳定排序：

```text
sort_key = normalized_ipv6_prefix(ip) + "/" + agent_id
```

排序规则：

- IPv6 地址可解析时，优先按 IPv6 数值或前缀排序。
- IPv6 缺失或不可解析时，按 `agent_id` 排序。
- 排序后每 7 台切一个 group。

### Group ID

Group ID 必须稳定、可读、可复现：

```text
group_id = cluster + "/" + area + "/" + shard_index
```

示例：

```text
cdn2/yg/000
cdn2/yg/001
doubao/hl/000
tlbmirror2/lf/000
unknown/unknown/000
```

如果后续需要避免同名冲突，可增加短 hash：

```text
group_id = cluster + "/" + area + "/" + shard_index + "-" + hash(agent_ids)
```

### Leader 选择

每个 group 选择一个 leader：

```text
leader = group 内排序后的第一个 alive agent
```

Leader 职责：

- 收集本组 follower 的 heartbeat state。
- 向 coordinator 发起 group heartbeat。
- 请求 payload 使用 `group_id` 和 `agents[]`。

Follower 职责：

- 生成本机 heartbeat state。
- 将 state 交给 leader。
- 如果 leader 不可用，等待下一轮 leader 选举结果。

### Leader 故障处理

Leader 故障时：

- group 内剩余 alive agent 按相同排序规则选出新 leader。
- 新 leader 使用同一个 `group_id` 上报。
- 每个 agent 仍保留自己的 `epoch` 和 `seq`，coordinator 按单 agent 维度做新旧状态判断。

### Batch 写入语义

coordinator 收到 group heartbeat 后：

- 对请求中的每个 agent 独立执行 `epoch + seq` 新旧判断。
- 响应中返回每个 agent 的 `accepted_seq`。
- `source` 记录为 `group_id`。
- 任何一个 agent 的旧状态不影响同请求内其他 agent 的新状态写入。

## Coordinator 内部分组编排

分组编排必须由 coordinator 完成，并维护在 coordinator 的数据结构中：

```text
NodeState(agent_id) -> HostView -> GroupAssignment
```

核心数据结构：

| 数据结构 | 维护者 | 说明 |
| --- | --- | --- |
| `states: agent_id -> NodeState` | coordinator | 已有心跳状态表 |
| `groupPlans: agent_id -> AgentGroupPlan` | coordinator | 每个 agent 当前应执行的 group plan |
| `groupViews: group_id -> GroupView` | coordinator | 每个 group 的 leader、members、cluster、area |

更新时机：

- 每次 `handleHeartbeat` 合并心跳后，coordinator 基于最新 `states` 重新计算 group assignment。
- 每次 `handleForward` 合并 peer state 后，也重新计算 group assignment。
- 只使用 `alive` host 参与 leader/follower 分组。
- `expired` host 不参与新 group，但仍保留在 host 视图中。

下发方式：

- 非必要不新增 API。
- coordinator 在现有 `/heartbeat` 响应中返回 `cmd.group_plan` 消息。
- 单机 heartbeat 使用 `HeartbeatResponse.messages[]` 下发当前 agent 的 group plan。
- group heartbeat 使用 `HeartbeatResponse.agents[].messages[]` 分别下发每个 agent 的 group plan。
- group leader 需要监听本地端口，接收 follower heartbeat，并把 coordinator 返回给 follower 的 `cmd.group_plan` 转发给 follower。

`cmd.group_plan` payload 示例：

```json
{
  "agent_id": "dc05-p11-t636-n012.byted.org",
  "group_id": "cdn2/yg/000",
  "group_mode": "follower",
  "leader_agent_id": "dc05-p11-t636-n010.byted.org",
  "leader_url": "http://[fdbd:dc05:11:636::10]:9977",
  "members": ["dc05-p11-t636-n010.byted.org", "dc05-p11-t636-n012.byted.org"],
  "cluster": "cdn2",
  "area": "yg",
  "size_limit": 7
}
```

没有 plan 时，coordinator 在 heartbeat response 中返回 direct plan：

```json
{
  "agent_id": "new-agent",
  "group_id": "direct",
  "group_mode": "direct",
  "leader_agent_id": "new-agent",
  "leader_url": "",
  "members": ["new-agent"],
  "cluster": "unknown",
  "area": "unknown",
  "size_limit": 7
}
```

agent 行为：

- agent 默认以 `dynamic` 模式启动。
- agent 从 heartbeat response 中解析 `cmd.group_plan`。
- `direct`：直接向 coordinator 上报单机 heartbeat。
- `leader`：启动本地 `/group/heartbeat`，聚合 follower state，并向 coordinator 批量上报。
- `follower`：将本机 heartbeat 上报给 leader，不再直接打 coordinator。
- follower 从 leader 的 `/group/heartbeat` 响应中获取 leader 转发的 `cmd.group_plan`。
- 非 leader 节点虽然保持本地 receiver 进程，但必须拒绝 follower `/group/heartbeat`，避免旧 leader 缓存的 plan 继续传播。
- leader 只能接受当前 `cmd.group_plan.members` 内的 follower；非成员必须被拒绝，让 stale follower 回退 coordinator 获取新 plan。
- plan 缺失或 leader 上报失败时，agent 可短暂 fallback 到 direct，避免 host 完全失联。

coordinator 一致性约束：

- 当前阶段不实现 coordinator 间强一致复制。
- agent 或 group leader 每轮 heartbeat 只写入一个 coordinator。
- coordinator 收到 `/heartbeat` 并合并本地状态后，通过 `/heartbeat_fwd` 将 `state.*` lazy 同步给 peers。
- `/heartbeat_fwd` 只传播状态，不传播 `cmd.group_plan` 或其他控制指令。
- follower 不直接打 coordinator，因此 coordinator 侧请求数从 `N` 收敛为 `group_count`，peer 同步由 coordinator 内部承担。
- 默认 heartbeat interval 为 5s，TTL 为 30s，允许 peer lazy 同步、网络抖动和部署重启的最终一致窗口。

废弃项：

- `docs/script/pulse-generate-group-plan.py` 仅保留为历史验证工具，不再作为生产部署路径。
- 部署脚本不再传入静态 group CSV。
- `PULSE_GROUP_MODE=leader/follower` 不再由部署脚本静态写死，默认使用 `dynamic`。

## 推荐新增配置

agent 环境变量：

| 变量 | 含义 | 示例 |
| --- | --- | --- |
| `PULSE_GROUP_ID` | 当前 agent 所属 group | `cdn2/yg/000` |
| `PULSE_GROUP_LEADER` | 当前 group leader agent id | `dc05-p11-t636-n012.byted.org` |
| `PULSE_GROUP_MEMBERS` | 当前 group 成员列表 | `agent-a,agent-b` |
| `PULSE_GROUP_SIZE_LIMIT` | group size 上限 | `7` |
| `PULSE_GROUP_MODE` | `dynamic`、`direct`、`leader` 或 `follower` | `dynamic` |

coordinator 可选配置：

| 变量 | 含义 | 默认 |
| --- | --- | --- |
| `PULSE_GROUP_SIZE_LIMIT` | 分组大小上限 | `7` |
| `PULSE_GROUP_STRATEGY` | 分组策略 | `cluster_area_ipv6` |

## 后续实现建议

### 阶段 A：Coordinator Group Plan

- coordinator 在 `handleHeartbeat` 后基于 `states` 计算 `groupPlans` 和 `groupViews`。
- coordinator 通过 heartbeat response message 下发 `cmd.group_plan`。
- agent 默认以 `dynamic` 模式从心跳响应中更新 plan。

### 阶段 B：Agent Leader 模式

- agent 启动后读取 coordinator plan。
- leader 周期性向本机或轻量 HTTP endpoint 收集 follower state。
- leader 调用 `/heartbeat`，使用 `agents[]` 批量上报。
- follower 不再直接向 coordinator 上报，除非 leader 长时间不可用。

### 阶段 C：可观测性与重平衡

- coordinator 新增 group 健康指标：
  - group alive count
  - leader freshness
  - last batch heartbeat time
  - batch size

## 当前状态

已完成：

- 静态 group assignment 已废弃为生产路径，仅保留为历史验证和回滚工具。
- group assignment 已迁移到 coordinator 的 `handleHeartbeat` 状态维护流程。
- coordinator 已通过 heartbeat response message 下发 `cmd.group_plan`。
- agent 已默认通过 coordinator plan 动态切换 `direct`、`leader`、`follower`。
- group leader 已监听 `/group/heartbeat` 并转发 follower 的 `cmd.group_plan`。

仍需验证和增强：

- 线上多 coordinator 场景需要确认所有 coordinator 都能稳定看到完整 alive host。
- coordinator 需要持续通过 `/heartbeat_fwd` lazy 同步 peer 状态，agent/group 不承担 peer fanout。
- 后续可补充 group 健康指标和 Web 只读展示，但不作为 agent 获取 plan 的接口。

这些缺口不影响已验证的 group heartbeat 服务端协议，但会影响“通过分组降低 coordinator heartbeat read/write 压力”这一最终目标。

## 开发门禁

这些规则是实现层硬约束，任何需求开发、修复和测试都必须先检查：

- agent 和 group leader 每轮 heartbeat 只能写一个 coordinator，禁止向所有 coordinator 广播 `/heartbeat`。
- coordinator 间状态收敛只能通过 `/heartbeat_fwd`，禁止把 peer 同步责任下沉到 agent 或 group。
- `/heartbeat_fwd` 只能转发 `state.*`，禁止转发 `cmd.*`、`reply.*` 和 group plan。
- group 只负责接收 follower `/heartbeat`、聚合 `agents[]`、转发 response message，禁止调用 `/heartbeat_fwd`。
- group plan 获取只能通过 `/heartbeat` response 中的 `cmd.group_plan`，禁止新增 agent plan API。
- 新增 API 前必须证明不能通过 `PulseMessage` 表达，否则拒绝新增。
- 多 coordinator 验证必须覆盖“单点写入一个 coordinator，其他 coordinator 通过 `/heartbeat_fwd` 最终收敛”。
- 单元测试必须覆盖上述不变量，至少包含 agent 不 fanout、coordinator forward state-only、非 leader 拒绝 follower heartbeat。

## 协议设计

### 单机心跳

单机心跳保持现有格式：

```json
{
  "agent_id": "host-a",
  "epoch": 1,
  "seq": 42,
  "ttl_ms": 15000,
  "messages": [
    {
      "message_id": "msg-host-a-42",
      "type": "state.heartbeat",
      "version": 1,
      "payload": {
        "host": "host-a",
        "ip": "fdbd::1",
        "cluster": "cdn2",
        "area": "yg",
        "zone": "yg",
        "role": "cdn_new",
        "load": "0.42"
      }
    }
  ]
}
```

### Group Heartbeat

Group heartbeat 使用同一个 `/heartbeat` endpoint，通过 `agents[]` 判定为批量请求：

```json
{
  "group_id": "manual-group-verify",
  "agents": [
    {
      "agent_id": "agent-1",
      "epoch": 1,
      "seq": 101,
      "ttl_ms": 15000,
      "messages": []
    }
  ]
}
```

响应包含每个 agent 的 accepted sequence：

```json
{
  "ok": true,
  "coordinator_id": "coordinator-a",
  "accepted_seq": null,
  "messages": [],
  "agents": [
    {
      "agent_id": "agent-1",
      "accepted_seq": 101,
      "messages": []
    }
  ]
}
```

## 状态合并

- coordinator 以内存态保存 `agent_id -> NodeState`。
- 对同一个 `agent_id`：
  - 更高 `epoch` 覆盖旧状态。
  - 相同 `epoch` 下更高 `seq` 覆盖旧状态。
  - 旧 `epoch` 或旧 `seq` 不覆盖新状态。
- group heartbeat 的 `source` 为 `group_id`，为空时为 `group`。
- 单机 heartbeat 的 `source` 为 `direct`。

## 集群元数据

### 采集来源

部署脚本在远端查找 `tide_worker`：

```bash
pgrep -f tide_worker | head -n 1
```

读取 `/proc/$pid/environ`：

- `_TIDELET_AREA`
- `_TIDELET_CLUSTER_ID`

### 映射关系

| tide_worker 变量 | Pulse 环境变量 | HostView 字段 |
| --- | --- | --- |
| `_TIDELET_CLUSTER_ID` | `PULSE_AGENT_CLUSTER` | `cluster` |
| `_TIDELET_AREA` | `PULSE_AGENT_AREA` | `area` |
| `_TIDELET_AREA` | `PULSE_AGENT_ZONE` | `zone` |
| 部署参数 | `PULSE_AGENT_ROLE` | `role` |

### 兜底策略

- `tide_worker` 不存在时：
  - `cluster` 使用部署参数，例如 `doubao` 或 `tlbmirror`
  - 如果部署参数为空，则为 `unknown`
  - `area` 为 `unknown`
- 读取 `/proc/$pid/environ` 失败时，按不存在处理。

## Web 分组展示

Web 页面按 `cluster` 进行一级分组：

- 每个 cluster 渲染一个 `cluster-section`。
- 组标题展示 cluster 名称与 host 数。
- 组内使用 `ui-ux-pro-max-skill` 推荐的 Flat Design + Real-Time Monitoring 风格，避免高阴影和厚重拟物。
- 磁贴必须为正方形，使用 `aspect-ratio: 1 / 1` 保持密度一致。
- 每个 cluster 使用不同主色相，便于跨集群快速扫视。
- 组内 host 按 `load` 从高到低排序。
- 同一 cluster 内，`load` 越高磁贴色彩越深，并提供底部 load bar。
- 磁贴内容超过可视区域时，必须在磁贴内部滚动，文字使用 `overflow-wrap`，禁止覆盖和溢出。
- 滑动和 hover 使用轻量流水高光动效；必须遵守 `prefers-reduced-motion`，用户关闭动效时不播放动画。
- 磁贴展示 `IP`、`Area`、`Role`、`Zone`、`Load`、`Seq`、`Source`、`Seen`。

## 部署设计

### 机器范围

- coordinator：仍使用 `cdn_new` 中选出的三台 IPv6 机器。
- agent：`cdn_new`、`doubao`、`tlbmirror` 全部目标机器。
- 安装目录：`/data24/otf/pulse`。
- coordinator 监听：IPv6 `::`，端口 `9966`。

### Java 版本

- Pulse 编译目标为 Java 17。
- 部署脚本先检测可用 `java` 主版本。
- 如果系统 Java 小于 17，则拒绝使用，并解压随包 JRE 到 `/data24/otf/pulse/jre`。

### systemd

- agent：`pulse-agent.service`
- coordinator：`pulse-coordinator.service`
- 每次部署后必须显式 `restart`，确保新 jar 与新环境变量生效。

## Arthas 调试设计

- Arthas boot jar 放置在 `/data24/otf/pulse/tools/arthas/arthas-boot.jar`。
- 只作为临时诊断工具，不常驻。
- 推荐观测点：
  - `CoordinatorService#handleHeartbeat`
  - `CoordinatorService#hosts`
  - `AgentHeartbeatFactory#nextHeartbeat`

## 验证标准

- 本地 `mvn test` 全部通过。
- 三组部署验证 `ok=total failed=0`。
- coordinator `/api/hosts` 返回 host 数等于 agent 数加临时验证 agent 数。
- `/api/hosts` 顶层字段包含 `cluster` 与 `area`。
- `/hosts` 页面包含 `cluster-section`，并展示 `cdn2`、`doubao`、`tlbmirror` 或对应 tide cluster。
- group heartbeat 响应包含 `agents[]` 和每个 agent 的 `accepted_seq`。

## 风险

- coordinator 仍是内存态，重启后需要等待 agent 下一轮心跳恢复视图。
- 部分机器 `tide_worker` 变量可能与 auto-ops tag 不完全一致，例如 `tlbmirror2`。
- 远端验证脚本打印的 `JAVA_BIN` 可能来自系统 PATH，不一定等于 systemd 实际 `ExecStart`，最终以 systemd status 为准。
