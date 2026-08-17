# Pulse Participant Protocol 与 Rust Agent 设计

## 结论

Pulse 不应把现有 Java Agent 直接翻译为 Rust，也不应要求所有接入对象都伪装成
Host Agent。下一阶段先把当前实现中稳定的控制面语义提炼为
`Pulse Participant Protocol`，再基于该协议建设两种一等参与者：

- `External Observer`：独立运行的 Rust Agent，绑定并观测进程外目标，负责
  host、process、disk 等基础设施采集和受控任务执行。
- `Embedded Participant`：嵌入业务服务进程的 Pulse 子模块，由宿主主动发布自身
  状态、事件和控制能力，生命周期与宿主服务一致。

两者共享 identity、capability、state、event、command、reply 和 session
语义，但不共享采集模型。Host group heartbeat 是 External Observer 的可选传输
优化，不是 Embedded Participant 的接入前提。

这意味着 Pulse 的平台边界从“host 心跳系统”扩展为“基础设施与业务服务共同参与的
内部状态、事件和受控操作平台”。扩展后的核心仍是精简消息协调，不演变为通用 RPC、
服务网格或任意代码执行平台。

默认接入仍采用 participant 主动连接的 outbound session。Embedded Participant
不因加入 Pulse 而强制开放公网或集群入站端口；需要即时控制、快照或调试时，再按
部署条件选择 local bridge、受保护 callback 或 cluster gateway。

## 背景

当前 Pulse Agent 同时承担以下职责：

- 采集 host、process、disk 和 `tide_worker` 状态；
- 通过 `/heartbeat` 发布 `state.*` 和 `event.publish`；
- 从 heartbeat response 接收 `cmd.*`；
- 执行远程任务并通过 `reply.*` 返回结果；
- 在 leader/follower/direct 模式间切换并聚合 group heartbeat；
- 维护重试、幂等、流式输出、spool 和 backpressure。

这些能力中有两类内容被耦合在一个 Java 进程里：

1. 与目标类型无关的协议运行时；
2. 仅适用于 host 外部观测的采集器和 group 行为。

容器服务的状态通常存在于服务内部，例如请求队列、连接池、分片、缓存、业务任务和
降级状态。外部 Agent 无法完整、低成本地推导这些状态，因此容器服务需要将 Pulse
作为子模块嵌入，而不是继续增加进程探测规则。

## 设计目标

- 定义与编程语言无关的 Participant Protocol。
- 保持现有 heartbeat 和 `PulseMessage` 语义可兼容演进。
- 用 Rust 重建轻量、资源可控的 External Observer runtime。
- 为容器化业务提供可嵌入的 SDK/module 接入面。
- 允许 participant 在不开放端口时完成状态、事件和异步控制交互。
- 将“是否开放端口”建模为 capability 和 transport 选择，而不是接入资格。
- 保留 task 无损输出、幂等、重试、spool 和 backpressure 等已验证约束。
- 使 Coordinator 能按 participant kind 和 resource kind 展示、路由和授权。

## 非目标

- 本设计不规定某个具体业务服务应发布哪些业务指标。
- 本设计不立即替换 Coordinator，也不要求 Coordinator 使用 Rust。
- 本设计不把 Java `ServiceLoader` SPI 定义为跨语言协议。
- 本设计不要求 Embedded Participant 支持 host group leader/follower。
- 本设计不允许 Coordinator 默认主动扫描所有容器实例端口。
- 本设计不在第一阶段提供任意远程 shell、任意 RPC 或通用代码执行。
- 本设计阶段不创建 Rust crate、不修改 Java Agent、不执行生产部署。

## 第一性原则

### 参与者不是观测目标

`participant` 是连接 Pulse 控制面的运行实体；`resource` 是被其描述或控制的目标。
两者必须分离：

```text
External Observer participant
  -> observes host/process/disk resources

Embedded Participant
  -> describes its own service/replica/shard resources
```

当前 `agent_id` 同时承担连接身份和 host 身份。协议演进后，Coordinator 内部必须
分别使用 `participant_id` 与 `resource_id`，兼容层可以暂时令两者相同。

### 语义与传输分离

`state.*`、`event.publish`、`cmd.*` 和 `reply.*` 是消息语义；heartbeat、local
bridge、callback 和 gateway 是传输方式。任何业务能力都不能依赖某个传输的私有
字段才能成立。

### Push 是基线，Gather 是可选优化

