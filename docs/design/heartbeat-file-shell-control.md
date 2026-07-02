# Heartbeat 文件下发与 Shell 执行设计

## 结论

Pulse 需要支持两类新的运维能力：

- 通过 heartbeat 链路向 agent 下发文件，默认保存到 `$agent_work_dir/files`。
- 通过 heartbeat 链路向 agent 下发 shell 脚本，并在 agent 的 `$agent_work_dir/workspace` 中串行执行。

核心约束：

- 不新增 API。所有 coordinator 到 agent 的控制指令仍只通过 `/heartbeat` response 或 group heartbeat response 下发。
- agent 仍保持 outbound-only，不新增入站文件上传接口，不新增 WebSocket/SSE。
- heartbeat response 支持 JSON 与二进制两种格式；二进制格式用于传输单个文件或脚本内容，文件名必须通过 HTTP header 声明。
- shell 脚本必须先作为文件经过 heartbeat 链路推送到 agent，再由后续 `cmd.shell_execute` 消息引用本地 staged script 串行执行。
- agent 文件写入、脚本落盘、脚本执行和输出回传都必须幂等、可校验、可审计。
- UI 只使用既有 coordinator Web API 能力扩展操作区域，不新增 agent API；新增 UI 操作最终都进入 coordinator 的 per-agent control queue。

## 非目标

- 不支持 agent 主动拉取外部 URL。
- 不支持 UI 直接访问 agent 上传文件。
- 不支持任意 shell command 字符串直接执行。
- 不支持绕过 staged file 的 inline shell 执行。
- 不支持在 group leader 上解包、改写、压缩或合并文件内容。
- 不把隐藏 UI 入口当作安全边界；真正安全边界是消息类型、路径约束、hash 校验、串行执行、审计和后续权限系统。

## 工作目录

agent 必须有稳定的工作根目录：

```text
agent_work_dir = ${PULSE_AGENT_WORK_DIR:-${install_root}/agent}
files_dir      = ${agent_work_dir}/files
workspace_dir  = ${agent_work_dir}/workspace
spool_dir      = ${agent_work_dir}/spool
```

`spool` 的含义：

- `spool` 是 agent 本地磁盘暂存队列，用于保存“已经产生但尚未被下一跳确认接收”的数据。
- `spool` 不是最终结果存储，也不是 UI 查询源；它只用于心跳失败、group leader 重启、coordinator 暂不可达或本地内存队列满时保证数据不丢。
- 上传文件的 incoming 临时文件、待重试的 `reply.file_received`、待重试的 task stream chunk、待重试的 `reply.task_result` 都可以进入 `spool`。
- 一旦下一跳通过 heartbeat 确认接收，agent 才能删除对应 spool 项。
- `spool` 必须有容量上限和可观测指标，超过上限时 agent 必须拒绝新上传或让任务进入明确的 backpressure/failed 状态，禁止静默丢弃。
- 前端最终看到的上传结果来自 coordinator 收到的 `reply.file_received`；前端最终看到的 shell 执行结果来自 coordinator completion queue 中的 `reply.task_result`，不能依赖 agent 本地 spool。

默认部署到 `/data24/otf/pulse` 时：

```text
/data24/otf/pulse/agent/files
/data24/otf/pulse/agent/workspace
/data24/otf/pulse/agent/spool
```

目录规则：

- agent 启动时创建 `files_dir`、`workspace_dir` 和 `spool_dir`。
- 文件上传默认写入 `files_dir`。
- shell 脚本默认写入 `workspace_dir/scripts/<task_id>/script.sh`。
- shell 执行默认工作目录为 `workspace_dir`。
- 所有路径都必须用 canonical path 校验，禁止 `..`、绝对路径逃逸、符号链接逃逸和控制字符文件名。
- 文件名来自 header 时只作为 display/original name；落盘路径必须使用 agent 侧安全归一化后的名称。

## 心跳响应格式

### JSON 响应

现有 JSON heartbeat response 保持不变：

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

body 继续是 `HeartbeatResponse`，用于下发普通 `PulseMessage`：

