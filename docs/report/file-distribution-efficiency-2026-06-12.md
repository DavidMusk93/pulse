# 文件分发效率评估报告

日期：2026-06-12

## 结论

初始评估时，系统只在“心跳连接/请求数”层面符合 group leader 预期，不在“文件内容分发字节”层面符合低 coordinator 压力预期。按建议开发后，P0/P1/P2/P3 已补齐指标、批量提交、group heartbeat 下行去重和单文件二进制响应。

- 符合预期的部分：50 台 cdn_new/cdn2 agent 当前稳定组成 7 个 group，7 个 leader 代表 50 个 agent 向 coordinator 提交 batch heartbeat，direct fallback 为 0。
- 已修复的部分：文件提交从 per-agent request 改为 `/api/files/batch_put`，group heartbeat response 内同内容文件从 per-agent inline payload 改为一份 `cmd.group_file_put` 加多份轻量 `cmd.file_put_ref`。
- coordinator 压力现在同时降低了连接数、heartbeat 请求数、上行提交字节和 group 下行文件 payload copy 数。
- P3 已补单文件二进制 heartbeat response：单 agent / follower 文件响应可用 raw bytes body，避免该段 wire 上的 base64 膨胀。
- 剩余差距：multi-agent coordinator batch response 仍使用 JSON/base64 承载 P2 group file object，因为设计中的 binary response 一次只承载一个文件 body。

## 设计对照

`docs/design/heartbeat-file-shell-control.md` 的关键约束：

- coordinator 到 agent 的控制指令只通过 `/heartbeat` response 或 group heartbeat response 下发。
- 小文件可以用 JSON `cmd.file_put` 承载 base64。
- 大文件应支持二进制 heartbeat response。
- 非目标明确写明：不在 group leader 上解包、改写、压缩或合并文件内容。

当前实现状态：

- 已实现 JSON `cmd.file_put`。
- 已实现 P2 `cmd.group_file_put` / `cmd.file_put_ref`，leader 在本地展开为标准 `cmd.file_put`。
- 已实现 `application/vnd.pulse.binary`、`X-Pulse-*` 二进制响应和 agent client 解码。
- group-level file object、content hash 去重、leader 一份内容多 follower 复用已落地在 group heartbeat response 内。
- group fanout 扩展突破了原文“非目标”，用于满足“coordinator 压力应该较低”的效率预期；P3 二进制响应已覆盖单文件 response，batch group object 仍保留 JSON 兼容路径。

## 当前链路

文件上传与下发链路：

- UI/API 调用 `POST /api/files/batch_put`，用一份文件内容携带多个 `agent_ids`。
- coordinator 为每个 agent 创建一个 `fileId` 和一个 per-agent `ControlCommand.filePut`。
- agent heartbeat 到 coordinator 时，coordinator 调用 `responseMessages(agentId)`。
- `responseMessages(agentId)` 每次返回 `cmd.group_plan` 加最多一个 `taskService.nextCommand(agentId)`。
- `nextCommand(agentId)` 对 file command 调用 `ControlCommand.toFilePutMessage(agentId)`。
- group batch response 生成后，coordinator 按 `content_sha256` 将重复 `cmd.file_put` 压缩成 leader 一份 `cmd.group_file_put` 和各 target 的 `cmd.file_put_ref`。

group leader/follower 链路：

- follower 把心跳发给 leader `/group/heartbeat`。
- leader 用 `GroupHeartbeatCollector` 聚合 follower heartbeat。
- leader 向 coordinator 发 batch heartbeat。
- coordinator 遍历 batch 内每个 agent，分别生成该 agent 的 `AgentHeartbeatResponse.messages`。
- leader 收到 batch response 后，先展开 `cmd.group_file_put`，再把每个 agent 的标准 messages 存入 `planMessages[agentId]`。
- follower 下一次请求 leader `/group/heartbeat` 时，leader 返回 `planMessages[agentId]`。

这说明 follower 的文件命令经 leader 转发，且同一 group 内相同文件内容已由 per-agent 消息优化为 per-group 共享对象。

## 运行时证据

### Host Group 状态

采样入口：`fdbd:dc05:11:634::45` 本机 `/api/hosts`。

```text
cdn_count 50
owners {
  fdbd:dc05:13:10c::40: 25,
  fdbd:dc05:11:634::45: 24,
  fdbd:dc07:0:810::44: 1
}
modes {
  leader: 7,
  follower: 43
}
groups {
  cdn2/gl/000: 1,
  cdn2/hl/001: 11,
  cdn2/yg/002: 7,
  cdn2/yg/003: 8,
  cdn2/yg/004: 7,
  cdn2/yg/005: 8,
  cdn2/yg/006: 8
}
```

### Group Metrics

采样窗口：最近 5 分钟，metric query range。

```text
group.submitted_agent_count series=7 latest_sum=50 latest_max=11
group.accepted_agent_count  series=7 latest_sum=50 latest_max=11
group.direct_fallback_count series=7 latest_sum=0  latest_max=0
group.leader_collect_ms     series=7 latest_max=1ms
group.group_latency_ms      series=7 latest_max=2.5ms
```