所有 participant 至少应具备 outbound delivery path 并发布状态。该路径可以由
participant 直接建立，也可以委托给本地 sidecar；委托不改变 participant 身份和
消息语义。Coordinator 主动 gather 只适用于具备明确网络、鉴权和服务发现条件的
场景，不能成为容器接入的默认假设。

### 能力必须声明，不能按类型猜测

Coordinator 只能下发 participant 已声明且策略允许的命令。`embedded_service`
不等于可执行任务，`external_agent` 也不等于允许任意 shell。

### 有界资源与可恢复传输

运行时中的队列、缓存和并发都必须有界。可靠消息在发送失败时进入持久 spool；
spool 达到上限后必须 backpressure 或显式失败，禁止静默丢弃和无限占用宿主内存。

## 参与者模型

### Participant Kind

| Kind | 运行位置 | 典型所有者 | 默认传输 | 是否默认开放端口 |
| --- | --- | --- | --- | --- |
| `external_agent` | 独立进程 | 基础设施平台 | outbound heartbeat | 否 |
| `embedded_service` | 业务服务进程内 | 业务服务 | outbound heartbeat/event stream | 否 |

`sidecar` 和 `gateway` 是 deployment/transport role，不是 participant kind，
不替代业务服务内部的语义埋点，也不拥有被代理 participant 的 resource。Phase 3
前不把它们固化为新的身份类型。
当业务状态只能由进程内部获知时，仍由 Embedded Participant 产生状态和事件，
sidecar 只负责转发、持久化和控制桥接。

### Participant Descriptor

participant 在 session 建立时提交 descriptor：

```json
{
  "protocol_version": "1.0",
  "participant_id": "payment/checkout/replica-7f8d",
  "participant_kind": "embedded_service",
  "instance_id": "0194cb55-8f6e-7fb0-ae10-22f50cd69f33",
  "descriptor_generation": 1,
  "runtime": {
    "language": "rust",
    "sdk_version": "0.1.0"
  },
  "location": {
    "cluster": "prod-a",
    "namespace": "payment",
    "zone": "az-1"
  },
  "capabilities": [
    {"name": "publish_state", "version": 1},
    {"name": "publish_event", "version": 1},
    {"name": "accept_command", "version": 1, "commands": ["cmd.snapshot"]}
  ],
  "transports": [
    {"mode": "outbound_heartbeat", "priority": 0}
  ]
}
```

约束：

- `participant_id` 在一个逻辑部署域内稳定，不能使用短生命周期连接 ID。
- `instance_id` 是每次冷启动生成的 opaque UUID，用于区分旧实例的迟到消息；
  不按大小排序，也不依赖 wall clock。
- `descriptor_generation` 位于 descriptor 内，在同一 `instance_id` 内从 `1`
  单调递增。冷启动后从 `1` 重新开始，Coordinator 以
  `(participant_id, instance_id, descriptor_generation)` 比较 descriptor。
- Coordinator 在 heartbeat ack 中返回 `accepted_descriptor_generation`；
  participant 在收到该确认前必须重发最新 descriptor。
- capability 参数必须结构化，不能使用自由文本表示权限。
- Coordinator 保存 observed descriptor，但最终授权由服务端 policy 决定。

## Resource 模型

一个 participant 可以发布多个 resource：

```text
Resource {
  resource_id: string
  resource_kind: string
  owner_participant_id: string
  parent_resource_id: string?
  labels: map<string, string>
  attributes: map<string, typed value>
}
```

首批标准 `resource_kind`：

- `host`
- `process`
- `disk`
- `service`
- `replica`
- `shard`
- `worker`
- `queue`

标准 kind 只定义通用身份和关系，不穷举业务字段。业务扩展字段位于带 namespace 的
state schema 中，例如 `payment.checkout.v1`，避免把 Coordinator 核心模型扩展为
无边界字段集合。

resource 生命周期独立于 participant session：

- 创建或更新通过 descriptor 的 `resources[]` 发布，并受
  `descriptor_generation` 保护；
- 正常退出使用 `protocol.resource_withdraw`，携带 `resource_id`、generation
  和 reason，Coordinator ack 后生成 tombstone。withdraw 还必须携带当前
  `instance_id`、`session_generation` 和 `descriptor_generation`；Coordinator
  只接受当前 active session 且 generation 不小于 resource 最近 descriptor 的请求，
  迟到旧实例不能退出新实例已重新声明的 resource；
- participant 非正常失联时，resource 先进入 stale，再在 session lease 过期后的
  retention window 到期时退出当前视图；