```json
{
  "accepted_seq": 42,
  "messages": [
    {
      "message_id": "cmd-shell-execute-01",
      "type": "cmd.shell_execute",
      "version": 1,
      "payload": {}
    }
  ]
}
```

### 二进制响应

当 coordinator 需要下发文件内容时，`/heartbeat` response 可以返回二进制 body：

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.pulse.binary
X-Pulse-Envelope-Version: 1
X-Pulse-Accepted-Seq: 42
X-Pulse-Message-Id: cmd-file-put-coordinator-a-01
X-Pulse-Message-Type: cmd.file_put
X-Pulse-Agent-Id: agent-a
X-Pulse-Task-Id: task-01
X-Pulse-File-Id: file-01
X-Pulse-File-Name: repair.sh
X-Pulse-File-Role: shell_script
X-Pulse-Target-Dir: workspace
X-Pulse-Content-Type: text/x-shellscript
X-Pulse-Content-Encoding: identity
X-Pulse-Content-Length: 12890
X-Pulse-Content-Sha256: sha256-of-raw-body
```

body 是原始文件字节，不做 JSON/base64 包装。

二进制响应规则：

- 一个 binary heartbeat response 只能承载一个文件。
- 文件名必须通过 `X-Pulse-File-Name` 声明。
- `X-Pulse-Content-Sha256` 必填，agent 必须在落盘前或落盘后校验原始 body hash。
- `X-Pulse-Content-Length` 必填，agent 必须校验读取字节数。
- `X-Pulse-File-Role` 用于声明用途：`generic_file` 或 `shell_script`。
- `X-Pulse-Target-Dir` 只能是 `files` 或 `workspace`，默认 `files`。
- `X-Pulse-Accepted-Seq` 保持原 heartbeat ack 语义，避免二进制响应破坏心跳确认。
- 二进制响应仍是 `/heartbeat` 的响应格式变化，不是新增 API。

### 为什么不用 multipart

第一版不使用 `multipart/mixed`：

- multipart 解析复杂度更高，容易引入边界、编码和内存放大问题。
- 一个 heartbeat round 下发一个文件更容易做幂等、hash 校验、重试和 backpressure。
- 多文件上传可以由 coordinator control queue 分多轮 heartbeat 下发，agent 逐个确认。

## 消息类型

### `cmd.file_put`

`cmd.file_put` 表示 coordinator 要求 agent 接收一个文件。

小文件可以用 JSON 消息承载：

```json
{
  "message_id": "cmd-file-put-01",
  "type": "cmd.file_put",
  "version": 1,
  "deadline_ms": 1710000030000,
  "payload": {
    "task_id": "task-01",
    "file_id": "file-01",
    "file_name": "config.json",
    "file_role": "generic_file",
    "target_dir": "files",
    "content_encoding": "base64",
    "content": "eyJrZXkiOiJ2YWx1ZSJ9",
    "content_sha256": "sha256-of-raw-content",
    "content_bytes": 15,
    "mode": "0644"
  }
}
```

大文件使用二进制 heartbeat response，header 中的 `X-Pulse-Message-Type=cmd.file_put` 表示同一语义。

字段规则：

- `file_id` 是文件内容幂等 key，同一 `file_id` + `content_sha256` 重复下发不得重复写坏文件。
- `file_name` 是原始文件名，必须归一化后落盘。
- `file_role=generic_file` 默认写入 `files_dir`。
- `file_role=shell_script` 默认写入 `workspace_dir/scripts/<task_id>/script.sh`。
- `mode` 只能在白名单内：普通文件 `0644`，shell 脚本 `0700`。
- 不允许 coordinator 指定任意绝对路径。

### `reply.file_received`

agent 完整落盘并校验通过后上报：

```json
{
  "message_id": "reply-file-received-agent-a-file-01",
  "type": "reply.file_received",
  "version": 1,
  "reply_to": "cmd-file-put-01",
  "payload": {
    "task_id": "task-01",
    "file_id": "file-01",
    "agent_id": "agent-a",
    "status": "received",
    "file_name": "repair.sh",
    "local_path": "/data24/otf/pulse/agent/workspace/scripts/task-01/script.sh",
    "content_sha256": "sha256-of-raw-content",
    "content_bytes": 12890,
    "received_at_ms": 1710000001000
  }
}
```

失败时仍使用 `reply.file_received`，`status=rejected|failed`，并提供 `runner_error`：

```json
{
  "type": "reply.file_received",
  "payload": {
    "task_id": "task-01",
    "file_id": "file-01",
    "agent_id": "agent-a",
    "status": "rejected",
    "runner_error": "sha256 mismatch"
  }
}
```

### `cmd.shell_execute`

shell 脚本必须先通过 `cmd.file_put` 成功下发，再执行：

```json
{
  "message_id": "cmd-shell-execute-01",
  "type": "cmd.shell_execute",
  "version": 1,
  "deadline_ms": 1710000630000,
  "payload": {
    "task_id": "task-01",
    "agent_id": "agent-a",
    "script_file_id": "file-01",
    "script_sha256": "sha256-of-script",
    "work_dir": "workspace",
    "args": ["--dry-run"],
    "env": {
      "PULSE_TASK_ID": "task-01"
    },
    "created_at_ms": 1710000000000
  }
}
```

执行规则：

- agent 必须确认 `script_file_id` 已收到且 hash 等于 `script_sha256`。
- `work_dir` 第一版只能为 `workspace` 或 `workspace/<safe-relative-dir>`，默认 `workspace`。
- `args` 必须按数组传入 `ProcessBuilder`，禁止 shell 字符串拼接。
- 执行形式为 `bash <local_script_path> <args...>`。
- `env` 只允许安全 key：`[A-Z_][A-Z0-9_]*`，禁止覆盖 `PATH`、`LD_PRELOAD`、`JAVA_TOOL_OPTIONS` 等高危变量，除非后续有专门白名单。
- 同一 agent 的 shell task 与现有 remote task 共用同一个串行 runner，并发度固定为 `1`。
- shell 输出继续使用 `reply.task_output_append` 和 `reply.task_result`，不引入新输出协议。
- `deadline_ms` 只表示消息生命周期/出队期限，不是 agent 子进程执行超时；shell task 执行不得由 coordinator 隐式超时强杀。

### `reply.task_accepted` 与 `reply.task_result`

`cmd.shell_execute` 复用现有 task 回执：

- 接收并排队后发送 `reply.task_accepted`。
- 执行中输出通过 `reply.task_output_append`。
- 完成后发送 `reply.task_result`。
- 无输出时仍通过 `state.async_tasks` 汇报 running 状态。

`reply.task_result.payload.task_type` 使用 `shell_script` 或更具体的 UI task type，例如：

```json
{
  "task_type": "shell_script",
  "status": "completed",
  "exit_code": 0,
  "output_type": "text",
  "output_encoding": "identity",
  "output_sha256": "..."
}
```

## Per-Agent 状态机

### Coordinator

coordinator 对每个 agent 维护独立状态机，而不是把 `cmd.file_put` 和
`cmd.shell_execute` 当成一次性 FIFO 消息。第一性原理约束：

- heartbeat response 是不可靠投递通道：coordinator 只能确认“已写入本次 response”，不能确认 agent 已处理。
- 只有 agent 后续 heartbeat 中的 `reply.file_received`、`reply.task_accepted`、`reply.task_result` 才是状态迁移依据。
- `cmd.shell_execute` 依赖本地 staged script，因此必须由 `reply.file_received status=received` 解锁。
- `cmd.file_put` 在 ack 前必须可重发；重复下发必须依赖 `file_id + sha256` 在 agent 侧幂等。
- 同一 agent 同一时刻最多推进一个控制动作，禁止跳过前置依赖。

```text
AgentControlState {
  agent_id
  control_queue: deque<ControlItem>
  file_transfers: map<file_id, FileTransfer>
  in_flight_tasks: map<task_id, RemoteTask>
  completions: deque<TaskResult>
}

