# cdn_new 文件上传回执链路 Trace 报告

日期：2026-06-12

## 背景

- 用户现象：`cdn_new` 文件上传从“提交失败 34/50”修复后，页面一度显示“全部待回执，等待很久”。
- 设计依据：`docs/design/heartbeat-file-shell-control.md`。
- 调试原则：不基于猜测判断，使用 coordinator task snapshot、file transfer trace 和线上运行状态取证。

## 设计基线

设计文档要求：

- 文件下发必须走 heartbeat response，不新增 agent 入站 API。
- 上传完成的唯一前端依据是 coordinator 收到 `reply.file_received`。
- agent 完整落盘并校验后必须上报 `reply.file_received`。
- `reply.file_received` 成功时需要包含 `status=received`、`local_path`、`content_sha256`、`content_bytes`。
- 前端不能依赖 agent 本地状态，必须以 coordinator 中的 file transfer 状态为准。

对应当前实现：

- UI/HTTP submit：`POST /api/agents/{agentId}/tasks`，`operation=file_put`。
- coordinator 入队：`RemoteTaskService.enqueueFilePut` 生成 `ControlCommand.filePut` 和 `FileTransferStatus(status=queued)`。
- heartbeat 下发：`RemoteTaskService.nextCommand` 对 `file_put` 返回 `cmd.file_put`，并把状态标为 `delivering`。
- agent 落盘：`AgentTaskRunner.handleFilePut` 写入 `/data24/otf/pulse/agent/files` 后 enqueue `reply.file_received`。
- coordinator 回执：`RemoteTaskService.handleReplies` 接收 `reply.file_received`，`acceptFileReceived` 更新状态并写入 `file.received_by_agent` trace。

## 运行时证据

### 提交层

修复 IPv6 coordinator 路由后，同一 coordinator 本机复现：

```text
LOCAL
HTTP/1.1 200 OK
agent=fdbd:dc05:11:636::32
event=file.enqueued

REMOTE
HTTP/1.1 200 OK
agent=fdbd:dc02:1a:34::13
event=file.enqueued
```

50 台批量小文件提交探针：

```text
summary total=50 ok=50 fail=0
```

### 回执层

样本 trace：

```text
AGENT=fdbd:dc05:11:636::32
files [('otf.tar.gz', 'received', 1781234017607, '')]
traces [
  ('file.received_by_agent', 'fdbd:dc05:11:636::32',
   {'status': 'received',
    'local_path': '/data24/otf/pulse/agent/files/otf.tar.gz',
    'runner_error': ''}),
  ('file.enqueued', 'fdbd:dc05:11:636::32',
   {'bytes': 15033, 'role': 'generic_file', 'file_name': 'otf.tar.gz'})
]

AGENT=fdbd:dc02:1a:34::13
files [('otf.tar.gz', 'received', 1781234017276, '')]
traces [
  ('file.received_by_agent', 'fdbd:dc02:1a:34::13',
   {'status': 'received',
    'local_path': '/data24/otf/pulse/agent/files/otf.tar.gz',
    'runner_error': ''}),
  ('file.enqueued', 'fdbd:dc02:1a:34::13',
   {'bytes': 15033, 'role': 'generic_file', 'file_name': 'otf.tar.gz'})
]
```

50 台聚合状态：

```text
50 received
DETAILS_NON_RECEIVED
```

结论：当前线上 coordinator 已经收到 50/50 `reply.file_received`，文件 `otf.tar.gz` 全部落盘成功。

## 是否缺关键逻辑

当前不是“永久缺回执”问题，运行证据显示 `reply.file_received` 已完整回到 coordinator。

但从设计出发，当前链路确实存在一个应补强的关键逻辑：

- `GroupHeartbeatCollector.hasUrgentMessage` 把 `reply.task_accepted`、`reply.task_result`、`reply.task_result_chunk`、urgent `reply.task_output_append` 视为 urgent。
- `reply.file_received` 没有被列为 urgent。
- 文件上传的完成语义完全依赖 `reply.file_received`，因此它应和 task result 一样触发快速 flush，而不是只依赖常规 group heartbeat flush。

这不是本次 50/50 最终回执的阻断点，但会造成 UI 上“待回执”窗口不必要变长，尤其在 group leader 批处理、计划切换、外部链路抖动时更明显。

## 第一性链路结论

文件上传链路应该分成三个状态机，而不是只看 submit：

- submit state：UI 到 owner coordinator，目标是 `file.enqueued`。
- delivery state：owner coordinator 到 agent，目标是 agent 收到 `cmd.file_put`。
- ack state：agent 到 owner coordinator，目标是 `file.received_by_agent`。

本次实际问题分两段：

- 第一段已修复：IPv6 remote owner route URI 未加方括号，导致 remote-owner submit 400。
- 第二段经 trace 验证：`otf.tar.gz` 当前 50/50 已收到回执，不存在持续丢失。

## 建议

- 已补设计一致性修复：把 `reply.file_received` 加入 `GroupHeartbeatCollector.hasUrgentMessage` 的 urgent 类型。
- 已增加测试：当 follower 上报 `reply.file_received` 时，group collector 的 flush decision 返回 `URGENT_MESSAGE`。
- UI 侧显示应拆分 submit/delivering/received 三段，避免“已提交但待回执”被误解为失败。
- coordinator trace 应在 UI 展示最近 `file.enqueued`、`delivering`、`file.received_by_agent` 时间差，便于定位是哪一段慢。

## Follow-up Fix

- Code: `GroupHeartbeatCollector.hasUrgentMessage` now treats `reply.file_received` as urgent.
- Test: `GroupHeartbeatCollectorTest#flushDecisionClassifiesSelfDueFirstAgentDueUrgentAndBatchFull` now covers file ack urgent flush.
- Verification: `mvn -Dtest=GroupHeartbeatCollectorTest test` passed, 3 tests, 0 failures.
- Deployment: full `cdn_new` rollout passed, `total=50 ok=50 fail=0`.
- Deployed JAR SHA: `ee47865eecead79b8a3e3c2721d059783c1bcdb40b3c7961c4f0b73d74d44762`.
- Post-deploy probe:
  - Submit `urgent-postfix-probe.txt`: `total=50 ok=50 fail=0`.
  - After 12s during restart/plan convergence: `31 received`, `19 delivering`.
  - After an additional 35s: `50 received`, no non-received details.

## UI Pending Evidence And Fix

User reported that tests were still shown as all pending. Runtime evidence from coordinator contradicted the UI:

```text
STATUS_COUNTS
     50 received
FILE_COUNTS
     50 otf.tar.gz
NON_RECEIVED
```

Frontend root cause:

- File upload batch summary did not preserve the submitted `file_id`.
- `clusterExecutionRow` only matched `task_id`, so file-only uploads in a batch had no matched item and fell through to `pending`.
- Success classification only considered task completions, not `file_transfers.status=received`.

UI fix:

- File upload submit now stores latest submitted generic file `file_id` per agent.
- Cluster row matching now accepts `file_id`/`fileId`.
- `file_transfers.status=received` is classified as success.
- `delivering` and `received` now have explicit UI labels/colors.
- Verification: `npm run build` passed.
- Commit: `6fe7839 Fix file upload batch receipt summary`.
- Coordinator UI deployment: 3/3 coordinators active with JAR SHA `2de8200b207e0cda30aa20d4d2d354aacb7317cd80da35cafd4d8c064de2cdee`.
