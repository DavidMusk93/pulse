# Pulse Remote Task 执行机制设计

## 结论

Pulse Remote Task 基于现有心跳机制实现远程任务下发、异步执行、结果回传和 UI 展示。

核心约束：

- agent 仍不监听端口，不新增 agent 入站接口。
- 执行任务只通过 `/heartbeat` response 下发到 agent。
- 执行结果只通过后续 `/heartbeat` request 上报回 coordinator。
- group leader 只转发对应 agent 的 execution queue，不成为状态权威。
- coordinator 是 execution queue、completion queue 和 trace log 的权威存储点。
- 当前只允许预定义任务，并且必须以 `--dry-run` 方式运行。

预定义任务 allowlist：

| Task ID | Script | 固定参数 | 说明 |
| --- | --- | --- | --- |
| `prepare_disk_layout_dry_run` | `/data24/otf/pulse/tasks/prepare-disk-layout.sh` | `--dry-run` | 远端磁盘布局准备预检查 |
| `analyze_block_layout_dry_run` | `/data24/otf/pulse/tasks/analyze-block-layout.py` | `--dry-run` | Block layout 分析预检查 |

脚本来源：

- 任务脚本随 Pulse 代码仓库放在 `docs/task/`。
- 部署脚本同步 `docs/task/` 到远端 `${install_root}/tasks`。
- agent 和 coordinator 通过 `PULSE_TASK_DIR` 指向远端任务目录。

## 设计原则

- 不允许任意 shell 命令下发，只允许 allowlist task。
- task runner 必须自动追加并强制校验 `--dry-run`。
- 所有 task 必须绑定 `trace_id`，从 UI 点击到 agent 执行完成全链路追踪。
- execution queue 和 completion queue 都按 `agent_id` 隔离。
- completion queue 默认保留结果，用户在 UI 上决定保留或弹出下一条。
- agent 执行必须异步，不能阻塞心跳线程。
- 重复下发、重复心跳、group 转发重试必须幂等。
- task 结果必须端到端无损；协议层不允许截断、摘要化、丢字段、改写 stdout/stderr 或未来的 JSON 输出。
- 如果 task result 超过单次 heartbeat 安全承载范围，只能使用无损压缩、分片和重组；不能用 tail、truncate、sampling 等有损手段。

## 角色职责

### Coordinator

- 维护每个 agent 的 `ExecutionQueue`。
- 维护每个 agent 的 `CompletionQueue`。
- 维护 `TaskTraceLog`。
- 在 `/heartbeat` response 中把 execution queue 的待执行 task 下发给 agent。
- 接收 agent 通过 `reply.task_result` 上报的结果，并写入 completion queue。
- 为 `/hosts` 的 run 按钮提供查询、入队、保留、弹出 completion 的控制面 API。

### Group Leader

group leader 是转发层，不是 task 权威。

- leader 自身作为 agent 时，从 coordinator 收到自己的 execution queue。
- leader 批量上报 follower heartbeat 后，从 `agents[].messages[]` 得到 follower 对应的 `cmd.task_execute`。
- leader 把每个 follower 的下行 task message 缓存在本地 per-agent response queue。
- follower 下一次 `/group/heartbeat` 时，leader 将对应 task message 返回给该 follower。
- follower 的 `reply.task_result` 仍随心跳经 leader 批量上报到 coordinator。

### Agent

- 在 heartbeat response 中接收 `cmd.task_execute`。
- 使用本地 `TaskRunner` 幂等接收 task。
- 在后台线程池异步执行 task。
- 执行完成后生成 `reply.task_result`，随下一次或立即触发的 heartbeat 上报。
- `reply.task_accepted` 与 `reply.task_result` 默认随后续 3 次 heartbeat 重复上报，确保轮询多 coordinator 时每个 coordinator 都能补齐 trace 与 completion。
- 维护本地已接收/执行过的 `task_id` 与 `trace_id` 缓存，避免重复执行。

### Web UI

