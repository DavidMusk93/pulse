# Pulse Task 流式输出设计

## 结论

长时间运行的任务可能持续产生连续日志，Pulse 需要把运行中输出作为 task state 的一部分上报和展示。第一版不强区分 stdout 和 stderr，agent 侧合并为单一 `output` 流后上报。该能力仍然只使用 heartbeat / group heartbeat 的 `PulseMessage` 链路，不新增 API、不引入 WebSocket/SSE、不增加 agent 入站接口。

核心约束：

- 运行中输出通过 `reply.task_output_append` 上报。
- 最终结果仍通过 `reply.task_result` 或 `reply.task_result_chunk` 进入 completion queue。
- stream chunk 不进入 completion queue。
- group follower 的 stream chunk 先到 leader，再由 leader 的 group heartbeat 聚合上报 coordinator。
- coordinator 继续作为 task queue、stream log、completion queue 和 trace 的权威。
- `/api/agents/{agent}/tasks` 可以扩展只读 snapshot 字段承载 stream log metadata 和完整输出视图，这不是新增 API。

## 消息类型

### `reply.task_output_append`

运行中增量输出消息：

```json
{
  "message_id": "stream-agent-a-task-1-42",
  "type": "reply.task_output_append",
  "version": 1,
  "reply_to": "cmd-task-task-1",
  "payload": {
    "task_id": "task-1",
    "agent_id": "agent-a",
    "task_type": "analyze_block_layout_dry_run",
    "stream_id": "output",
    "stream_seq": 42,
    "stream_offset": 65536,
    "output_encoding": "identity",
    "output_type": "text",
    "payload": "scan disk /data00 ...\n",
    "payload_sha256": "..."
  }
}
```

字段语义：

| 字段 | 说明 |
| --- | --- |
| `task_id` | 当前任务 ID |
| `stream_id` | 第一版固定为 `output`；仅作为未来多 stream 扩展和幂等 key 字段 |
| `stream_seq` | 单 task 的 `output` 流内单调递增序号，用于去重和乱序处理 |
| `stream_offset` | 该 chunk 在 stream 中的字节偏移，便于校验顺序和定位缺口 |
| `output_encoding` | `identity`、`base64` 或后续压缩编码；非 UTF-8 内容必须使用 `base64` |
| `output_type` | `text`、`jsonl`、`binary` 等展示提示，不参与协议路由 |
| `payload` | 当前增量内容 |
| `payload_sha256` | 当前 payload 校验值 |

第一版字段约束：

- `task_id` 是任务生命周期唯一关联 ID，用于串联下发、accepted/running、stream log、completion 和 pop。
- `reply.task_output_append` 禁止引入无业务语义的重复关联字段。
- 如果未来接入外部观测系统，新增观测字段只能作为可选元信息，不能参与路由、幂等、队列或 UI 主展示。

## Agent 任务执行规则

### 串行化

- 第一版 agent task runner 必须 per-agent 串行执行，并发度固定为 `1`。
- 多个用户对同一 agent 发起 task 时，只能进入该 agent 的 coordinator execution queue，不能在 agent 上并发执行。
- agent 本地最多只有一个 `running` task，其余 task 保持 `queued/delivered/accepted` 状态。
- 后续如引入 `PULSE_TASK_MAX_CONCURRENCY`，必须先补资源隔离和 UI 并发展示设计，不能直接放开。

### 多用户队列语义

- coordinator 是多用户入队仲裁点，按每个 agent 独立 FIFO 排队。
- `RemoteTask.created_by` 必须保留，第一版可使用 `pulse-ui`，后续接入真实用户身份。
- Run UI 必须展示队列位置、task type、status、created_by、created_at 和 task_id，避免多用户误以为任务丢失。
- 同一用户或不同用户重复提交同类 dry-run task 默认允许排队，但必须受 per-agent queue 上限保护。
- 第一版建议每个 agent pending queue 上限为 `20`，超过后拒绝新任务并提示队列已满。
- completion queue 是 agent 级共享结果队列；用户点击 `pop` 会弹出队头结果，因此 UI 必须明确“弹出会影响当前 agent 的共享结果队列”。
- 第一版不做优先级和抢占；紧急消息只影响心跳发送时机，不改变 task 执行顺序。

