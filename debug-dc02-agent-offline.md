# Debug Session: dc02-agent-offline

Status: [OPEN]

## Symptom

- 节点 `fdbd:dc02:11:c::43` 很容易被判定离线或进入 `warming`。
- 当前 UI 显示该节点 `mode=direct`、`group=direct`、`age` 容易接近或超过 heartbeat interval。
- 用户要求使用 debug 技能分析原因。

## Constraints

- 在获得运行时证据前，不修改业务逻辑。
- 优先使用线上 API、systemd/journal、Arthas 或只读命令采集证据。
- 临时产物放入 `.tmp/analysis/dc02-agent-offline/`。

## Hypotheses

1. agent 进程或 systemd 服务在该节点上频繁重启，导致 epoch/seq 或心跳窗口重置，确认数长期不足。
2. agent 仍在 direct 模式且每轮 heartbeat 轮询 3 个 coordinator，单个 coordinator 直接接收间隔约 15s；如果 `/heartbeat_fwd` 未及时收敛，UI 容易看到低确认或离线。
3. 该节点到部分 coordinator 的网络请求超时或失败，导致 heartbeat 发送间隔不稳定。
4. agent heartbeat 构造或采集过慢，例如读取 tide_worker/proc 信息卡顿，使 5s 心跳周期被拉长。
5. coordinator 侧状态合并窗口过窄或时间戳异常，使该节点的 recent confirmations 被过早淘汰。

## Evidence Log

- 2026-06-01 20:30：对三台 coordinator `/api/hosts` 连续采样 10 轮，确认 `n043` 在 `alive/warming` 间抖动，`group_id` 在 `tlblog_stream_olap_separate/hl/000` 与 `direct` 间切换。
- 2026-06-01 20:32：登录 `fdbd:dc02:11:c::43`，确认 `pulse-agent` 运行 5h+，未频繁重启。
- 2026-06-01 20:33：读取目标节点 `/data24/otf/pulse/logs/pulse-agent.err`，发现大量 `409 not_group_member`，目标是 `http://[fdbd:dc02:11:c::14]:9977`。
- 2026-06-01 20:34：检查目标 agent 进程，监听 `9977` 正常，direct coordinator heartbeat 存在成功记录。
- 2026-06-01 20:35：在 leader `fdbd:dc02:11:c::14` 使用 Arthas watch `GroupHeartbeatReceiver#handleHeartbeat`，确认 `acceptedMembers` 不包含 `dc02-p11-tc-n043.byted.org`。
- 2026-06-01 20:37：Arthas watch `GroupHeartbeatReceiver#setAcceptingFollowers`，确认 leader 成员集在 `dc02-p11-tc-n043` 和 `dc02-p11-t304-n049` 间切换。
- 2026-06-01 20:39：阅读 `CoordinatorService#recomputeGroups`，确认动态分组只纳入 `alive` host，`warming` host 被排除。

## Analysis

- H1 agent/systemd 频繁重启：否定。服务持续运行 5h+，没有 epoch 重置证据。
- H2 direct 轮询与 lazy sync 单独导致：部分相关但不是主因。direct fallback 存在，但关键错误是 follower 到 leader 被 `not_group_member` 拒绝。
- H3 网络到 coordinator 失败：非主因。存在少量 failed，但大量 direct heartbeat 成功，无法解释周期性 group 抖动。
- H4 heartbeat 构造/采集卡顿：未发现直接证据。当前 seq 停顿与 `not_group_member` 和 group plan 抖动更吻合。
- H5 coordinator 窗口和 group plan 抖动：成立。`recomputeGroups` 只纳入 `alive`，目标确认数降到 2 后进入 `warming`，被踢出 group；随后 agent/leader plan 存在一轮延迟，触发 `not_group_member`，fallback direct 后恢复，再次进入 group，形成循环。

## Output

- `docs/analysis/dc02-agent-offline.md`
