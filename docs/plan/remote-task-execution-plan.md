# Pulse Remote Task 执行机制实施计划

## 执行原则

- 先设计后实现，设计文档见 `docs/design/remote-task-execution.md`。
- 不新增 agent 入站接口，所有 agent 指令仍走 `/heartbeat` response。
- 不允许任意命令执行，第一版只支持 allowlist dry-run task。
- 每次代码改动后执行 `mvn test` 和 `mvn package`。
- 部署前必须 dry-run 确认目标范围。
- 有效改动必须及时提交并推送。
- 不做删除动作；临时验证文件放在 `.tmp/` 并保持忽略。

## 阶段 1：协议与模型

新增或调整模型：

- `RemoteTask`
  - `task_id`
  - `trace_id`
  - `agent_id`
  - `task_type`
  - `script_path`
  - `args`
  - `status`
  - lifecycle timestamps
  - `deadline_ms`
  - `attempt`
- `TaskResult`
  - `task_id`
  - `trace_id`
  - `agent_id`
  - `status`
  - `exit_code`
  - `stdout_tail`
  - `stderr_tail`
  - `output_truncated`
- `TaskTraceLogEntry`
  - `trace_id`
  - `task_id`
  - `agent_id`
  - `event`
  - `actor`
  - `source_id`
  - `observed_at_ms`
  - `detail`

新增消息类型：

- `cmd.task_execute`
- `reply.task_accepted`
- `reply.task_result`

约束：

- `cmd.task_execute` 必须携带 `trace_id`。
- `reply.task_*` 必须通过 `reply_to` 关联原始 `message_id`。
- agent 端必须再次校验 task allowlist，不能盲信 coordinator。

## 阶段 2：Coordinator 队列

新增 coordinator 内存组件：

- `TaskRegistry`
  - 维护 allowlist task。
  - 固定两个 task：
    - `prepare_disk_layout_dry_run`
    - `setup_local_dev_dry_run`
  - 自动填充 `script_path` 和 `args=["--dry-run"]`。
- `TaskQueueService`
  - `agent_id -> ExecutionQueue`
  - `agent_id -> CompletionQueue`
  - `trace_id -> TaskTraceLogEntry[]`
- `TaskIdGenerator`
  - 生成 `task_id`
  - 生成 `trace_id`

CoordinatorService 改动：

- 在 `handleHeartbeat` 单 agent 路径中：
  - 合并 `reply.task_accepted`。
  - 合并 `reply.task_result`。
  - 从该 agent execution queue 取待下发 task。
  - 在 response messages 中追加 `cmd.task_execute`。
- 在 batch `agents[]` 路径中：
  - 对每个 `AgentHeartbeat` 独立合并 task reply。
  - 对每个 agent 独立追加 `agents[].messages[]` 中的 `cmd.task_execute`。
- task 下发时写 trace：
  - `task.dequeued_for_delivery`
  - `task.delivered_to_agent` 或 `task.delivered_to_group_leader`
- task result 到达时：
  - 从 `in_flight` 移除。
  - 写入 completion queue。
  - 写 trace `task.result_received`。

边界：

- 不通过 `/heartbeat_fwd` 转发 `cmd.task_execute`。
- peer coordinator 第一版只看到状态最终一致，不共享 task queue。
- UI 操作应固定访问当前页面所在 coordinator。

## 阶段 3：Coordinator 控制面 API

在 `CoordinatorHttpServer` 增加最小 UI API：

- `GET /api/agents/{agent_id}/tasks`
  - 返回 execution queue。
  - 返回 completion queue latest result。
  - 返回 trace summary。
- `POST /api/agents/{agent_id}/tasks`
  - body: `{ "task_type": "prepare_disk_layout_dry_run" }`
  - 校验 allowlist。
  - 入队 execution queue。
  - 返回 `task_id`、`trace_id`、queue snapshot。
- `POST /api/agents/{agent_id}/tasks/completions/{task_id}/keep`
  - 记录 keep/display trace。
  - 不移除 completion。