### 输出采集

- 第一版 agent 必须把 stderr 合并到 stdout，形成单一 `output` 流，避免 UI、buffer 和 completion 逻辑围绕 stdout/stderr 分叉。
- 合并输出仍能通过日志自身 tag 区分错误，Pulse 不在协议层重复建模日志级别。
- agent 将合并输出按大小或时间切片形成 `reply.task_output_append`：
  - 单 chunk 建议不超过 `32 KiB`。
  - flush 间隔建议不超过 `1s`。
  - 每轮 heartbeat 至少 drain 一次 pending output。
  - 单 task 维护一组 `stream_seq` 和 `stream_offset`。
- agent 本地内存 buffer 必须有上限，但超过内存上限后必须转入本地 spool 或待发送队列，禁止丢弃已产生输出。
- heartbeat payload 中的 `state.async_tasks` 必须继续包含任务执行中状态，并可附带轻量 stream 摘要：
  - `stream_bytes`
  - `stream_chunks`
  - `stream_lines`
  - `last_output_at_ms`
  - `spooled_bytes`
  - `pending_bytes`
  - `backpressure_active`
- 任务结束时仍发送 `reply.task_result`：
  - 小输出可以继续 inline。
  - 大输出继续使用 `reply.task_result_chunk`。
  - 如果最终结果太大或不是 JSON，completion viewer 必须展示完整结果的大小、行数、编码、校验值和可拷贝/可读取内容；禁止只展示截断 tail 后伪装完成。

### 无输出任务行为

任务执行过程中可能长时间没有任何输出。无输出不是异常，agent 必须用 heartbeat 明确汇报任务仍在执行：

- agent 不生成空的 `reply.task_output_append`，也不发送占位 payload。
- 每轮 heartbeat 的 `state.async_tasks` 必须包含该 task 的运行中状态，至少包含 `task_id`、`task_type`、`status=running`、`started_at_ms`、`updated_at_ms`、`runtime_ms`。
- 无输出时 stream 摘要必须稳定表达为 `stream_bytes=0`、`stream_chunks=0`、`stream_lines=0`、`last_output_at_ms=null` 或缺省。
- 如果任务此前有输出，后续一段时间无新增输出时，`stream_bytes/stream_chunks/stream_lines` 保持累计值，`last_output_at_ms` 保持最后一次输出时间。
- coordinator 收到只有 `state.async_tasks`、没有 `reply.task_output_append` 的 heartbeat 时，必须刷新 task 的 observed/running 状态，不能误判为输出链路丢失。
- Run UI 在 completion 未到达且没有 stream 输出时，必须展示 `任务执行中，暂未收到输出`，不能显示空白结果，也不能提示失败。

### Agent 内部传递队列

task runner 与 heartbeat 线程之间必须通过明确队列传递输出，不能让 runner 线程直接发送 heartbeat：

```text
process output pipe
  -> output reader
  -> per-task output queue
  -> optional local spool
  -> pending heartbeat replies
  -> heartbeat / group heartbeat request
```

队列规则：

