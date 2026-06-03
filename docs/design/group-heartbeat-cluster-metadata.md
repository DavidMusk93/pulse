# Group Heartbeat 与集群元数据设计

## 背景

Pulse coordinator 已支持单机 agent 心跳和批量 `agents[]` group heartbeat。当前新需求要求：

- 在 coordinator 中验证 group heartbeat 功能是否正常。
- 使用 Arthas 作为 Java 线上调试手段，并沉淀部署与使用文档。
- agent 从本机 `tide_worker` 进程环境变量中采集集群元数据。
- 前端展示按集群名称分组。
- 将 auto-ops 中的 `doubao`、`tlbmirror` 集群也接入 agent 列表。
- 后续每个新需求都必须先补充 `docs/design` 与 `docs/plan` 文档，再进入开发、部署、验证。

## 平台定位

Pulse 的定位不是教学展示页，而是一个基于精简消息机制构建的心跳平台。

平台内核：

- 以 heartbeat + `PulseMessage` 作为统一控制面，尽量复用现有消息通道。
- coordinator、group leader、agent 之间优先交换状态消息与命令消息，非必要不增加 API。
- 通过心跳消息承载状态同步、任务分发、任务回执、资源快照和告警信号。

平台能力边界：

- 任务管理：通过消息下发 dry-run 或运维任务，并通过 heartbeat 回收执行状态与结果摘要。
- 集群运维：基于 cluster/area/group 维度编排机器、执行批量运维动作。
- 资源管理：通过 heartbeat 汇聚节点资源信息，为后续调度和容量判断提供输入。
- 资源监控：通过 heartbeat 形成近实时主机、进程、集群视图。
- 告警：通过消息或状态聚合识别异常节点、异常 group、异常资源趋势。

UI 与产品表达必须围绕“心跳平台”展开，描述简洁、专业、中文化，禁止使用课程、校园、实验室、学员等教育化叙事。

表达边界：

- 页面描述保持克制，围绕任务、集群、资源、监控和告警形成想象空间，避免同义反复。
- 可见节点标识只允许使用 IPv6；hostname 只能作为内部协议字段存在，禁止进入 UI 文案、DOM data 属性或任务面板标题。
- UI 必须保持平面表达，禁止高光、发光、模糊、渐变扫光、水波、果冻抖动等持续视觉效果。
- Run UI 必须反馈 agent heartbeat 中的 `async_tasks`，让 `accepted`、`running` 等执行中状态在 completion 返回前可见。

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

因此，静态 group plan 只能作为验证手段，不能作为最终设计。最终分组逻辑必须位于 coordinator 的心跳处理逻辑中，由 coordinator 基于内存中的 `NodeState` 维护 group assignment，并通过 heartbeat response message 下发给 agent。agent 不应该依赖部署脚本生成的静态 CSV 来决定 leader/follower。

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
- 集群机器数小于 5 时不启用 group leader。
- group leader 数量为 `floor(sqrt(num_agents))`。
- 不再使用固定 group size 上限；`PULSE_GROUP_SIZE_LIMIT` 已废弃。
- `cluster=unknown` 的 agent 单独进入 `unknown` 集合，不与已知 cluster 混组。
- `area=unknown` 的 agent 可以在同 cluster 内组成 `unknown` area group。
- 详细算法见 `docs/design/group-leader-location-aware-plan.md`。

### 稳定排序

为了避免频繁换组，分组前对同一 `cluster` 内的 agent 做稳定排序：

```text
sort_key = area + "/" + ipv6_numeric_order(ip) + "/" + agent_id
```

排序规则：

- IPv6 地址可解析时，优先按 IPv6 数值或前缀排序。
- IPv6 缺失或不可解析时，按 `agent_id` 排序。
- 按 cluster 级 `floor(sqrt(num_agents))` 计算 group 数。
- 优先在 area 内连续切片；只有 area 数量多于 leader 数时，才允许位置序列相邻 area 在边界处合并。

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
- 新 host 必须达到 `alive` 后才允许进入 leader/follower 分组，避免刚启动或低确认节点直接扰动 group。
- 已有 group member 不能因为一次 `alive -> warming` 边界抖动立即被踢出 group；只要该 member 尚未 `expired`，coordinator 必须在 group recompute 中保留其 membership，形成 graceful hysteresis。
- `expired` host 不参与新 group，也不享受 membership grace；过期节点必须退出 group，避免 leader 长期等待失联 follower。
- group membership grace 只稳定已有成员，不把 direct warming 节点拉入 group；这保证 group 降压能力稳定，同时避免 stale follower 与 leader `acceptedMembers` 短时间不一致导致 `not_group_member` 循环。
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
  "size_limit": 8
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
  "size_limit": 1
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
| `PULSE_GROUP_MODE` | `dynamic`、`direct`、`leader` 或 `follower` | `dynamic` |