- `POST /api/agents/{agent_id}/tasks/completions/{task_id}/pop`
  - 移除当前 completion。
  - 返回下一条 latest completion。

测试：

- 不在 API 中允许任意 `script_path`。
- 不在 API 中允许任意 args。
- 入队后 `args` 必须是 `["--dry-run"]`。
- unknown `task_type` 返回 400。
- pop 只影响 completion queue，不影响 trace log。

## 阶段 4：Agent TaskRunner

新增 `AgentTaskRunner`：

- 后台线程池，第一版并发度 `1`。
- 接收 `cmd.task_execute`。
- 校验 allowlist：
  - task id 和 script path 匹配。
  - args 必须包含 `--dry-run`。
  - 不允许额外 shell 片段。
- 幂等缓存：
  - `task_id -> accepted/running/completed`。
  - 重复 task 不重复执行。
- 执行命令：

```bash
bash "$script_path" --dry-run
```

- 超时默认 `120000ms`。
- 捕获 stdout/stderr。
- stdout/stderr tail 默认各 `64KiB`。
- 结果写入待上报 reply buffer。

PulseAgentApp 改动：

- 每次 heartbeat response 后处理 `cmd.task_execute`。
- task accepted 后尽快触发一次 heartbeat，携带 `reply.task_accepted`。
- task 完成后尽快触发一次 heartbeat，携带 `reply.task_result`。
- direct、leader、follower、dynamic 模式都共用同一 TaskRunner。

测试：

- allowlist task 被接受。
- 非 allowlist task 被拒绝。
- 重复 `task_id` 不重复执行。
- dry-run 参数被强制追加。
- stdout/stderr 被截断并设置 `output_truncated`。

## 阶段 5：Group Leader 转发

当前 `GroupHeartbeatReceiver` 已维护 follower plan messages。

需要补充：

- leader 从 coordinator batch response 的 `agents[].messages[]` 获取 follower 的 `cmd.task_execute`。
- leader 将 task message 放入对应 follower 的本地 response queue。
- follower 调用 `/group/heartbeat` 时返回自己的 messages。
- follower reply 仍在下一次 heartbeat 中给 leader，再由 leader batch 上报 coordinator。

测试：

- follower task 只返回给目标 follower。
- 非 member follower 不能获取 task。
- leader 不执行 follower task。
- follower result 能通过 batch 回到 coordinator completion queue。

## 阶段 6：Run UI

Host card：

- 每张卡片增加 `Run` 按钮。
- agent 非 `alive` 时 disabled。
- 按钮样式低对比、圆角、非危险色。

Task modal：

- 标题展示 agent IP 或 agent id。
- 标题下展示当前 `trace_id`，支持复制。
- 左侧展示 execution queue：
  - task type
  - status
  - created time
  - delivered time
  - trace id
- 右侧展示 completion queue latest result：
  - status
  - exit code
  - duration
  - finished time
  - stdout/stderr
- 下方 task selector：
  - `prepare_disk_layout_dry_run`
  - `setup_local_dev_dry_run`
- 操作按钮：
  - `Run dry-run`
  - `Keep result`
  - `Pop and show next`
  - `Close`

视觉要求：

- 弹窗宽度适合日志阅读。
- stdout/stderr 使用等宽字体和可滚动区域。
- 状态色低饱和。
- 所有矩形区域保持圆润边角。
- 不做整页刷新。
- 使用现有 `PulseView` keyed runtime，不引入外部 CDN。

测试：

- `/hosts` 包含 Run 按钮。
- `/hosts` 包含 task modal runtime。
- `/hosts` 包含两个 allowlist task id。
- `/hosts` 不包含任意命令输入框。
- 自动刷新不重建整个 app。

## 阶段 7：本地验证

执行：

```bash
mvn test
mvn package
```

建议补充集成测试：