- output reader 按 chunk size 或 flush interval 生成 `OutputChunk`，写入 per-task output queue。
- `OutputChunk` 必须包含 `task_id`、`stream_id=output`、`stream_seq`、`stream_offset`、payload、字节数、行数和 `payload_sha256`。
- per-task output queue 保持 FIFO，`stream_seq` 必须连续递增；发现缺口时 coordinator 必须标记 stream incomplete 并等待补齐或任务失败。
- heartbeat builder 每轮从 per-task output queue drain 可发送 chunk，转换为 `reply.task_output_append` 放入 pending heartbeat replies。
- pending heartbeat replies 是“已准备发送但未确认”的队列；发送失败、超时或 follower 到 leader 失败时必须保留并重试。
- chunk 只有在下一跳确认接收后才能从当前 agent 的 pending 队列移除：
  - direct 模式：coordinator heartbeat response 成功后移除。
  - follower 模式：leader 接收并持久化/纳入自身 pending 后移除，后续由 leader 负责向 coordinator 完整转发。
  - leader 模式：coordinator group heartbeat response 成功后移除。
- 内存队列达到阈值时必须写入 local spool；local spool 仍属于待发送数据，禁止被当成可丢弃 tail。
- local spool 达到硬上限时，agent 必须对任务施加 backpressure、阻塞读取或将任务置为 `output_blocked/failed`，并通过 heartbeat 上报明确原因。
- 最终 `reply.task_result` 必须在所有已产生 stream chunk 之后排队；如果仍有未确认 stream chunk，result 可以进入 pending queue，但发送顺序必须保证 coordinator 不会先看到完成再缺失运行中输出。

### stdout/stderr 取舍

实践中任务错误日志通常已经带有明确 tag、level 或前缀，额外在 Pulse UI 中强制区分 stdout/stderr 会增加协议、buffer、排序和展示复杂度，但收益有限。第一版最佳选择是合并输出：

- agent 侧使用单一输出管道或等价方式把 stderr 合并到 stdout，例如 Java runner 可使用 `redirectErrorStream(true)`。
- `stream_id` 固定为 `output`，不作为 UI 分栏依据。
- UI 默认只展示一个按产生顺序接近真实终端体验的 output viewer。
- 错误识别依赖日志内容中的 `[ERROR]`、`level=error`、栈信息等 tag。
- 后续只有在出现明确需求时，才扩展多 stream 或按来源过滤。

## Group 聚合发送状态机

group 能降低 coordinator 压力，stream 输出不能破坏这一目标。leader 必须用明确状态机决定何时向 coordinator 发送聚合 heartbeat，而不是每个 follower 输出都立即转成 coordinator 请求。

### 状态

```text
IDLE
  无待发送 follower heartbeat 或 stream message。

ACCUMULATING
  已收到 follower heartbeat、stream chunk 或其他待上报消息，等待 flush trigger。

FLUSHING
  正在向一个 coordinator 发送 batch heartbeat。

BACKOFF
  上一次发送失败，等待短暂退避；期间继续接收 follower 数据并合并。
```

### 触发条件

leader 在 `ACCUMULATING` 状态遇到以下任一条件时必须 flush：

| Trigger | 条件 | 说明 |
| --- | --- | --- |
| `SELF_DUE` | leader 自己的 heartbeat 发送时间到 | 每次 group batch 都必须带 leader 自己的 heartbeat，不能只转发 follower |
| `FIRST_AGENT_DUE` | 第一个进入 buffer 的 follower 距上次成功上报达到 `heartbeat_interval + grace`，默认 `5s + 3s` | 防止 follower 被 leader 长时间缓存导致 coordinator 误判 warming/offline |
| `URGENT_MESSAGE` | buffer 中存在紧急消息 | 降低 task accepted/result/stream 可见延迟 |
| `BATCH_FULL` | agent 数、message 数或字节数达到 batch 上限 | 防止内存和单次请求过大 |
| `PLAN_CHANGE` | leader 收到新的 group plan 或成员变化 | 避免 stale member 继续积压 |
| `SHUTDOWN` | agent 进程准备退出 | 尽力 flush 最后一批状态 |

紧急消息定义：

- `reply.task_accepted`
- `reply.task_result`
- `reply.task_result_chunk`
- `reply.task_output_append` 中带有 `urgent=true` 的 chunk
- `task` 状态进入 `failed`、`timed_out`、`rejected`
- follower 明确要求立即可见的 control reply

