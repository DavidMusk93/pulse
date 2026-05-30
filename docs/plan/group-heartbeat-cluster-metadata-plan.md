# Group Heartbeat 与多集群 Agent 执行计划

## 执行原则

- 每个新需求必须先补 `docs/design` 与 `docs/plan`。
- 每次代码或脚本改动后必须本地验证。
- 每次有效改动必须及时提交并使用 `127.0.0.1:2080` 推送。
- 访问 coordinator Web/API 必须使用 `127.0.0.1:6699`。
- 部署前先 dry-run 确认目标机器范围。
- auto-ops 部署和验证统一使用 `--parallel 8`，避免过高并发导致远端更新慢和服务抖动。

## 阶段 1：设计与计划

- 新增 `docs/design/group-heartbeat-cluster-metadata.md`。
- 新增 `docs/plan/group-heartbeat-cluster-metadata-plan.md`。
- 明确 group heartbeat、tide metadata、Web 分组、Arthas、多集群部署与 Java 17 约束。

## 阶段 2：代码开发

- `AgentHeartbeatFactory`：
  - 增加 `cluster`、`area` 字段。
  - 从 `PULSE_AGENT_CLUSTER`、`PULSE_AGENT_AREA` 读取。
  - 心跳 payload 上报 `cluster`、`area`。
- `HostView`：
  - 增加顶层 `cluster`、`area` 字段。
- `CoordinatorService`：
  - 从 state 中提取 `cluster`、`area`。
  - host 列表按 `cluster/status/agentId` 排序。
- `HostTilesPage`：
  - 按 `cluster` 渲染 `cluster-section`。
  - 磁贴增加 `Area` 展示。
  - UI 重构为 `ui-ux-pro-max-skill` 取向的扁平化实时监控风格。
  - `/hosts` 改为前端 app shell，禁止 `<meta refresh>` 整页刷新。
  - 内嵌轻量 keyed runtime `PulseView`，不依赖公网 CDN；当前不引入 React。
  - `PulseView` 每 5s fetch `/api/hosts`，按 `cluster` 和 `agent_id` 复用 DOM 节点，仅更新文字、状态、排序和样式。
  - `PulseView` 每次刷新前后恢复 `window.scrollX/scrollY`，禁止刷新后页面滚到顶部。
  - `PulseView` 通过 DOM 节点复用自然保留磁贴内部滚动 cursor，禁止整块重建 `#pulse-app`。
  - 磁贴改为正方形，内部支持滚动，禁止文字覆盖。
  - 不同 cluster 使用不同低饱和冷静色，避免紫色、红色等高刺激亮色。
  - cluster 内按机器 `load` 降序排序。
  - `load` 越高磁贴色彩越重，并展示深色轨道和 cluster 深色填充的 load bar。
  - 去掉额外交互动效，保持自然滚动，禁止 `jelly-scroll`、持续水波或扫光背景动态。

## 阶段 2.1：减压型分组策略设计补充

- 明确当前缺口：
  - Web 展示分组已实现，但不能降低 coordinator 压力。
  - `/heartbeat` 的 `agents[]` 协议已实现，但线上 agent 尚未按 group 批量上报。
  - 真正缺少的是 group assignment、leader/follower 模式和 follower 到 leader 的 state 传递。
- 分组目标：
  - 同一集群内按 group 批量化心跳。
  - 每组最多 7 个 agent。
  - coordinator 请求数从 `N` 降为 `ceil(N / 7)`。
- 分组策略：
  - 一级边界：`cluster`。
  - 二级边界：`area`。
  - 组内排序：IPv6 近似度优先，缺失时按 `agent_id`。
  - `group_id` 格式：`cluster/area/shard_index`。
  - leader：组内排序后的第一个 alive agent。
- 演进路径：
  - 废弃生产路径中的 auto-ops 静态 group CSV。
  - coordinator 在 `handleHeartbeat` 后维护 `groupPlans` 和 `groupViews`。
  - agent 默认以 `dynamic` 模式从 heartbeat response message 中更新 plan，再切换 leader/follower。