ControlItem =
  FilePut(file_id, file_name, bytes/spool_ref, target_dir, sha256)
  ShellExecute(task_id, script_file_id, work_dir, args)

FileTransfer.status =
  queued
  delivering
  received
  failed | rejected | timed_out

RemoteTask.status =
  queued
  delivered
  accepted
  running
  completed | failed | rejected | timed_out
```

#### Coordinator 调度规则

- 每次 heartbeat response 最多推进一个 control item。
- `FilePut(queued|delivering)`：返回 `cmd.file_put`，并把状态标记为 `delivering`；ack 前不从队列删除，允许下一轮 heartbeat 重发。
- `reply.file_received status=received`：把 `file_transfers[file_id]` 标记为 `received`。
- 队首 `FilePut(received)`：从队列移除，继续检查下一个 item。
- 队首 `ShellExecute`：只有 `script_file_id` 对应 `FileTransfer.status=received` 时才能出队并返回 `cmd.shell_execute`。
- `ShellExecute` 出队后进入 `in_flight_tasks`，等待 `reply.task_accepted`、`state.async_tasks` 和 `reply.task_result` 推进状态。
- `FilePut(failed|rejected|timed_out)`：依赖该文件的 `ShellExecute` 必须变成明确 failed/rejected completion，不能静默 pending。
- 重复 heartbeat、response 丢失、agent 重启或 group leader 切换时，按 `file_id`、`task_id` 和 hash 幂等处理。

#### Shell 脚本状态机

```text
UI submit
  -> FilePut.queued + ShellExecute.blocked
  -> FilePut.delivering        (heartbeat response contains cmd.file_put)
  -> FilePut.delivering        (no ack; heartbeat may resend cmd.file_put)
  -> FilePut.received          (agent reply.file_received status=received)
  -> ShellExecute.delivered    (heartbeat response contains cmd.shell_execute)
  -> ShellExecute.accepted     (agent reply.task_accepted)
  -> ShellExecute.running      (agent state.async_tasks or output append)
  -> completed/failed/rejected (agent reply.task_result)