普通 stream chunk 默认不是紧急消息。持续输出任务的普通 chunk 应按 `FIRST_AGENT_DUE` 或 `BATCH_FULL` 批量发送，避免输出型任务把 group 降压打穿。

### 状态转移

```text
IDLE
  on follower heartbeat/message -> ACCUMULATING
  on SELF_DUE -> FLUSHING with leader self heartbeat

ACCUMULATING
  on SELF_DUE | FIRST_AGENT_DUE | URGENT_MESSAGE | BATCH_FULL | PLAN_CHANGE | SHUTDOWN -> FLUSHING
  otherwise keep merging by agent_id

FLUSHING
  on success -> IDLE if buffer empty, else ACCUMULATING
  on failure -> BACKOFF

BACKOFF
  on backoff elapsed -> FLUSHING
  on new follower message -> stay BACKOFF and merge
  on max backoff exceeded -> followers may fallback direct according to existing plan failure rules
```

### Batch 内容规则

- 每次 leader 向 coordinator 发送 group batch 时，都必须包含 leader 自己的 heartbeat。
- leader 自己的 heartbeat 在 batch 中使用与 direct heartbeat 同等的数据结构，确保 coordinator 能持续确认 leader 存活。
- follower heartbeat 按 `agent_id` 合并，保留最新 `state.heartbeat` 和尚未成功上报的 task/stream messages。
- 对同一 `(agent_id, task_id, stream_id, stream_seq)` 的 stream chunk，leader 必须幂等去重。
- 当 batch 超过大小预算时：
  - 必须拆分为多个 heartbeat batch，或保留在 leader spool/pending queue 中等待后续发送。
  - 可以优先发送 `reply.task_result`、`reply.task_accepted` 等状态型消息，但不能删除旧 stream chunk。
  - 如果 spool 或 pending queue 达到硬上限，必须触发 backpressure、拒绝继续接收该 follower 的新增输出，或让任务失败并上报明确错误；禁止截断后继续标记成功。
- leader 不解析输出内容，不做 JSON 格式化、不改写 payload。
- leader 不能因为某个 follower 输出量大而阻塞其他 follower 的心跳。

### 时间参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `heartbeat_interval_ms` | `5000` | agent 基础心跳周期 |
| `group_flush_grace_ms` | `3000` | follower 首次进入 buffer 后最多额外等待 |
| `urgent_flush_delay_ms` | `0-200` | 紧急消息可加极小 jitter 合并同一时间片消息 |
| `max_batch_agents` | `7` | group size 上限 |
| `max_batch_bytes` | 实现期配置 | 单次 group heartbeat payload 安全上限 |
| `backoff_initial_ms` | `250` | 发送失败初始退避 |
| `backoff_max_ms` | `5000` | 发送失败最大退避 |

## Coordinator 行为

- coordinator 处理 `reply.task_output_append` 时按 `(agent_id, task_id, stream_id, stream_seq)` 去重。
- coordinator 为每个 running task 维护完整 stream log，内存只作为缓存，超过内存预算必须写入本地 spool 或持久化结构：
  - 保存完整 chunk 列表或等价可恢复表示。
  - 记录 `first_stream_seq`、`last_stream_seq`、`stream_bytes`、`stream_lines`、`stream_chunks`、`spooled_bytes`、`pending_bytes`、`backpressure_active`。
- coordinator 不把 stream chunk 放入 completion queue；completion queue 只保存最终 `TaskResult`。
- coordinator snapshot 可以通过已有 `/api/agents/{agent}/tasks` 返回 `output_streams` 或 `stream_log` 字段，供 Run UI 展示运行中输出。
- coordinator peer sync 仍只通过已有 lazy sync 机制传播必要 task state；stream log 不要求 peers 强一致，但接收该 task 的 coordinator 必须保证本地完整性。
- 如果最终 `reply.task_result` 到达，coordinator 必须把 task 状态切到 terminal，并保留完整 stream log 摘要供 trace 解释。