- 每个 host card 增加 `Run` 按钮。
- 点击后弹出任务框。
- 任务框展示该 agent 的 execution queue。
- 任务框提供预定义 task 选择和 run 操作。
- 任务框展示 completion queue 的最后一条数据。
- 对已展示 completion，默认保留；用户点击“不保留/弹出下一条”后才从 queue 中移除当前展示项并展示下一条。

## 数据模型

### RemoteTask

```text
RemoteTask {
  task_id: string
  trace_id: string
  agent_id: string
  task_type: string              // allowlist task id
  script_path: string
  args: list<string>             // always contains "--dry-run"
  status: string                 // queued | delivered | accepted | running | completed | failed | timed_out | cancelled
  created_at_ms: int64
  delivered_at_ms: int64?
  accepted_at_ms: int64?
  started_at_ms: int64?
  finished_at_ms: int64?
  deadline_ms: int64
  created_by: string             // ui user or local operator identity, default "pulse-ui"
  attempt: uint32
}
```

### TaskResult

```text
TaskResult {
  task_id: string
  trace_id: string
  agent_id: string
  task_type: string
  status: string                 // completed | failed | timed_out | rejected
  exit_code: int?
  started_at_ms: int64
  finished_at_ms: int64
  duration_ms: int64
  output: string                 // 完整无损输出；当前来自 stdout，后续优先为 JSON 文本
  output_type: string            // text | json | compressed_json | compressed_text
  output_encoding: string        // identity | gzip+base64 | zstd+base64
  output_sha256: string          // 解码/解压后的完整原文 sha256
  output_bytes: int64            // 解码/解压后的完整原文字节数
  stderr: string?                // 兼容旧脚本；完整无损，不允许 tail
  stderr_sha256: string?
  stderr_bytes: int64?
  chunks: list<ResultChunk>?     // 大结果分片上报时使用
  runner_error: string?
}
```

输出与消息完整性约束：

- 第一性原则是无损：task 输出是 task 语义结果的一部分，不是日志尾部，不允许 coordinator、group leader、agent、UI 任一环节截断或改写。
- heartbeat 可以有单次消息大小预算，但大小预算只能影响传输方式，不能影响结果内容。
- 当完整 result 小于单次 heartbeat 安全承载范围时，使用 `output_encoding=identity` 直接上报完整 `output`。
- 当完整 result 超过单次 heartbeat 安全承载范围时，agent 必须选择无损压缩或分片：
  - 优先对完整原文做无损压缩，例如 `gzip+base64` 或 `zstd+base64`。
  - 压缩后仍超过预算时，按 `task_id + trace_id + chunk_index/chunk_count` 分片上报。
  - coordinator 必须在所有分片到齐并校验 `output_sha256` 后，才把 result 放入 completion queue。
  - 分片未到齐时只能显示 `receiving` / `incomplete` 状态，不能展示“部分结果”冒充完整 completion。
- `output_sha256` 是完整性校验必填字段；UI 可以展示该 hash，便于确认结果未被传输层破坏。
- `output_truncated`、`stdout_tail`、`stderr_tail` 这类有损字段只允许作为历史兼容字段读取，不允许作为新协议语义继续使用。
- 未来 task 输出格式改为 JSON 后，agent 仍按完整文本处理；可以标记 `output_type=json`，但不能解析后重排、过滤或丢字段再上传。

### ResultChunk

```text
ResultChunk {
  task_id: string
  trace_id: string
  agent_id: string
  chunk_id: string
  chunk_index: uint32
  chunk_count: uint32
  encoding: string               // gzip+base64 | zstd+base64 | identity
  payload: string                // chunk bytes encoded as text
  payload_sha256: string
  full_output_sha256: string
  full_output_bytes: int64
}
```

分片规则：

- 分片只服务于 heartbeat 传输，不改变 completion queue 的结果模型。
- coordinator 按 `task_id + trace_id` 组装分片。
- 分片可重复上报，coordinator 按 `chunk_index` 和 `payload_sha256` 幂等去重。
- 任一分片 hash 不匹配时，result 标记为 `corrupt_result`，不得进入 completed completion。
- 分片组装完成后，completion queue 保存完整可还原输出及完整性元数据。

### ExecutionQueue

