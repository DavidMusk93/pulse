# Heartbeat 文件下发与 Shell 执行开发计划

## 目标

在不新增 agent API 的前提下，完成两类生产级能力：

- 文件上传：UI 将文件交给 coordinator，coordinator 通过 heartbeat response 下发到 agent，agent 默认写入 `$agent_work_dir/files`，上传结果必须回到前端。
- Shell 脚本执行：UI 将脚本交给 coordinator，coordinator 先通过 heartbeat 推送脚本到 agent，再下发执行指令，agent 在 `$agent_work_dir/workspace` 串行执行，执行结果必须回到前端。

## Spool 定义

`spool` 是 agent 本地磁盘暂存队列，不是最终结果存储。

用途：

- 临时保存正在接收但尚未完成校验的上传内容。
- 临时保存已经产生但尚未被下一跳确认接收的 `reply.file_received`、`reply.task_output_append`、`reply.task_result`。
- 在 heartbeat 失败、group leader 重启、coordinator 暂不可达或内存队列满时保证数据不丢。

门禁：

- spool 必须有容量上限。
- spool 达到上限时必须通过 heartbeat 上报 `backpressure_active`，或明确拒绝新文件/新任务。
- spool 中的数据一旦被下一跳确认接收，必须及时删除。
- 前端不读取 spool；前端只展示 coordinator 已收到并持久化到内存状态中的 upload status、stream log 和 completion result。

## 不变量

- 不新增 agent API。
- 不新增 WebSocket/SSE。
- 不新增 coordinator 到 agent 入站连接。
- 所有 coordinator 到 agent 的控制动作只通过 `/heartbeat` response 或 group heartbeat response。
- shell 脚本不能直接 inline 执行，必须先 `cmd.file_put` 成功，再 `cmd.shell_execute`。
- agent 本地 shell runner 并发度固定为 `1`。
- 上传结果必须通过 `reply.file_received` 回到 coordinator，并能被 UI 展示。
- shell 执行结果必须通过 `reply.task_output_append` 和 `reply.task_result` 回到 coordinator，并能被 UI 展示。
- 文件路径必须限制在 `$agent_work_dir/files` 或 `$agent_work_dir/workspace`。
- 所有文件内容必须校验 `sha256` 和字节数。
- 失败必须可观测：UI、trace、auto-ops verify 都能看到失败阶段和原因。

## 阶段拆分

### 阶段 1：文档与计划

交付：

- 澄清 `spool` 语义。
- 补充上传结果和 shell 结果必须回到前端的设计门禁。
- 形成本实施计划。

验证：

- 文档自检：包含 `spool`、`reply.file_received`、`reply.task_result`、不新增 API、安全门禁。

提交：

- `Clarify heartbeat file shell plan`

### 阶段 2：后端协议与 agent 执行

交付：

- coordinator per-agent control queue。
- 复用现有 `/api/agents/{agent}/tasks` endpoint，扩展 request body 支持 `operation=file_put|shell_script`，不新增 endpoint。
- 小文件第一版使用 JSON/base64 承载到 coordinator；coordinator 到 agent 优先实现 JSON `cmd.file_put`，为二进制 heartbeat response 保留协议扩展点。
- agent 实现 `cmd.file_put`：
  - 校验文件名、目标目录、大小、sha256。
  - 写入 `${spool_dir}/incoming` 临时文件。
  - atomic move 到 `$agent_work_dir/files` 或 `$agent_work_dir/workspace/scripts/<task_id>/script.sh`。
  - 上报 `reply.file_received`。
- agent 实现 `cmd.shell_execute`：
  - 校验 staged script 存在且 hash 匹配。
  - 在 `$agent_work_dir/workspace` 串行执行。
  - 输出复用 `reply.task_output_append`。
  - 结果复用 `reply.task_result`。
- coordinator 处理 `reply.file_received` 并将 upload status 暴露到现有 task snapshot。

验证：

- 单元测试覆盖 file put 成功、hash mismatch、非法路径拒绝。
- 单元测试覆盖 shell 必须依赖已收到脚本、串行执行、结果回传。
- 单元测试覆盖 `/api/agents/{agent}/tasks` 扩展 body 不新增 endpoint。
- `mvn test -DskipITs`。
- `mvn package -DskipITs`。

提交：

- `Implement heartbeat file shell backend`

### 阶段 3：前端 UI 与本地真实交互

交付：

- Run UI 新增 `文件与脚本` 操作区域。
- 文件上传：
  - 选择文件。
  - 展示文件名、大小、sha256。
  - 默认目标目录 `$agent_work_dir/files`。
  - 提交后展示 `queued/delivering/received/failed`。
- Shell 脚本：
  - 支持粘贴脚本或选择 `.sh` 文件。
  - 展示 sha256。
  - 默认工作目录 `$agent_work_dir/workspace`。
  - 默认参数 `--dry-run`。
  - 结果进入现有 stream/completion viewer。
- cluster scope 复用现有逐 host 入队模式，不新增 cluster task API。

验证：

- `npm run build`。
- headless Chrome 验证 UI 可操作，文件上传状态可见，shell completion 可见。
- 本地 coordinator + agent 端到端 smoke：
  - 上传小文本文件，UI 显示 received。
  - 上传并执行 `echo` shell，UI 显示 output 和 completion。

提交：

- `Add heartbeat file shell UI`

### 阶段 4：生产部署

使用最新 auto-ops skill：`/Users/bytedance/Documents/gitlab/olap-toolbox/docs/skill/auto-ops.md`。

运行模式：

- `central-runtime`
- runtime repo：`/Users/bytedance/Documents/gitlab/olap-toolbox`
- project repo：`/Users/bytedance/Documents/01_Projects/pulse`

流程：

1. `dry-run` 确认 `471` 台范围。
2. `demand` 获取同一范围权限。
3. canary 一台非关键 agent 部署并 verify。
4. staged rollout：
   - 第一批 `--parallel 1` 小范围。
   - 第二批 `--parallel 4`。
   - 全量 `--parallel 8`。
5. 强校验：
   - JAR SHA。
   - `pulse-agent.service=active`。
   - coordinator service active。
   - `$agent_work_dir/files`、`workspace`、`spool` 存在且权限正确。
   - 旧失败主机单独列出 SSH 阶段原因。

提交：

- 部署脚本或验证脚本如有改动，阶段内单独提交。

### 阶段 5：真实 coordinator 前端验证

验证入口：

- 真实 coordinator UI：`http://127.0.0.1:18081/hosts`
- 如需远端访问，使用现有 coordinator SOCKS：`127.0.0.1:6699`

验证项：

- UI 能看到 `文件与脚本` 区域。
- 单 host 文件上传：
  - 上传小文件。
  - 前端显示 `received`。
  - 远端 verify 文件存在、sha256 正确。
- 单 host shell 执行：
  - 上传并执行 dry-run 脚本。
  - 前端显示 accepted/running/completed。
  - stream viewer 显示输出。
  - completion viewer 显示 exit code、output、sha256。
- cluster 小范围验证：
  - 选一个小集群或 limit scope。
  - 执行 dry-run echo 脚本。
  - 前端显示每台 host 的上传/执行结果。

验收标准：

- 上传结果能回到前端。
- shell 执行结果能回到前端。
- 失败原因能回到前端。
- 不新增 agent API。
- 线上成功主机通过强校验。