- `CoordinatorServiceTest`
  - direct agent task enqueue -> heartbeat response 下发 -> result 上报 -> completion queue 可见。
  - batch heartbeat follower task 下发。
- `CoordinatorHttpServerTest`
  - task API 入队。
  - task API 拒绝 unknown task。
  - completion keep/pop。
  - `/hosts` UI 包含 Run modal。
- `AgentTaskRunnerTest`
  - dry-run command 生成。
  - allowlist 校验。
  - duplicate task 去重。

## 阶段 8：灰度部署

因为该能力涉及 agent 执行逻辑，不能只升级 coordinator。

建议顺序：

1. coordinator-only 部署到 3 台 coordinator，验证 UI API 和空队列无副作用。
2. 选择 1 台非关键 agent 灰度升级，验证 direct task dry-run。
3. 选择 1 个 group 中 follower 灰度，验证 coordinator -> group leader -> follower 链路。
4. 全量同步 3 个集群 coordinator & agent。

部署前 dry-run：

```bash
bash scripts/call.sh -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-deploy.sh -t cdn_new --dry-run
bash scripts/call.sh -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-deploy.sh -t doubao --dry-run
bash scripts/call.sh -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-deploy.sh -t tlbmirror --dry-run
```

全量部署范围：

- `cdn_new`：50 台，包含 3 台 coordinator。
- `doubao`：8 台 agent。
- `tlbmirror`：5 台 agent。

## 阶段 9：线上验证

基础验证：

- 三台 coordinator `/api/hosts` 仍显示 63 alive。
- 页面 Run 按钮存在。
- 空 execution queue 不影响 heartbeat。
- 没有 task 时 response messages 不额外膨胀。

direct task 验证：

- 对一台 direct 或 fallback agent 点击 Run。
- 验证 execution queue 出现 queued/running。
- 验证 agent 收到 `cmd.task_execute`。
- 验证 completion queue 出现 latest result。
- 验证 result 包含 `trace_id`、`exit_code`、stdout/stderr。

group follower task 验证：

- 对 follower agent 点击 Run。
- 验证 coordinator batch response 中 `agents[].messages[]` 包含 task。
- 验证 group leader 返回给目标 follower。
- 验证 follower result 经 leader batch 回到 coordinator。

UI 验证：

- completion latest result 默认保留。
- 点击 Keep 不移除 result。
- 点击 Pop and show next 后当前 result 被移除并展示下一条。
- stdout/stderr 展示可读，不撑爆页面。

Trace 验证：

- 每个 task 都有 `trace_id`。
- trace log 至少包含：
  - `task.enqueued`
  - `task.dequeued_for_delivery`
  - `task.delivered_to_agent` 或 `task.delivered_to_group_leader`
  - `task.accepted_by_agent`
  - `task.started`
  - `task.result_received`

## 阶段 10：风险与回滚

风险：

- agent 执行脚本路径在远端不存在。
- `setup-local-dev.sh --dry-run` 如当前未支持，需要先补脚本 dry-run 分支。
- 输出过大影响 heartbeat payload。
- coordinator 重启导致内存 queue 丢失。
- group leader 重启导致 follower 下行消息延迟。

缓解：

- allowlist task 上线前先通过 auto-ops 探测脚本存在性和 dry-run 支持。
- stdout/stderr tail 截断。
- task 超时。
- agent 幂等去重。
- UI 明确显示 trace-id 和状态。

回滚：

- 停止从 UI 入队新 task。
- coordinator 禁用 task API 或返回 503。
- agent 忽略 `cmd.task_execute`。
- 如需二进制回滚，使用既有部署脚本回滚到上一 commit jar。

## 第一版交付清单

- `docs/design/remote-task-execution.md`
- `docs/plan/remote-task-execution-plan.md`
- Coordinator task queue service。
- Coordinator task control API。
- Agent task runner。
- Group follower task 转发。
- Host card Run 按钮与 task modal。
- 单元测试与集成测试。
- 线上验证报告。