```text
ExecutionQueue {
  agent_id: string
  pending: deque<RemoteTask>
  in_flight: map<task_id, RemoteTask>
  completed_delivery: set<task_id>
}
```

规则：

- 每个 agent 独立队列。
- 同一 `task_id` 只允许入队一次。
- coordinator 下发 task 后先标记 `delivered` 并放入 `in_flight`。
- agent 回 `reply.task_accepted` 后标记 `accepted`。
- agent 回 `reply.task_result` 后从 `in_flight` 移除，结果进入 completion queue。
- coordinator 按 `task_id` 对 completion queue 去重；同一 result 重复到达时更新为最后一次结果，不新增重复 completion。
- 超过 `deadline_ms` 未完成时标记 `timed_out`，仍允许迟到 result 进入 completion queue，并在 trace log 标记 `late_result`。

### CompletionQueue

```text
CompletionQueue {
  agent_id: string
  results: deque<TaskResult>
  displayed_result_id: string?
}
```

规则：

- completion queue 默认保留。
- UI 默认展示最后一条 result，但不自动移除。
- 用户选择“保留”时，仅关闭或保持展示，不改变 queue。
- 用户选择“不保留/弹出下一条”时，移除当前展示 result，再展示下一条最新 result。
- queue 有容量上限，例如每 agent 保留最近 `50` 条；超过上限时丢弃最旧 result，并写 trace log。

### TaskTraceLog

```text
TaskTraceLogEntry {
  trace_id: string
  task_id: string
  agent_id: string
  event: string
  actor: string                  // ui | coordinator | group_leader | agent
  source_id: string
  observed_at_ms: int64
  detail: map<string, any>
}
```

事件枚举：

- `task.enqueued`
- `task.dequeued_for_delivery`
- `task.delivered_to_agent`
- `task.delivered_to_group_leader`
- `task.forwarded_by_group_leader`
- `task.accepted_by_agent`
- `task.rejected_by_agent`
- `task.started`
- `task.completed`
- `task.failed`
- `task.timed_out`
- `task.result_received`
- `task.completion_displayed`
- `task.completion_popped`
- `task.deduplicated`

trace 要求：

- `trace_id` 在 UI enqueue 时生成。
- 所有消息、队列项、result、trace log 都必须携带同一个 `trace_id`。
- 日志是追加式，不因 completion queue 弹出而删除。

## 消息协议

### `cmd.task_execute`

coordinator 通过 heartbeat response 下发。

```json
{
  "message_id": "cmd-task-coordinator-a-1001",
  "type": "cmd.task_execute",
  "version": 1,
  "deadline_ms": 1710000030000,
  "payload": {
    "task_id": "task-01HY...",
    "trace_id": "trace-01HY...",
    "agent_id": "agent-1",
    "task_type": "prepare_disk_layout_dry_run",
    "script_path": "/data24/otf/pulse/tasks/prepare-disk-layout.sh",
    "args": ["--dry-run"],
    "timeout_ms": 600000,
    "created_at_ms": 1710000000000
  }
}
```

约束：

- `task_type` 必须在 allowlist。
- `args` 必须包含且只能以 dry-run 安全参数为主；第一版固定为 `["--dry-run"]`。
- agent 需要再次校验 `script_path` 与 `task_type` 是否匹配 allowlist。
- agent 不信任 coordinator 下发的任意 path 或 args。

### `reply.task_accepted`

agent 接收并排入本地 runner 后上报。

```json
{
  "message_id": "reply-task-accepted-agent-1-1001",
  "type": "reply.task_accepted",
  "version": 1,
  "reply_to": "cmd-task-coordinator-a-1001",
  "payload": {
    "task_id": "task-01HY...",
    "trace_id": "trace-01HY...",
    "agent_id": "agent-1",
    "status": "accepted",
    "accepted_at_ms": 1710000001000
  }
}
```

### `reply.task_result`

agent 执行完成后上报。