coordinator 可选配置：

| 变量 | 含义 | 默认 |
| --- | --- | --- |
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
  - `cluster` 必须为 `unknown`
  - `area` 为 `unknown`
- 读取 `/proc/$pid/environ` 失败时，按不存在处理。
- host 所属集群以 agent 环境变量 `PULSE_AGENT_CLUSTER` 为准；如果该变量不存在或为空，统一视为 `unknown`。

## Web 分组展示

Web 页面按 `cluster` 进行一级分组：

- 每个 cluster 渲染一个 `cluster-section`。
- 组标题展示 cluster 名称与 host 数。
- 集群 section 必须支持折叠/展开，但首页默认展开，不能把监控主视图默认折成目录树。
- 组内使用 Apple 网站式的克制留白、明确层级与轻量面板，避免“上细下粗”的视觉重心失衡。
- `/hosts` 必须是现代前端应用 shell，禁止使用 `<meta http-equiv="refresh">` 或整页刷新。
- 前端采用 React + Ant Design 作为成熟组件库，替代手写粗糙组件；Ant Design、React 和业务代码必须本地打包为 coordinator 静态资源，禁止运行时依赖公网 CDN。
- `/hosts` 只输出最小 app shell，通过本地 `/assets/pulse-hosts.js` 和 `/assets/pulse-hosts.css` 挂载应用；复杂功能后续通过 Ant Design 的 `Card`、`Statistic`、`Button`、`Select`、`Modal`、`Tabs`、`Badge`、`Progress`、`List`、`Typography` 等组件组合演进。
- React app 每 5s fetch `/api/hosts`，按稳定 IPv6 key 渲染 cluster 和 host；刷新前后必须恢复 viewport scroll，保留页面、CSS、JS runtime 和磁贴内部滚动 cursor。
- 磁贴必须为正方形，使用 `aspect-ratio: 1 / 1` 保持密度一致。
- 每个 cluster 使用不同主色相，便于跨集群快速扫视。
- cluster 调色板必须使用低饱和冷静色，禁止紫色、红色等高刺激亮色。
- 组内 host 按 `load` 从高到低排序。
- 同一 cluster 内，`5min AVG` 越高磁贴色彩越深，并提供底部 load bar；load bar 必须使用深色轨道和深色/cluster 色填充，禁止白底白条。
- 磁贴内容超过可视区域时，必须在磁贴内部滚动，文字使用 `overflow-wrap`，禁止覆盖和溢出。
- 磁贴、状态徽标、指标行、worker 小卡和底部 load bar 等矩形元素必须使用圆润边角，避免硬直方角。
- 磁贴不做额外交互动效，保持自然滚动；禁止持续播放的水波、扫光、果冻抖动或背景动态。
- cluster 只用于 section/group 表达，不在单个 agent 磁贴中重复展示。
- 磁贴不展示 hostname，不展示 `Seq` 和 `Rank`；所有可见节点地址只展示 IPv6。
- 磁贴 header 只展示单行短时间与纯文字任务按钮；取消独立状态点，agent 状态通过 `任务` 按钮背景色表达。
- 磁贴正文展示 `IP`、`Area`、`5min AVG`、`Confirm`。
- 磁贴必须提供轻量只读调试字段，帮助解释 group heartbeat 聚合行为；至少包含 `last_observed_age_ms`、`group_id`、`group_mode`、`leader_agent_id`、`group_size`、`group_size_limit`，用于区分 transient warming、leader 聚合和 lazy sync 收敛。
- 调试字段只用于观测，不参与 agent 获取 plan，也不新增控制 API；group plan 仍只能通过 heartbeat response 的 `cmd.group_plan` 下发。
- `Confirm` 文案应避免被理解为 coordinator 数或 group size，UI 中应使用 `20s确认` 或等价文案。
- `Confirm` 不放在 header，必须放入磁贴正文指标区。
- `Seen` 时间禁止使用浏览器默认 `toLocaleString` 长格式；磁贴内展示 `YYYY/MM/DD HH:mm:ss`，并保持单行不换行。
- 任务按钮只展示文字，不展示播放等图标；按钮背景色承载状态语义，`alive` 使用绿色系，`warming` 使用黄色系，`expired` 使用灰色系。
- 磁贴最小宽度必须足够承载 header 与进程卡片，禁止为了密度把磁贴压缩到导致 header 换行或 pid 信息拥挤的宽度。
- 对 `Coordinator`、磁贴时间、IPv6 等超长单行文本，前端应优先在容器内自适应缩小字号，避免直接溢出、裁切主体信息或把布局撑坏。
- Run UI 中 `trace` 与 `completion` 禁止放在同一可切换结果容器里互相覆盖；`trace` 固定放左侧信息区独立卡片，右侧仅展示 completion 输出。
- `trace` 展示必须包含 `task_id`，并允许在滚动容器内稳定查看与恢复，不因 tab 切换或结果渲染互相污染。
- Run UI 的 `trace` 默认只展示最近 4 条，避免重复 keep/pop 事件把左侧信息区撑满；完整 trace 后续如需扩展必须通过独立详情视图承载。
- `弹出结果` 语义必须是 completion queue 的 head pop：移除当前队头结果，刷新后展示新的队头结果；禁止用 `keep` 伪装弹出，也禁止按 task_id 删除队列中任意位置的结果。
- Run UI 的“结果查看”标题区与下方格式化/拷贝工具栏之间必须保持 Apple 风格的松弛留白，避免标题、分隔线和按钮挤在一起形成压迫感。
- 顶部指标卡（如 `5min AVG`、`Coordinator`、`刷新`）必须在同一行保持统一高度与内容基线；超长值只允许缩小字号，不能把单张卡片高度撑高破坏整排对齐。
- 顶部指标卡数值在默认状态下必须共享同一字号、行高和基线；`Coordinator` 这类使用自适应文本的卡片只有在真实发生 overflow 时才允许缩小字号，禁止在空间充足时因独立字号策略造成视觉不对齐。
- 集群折叠状态必须在前端本地持久化，页面自动刷新或重新打开后应恢复用户上一次的展开/折叠选择。
- 含异常节点的集群不能在默认逻辑里被自动隐藏；如果集群中存在 `warming`、`expired`、失败任务或执行中任务，前端必须强制保持展开或自动重新展开，保证异常一眼可见。
- tide worker 列表必须以轻量进程卡片展示，避免表格硬塞；至少包含 `pid`、总 `cpu_percent`、`user_cpu_percent`、`sys_cpu_percent`、`rss_kb`、`mem_percent`、`threads`、`port1`、`component_version` 中可用字段，并通过磁贴内部滚动承载更多行。
- 卡片内不展示瞬时 `Load`，避免 5s 轮询导致数值抖动和频繁重排。
- `5min AVG` 由前端在本地固定窗口计算；每个 5 分钟窗口开始时只采样计算一次，窗口内禁止继续累计或更新展示值，避免 5s 轮询造成视觉抖动。
- `Area` 和 `Zone` 语义重复时只展示 `Area`，不展示 `Zone`。
- 磁贴不展示 `Role` 和 `Source`。
- agent 侧从 `/proc/$pid/stat` 计算 `user_cpu_percent` 与 `sys_cpu_percent`，从 `/proc/$pid/status` 读取 `VmRSS`、`Threads`，继续保留 `/proc/$pid/environ` 中的 `PORT1`、`TIDELET_COMPONENT_VERSION` 等环境变量字段。
- 磁贴展示每个 `tide_worker` 的 `pid`、`cpu_percent`、`user_cpu_percent`、`sys_cpu_percent`、`rss_kb`、`mem_percent`、`threads`、`PORT1`、`TIDELET_COMPONENT_VERSION`；`pid` 变化用于判断进程重启。
- Run UI 使用 Ant Design `Modal`、`Card`、`Select`、`Button`、`Tabs`、`List` 等组件，页面宽高与主页面留白使用黄金分割思路控制，避免占满视口或形成狭窄弹窗。
- Run UI 左侧信息区与右侧 completion 展示区按 `1 : 1.618` 分栏，completion 承担主要阅读空间。
- 任务按钮必须水平排列，禁止按钮文字竖排或因空间挤压折行。
- Run UI 不再展示“执行任务”标题，由执行按钮承担语义；任务类型选择控件必须展开为操作栏主区域，避免左侧任务框被压缩。
- Run UI 关闭控件参考 macOS 窗口控制点，放在独立标题栏内，禁止绝对定位覆盖业务 UI。
- 任务执行中状态必须从当前 host 的 `state.async_tasks` 读取，并在 agent 已接收或正在执行时在结果区顶部用显著提示条展示。
- Run UI 的 completion 输出必须展示，不允许空白吞掉 JSON；当输出是 JSON 时必须提供一键格式化、拷贝和基础高亮能力，结果区域必须是内部滚动容器，禁止大 JSON 撑破 modal 或页面。
- `prepare_disk_layout_dry_run` 的 agent 端脚本来源为 olap-toolbox 的 `tidelet/prepare-disk-layout.sh`；更新时必须先同步到 Pulse 仓库 `docs/task/prepare-disk-layout.sh`，再分发到所有 agent 的 `/data24/otf/pulse/tasks/prepare-disk-layout.sh`。
- `analyze_block_layout_dry_run` 的 agent 端脚本只保留一个实现：`docs/task/analyze-block-layout.py`；更新时从 olap-toolbox 的 `tidelet/analyze-block-layout.py` 同步，不再维护 `analyze-block-layout-py35.py`。
- `repair_corrupt_sqlite3_dry_run` 的 agent 端脚本来源为 olap-toolbox 的 `tidelet/repair-corrupt-sqlite3.sh`；默认参数仍为 `--dry-run`，修复所需端口等参数必须通过隐藏参数输入显式传入。
- Pulse 任务类型保持 dry-run 后缀作为安全语义边界；非 dry-run 操作只能由用户显式传参触发，agent 侧继续做任务身份、脚本路径与参数安全校验。

