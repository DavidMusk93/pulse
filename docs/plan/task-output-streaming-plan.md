# Pulse Task 流式输出实现计划

## 执行原则

- 本计划只覆盖长任务运行中输出、stream tail、completion 展示增强、group 聚合发送状态机、agent 串行执行和多用户队列语义。
- 基础 group heartbeat、集群元数据和 Web 分组计划继续维护在 `docs/plan/group-heartbeat-cluster-metadata-plan.md`。
- 设计约束以 `docs/design/task-output-streaming.md` 为准；实现前必须先检查该文档的“实现门禁”。
- 不新增 task streaming API，不新增 WebSocket/SSE，不新增 agent 入站端口。
- 所有运行中输出仍通过 heartbeat / group heartbeat 的 `PulseMessage` 链路传输。

## 阶段 1：协议与模型

- 新增 `reply.task_output_append` PulseMessage 类型。
- 第一版采用单一合并输出流，`stream_id` 固定为 `output`。
- 不在 UI、buffer 和 completion 逻辑中强制区分 stdout/stderr；错误识别依赖日志 tag 和内容。
- 定义 stream payload 字段：
  - `task_id`
  - `trace_id`
  - `agent_id`
  - `task_type`
  - `stream_id`
  - `stream_seq`
  - `stream_offset`
  - `output_encoding`
  - `output_type`
  - `payload`
  - `payload_sha256`
- `stream_seq` 必须在单 task 的 `output` 流内单调递增，用于 coordinator 和 leader 幂等去重。
- `stream_offset` 必须按字节偏移计算，用于解释 tail 截断和丢弃窗口。
- 非 UTF-8 或二进制内容必须使用 `base64`，禁止把非法文本直接塞进 JSON。
- `reply.task_output_append` 禁止进入 completion queue，completion queue 只接受最终 `reply.task_result` 或 `reply.task_result_chunk`。

## 阶段 2：Agent Runner

- Agent task runner 第一版必须 per-agent 串行执行，并发度固定为 `1`。
- Agent 本地最多只能有一个 `running` task；其他 task 只能停留在 coordinator execution queue 或 agent 已接收未运行状态。
- 执行进程启动后必须合并 stderr 到 stdout，并持续 drain 单一 output pipe，不能等进程退出后再一次性读取。
- 输出 chunk 规则：
  - 单 chunk 建议不超过 `32 KiB`。
  - flush 间隔建议不超过 `1s`。
  - 每轮 heartbeat 至少 drain 一次 pending output。
  - 单 task 维护一组 `stream_seq`、`stream_offset`、行数和字节数。
- Agent 本地必须维护有限 tail buffer，默认最近 `1-4 MiB` 或最近 `N` 条 chunk。
- 输出超过本地 buffer 时必须保留最近 tail，并累计 `dropped_bytes`、`dropped_chunks`、`tail_truncated`。
- 任务结束时仍必须发送最终 `reply.task_result` 或 `reply.task_result_chunk`，stream tail 不能替代最终结果。

## 阶段 3：多用户队列

- Coordinator 是多用户入队仲裁点，按 agent 维度维护 FIFO execution queue。
- `RemoteTask.created_by` 必须保留；第一版可使用 `pulse-ui`，后续接入真实用户身份。
- 同一 agent 的多个用户任务不得在 agent 上并发执行。
- 第一版不做优先级和抢占，紧急消息只影响心跳 flush 时机，不改变 task 执行顺序。
- 每个 agent pending queue 必须有上限，第一版建议 `20`。
- 队列满时必须拒绝新任务，并在 UI 中明确提示当前 agent 队列已满。
- Completion queue 是 agent 级共享结果队列；`pop` 是队头弹出，会影响该 agent 的共享结果展示。
- Run UI 必须展示 task type、status、created_by、created_at、task_id 和队列位置。

## 阶段 4：Group 聚合状态机

- Leader 必须实现明确发送状态机：
  - `IDLE`
  - `ACCUMULATING`
  - `FLUSHING`
  - `BACKOFF`
- Leader 必须维护 follower buffer，按 `agent_id` 合并 heartbeat 和 pending messages。
- 每次 leader 向 coordinator 发送 group batch 时，都必须携带 leader 自己的 heartbeat。
- Flush trigger 必须包含：
  - `SELF_DUE`：leader 自己 heartbeat 发送时间到。
  - `FIRST_AGENT_DUE`：第一个进入 buffer 的 follower 距上次成功上报达到 `heartbeat_interval + grace`，默认 `5s + 3s`。
  - `URGENT_MESSAGE`：buffer 中存在紧急消息。
  - `BATCH_FULL`：agent 数、message 数或字节数达到 batch 上限。
  - `PLAN_CHANGE`：group plan 或成员发生变化。
  - `SHUTDOWN`：进程退出前尽力 flush。