- tombstone retention 必须长于可靠消息最大重试窗口，避免迟到 state 复活已退出
  resource。

## Capability 模型

### 基线能力

| Capability | 含义 |
| --- | --- |
| `publish_state` | V1 发布 resource 完整 snapshot |
| `publish_event` | 发布离散事件和 incident 转移 |
| `accept_command` | 通过已建立 session 接收 allowlist 命令 |
| `serve_snapshot` | 响应即时快照请求 |
| `serve_debug` | 暴露受限调试能力 |
| `execute_task` | 执行预注册 task，并返回无损结果 |
| `aggregate_participants` | 聚合其他 participant 的 session/message |

`accept_command` 只表示运行时具备下行通道。每个命令还必须出现在 descriptor 的
command allowlist 中，并通过 Coordinator policy、租户边界和操作者权限校验。

### 能力协商

Coordinator 在 session response 中返回：

```text
accepted_protocol_version
accepted_capabilities[]
rejected_capabilities[{name, reason}]
policy_generation
session_generation
session_lease_ms
messages[]
```

participant 只能启用双方接受的能力。未知 capability 必须忽略并记录，不能导致
基础 heartbeat 失败；未知 command 必须返回结构化 `reply.command_rejected`。

## 消息协议

### 通用 Envelope

现有 `PulseMessage` 演进为语言无关 envelope：

```text
Message {
  message_id: string
  type: string
  version: uint32
  participant_id: string
  instance_id: string
  origin_instance_id: string?
  session_generation: uint64
  resource_id: string?
  observed_at_ms: int64
  sequence: uint64?
  reply_to: string?
  trace_id: string?
  deadline_ms: int64?
  idempotency_key: string?
  payload: typed object
}
```

规则：

- `participant_id + message_id` 构成全生命周期消息去重基础；message ID 必须在同一
  participant 下跨重启唯一。
- 正常消息省略 `origin_instance_id`；重放旧 spool 时 `instance_id` 使用当前实例，
  `origin_instance_id` 保留最初产生消息的实例，仅用于审计和恢复诊断。
- `session_generation` 由 participant 在同一 `instance_id` 内单调递增：初始为
  `1`，每次切换 Coordinator session owner 时加一。Coordinator 只能在当前
  heartbeat response 中回显该值；participant 拒绝其他 generation 的 command。
- state 使用 per-resource `sequence`，不能用网络到达顺序覆盖更新的状态。
- command 必须携带 `deadline_ms` 和 `idempotency_key`。
- reply 必须携带 `reply_to`；长任务继续使用稳定 `task_id` 和 `trace_id`。
- payload schema 由 `type + version` 唯一确定。
- 未知可选字段忽略；未知 type 不执行，并返回可观测的拒绝原因。

### 消息分类

| Prefix | 方向 | 语义 |
| --- | --- | --- |
| `state.*` | participant -> Coordinator | 可覆盖的当前状态 |
| `event.publish` | participant -> Coordinator | 不可覆盖的事件转移 |
| `cmd.*` | Coordinator -> participant | 受控命令 |
| `reply.*` | participant -> Coordinator | command 接收、进度和结果 |
| `protocol.*` | 双向 | descriptor、lease、ack 和错误 |

现有 `state.heartbeat`、`event.publish`、`cmd.group_plan`、`cmd.task_execute`、
`reply.task_accepted`、`reply.task_output_append` 和 `reply.task_result` 保持语义。
新协议先扩充 envelope，不重命名稳定消息类型。

### State 与 Event

state 表示“现在是什么”，允许新 sequence 覆盖旧值；event 表示“发生了什么”，必须
按 event/incident 幂等处理。

Embedded Participant 不应把所有高频指标塞入 heartbeat。第一阶段采用：

- heartbeat 携带 health、resource summary、capability 和轻量状态；
- 重要状态变化可立即触发 heartbeat；
- `event.publish` 对 firing/resolved 等转移使用 urgent flush；
- 高频时序指标使用有界 batch，并在后续定义独立 metric payload schema。

V1 的 `state.*` payload 必须是单个 `resource_id + schema` 的完整 snapshot。
Coordinator 只用该 resource/schema 的 `state_sequence` 覆盖旧 snapshot。V1 不支持
独立字段 patch；未来增量必须使用新的 payload version，并携带明确
`base_sequence` 和 merge 规则，避免乱序字段更新被错误丢弃。

### Command 与 Reply

command 分为三档：

