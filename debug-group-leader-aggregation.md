# Debug Session: group-leader-aggregation

Status: [OPEN]

## Symptom

- 部分机器 heartbeat 确认数不符合预期。
- 用户怀疑与 group leader 聚合/转发心跳逻辑有关。
- 需要依据 `docs/debug/arthas.md` 对 group leader 所在节点做运行时分析。

## Constraints

- 在获得运行时证据前，不修改业务逻辑。
- 优先使用 Arthas 和线上只读接口采集当前行为。
- 将当前聚合行为记录到 `docs/analysis`。

## Hypotheses

1. group leader 未实际接收部分 follower 的 `/group/heartbeat`，导致这些 follower 仍 direct 或确认数增长异常。
2. group leader 收到 follower 心跳，但批量上报 `/heartbeat` 时 `agents[]` 不完整或被过滤。
3. coordinator 返回的 per-agent `accepted_seq` 或 `cmd.group_plan` 未被 leader 正确转发给 follower，导致 follower 确认数不符合预期。
4. coordinator 动态 group plan 频繁变化，leader/follower 角色抖动，导致确认数在窗口内不稳定。
5. coordinator peer lazy sync 与 group source 展示存在延迟，表现为确认数异常但真实 leader 聚合正常。

## Evidence Log

- 2026-06-01 19:43：从 3 台 coordinator `/api/hosts` 采集当前 host/source/confirmation 分布，保存到 `.tmp/analysis/group-leader-aggregation/`。
- 2026-06-01 19:46：定位低确认 group 样本 `dc03-pf-t418-n048.byted.org`，source 为 `tlblog_stream_olap_separate/lq/000`，计算 leader 为 `dc03-pf-t400-n048.byted.org`。
- 2026-06-01 19:48：按 `docs/debug/arthas.md` 在 leader 部署 Arthas；发现 bundled JRE 缺少 attach API，改用远端 `/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java` 启动 Arthas。
- 2026-06-01 19:50：Arthas watch `GroupHeartbeatCollector#batch`，连续 5 次 `agents=7`。
- 2026-06-01 19:50：Arthas watch `GroupHeartbeatCollector#record`，观察到 `dc03-pf-t418-n048.byted.org` 连续进入 collector。
- 2026-06-01 19:57：Arthas watch coordinator `CoordinatorService#handleHeartbeat`，对 `tlblog_stream_olap_separate/lq/000` 连续观测到 `request_agents=7`、`response_agents=7`。
- 2026-06-01 19:59：leader journal 显示 batch 每 5 秒发往一个 coordinator，按 3 个 coordinator 轮询；单个 coordinator 直接接收间隔约 15 秒。
- 2026-06-01 20:01：对目标 follower 做 8 轮 `/api/hosts` 采样，稳定后 3 台 coordinator 均显示 `confirmations=4`、`status=alive`。

## Analysis

- H1 leader 未收到部分 follower：否定，Arthas `record` 捕获到目标 follower 连续上报。
- H2 leader batch 不完整：否定，Arthas `batch` 连续显示 `agents=7`。
- H3 coordinator 未处理 batch 或 response 不完整：否定，coordinator Arthas 显示 request/response 都是 7 个 agent。
- H4 group plan 抖动导致目标持续低确认：未发现持续证据，目标后续 8 轮稳定为 `confirmations=4`。
- H5 lazy sync/确认窗口导致短时观测差异：部分成立。leader 每 5 秒只发一个 coordinator，3 个 coordinator 轮询导致单 coordinator 直接接收约 15 秒一次；确认数是 20 秒窗口内 distinct seq 数，稳定常见值为 4，刚进入 group 或状态切换时会短暂低于 3。

## Output

- `docs/analysis/group-leader-aggregation.md`