## 阶段 2.2：Coordinator 动态分组实现

- `CoordinatorService`：
  - 新增 `groupPlans: agent_id -> AgentGroupPlan`。
  - 新增 `groups: group_id -> GroupView`。
  - 在 `handleHeartbeat` 和 `handleForward` 合并状态后重新计算 group assignment。
  - 按 `cluster -> area -> ipv6/agent_id` 稳定排序。
  - 每 7 个 alive agent 形成一个 group。
  - 每个 group 第一个 alive agent 为 leader。
- `CoordinatorHttpServer`：
  - 不新增非必要 API。
  - 继续只通过 `/heartbeat` 完成心跳和 group plan 交互。
- `PulseAgentApp`：
  - 默认 `PULSE_GROUP_MODE=dynamic`。
  - 每轮先生成 heartbeat，再从 heartbeat response 中解析 `cmd.group_plan`。
  - plan 为 `leader` 时聚合 follower 并批量上报。
  - plan 为 `follower` 时上报给 leader。
  - 非 leader 节点拒绝 `/group/heartbeat`，避免旧 leader 缓存 plan 继续传播。
  - leader 只接受当前 plan `members` 内的 follower，避免 stale follower 挤占 group batch。
  - leader 将 batch heartbeat response 中的 `agents[].messages[]` 转发给 follower。
  - agent 和 group leader 每轮 heartbeat 只写一个 coordinator，禁止向所有 coordinator 广播 `/heartbeat`。
  - plan 不存在或请求失败时 fallback 到 direct。
- `CoordinatorHttpServer`：
  - 在 `/heartbeat` 成功合并状态后，异步调用 peer `/heartbeat_fwd`。
  - `/heartbeat_fwd` 只发送 `state.*`，不发送 `cmd.group_plan`。
- 部署脚本：
  - 不再依赖静态 group CSV。
  - 默认写入 `PULSE_GROUP_MODE=dynamic`。
  - 默认写入 `PULSE_TTL_MS=30000`。
  - coordinator 写入 `PULSE_COORDINATOR_PEERS`，内容为除自身外的 peer coordinator URL。
  - 保留静态配置仅作为回滚或本地验证能力。

## 阶段 3：测试驱动

- 更新 `AgentHeartbeatFactoryTest`：
  - 验证 heartbeat payload 包含 `cluster`、`area`。
- 更新 `CoordinatorServiceTest`：
  - 验证 coordinator HostView 顶层字段包含 `cluster`、`area`。
  - 保持 group heartbeat per-agent `accepted_seq` 测试。
- 更新 `CoordinatorHttpServerTest`：
  - 验证 `/hosts` 包含 `cluster-section` 和 cluster 名称。
  - 验证 `/hosts` 包含正方形磁贴、内部滚动、load 排序和 keyed DOM refresh 相关 CSS/JS。
  - 验证 `/hosts` 不包含 `http-equiv="refresh"`。
  - 验证 `/hosts` 包含 `PulseView`、`fetch('/api/hosts'` 和 Keyed DOM refresh 文案。
  - 验证 `/hosts` 包含 `clusterSections`、`tiles`、`updateClusters`、`updateTiles`、`placeChild`、`restoreViewportScroll` 和低饱和 palette。
  - 验证 `/hosts` 不包含 `jelly-scroll`、`liquid-flow`、`water-ripple`、`repeating-radial-gradient` 和白色 load bar 填充。
- 执行：

```bash
bash -n docs/script/pulse-cdn-new-deploy.sh docs/script/pulse-cdn-new-probe.sh docs/script/pulse-cdn-new-verify.sh docs/script/pulse-arthas-deploy.sh
mvn test
mvn package
```

## 阶段 4：脚本开发

- `docs/script/pulse-cdn-new-deploy.sh`：
  - 增加部署参数 `cluster_fallback`。
  - 读取 `tide_worker` 环境变量。
  - 写入 `PULSE_AGENT_CLUSTER`、`PULSE_AGENT_AREA`。
  - 检测 Java 17+，低版本自动切换 bundled JRE。
  - 部署后显式 `restart` agent/coordinator。