1. `read_only`：快照、配置查询、诊断摘要；
2. `bounded_mutation`：reload、flush、受控开关；
3. `task_execution`：预注册任务执行。

第一阶段 Embedded Participant 默认只允许 `read_only`。任何 mutation 或 task 都
必须由宿主显式注册 handler，并声明：

- command type 和 schema version；
- 并发上限与队列上限；
- deadline 和 cancellation 行为；
- 幂等策略；
- 资源影响等级；
- 输出大小和编码上限。

handler 禁止阻塞宿主请求线程或 runtime 核心调度线程。

command 还必须携带 Coordinator 校验时的 `policy_generation`、operator identity
和 `session_generation`。handler 开始执行前重新校验 generation、deadline 与当前
授权；策略已撤销、session 已被 fencing 或 deadline 已过期时返回拒绝，不执行排队
命令。授权撤销不强杀已开始的 mutation；需要中断的 command 必须显式定义
cancellation handler。

`deadline_ms` 使用 Coordinator wall clock 表达绝对截止时间。participant 在收到
command 时按本地 wall clock 校验，并以配置的最大时钟偏差预算折算为本地 monotonic
deadline；超过偏差预算时拒绝 mutation/task command 并报告 `clock_skew`，不能猜测
剩余执行时间。

## Session 与传输

### Outbound Heartbeat

这是所有 participant 的基线 outbound delivery mode：

```text
participant or delegated sidecar
  -> POST /heartbeat: descriptor delta + state/event/reply batch
  <- response: ack + lease + cmd batch
```

Embedded Participant 可以直接持有 session，也可以通过 Level 1 sidecar 委托
transport：

- direct 模式由 participant 持有 session、lease、ack 和 spool；
- delegated 模式由 sidecar 持有网络连接、lease transport 和 spool，但
  participant 仍拥有 message identity、resource state 和 command handler；
- sidecar 必须把 per-participant ack 和 command 完整返回，不能用 aggregate HTTP
  success 替代；
- sidecar 不可达时 embedded module 必须报告本地 degraded；是否回退 direct 由部署
  配置决定，不能临时创建未授权外联。

优点：

- participant 无需开放端口；
- 复用现有 Coordinator 部署和消息处理；
- NAT、容器网络和多 Coordinator 环境下行为明确；
- command、reply 和状态在一个 lease/session 中闭环。

限制：

- command 延迟受 heartbeat interval 影响；
- 高频消息会增加 HTTP 请求或 payload 压力；
- 大量实例需要 batch、jitter、连接复用或 gateway 降压。

因此第一阶段继续使用 heartbeat，但 Rust runtime 必须支持 keep-alive、随机抖动、
payload budget、urgent flush 和服务端 backoff。

### Outbound Event Stream

当 heartbeat 无法承载高频事件时，可在同一身份和鉴权体系下建立 participant 主动
发起的长连接。它是传输优化，不改变消息 envelope，也不能成为状态权威的第二套
协议。断连后可靠消息回到本地 spool，并通过恢复后的 stream 或 heartbeat 重发。

第一阶段不实现该模式，只保留协议边界。

### Inbound Callback

只有 participant 声明 `inbound_callback` 且 Coordinator policy 接受时才启用。
适用于低延迟快照或控制，但必须具备：

- mTLS workload identity；
- 网络策略和明确 allowlist；
- callback address 的 lease 和短 TTL；
- request nonce、deadline、重放保护；
- outbound heartbeat fallback；
- callback 不可达时不影响状态上报。

Coordinator 不得因为 descriptor 中存在地址就直接访问；地址必须通过服务发现或
gateway 验证，禁止信任 participant 自报的任意 URL。

### Local Sidecar Bridge

Embedded Participant 可通过 loopback 或 Unix Domain Socket 与本地 sidecar 通信：

```text
service process
  -> Pulse embedded module
  -> UDS/loopback bridge
  -> Pulse sidecar/node runtime
  -> Coordinator
```

该模式适合：

- 宿主不希望携带网络、TLS、spool 依赖；
- 多语言 SDK 只实现轻量本地协议；
- sidecar 统一完成认证、批量、重试和限流；
- callback 只需暴露在 Pod/host 本地。

bridge 不能替宿主推导内部业务状态，也不能改变 message identity。sidecar 转发时
保留原 `participant_id`，自身身份只进入 transport metadata。每一跳必须认证：
embedded module 与 sidecar 通过 Pod/host 本地 workload identity 绑定，sidecar
向 Coordinator 使用代理身份并携带可验证的 participant delegation。Coordinator
同时校验代理身份、delegation scope 和原 participant identity；代理不得扩大原
participant 的 resource scope 或 capability。