解释：

- 7 个 leader 每轮覆盖 50 个 agent。
- 没有 direct fallback。
- leader 本地聚合和 group latency 很低。
- group heartbeat 机制本身有效，问题不在 follower 绕过 leader。

### 受控上传 Probe

文件：`efficiency-probe-1781238290360.bin`

```text
content_bytes 131072
base64_chars 174764
submit_wall_ms 69
submit_status_counts {'200': 50}
submit_latency_ms {'min': 2, 'p50': 4.0, 'max': 19}
```

回执轮询：

```text
poll_elapsed_ms 110   status_counts {'delivering': 14, 'queued': 36}
poll_elapsed_ms 5157  status_counts {'delivering': 48, 'received': 2}
poll_elapsed_ms 10190 status_counts {'delivering': 31, 'received': 19}
poll_elapsed_ms 15234 status_counts {'received': 50}
```

最终延迟：

```text
delivery_latency_ms {'min': 10457, 'p50': 14783.0, 'p95': 15081, 'max': 15083}

by_mode
follower count 43 statuses {'received': 43} p50_ms 14783 max_ms 15083
leader   count 7  statuses {'received': 7}  p50_ms 14782 max_ms 15082
```

解释：

- 全部 50 台最终 received，功能正确。
- leader 与 follower 延迟几乎一致，且同 group 内更新时间高度一致，说明 group batch 分发路径在工作。
- 约 15s 的完成时间主要由 heartbeat/group flush cadence 决定，不是 HTTP submit 慢。

## Coordinator 压力估算

受控 probe 的 raw 文件为 `131072` bytes，base64 后为 `174764` chars。

当前 per-agent 复制：

```text
174764 * 50 = 8,738,200 base64 chars
```

理想 group-level 共享内容：

```text
174764 * 7 = 1,223,348 base64 chars
```

放大倍数：

```text
50 / 7 = 7.14x
```

这个估算还没有包含：

- JSON key/value 结构开销。
- 每个 `cmd.group_plan` 的 members 列表开销。
- UI submit 阶段对 50 台重复提交同一份内容。
- remote owner proxy 阶段对非本 coordinator owner 的重复转发。

因此 coordinator 压力并不低，只是从“50 个 agent 直连请求”降成“7 个 leader batch 请求”；文件内容流量仍按目标 agent 数线性增长。

## Hypothesis 判定

- H1 部分成立：follower 的 heartbeat/response 确实通过 group leader，runtime metric 显示 7 个 leader 覆盖 50 台，direct fallback 为 0。
- H2 成立：coordinator 仍为每个 agent 生成完整 base64 `cmd.file_put`，下行内容为 O(N * file_size)。
- H3 当前不成立：本次采样 direct fallback 为 0，50 台均在 group 模式内。
- H4 成立：已补的 `reply.file_received` urgent 只改善回执 flush，不改变文件内容下发策略。
- H5 成立：现有 metric 能证明 group heartbeat 生效，但缺少 `coordinator_response_bytes`、`leader_forward_bytes`、`file_payload_copy_count` 等一等指标。

## 是否符合预期

如果预期是“通过 group leader 降低 coordinator 连接数和 heartbeat 请求数”，当前符合。

如果预期是“文件内容由 coordinator 发给每个 group leader 一次，再由 leader 分发给组内 follower，从而显著降低 coordinator 文件字节压力”，当前不符合。

当前更准确的描述是：

```text
per-agent file command + group heartbeat transport
```

不是：

```text
per-group file object + leader fanout
```

## 建议方案

### P0 可观测性

已补运行时指标，不直接改分发语义：

- `group.response_bytes`：coordinator 生成的 group batch response 字节。
- `group.file_payload_bytes`：本轮 group response 内 `cmd.file_put` raw bytes 累加。
- `group.file_payload_base64_bytes`：本轮 group response 内 `cmd.file_put.content` base64 bytes 累加。
- `group.file_command_copy_count`：本轮 group response 内 `cmd.file_put` copy 数。
- `group.file_unique_content_count`：本轮 group response 内唯一 `content_sha256` 数。
- `group.file_shared_lower_bound_bytes`：按唯一内容去重后的 base64 lower bound。
- SQLite schema 已增加兼容迁移，旧 DB 启动时会自动 `ALTER TABLE` 补列。
- 验证：`mvn -Dtest=LocalMetricStorageTest test` 和 `mvn -DskipTests package` 通过。

后续可继续增强：

- `file.submit_bytes_total` 可基于 P1 batch submit 接口补更准确的 submit 侧单份/多份内容统计。
- `group.leader_forward_bytes` 可基于 P2/P3 的 leader fanout 与 binary follower response 补 leader 本地转发字节统计。
- trace 可继续增加 `group_id`、`leader_agent_id`、`content_sha256`，方便把 group file object 与回执串联。

### P1 降低提交侧压力

已把“批量文件上传”从 per-agent submit 改成批量 API：

```text
POST /api/files/batch_put
targets=[agentIds...]
content once
```

实现状态：