## Run UI 规则

### 运行中状态

- 如果 task 已 `accepted/running` 但 completion 未到达，结果区顶部必须显示明显提示：
  - `任务执行中，正在展示运行中输出`
  - `最后输出：x 秒前`
  - `已接收：N 行 / M KiB`
- 如果尚无 stream 输出，展示：
  - `任务未完成，暂未收到输出`
  - 当前 task_id、task_type、开始时间和队列位置。
- 如果发生 backpressure，展示低饱和提示：
  - `输出较多，正在排队传输，已缓存 X KiB`
  - 该提示只能表示传输延迟，不能表示数据被截断或丢弃。

### Stream Viewer

- stream viewer 必须支持普通文本，不强制 JSON 格式化；第一版只展示单一合并 output。
- stream viewer 必须展示：
  - 合并输出
  - 行数
  - 字节数
  - chunk 数
  - 最后输出时间
  - 是否存在传输积压
- stream viewer 必须支持：
  - 自动滚动到最新输出。
  - 用户暂停自动滚动。
  - 拷贝当前输出或完整输出。
  - 清晰标识当前内容不是最终 completion。
- JSONL 可以按行轻量高亮；普通 text 只用等宽字体和内部滚动。错误行不依赖 stderr 来源，而依赖日志 tag 和内容识别。

### Completion Viewer

- completion 到达后，右侧结果区默认切换到 completion viewer。
- completion viewer 必须展示：
  - `status`
  - `exit_code`
  - `duration`
  - `output_type`
  - `output_encoding`
  - `output_bytes`
  - 行数
  - `output_sha256`
- JSON 输出继续支持格式化、原始、拷贝和高亮。
- 非 JSON 输出展示原始文本、行数、字节数和拷贝按钮。
- 如果最终结果分片未齐，禁止展示为完成结果；只能显示 `结果接收中`。

## 背压与安全边界

- 单 task 内存 buffer 必须有上限，禁止无界占用内存。
- 单 heartbeat 或 group heartbeat 的 stream chunk 数和总字节数必须有上限，超过上限时必须分批发送。
- 当输出生产速度超过心跳传输能力时，系统必须选择排队、落盘 spool、backpressure 或任务失败，而不是截断输出。
- stream chunk 是生产级任务输出的一部分，禁止静默丢弃，禁止只保留最近 tail。
- 若本地 spool 或 pending queue 达到硬上限，必须让任务进入 `failed` 或 `output_blocked` 等明确状态，并上报完整错误原因；禁止返回成功但输出不完整。
- task result 是最终语义结果，不允许因为 stream 传输策略而被截断或丢弃。
- UI 可以虚拟滚动或分页展示大输出，但展示优化不能改变后端数据完整性。

## 实现门禁

- 禁止新增 task streaming API。
- 禁止新增 agent 入站端口。
- 禁止让 follower 因每个 stream chunk 都直接打 coordinator，必须继续通过 leader 聚合。
- 禁止 group leader 发送不包含自己 heartbeat 的 batch。
- 禁止普通 stream chunk 绕过 group batch 约束成为 coordinator 请求风暴。
- 禁止 Run UI 在 task 未完成时把 stream log 或运行中输出文案伪装成最终结果。
- 禁止 completion viewer 不展示行数、字节数和未完成/传输积压状态。
- 禁止 agent 同一时间并发执行多个 task，除非先完成并发资源隔离设计。
- 禁止第一版 UI 强制拆分 stdout/stderr；除非先证明错误 tag 不足以支撑排障。
- 禁止截断、丢弃或只保留最近 tail；生产级任务输出必须完整传输、完整保存，并能被 UI 识别为完整或明确失败。
- 禁止使用 `tail_truncated`、`dropped_bytes`、`dropped_chunks` 作为正常成功路径字段；容量不足只能触发 backpressure、spool 或失败。