```

必须保持的不变量：

- 没有 `FilePut.received`，就不能出现 `ShellExecute.delivered`。
- `cmd.file_put` 可以重复出现；`cmd.shell_execute` 对同一 `task_id` 只能投递一次。
- `script_file_id`、`script_sha256`、`task_id` 是依赖关系的唯一来源，不能靠队列顺序猜测。
- UI 的“提交成功”只代表 coordinator 接收并建状态机，不代表 agent 已 staged 或执行完成。

### Agent

agent 侧维护两个本地表：

```text
ReceivedFiles[file_id] = {
  local_path,
  sha256,
  bytes,
  status
}

TaskRunnerQueue = FIFO shell/task execution queue, concurrency=1
```

agent 接收 `cmd.file_put`：

1. 校验 header/payload。
2. 写入临时文件 `${spool_dir}/incoming/<file_id>.tmp`。
3. 校验 length 和 sha256。
4. 使用 atomic move 移入目标目录。
5. 设置权限。
6. 记录 `ReceivedFiles[file_id]`。
7. 上报 `reply.file_received`。

agent 接收 `cmd.shell_execute`：

1. 校验 `script_file_id` 已存在。
2. 校验脚本 hash。
3. 校验工作目录在 `workspace_dir` 内。
4. 放入串行 `TaskRunnerQueue`。
5. 上报 `reply.task_accepted`。
6. 执行并回传 stream/result。

## Group Heartbeat

group 模式保持“不新增 API，不把权威下沉到 leader”：

- follower 的 file/script 控制权仍在 coordinator。
- group leader 只转发 `cmd.file_put`、`cmd.shell_execute`、`reply.file_received` 和 task reply。
- leader 不解析脚本内容，不改写 header，不重新计算业务 hash，最多做传输层缓存和 backpressure。
- 对 follower 下发二进制文件时，coordinator 的 group heartbeat response 必须能表达目标 `agent_id`。
- 第一版建议：group response 中的二进制下发一次只面向一个 follower；header `X-Pulse-Agent-Id` 指定目标 agent，leader 将该 binary envelope 缓存到该 follower 的 pending response queue。
- follower 下一次 `/group/heartbeat` 到 leader 时，leader 返回同样的 binary response envelope。
- 如果 leader 重启导致 pending binary 丢失，coordinator 会在未收到 `reply.file_received` 时重新下发。

## UI 设计

Run UI 增加两个独立操作区域：`文件上传` 和 `Shell 执行`。

交互原则：

- 文件上传和 Shell 执行是两个独立功能，不共享按钮语义。
- 文件上传只做文件投递，不触发执行。
- Shell 执行只表达“执行这段脚本内容”，不要求用户先在文件上传区域选择文件。
- Shell 执行在实现上可以通过 heartbeat 内部 staging 一个临时脚本文件，但这个 staging 是执行协议细节，不等价于用户发起的文件上传。
- UI 必须避免使用“推送并执行”这类容易混淆上传和执行的文案。

### 文件上传区域

能力：

- 选择目标：单 host 或当前 cluster。
- 选择本地文件。
- 展示文件名、大小、sha256、目标目录。
- 默认目标目录为 `$agent_work_dir/files`。
- 可选择目标目录：`$agent_work_dir/files` 或 `$agent_work_dir/workspace/files`。
- 点击“仅上传文件”后，UI 将文件内容交给 coordinator，coordinator 入队 `FilePut`。
- 文件上传成功只产生 `reply.file_received`，不产生 `reply.task_accepted` 或 `reply.task_result`。

约束：

- UI 不直接调用 agent。
- 文件名必须展示原名，但落盘名以 agent 归一化结果为准。
- 大文件上传前必须展示大小和 hash。
- 上传状态展示为 `queued`、`delivering`、`received`、`failed`。
- 批量上传到 cluster 时，UI 按 host 展示成功/失败数量，不回滚已成功主机。

### Shell 脚本区域

能力：

- 支持粘贴脚本内容。
- 展示脚本 sha256。
- 默认执行工作目录为 `$agent_work_dir/workspace`。
- 普通 Shell 脚本默认参数为空；只有预置 dry-run 任务才默认携带 `--dry-run`。
- 支持隐藏参数输入沿用现有 Run UI 规则。
- 点击“执行 Shell 脚本”时，coordinator 创建 shell execution task。
- 对 agent 的实现细节是：coordinator 为该 agent 建立 `FilePut(shell_script) -> ShellExecute` 状态机；`ShellExecute` 可以预先成为 blocked control item，但必须等 `reply.file_received status=received` 后才能下发。
- 内部 `FilePut(shell_script)` 的状态可以作为“脚本投递状态”展示，但不能混入“文件上传”结果列表，避免用户误解为上传了一个普通文件。

风险提示：

- shell 脚本是高风险能力，UI 必须明确展示目标 host 数、脚本 hash、参数和工作目录。
- 非 dry-run 参数必须二次确认，且进入 trace/detail。
- cluster 执行必须展示“批量执行会放大风险”。

### 输出展示

- 执行中状态继续展示在现有 task running 区域。
- 输出继续进入现有 stream viewer。
- completion 继续进入现有 completion queue。
- 文件上传本身不是 shell execution completion，但 UI 必须显示 `reply.file_received` 的状态卡，确保用户能看到 `queued/delivering/received/failed` 和失败原因。
- shell 执行必须在现有 completion viewer 中展示完整 `reply.task_result`；如果任务没有输出，UI 也必须展示完成状态、exit code、耗时和 output bytes，禁止空白。
- Shell 执行 UI 必须能展示 `script staging -> ShellExecute -> task_result` 的生命周期，但它属于 Shell 执行详情，不属于文件上传功能。

### Cluster Run UI

cluster Run UI 和 host Run UI 不能完全一致：

- host Run UI 面向单机诊断，展示详细文件状态、执行队列、trace、stream 和 completion。
- cluster Run UI 面向批量操作，默认展示提交进度和结果统计。
- cluster Run UI 必须展示目标 host 数、提交成功数、提交失败数、失败摘要。
- cluster Run UI 不应把第一台 host 的详细执行结果伪装成整个 cluster 的结果。
- 单台详细结果应通过每个 host 的 Run UI 查看。
- cluster 执行 Shell 时，UI 必须强调“批量提交已完成/部分失败”，实际完成结果需要按 host 回看或后续增加聚合 completion 统计。

## 安全边界

路径安全：

- 禁止绝对路径下发。
- 禁止 `..`。
- 禁止 NUL、换行和控制字符。
- 禁止跟随 symlink 逃出 `files_dir` 或 `workspace_dir`。
- atomic move 前后都要做 canonical path 校验。

内容安全：

- 所有文件必须有 sha256。
- binary response body 必须完整读取并校验 length/hash。
- 文件大小必须有限制；超过上限时 coordinator 拒绝入队。
- agent 本地 spool 必须有容量上限；超过时通过 heartbeat 上报 `backpressure_active` 或拒绝新文件。

执行安全：

- 禁止直接执行未 staged 的 inline command。
- shell 脚本只能通过 `bash <local_script_path> <args...>` 执行。
- 同一 agent 串行执行，避免多个修复脚本并发破坏状态。
- 普通 Shell 脚本默认参数为空；预置 dry-run 任务默认参数为 `--dry-run`。
- 非 dry-run 必须显式输入和审计。
- timeout 必填，有默认上限。

审计：

- `FilePut`、`ShellExecute`、`reply.file_received`、`reply.task_accepted`、`reply.task_result` 必须记录到 trace log。
- trace detail 至少包含 `file_id`、`file_name`、`sha256`、`bytes`、`work_dir`、`args`、`created_by`。
- completion queue 弹出不删除 trace。

## 失败处理

| 场景 | 处理 |
| --- | --- |
| heartbeat response 二进制读取失败 | agent 不落盘；下一轮 heartbeat 继续等待 coordinator 重发 |
| sha256 不匹配 | agent 删除临时文件，上报 `reply.file_received status=rejected` |
| 文件名非法 | agent 拒绝并上报原因 |
| spool 空间不足 | agent 拒绝或进入 backpressure，并在 `state.async_tasks`/file state 中上报 |
| `cmd.shell_execute` 先于文件到达 | agent 拒绝或保持 pending，但不得执行 |
| shell 超时 | agent kill process tree，上报 `reply.task_result status=timed_out` |
| heartbeat 到不同 coordinator | coordinator 通过现有 peer state/trace 最终一致收敛，agent 重复上报 reply |
| group leader 重启 | follower 或 coordinator 重试；文件未确认前不能进入 shell execute |

## 与现有 Remote Task 的关系

本设计是 `remote-task-execution.md` 的扩展，不替代现有 allowlist task：

- 预定义 task 继续走 `cmd.task_execute`。
- 上传文件走 `cmd.file_put`。
- 上传后执行 shell 走 `cmd.shell_execute`。
- shell 输出仍走 `reply.task_output_append` 和 `reply.task_result`。
- UI 可以把预定义 task 与文件/脚本操作放在同一个 Run UI modal 的不同区域。

## 实施门禁

- 不新增 agent API。
- 不新增 coordinator 到 agent 入站连接。
- 不新增 WebSocket/SSE。
- 二进制 heartbeat response 必须保留 `accepted_seq` 语义。
- header 必须声明文件名：`X-Pulse-File-Name`。
- 所有二进制 body 必须校验 `X-Pulse-Content-Sha256`。
- shell 脚本必须先 `cmd.file_put` 成功，再 `cmd.shell_execute`。
- agent shell runner 并发度固定为 `1`。
- 路径必须限制在 `$agent_work_dir/files` 或 `$agent_work_dir/workspace`。
- UI 只能扩展现有操作区和现有 coordinator API，不得引入新的 agent API。