```json
{
  "message_id": "reply-task-result-agent-1-1001",
  "type": "reply.task_result",
  "version": 1,
  "reply_to": "cmd-task-coordinator-a-1001",
  "payload": {
    "task_id": "task-01HY...",
    "trace_id": "trace-01HY...",
    "agent_id": "agent-1",
    "task_type": "prepare_disk_layout_dry_run",
    "status": "completed",
    "exit_code": 0,
    "started_at_ms": 1710000002000,
    "finished_at_ms": 1710000009000,
    "duration_ms": 7000,
    "output": "{\"ok\":true}",
    "output_type": "json",
    "output_encoding": "identity",
    "output_sha256": "sha256-of-complete-output",
    "output_bytes": 11,
    "stderr": "",
    "stderr_sha256": "sha256-of-complete-stderr",
    "stderr_bytes": 0
  }
}
```

结果消息约束：

- `reply.task_result` 表示完整结果可用；如果结果需要分片，则只有所有分片重组与 hash 校验通过后才能生成完整 completion。
- 不允许因为 heartbeat response/request 有大小限制而截断 `output`。
- group leader 对 result message 和 chunk message 只能透明转发，不能解压、重压缩、重排、截断或合并字段。
- coordinator 可以存储压缩态或解压态，但对 UI/API 必须能还原完整原文。

## 心跳链路

### Direct Agent

```text
UI Run
  |
  v
coordinator ExecutionQueue[agent]
  |
  | next /heartbeat response: cmd.task_execute
  v
agent TaskRunner async execute
  |
  | next /heartbeat request: reply.task_result
  v
coordinator CompletionQueue[agent]
  |
  v
UI modal displays latest completion
```

### Group Follower

```text
UI Run
  |
  v
coordinator ExecutionQueue[follower]
  |
  | leader batch heartbeat response: agents[].messages[]
  v
group leader per-agent response queue
  |
  | follower /group/heartbeat response: cmd.task_execute
  v
follower TaskRunner async execute
  |
  | follower /group/heartbeat request: reply.task_result
  v
group leader batch /heartbeat agents[].messages[]
  |
  v
coordinator CompletionQueue[follower]
```

关键点：

- coordinator 不需要知道 agent 当前是 direct、leader 还是 follower 才能入队。
- 如果 agent 经 group 上报，task 会自然通过 batch response 的 `agents[].messages[]` 到达 leader，再由 leader 返回给 follower。
- group leader 不修改 task 内容，只记录转发 trace event。
- follower 结果走原有上行 heartbeat 聚合路径返回。

## Coordinator 控制面 API

心跳链路不新增 API；但 Web UI 需要对 coordinator 发起入队和查看队列操作，因此需要最小控制面 API。

这些 API 只在 coordinator 上提供，不提供给 agent，不参与 agent 执行链路。

### `GET /api/agents/{agent_id}/tasks`

返回 execution queue、completion queue 最新状态和 trace 摘要。

### `POST /api/agents/{agent_id}/tasks`

入队一个 allowlist task。

请求：

```json
{
  "task_type": "prepare_disk_layout_dry_run"
}
```

coordinator 自动生成：

- `task_id`
- `trace_id`
- `script_path`
- `args=["--dry-run"]`
- `deadline_ms`

### `POST /api/agents/{agent_id}/tasks/completions/{task_id}/pop`

用户选择不保留当前 completion 时调用。

行为：

- 从 completion queue 移除该 result。
- 写入 `task.completion_popped` trace log。
- 返回下一条最新 completion，如果没有则返回空。

### `POST /api/agents/{agent_id}/tasks/completions/{task_id}/keep`

用户选择保留当前 completion 时调用。

行为：

- 不移除 result。
- 写入 `task.completion_displayed` 或 `task.completion_kept` trace log。

## Run UI 设计

### 卡片入口

- 每张 host card 增加 `Run` 按钮。
- 按钮放在 header 右侧 status 附近，但不能挤占 `Seen` datetime。
- `Run` 按钮使用低对比、圆角、轻量边框样式，避免像危险操作。
- agent 非 `alive` 时按钮 disabled，提示 `agent is not alive`。

### 任务弹窗

布局：