- UI 文件上传现在只发送一次 `agent_ids + content`。
- coordinator 按 owner coordinator 分组，本地批量入队，remote owner 每个 owner 只转发一次 batch body。
- `RemoteTaskService.enqueueFilePutBatch` 只 decode/hash 校验一次内容，再生成多个 per-agent transfer。
- 仍保持 per-agent `file_id`、per-agent trace、per-agent `reply.file_received`。
- 验证：`CoordinatorHttpServerTest#batchFilePutSubmitsOnePayloadForMultipleAgents+taskApiRoutesForwardedAgentRequestsToHeartbeatOwner`、`npm run build`、`mvn -DskipTests package` 通过。

效果：

- UI 到入口 coordinator：从 `N * file` 降到 `1 * file`。
- 入口 coordinator 到 remote owner：从 `remoteAgentCount * file` 降到 `remoteOwnerCount * file`。
- coordinator 到 group leader/agent 的下行仍是 per-agent file command，留给 P2。

### P2 降低 coordinator 下行字节

已引入 group-level file delivery：

- coordinator 在同一个 batch heartbeat response 内按 `content_sha256` 聚合同内容文件命令。
- batch heartbeat response 给 leader 一份 `cmd.group_file_put`，包含一份 base64 内容和 target member list。
- 每个目标 agent 的原始 `cmd.file_put` 被替换为轻量 `cmd.file_put_ref`。
- leader 收到 response 后在本地展开为标准 per-agent `cmd.file_put`，leader 自身和 follower 都继续走原有 `TaskRunner` 文件写入逻辑。
- follower 仍上报自己的 `reply.file_received`，coordinator 保持 per-agent 状态与审计。

实现状态：

- `CoordinatorService` 在生成 batch heartbeat response 后执行 group file compression，再写入 P0 指标。
- `PulseAgentApp.GroupHeartbeatReceiver` 展开 `cmd.group_file_put`，不新增 follower 入站 API。
- 每个 target descriptor 保留独立 `message_id`、`file_id`、`file_name`、`target_dir`、`file_role`、`mode`、`content_sha256` 和 `content_bytes`。
- 验证：`CoordinatorServiceTest#batchHeartbeatCompressesDuplicateFilePayloadsIntoGroupFileMessage+groupLeaderExpandsGroupFileMessageForFollowers` 与 `mvn -DskipTests package` 通过。

需要保留的约束：

- agent outbound-only。
- 不新增 follower 入站 API。
- 每个 follower 必须独立 hash 校验、独立回执。
- 当前 leader 不持久化 group file cache，仅在收到 batch response 时展开到 `planMessages`；后续如支持大文件/分片，需要补容量上限、TTL、sha256 校验、重启恢复/降级策略。

### P3 二进制响应

已实现设计文档中的单文件 binary heartbeat response：

- JSON/base64 仍是默认兼容路径。
- 当单 agent heartbeat response 内恰好有一个 `cmd.file_put` inline payload 时，HTTP response 使用 `Content-Type: application/vnd.pulse.binary`。
- raw 文件字节直接作为 body，`X-Pulse-File-*`、`X-Pulse-Content-*`、`X-Pulse-Message-*` headers 保留 envelope 元数据。
- agent client 验证 `X-Pulse-Content-Length` 与 `X-Pulse-Content-Sha256` 后，重建原有 `cmd.file_put` 给 `AgentTaskRunner`，落盘/回执状态机不变。
- `cmd.group_plan` 等 companion messages 通过 `X-Pulse-Companion-Messages` 保留，避免 binary 文件响应丢失 plan 更新。
- group leader 的 `/group/heartbeat` follower response 复用同一 binary writer，因此 follower 拉取单个文件时不再走 base64 wire body。

当前限制：

- coordinator 的 multi-agent batch heartbeat response 仍是 JSON，因为设计规定一个 binary heartbeat response 只能承载一个文件；P2 已把 batch 内同内容文件降为一个 group object，避免重新回到 per-agent copy。
- 若后续要求 `coordinator -> leader` 的 P2 group object 也使用 raw bytes，需要扩展 binary envelope 或新增单文件 group object response negotiation。

验证：

- `mvn -Dtest=CoordinatorHttpServerTest#heartbeatEndpointCanReturnBinaryFilePayload+agentHeartbeatClientParsesBinaryFilePayload test`
- `mvn -DskipTests package`

以本次 50 节点、7 group、128 KiB probe 粗估：

- 当前：约 `8.7M` base64 chars，不含 JSON。
- P2 group 去重：约 `1.2M` base64 chars，不含 JSON。
- P2 + P3 完整覆盖后的理论值：约 `917KiB` raw bytes；当前实现已覆盖单文件和 leader-to-follower 段，coordinator batch group object 段仍是 base64。

## 最终判断

当前系统功能正确、group heartbeat 有效，但文件分发效率只优化了控制面连接数，没有优化数据面字节数。

若“coordinator 压力应该较低”指文件内容压力，P0/P1/P2/P3 已分别覆盖可观测性、提交侧去重、group heartbeat 下行去重和单文件二进制响应；剩余主要差距是 multi-agent batch group object 的 raw binary envelope。