- 紧急消息第一版包括 `reply.task_accepted`、`reply.task_result`、`reply.task_result_chunk`、带 `urgent=true` 的 `reply.task_output_append`、terminal failure 状态和 control reply。
- 普通 stream chunk 默认不是紧急消息，必须按 `FIRST_AGENT_DUE` 或 `BATCH_FULL` 批量发送，避免输出型任务打穿 group 降压目标。
- Leader 不解析输出内容，不格式化 JSON，不改写 payload。
- 单 follower 输出过大时，leader 必须裁剪该 follower 的旧 stream chunk，不能阻塞其他 follower heartbeat。

## 阶段 5：Coordinator Stream Tail

- Coordinator 处理 stream chunk 时必须按 `(agent_id, task_id, stream_id, stream_seq)` 去重。
- Coordinator 必须维护 per running task 的有限 stream tail buffer。
- Stream tail snapshot 至少包含：
  - 合并 output 的最近文本。
  - `first_stream_seq`
  - `last_stream_seq`
  - `stream_bytes`
  - `stream_lines`
  - `stream_chunks`
  - `last_output_at_ms`
  - `dropped_bytes`
  - `dropped_chunks`
  - `tail_truncated`
- `/api/agents/{agent}/tasks` 可扩展只读 snapshot 字段返回 stream tail；这不是新增 API。
- Peer lazy sync 不要求 stream tail 强一致；最终 task terminal 状态仍以 `reply.task_result` 为准。

## 阶段 6：Run UI

- Completion 未到达但 task 已 `accepted/running` 时，结果区必须展示运行中提示。
- 运行中提示必须至少包含：
  - `任务执行中，正在展示运行中输出`
  - 最后输出时间
  - 已接收行数
  - 已接收字节数
- 尚无 stream 输出时必须展示 `任务未完成，暂未收到输出`，并展示 task_id、task_type、开始时间和队列位置。
- Stream viewer 必须展示合并 output、行数、字节数、chunk 数、最后输出时间和截断状态。
- Stream viewer 必须支持自动滚动、暂停自动滚动、拷贝当前 tail。
- Completion viewer 必须展示 status、exit_code、duration、output_type、output_encoding、output_bytes、行数和 output_sha256。
- 非 JSON completion 必须展示原始文本、行数、字节数和拷贝按钮。
- 最终结果分片未齐时禁止展示为完成结果，只能显示 `结果接收中`。
- UI 必须明确提示 `pop` 会弹出当前 agent 共享 completion queue 的队头结果。

## 阶段 7：测试门禁

- `PulseMessage` 测试：
  - 验证 `reply.task_output_append` 序列化和反序列化。
  - 验证非 UTF-8 内容必须 base64。
- `AgentTaskRunnerTest`：
  - 构造持续输出且包含错误 tag 的长任务，验证运行中合并 output 形成 stream chunk。
  - 验证任务结束仍发送最终 `reply.task_result`。
  - 验证 per-agent 并发度为 `1`，第二个任务不会并发运行。
  - 验证 stream buffer 超限时保留最近 tail 并设置 dropped/truncated 字段。
- `GroupHeartbeatCollectorTest`：
  - 验证 `SELF_DUE` flush 每次携带 leader 自己 heartbeat。
  - 验证 `FIRST_AGENT_DUE` 在 `5s + 3s` 到期后 flush 第一个 follower。
  - 验证 `URGENT_MESSAGE` 触发快速 flush。
  - 验证普通 stream chunk 不会每条都触发 coordinator 请求。
  - 验证单 follower 大输出不会阻塞其他 follower。
- `CoordinatorServiceTest`：
  - 验证重复和乱序 stream chunk 不重复进入 stream tail。
  - 验证 stream chunk 不进入 completion queue。
  - 验证最终 `reply.task_result` 才进入 completion queue。
  - 验证多用户 task 按同一 agent FIFO 排队。
  - 验证 queue full 拒绝新任务。
- `CoordinatorHttpServerTest`：
  - 验证 task snapshot 暴露 stream tail 字段。
  - 验证 Run UI bundle 包含 stream viewer、合并 output、行数、字节数、tail truncated、未完成提示和拷贝文案。
  - 验证 completion viewer 包含行数、字节数、分片未齐提示。
  - 验证 pop 文案提示共享 completion queue 影响。

## 阶段 8：验证与发布

- 本地执行 Maven 测试和前端构建。
- 使用浏览器验证 Run UI：
  - 长任务运行中能展示 stream tail。
  - 无输出时能提示任务未完成。
  - completion 到达后能切换到 completion viewer。
  - JSON 与非 JSON completion 都能展示行数和字节数。
- 线上灰度优先选择少量 agent，确认 group leader 请求量不会因 stream chunk 显著上升。
- 线上验证必须记录：
  - group batch flush trigger 分布。
  - per-agent stream tail 内存占用。
  - tail_truncated 发生次数。
  - completion queue pop 语义保持队头弹出。
- 验证通过后再扩大到目标集群。