```text
+------------------------------------------------------+
| Run Task · agent-ip                                  |
| trace: trace-...                                     |
+----------------------+-------------------------------+
| Execution Queue      | Completion Queue              |
| - queued/running     | Latest result                 |
| - delivered          | stdout/stderr viewer          |
|                      | exit code / duration / status |
+----------------------+-------------------------------+
| Task                                                        |
| [prepare_disk_layout_dry_run v] [Run dry-run]               |
| [analyze_block_layout_dry_run    v]                              |
+-------------------------------------------------------------+
| [Keep result] [Pop and show next] [Close]                   |
+-------------------------------------------------------------+
```

视觉要求：

- 弹窗宽度适合日志阅读，桌面端建议 `min(960px, 92vw)`。
- stdout/stderr 使用等宽字体、深浅分区、可滚动区域。
- status 使用语义色但低饱和：queued 灰蓝、running 蓝、completed 绿、failed 暖橙。
- trace-id 放在标题下方，可复制。
- 输出区域默认展示 latest completion。
- execution queue 列表展示 `task_type`、`status`、`created_at`、`delivered_at`、`trace_id`。
- completion queue 展示 `task_type`、`status`、`exit_code`、`duration`、`finished_at`。
- “Pop and show next”必须是明确按钮，不能自动删除。

## Agent TaskRunner

执行策略：

- 单 agent 第一版并发度为 `1`，避免多个远程任务同时抢资源。
- 后续可以通过 `PULSE_TASK_MAX_CONCURRENCY` 调整。
- 每个 task 使用独立进程执行。
- 命令固定为：

```bash
bash "$script_path" --dry-run
```

安全限制：

- 只允许 allowlist 中的 script。
- 强制 `--dry-run`。
- 不允许 UI 或 coordinator 传任意 shell 片段。
- 不通过 shell 拼接用户输入。
- 设置超时，例如 `600s`，避免 block layout dry-run 在大盘机器上被过早中断。
- 捕获 stdout/stderr。
- 记录 exit code。

幂等：

- agent 维护最近执行过的 `task_id` LRU。
- 重复 `cmd.task_execute` 不重复启动进程。
- 如果 task 已完成，agent 可以重发同一 `reply.task_result`。

## 一致性与失败处理

| 场景 | 处理 |
| --- | --- |
| coordinator 重复下发同一 task | agent 通过 `task_id` 去重 |
| agent 执行中重启 | coordinator `in_flight` 超时，UI 显示 timed out |
| agent accepted/result 上报失败 | agent 在后续 heartbeat 重试 `reply.task_accepted` 和 `reply.task_result` |
| result 超过单次 heartbeat 大小预算 | agent 无损压缩；仍超限则分片上报，coordinator 完整重组并 hash 校验 |
| result 分片丢失或乱序 | coordinator 保持 `receiving`，按 chunk index 去重重组，不生成 completed completion |
| result hash 校验失败 | 标记 `corrupt_result`，不展示为有效 completion，写 trace log |
| group leader 重启 | follower fallback direct 或等待新 plan；coordinator queue 保留 task |
| completion queue 满 | 丢弃最旧 result，写 trace log |
| task 超时 | agent kill 子进程，上报 `timed_out` |
| task path 不在 allowlist | agent 拒绝并上报 `reply.task_result status=rejected` |

## 非目标

- 第一版不支持任意命令执行。
- 第一版不支持上传任意外部 artifact 文件；但 task result 文本本身必须通过 heartbeat 无损传输。
- 第一版不做持久化数据库，先以内存结构实现；进程重启后队列丢失是可接受限制。
- 第一版不做权限体系，默认仅在受控网络和现有 coordinator 访问路径下使用。
- 第一版不让 coordinator 反连 agent。
- 第一版不通过 `/heartbeat_fwd` 转发 task 指令。

## 后续演进

- 将 queue 和 trace log 持久化到本地文件或轻量 KV。
- 增加任务取消 `cmd.task_cancel`。
- 增加日志分片 `reply.task_output_chunk`。
- 增加 UI 权限和审计用户身份。
- 增加 task template 参数，但仍需 allowlist 和参数 schema 校验。