- `docs/script/pulse-cdn-new-probe.sh`：
  - 输出 `tide_worker` pid 与 tide metadata。
- `docs/script/pulse-cdn-new-verify.sh`：
  - 输出 agent env 中的 cluster/area/role/zone。
- `docs/script/pulse-arthas-deploy.sh`：
  - 上传 Arthas boot jar 到 `/data24/otf/pulse/tools/arthas`。
- `docs/debug/arthas.md`：
  - 记录部署与使用方式。

## 阶段 5：本地提交

- 本地验证通过后提交并推送：

```bash
git add ...
git commit -m "Group hosts by cluster and capture tide metadata"
git -c http.proxy=socks5h://127.0.0.1:2080 \
    -c https.proxy=socks5h://127.0.0.1:2080 \
    push origin main
```

## 阶段 6：线上验证 Group Heartbeat

- 通过 `127.0.0.1:6699` 代理向 coordinator 发送 `agents[]` 请求。
- 验证响应：
  - `ok=true`
  - `accepted_seq=null`
  - `agents[].accepted_seq` 正确
- 拉取 `/api/hosts`：
  - 验证测试 agent 的 `source=group_id`
  - 验证 status 为 `alive`

## 阶段 7：多集群部署

- dry-run：

```bash
bash scripts/call.sh -f docs/script/pulse-cdn-new-deploy.sh -t cdn_new --dry-run
bash scripts/call.sh -f docs/script/pulse-cdn-new-deploy.sh -t doubao --dry-run
bash scripts/call.sh -f docs/script/pulse-cdn-new-deploy.sh -t tlbmirror --dry-run
```

- 真实部署：
  - `cdn_new`：50 台，包含 3 台 coordinator。
  - `doubao`：8 台 agent。
  - `tlbmirror`：5 台 agent。
- coordinator 地址：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

## 阶段 8：线上验证

- 使用 verify 脚本验证三组机器：
  - agent active
  - coordinator active
  - Java 17+ 或 systemd ExecStart 指向 bundled JRE
  - env 包含 cluster/area
- 通过 `127.0.0.1:6699` 访问 coordinator：

```bash
curl -g -sS --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/api/hosts"

curl -g -sS --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/hosts"
```

- 验证：
  - host count 达到预期。
  - status 主要为 `alive`。
  - API 顶层包含 `cluster`、`area`。
  - HTML 包含 `cluster-section`。
  - HTML 展示 `doubao`、`tlbmirror`、`cdn2` 或 tide 实际 cluster。

## 阶段 9：报告与收尾

- 新增 `docs/report/group-heartbeat-cluster-metadata-verification.md`。
- 记录：
  - group heartbeat 请求与响应。
  - 三组部署数量与验证结果。
  - coordinator API host count 与 cluster 分布。
  - Web 分组展示校验。
  - Java 17 与 bundled JRE 切换情况。
- 提交并使用 `127.0.0.1:2080` 推送。

## 当前进度

- 已完成代码开发、测试、打包。
- 已完成首次多集群部署。
- 已发现并修复两个部署问题：
  - 系统 Java 11 被误用，已改为 Java 17+ 检测与 bundled JRE 兜底。
  - 部署后未重启旧服务，已改为显式 restart。
- 已补充减压型分组策略设计：
  - 当前实现缺少 group assignment 和 agent leader/follower 模式。
  - 后续应按最多 7 个 agent 一组推进批量化心跳上报。
- 已发现静态 group plan 不符合最终目标：
  - 静态 plan 是验证捷径，不应作为生产路径。
  - group 逻辑必须放到 coordinator 心跳状态处理中维护。
- 下一步：
  - 实现 coordinator heartbeat response message 下发 group plan。
  - 部署 agent dynamic 模式。
  - 验证 `/heartbeat` response、leader 本地转发和 coordinator source 分布。