### Cluster Gateway

大规模集群可由 gateway 承担连接汇聚和 callback 路由。gateway 类似传输代理：

- 不成为 resource state 权威；
- 不重写业务 payload；
- 不合并不同 participant 的 sequence；
- 必须逐 participant 返回 ack 和 command；
- 必须有 per-participant quota，避免单实例拖垮共享通道。
- 作为受信任 transport terminator 时，必须使用短期、可撤销 delegation；否则必须
  透传 participant 端到端签名。Phase 3 实施计划必须二选一并完成 threat model，
  不能仅凭 payload 中的 `participant_id` 代理身份。

### 端口等级

| Level | 暴露方式 | 适用场景 | 安全要求 |
| --- | --- | --- | --- |
| 0 | 无入站端口 | 默认 heartbeat/event push | outbound TLS |
| 1 | loopback/UDS | embedded + sidecar | 文件权限或本地身份 |
| 2 | service callback port | 低延迟控制/快照 | mTLS、ACL、NetworkPolicy |
| 3 | cluster gateway | 大规模统一接入 | workload identity、租户隔离、配额 |

Participant Protocol 必须完整支持 Level 0。Level 1-3 只增加下行及时性和汇聚能力，
不能改变基础状态语义。

## Embedded Participant API

嵌入式 SDK 应提供最小、非侵入接口，而不是复制 External Agent 的采集循环：

```text
PulseModule::start(config, identity)
PulseModule::register_resource(descriptor)
PulseModule::publish_state(resource_id, schema, value)
PulseModule::publish_event(event)
PulseModule::register_command(command_type, handler)
PulseModule::update_health(health)
PulseModule::shutdown(deadline)
```

可靠 publish 返回显式结果：

```text
accepted(message_id)
retryable(reason)
rejected(reason)
```

只有 `accepted` 后消息所有权才转移给 runtime。`retryable` 时所有权仍在 producer，
handler 必须按声明的有界重试或降级策略处理；宿主关键线程禁止无限阻塞。

运行时约束：

- 默认后台线程/任务数量固定且可配置；
- 不持有宿主业务对象的无界引用；
- publish 快路径只进入 bounded queue，不执行网络 IO；
- 队列满时按消息语义处理：state 可合并为最新值，event/reply 必须 spool 或报错；
- SDK panic/exception 不能终止宿主进程；
- shutdown 在 deadline 内尽力 flush，未确认可靠消息保留到持久 spool；
- SDK 必须公开自身 queue depth、spool bytes、last success 和 dropped-state-coalesced
  等自观测指标。

state 合并只允许同一 `resource_id + schema` 的旧完整 snapshot 被新 sequence
覆盖。event、reply 和 task output 禁止合并或丢弃。

runtime descriptor 必须声明 `delivery_durability`：

- `process`：只保证当前进程生命周期内重试；
- `restart`：同一实例存储可用时跨进程重启恢复；
- `reschedule`：持久卷或 sidecar durable spool 可跨容器重建恢复。

需要无损 event/reply/stream 的 capability 必须要求 `restart` 或更高等级。无持久卷
的 Embedded Participant 应委托给 durable sidecar；无法满足时 Coordinator 拒绝
对应可靠 capability，不能把 `process` 级能力描述为跨重启可靠。

## Rust External Agent 架构

Rust Agent 按职责拆分，避免形成新的单体：

```text
pulse-protocol
  envelope, schema, versioning, validation

pulse-runtime
  session, retry, ack, lease, spool, backpressure, capability registry

pulse-observer
  host/process/disk collectors and target binding

pulse-command
  allowlist dispatch, task lifecycle, stream output

pulse-group
  optional leader/follower aggregation

pulse-agent
  configuration and process lifecycle
```

### Runtime 不变量

- 所有网络和磁盘操作异步且有 deadline。
- 所有内存 channel 有显式容量。
- reliable queue 使用 write-ahead spool，写入成功后才能确认上游接收。
- ack 以 batch 中的 message identity 为粒度，不能只依赖 HTTP 200。
- 重试使用 bounded exponential backoff + jitter。
- `session_generation` 变化后，旧 generation 的 command 不得执行。
- task output 顺序、hash 和无损约束保持与现有设计一致。
- capability registry 只调度显式注册的 handler。
- collector failure 降级为对应 resource 的错误状态，不终止 session。

### 不直接翻译 Java 实现