## 长任务流式输出

长任务流式输出、`reply.task_output_append` 消息定义、group 聚合发送状态机、completion/stream viewer 展示规则、agent 串行执行和多用户队列语义，统一维护在独立设计文档 `docs/design/task-output-streaming.md`。

本文件只保留 group heartbeat、集群元数据和 Web 分组展示的基础约束，避免把不同设计主题继续写入同一个不断膨胀的文档。

UI 开发门禁：

- 禁止整页刷新作为数据更新手段。
- 禁止为 UI 引入必须访问公网 CDN 的依赖。
- 禁止回退到手写大型 HTML/CSS 组件；新增复杂交互优先用 Ant Design 组件表达，再通过少量 CSS token 做 Apple 式留白和视觉重心调整。
- Ant Design 构建产物必须随 jar 一起发布，coordinator 通过 classpath 静态资源服务 `/assets/*`。
- `/api/hosts` 是 UI 数据源，`/hosts` 只负责前端 app shell。
- 自动刷新必须是 JSON 增量数据流的客户端更新，不能重新下载整页 HTML，也不能整块重建 `#pulse-app`。
- 自动刷新必须通过 keyed DOM 复用保留每个磁贴内部 `.tile-scroll` 的 scrollTop/scrollLeft。
- 自动刷新必须保留页面级 `window.scrollX/scrollY`，禁止刷新后回到页面顶部。
- 磁贴必须保证 `.tile-scroll` 为真实可滚动容器，内容高度超过可视区时可滚动，header 操作区不得挤占滚动内容。
- UI 调色板不得使用刺眼亮色，尤其避免紫色和红色作为 cluster 主色。
- load 比例条必须在浅色和深色磁贴上都具备可读对比度。
- 磁贴动效不得抢占视觉焦点，当前禁止 `jelly-scroll`、`water-ripple`、`liquid-flow` 一类额外动效。
- 禁止使用 `box-shadow`、`backdrop-filter`、`linear-gradient`、`radial-gradient` 或其他高光/发光/模糊表达。
- 所有面对用户的 UI 文案必须使用简洁中文，禁止课程化、营销化和英文 slogan。
- 中文排版参考 Apple 官网的克制表达：减少负字距，增加留白，标题避免过密、过重、过长。
- 所有可见节点标识必须经过 IPv6-only 归一化，hostname、FQDN 和内部域名不得出现在页面文本或 DOM 标识里。
- `5min AVG` 的排序与展示必须使用同一份固定窗口开窗采样结果，禁止窗口内每次轮询更新样本、均值或排序权重。
- Run UI 和磁贴 UI 的最终验收必须在 coordinator 线上页面完成，使用真实浏览器确认 header 不换行、无独立状态点、任务按钮承载状态色、pid 卡片可读、completion JSON 可展示/格式化/拷贝、高亮、agent 执行中状态、IPv6-only 和无高光样式。
- 远程任务脚本更新必须验证本地 `bash -n`、`py_compile` 或 `--help`、SHA 一致性，并通过 auto-ops dry-run 确认全量 agent 范围后再分发；分发脚本不得重启 agent，除非本次同时更新 agent jar 或 systemd 配置。

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