Java Agent 中的以下内容先提炼行为测试，再在 Rust 中重建：

- heartbeat request/response 编解码；
- direct/leader/follower 状态机；
- command 去重和 task 生命周期；
- pending reply 重试；
- task stream 顺序和 spool/backpressure；
- Event Source 配置 generation；
- shutdown 与进程重启恢复。

Java 类结构、线程模型和集合类型不是兼容契约。尤其不能把当前无界
`acceptedTasks` 或 `receivedFiles` 持有方式带入 Rust runtime。

## Group Heartbeat 的边界

group heartbeat 解决大量 External Agent 的 Coordinator 请求放大问题，但它依赖：

- participant 之间可达；
- leader 监听本地端口；
- 同 cluster/area 的稳定分组；
- leader 转发 per-agent ack 和 command。

这些假设不适用于一般 Embedded Participant。协议规定：

- `aggregate_participants` 是独立 capability；
- 只有声明该 capability 的 external agent 才接收 `cmd.group_plan`；
- embedded service 默认不参与 host group assignment；
- gateway transport 可以复用 `agents[]` batch envelope，但不能接收 host group
  identity 或伪装为 group leader；
- Coordinator 的 group planner 必须按 participant kind 和 capability 过滤。

## 与现有协议的兼容

### 兼容映射

| 当前字段/行为 | Participant Protocol |
| --- | --- |
| `agent_id` | 暂映射为 `participant_id`，host resource 可使用同值 |
| `epoch` | legacy adapter 生成 `instance_id=legacy:{epoch}`，并保留旧 epoch 排序 |
| heartbeat `seq` | session batch sequence；resource state 另有 sequence |
| `PulseMessage` | 通用 Message envelope |
| `state.heartbeat` | host resource state schema |
| `agents[]` | transport batch，不定义 participant kind |
| `cmd.group_plan` | 仅 `aggregate_participants` capability |
| Agent plugin SPI | 单语言进程内扩展，不作为跨语言协议 |

### Coordinator 兼容层

第一阶段 Coordinator 同时接受：

1. legacy heartbeat；
2. 带 `protocol_version` 和 descriptor 的 participant heartbeat。

legacy 请求进入兼容适配器后生成：

```text
participant_kind = external_agent
participant_id = agent_id
instance_id = "legacy:" + epoch
resource_kind = host
resource_id = agent_id
capabilities = inferred legacy capability set
```

“推断 capability”只存在于 legacy adapter。新协议请求必须显式声明，Coordinator
不得长期维护按 participant kind 猜测能力的第二套逻辑。

### `/heartbeat_fwd`

`/heartbeat_fwd` 继续只负责 Coordinator peer 状态和事件收敛：

- 允许转发 `state.*` 与 `event.publish`；
- 禁止转发 `cmd.*`、`reply.*` 和 session lease；
- peer 不因收到 forwarded state 而成为 participant session owner；
- command 始终由持有当前 session/lease 的 Coordinator 下发。
- participant 切换 session owner 时递增 `session_generation`；每条 command 都
  回显该 generation，participant 拒绝旧 owner 的 command。该 fencing 不要求
  Coordinator 之间强一致。

## 可靠性与背压

消息按可靠性分类：

| 类别 | 示例 | 本地策略 |
| --- | --- | --- |
| 可合并状态 | health、queue depth、load | 保留最新 sequence |
| 可靠事件 | firing/resolved | spool，直到 ack |
| 可靠 reply | accepted/result | spool，直到 ack |
| 无损 stream | task output | 有序 spool + backpressure |
| descriptor | capability/resource 变化 | generation 合并并重发 |

ack 必须表达：

- `accepted_batch_seq`：仅确认当前 session 的 transport batch；
- `accepted_descriptor_generation`：仅确认当前 instance 的 descriptor；
- reliable message 的 accepted/rejected message IDs；
- 可选 `reliable_message_watermark` 必须按
  `(participant_id, instance_id, reliable_stream)` 分区，禁止用于 resource state；
- rejection reason；
- 当前 lease、`session_generation` 和 `policy_generation`。

HTTP 成功只证明传输完成，不证明每条消息被接受。

新 session 可以重放 durable spool 中由旧 `instance_id` 产生的 event/reply/stream。
Coordinator 按原 message identity 去重并接受这些 outbound reliable messages，
但旧 instance 永远不能接收或执行 command。重放 envelope 保留原
`origin_instance_id`，同时使用当前 `instance_id` 和 `session_generation` 证明由
当前 session 上送。

当 runtime 过载时按以下顺序处理：

1. 合并旧 state；
2. 延迟非紧急 state flush；
3. 将 event/reply/stream 写入 spool；
4. 对 command handler 和 task output 施加 backpressure；
5. spool 达到硬上限后拒绝新 command，并发布明确 degraded 状态。

禁止丢弃 event/reply/stream 后继续报告 healthy。
新可靠消息无法进入满 spool 时，publish 返回 `retryable(spool_full)`，所有权仍在
producer；producer 按其 capability 声明的有界重试或降级策略处理，不能假装已被
Pulse 接收。

## 安全模型

- 每个 participant 使用 workload identity，不共享静态全局 token。
- identity 与允许的 `participant_id`、namespace、resource kind 绑定。
- capability descriptor 是请求，不是授权。
- Coordinator policy 决定可接受 capability、command 和 resource scope。
- 所有 command 记录 operator、trace、deadline、参数 hash 和结果。
- Embedded handler 默认 deny；只有显式注册和服务端授权后可调用。
- callback/gateway 使用 mTLS，禁止信任自报 URL。
- payload 和 descriptor 有严格大小、深度、字段数和字符串长度限制。
- secret 不进入 state、event、trace、debug snapshot、reply 或 task output。
- command handler 必须声明输出 schema 和敏感字段策略；未分类的自由文本输出默认
  不允许持久化，受控 task 的完整输出需经过显式数据分类与访问授权。
- spool 视为敏感数据存储：文件权限 owner-only，设置 retention 和确认后安全删除
  规则；包含 reply/task output 的记录必须静态加密，密钥不与 spool 同目录保存。
- protocol parser 必须支持 fuzz、未知字段和恶意 payload 验证。

## 可观测性

Coordinator 按 participant 暴露：

- session state、lease age、last heartbeat；
- protocol/runtime version；
- participant kind 和 accepted capabilities；
- message accepted/rejected/retried 数；
- heartbeat bytes、latency 和 error；
- resource count 和 stale resource count；
- callback/gateway transport health。

participant runtime 至少暴露：

- queue depth/limit；
- spool bytes/limit；
- oldest unacked age；
- last successful session time；
- retry/backoff state；
- command running/queued/rejected；
- state coalesced count；
- reliable message loss count，正常值必须恒为 `0`。

## 版本演进

- protocol 使用 `major.minor`。
- major 不兼容；Coordinator 可并行支持有限个 major。
- minor 只能增加可选字段、消息 type 或 capability。
- 每个 payload 仍使用独立整数 `version`，避免协议 minor 与业务 schema 耦合。
- descriptor 协商失败时返回支持版本范围，不能静默按错误语义解析。
- Rust 与 Java runtime 必须共享 golden JSON fixtures 和兼容矩阵。

## 分阶段迁移

### Phase 0：固化现有行为

- 为 legacy heartbeat、group heartbeat、task、stream 和 EventBus 建立 golden fixtures。
- 明确消息 ack、重试和 legacy epoch 行为。
- 给现有无界集合补充容量和生命周期设计，避免迁移期间继续累积风险。
- 选择一个真实容器服务做最小 Embedded spike，只验证稳定 identity、完整 state
  snapshot、event、shutdown、队列饱和和 Level 0 outbound，不承诺 SDK API 稳定。

完成条件：现有 Java Agent 行为可以通过协议 fixture 和端到端测试描述，而不是依赖
Java 类结构；真实 embedded spike 未发现推翻 Participant/Resource 分离和 Level 0
模型的宿主约束。

### Phase 1：Coordinator Participant 兼容层

- 引入 participant descriptor、kind、resource 和 capability 数据模型。
- legacy heartbeat 通过 adapter 映射为 `external_agent + host resource`。
- 新旧请求共用同一个消息路由和状态存储入口。
- UI 暂保持 host 视图，新增 participant API 但不强行改版。

完成条件：legacy Agent 行为不变，测试可使用 synthetic embedded participant 发布
service state 和 event。

### Phase 2A：Rust External Agent 对等实现

- 实现 `pulse-protocol`、`pulse-runtime` 和基础 collectors。
- 先支持 direct heartbeat，再支持 task/event，最后迁移 group。
- Java 与 Rust Agent 在同一 Coordinator 下做 shadow/canary 对比。
- 对比状态 payload、命令结果、网络流量、RSS、CPU 和故障恢复。

完成条件：Rust Agent 覆盖现有生产能力，协议行为一致，资源目标达标，且具备逐 host
回滚到 Java Agent 的路径。

### Phase 2B：Embedded SDK 试点

- 选择一个容器服务，只接入 health、service/replica state 和 event。
- 默认 Level 0 outbound heartbeat，不开放端口。
- command handler 仅开放只读 snapshot。
- 验证宿主启动、优雅退出、SDK 故障隔离和 backpressure。
- 绑定一个具体运维流程，例如“服务 owner 在实例异常时通过内部 queue/worker state
  区分业务拥塞与 host 故障”，并记录相比仅有 host 观测减少的诊断步骤或时间。

完成条件：SDK 故障不影响宿主服务，Coordinator 能区分 host 与 service resource，
无需外部进程推导业务状态，并证明至少一个 service-owner 诊断流程获得可观察改进。

Phase 2A 与 2B 在 Phase 1 稳定后可并行，只共享协议 fixtures 和 Coordinator
兼容层，不要求 Embedded 试点等待 Rust External Agent 完成全能力对等。

### Phase 3：Bridge 与 Gateway

- 按规模和语言覆盖需求引入 UDS sidecar bridge。
- 只有低延迟控制存在明确收益时才引入 callback/gateway。
- gateway 上线前完成 workload identity、quota、隔离和故障域验证。

完成条件：传输优化不改变 message/resource 语义，断开 gateway 后 participant 能回退
到受支持的 outbound 路径或明确 degraded。

## 验证策略

### 协议测试

- Java/Rust golden fixture 双向解析。
- 未知 optional field、未知 message type 和版本拒绝。
- instance 切换、session fencing、迟到消息和 sequence 乱序。
- capability accept/reject 和未授权 command。
- batch 部分接受、部分拒绝和精确 ack。

### 可靠性测试

- Coordinator 不可达时 spool 增长且无可靠消息丢失。
- 进程重启后 event/reply/stream 恢复发送。
- state 在过载时正确合并，不覆盖新 sequence。
- spool 满时 command 被拒绝并进入 degraded。
- task output 保持顺序、字节数和 SHA-256。

### Embedded 故障隔离

- 网络超时不阻塞宿主请求线程。
- SDK panic/exception 不终止宿主。
- Coordinator 慢响应不造成宿主内存无界增长。
- shutdown deadline 到达后宿主可以退出。
- disabled SDK 不改变宿主业务语义。
- 无持久卷且无 durable sidecar 时，可靠 capability 协商被拒绝。
- durable sidecar 重启后按原 message identity 恢复重放。

### 规模测试

- 大量 Level 0 participant heartbeat jitter 后无同步请求尖峰。
- gateway/sidecar 能按 participant 隔离 quota。
- 一个 participant 的大 payload 或持续重试不阻塞其他 participant。
- Coordinator 可按 participant/resource 维度定位 stale、reject 和 backpressure。

## 必须保持的不变量

- Coordinator 是 command queue 和 task completion 的权威。
- participant 默认 outbound-only。
- `/heartbeat_fwd` 不传播 command、reply 或 lease。
- group/sidecar/gateway 是转发层，不是业务 state 权威。
- command 只调用显式 capability 和 allowlist handler。
- task output 端到端无损。
- reliable event/reply/stream 不因 payload budget 被截断或丢弃。
- 所有队列和并发有界。
- Embedded Participant 不被要求伪装成 Host Agent。
- 没有入站端口的 participant 仍是一等参与者。
- session owner 通过单调 `session_generation` fencing，旧 owner 的 command 被拒绝。

## 待后续决策

以下问题应在 Phase 0/1 的实现计划中通过原型和测试确定，不阻塞本设计方向：

- wire encoding 第一阶段仅 JSON，还是同时定义 Protobuf。
- reliable ack 在 message ID 列表与分区 watermark 间的具体压缩策略。
- participant/resource API 与现有 `/api/hosts` 的长期关系。
- Embedded SDK 首个语言和宿主 runtime。
- persistent spool 的文件格式、配额、密钥提供方和 retention 数值。
- workload identity 对接现有基础设施的具体实现。
- 高频 metrics 是否继续走 Participant Protocol，或拆分独立 data plane。

## 相关设计

- [`remote-task-execution.md`](remote-task-execution.md)
- [`task-output-streaming.md`](task-output-streaming.md)
- [`metrics-eventbus.md`](metrics-eventbus.md)
- [`group-heartbeat-cluster-metadata.md`](group-heartbeat-cluster-metadata.md)
