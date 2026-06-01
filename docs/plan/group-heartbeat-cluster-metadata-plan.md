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
  - UI 定位为心跳平台控制台，所有面向用户的描述统一为简洁中文。
  - UI 重构为扁平化实时监控风格。
  - `/hosts` 改为前端 app shell，禁止 `<meta refresh>` 整页刷新。
  - 将内嵌轻量 runtime 升级为 React + Ant Design 本地打包应用，禁止运行时依赖公网 CDN。
  - `/hosts` 只输出 app shell，加载本地 `/assets/pulse-hosts.js` 与 `/assets/pulse-hosts.css`。
  - React app 每 5s fetch `/api/hosts`，按 IPv6 稳定 key 渲染 cluster 和 host。
  - 使用 Ant Design `Card`、`Statistic`、`Button`、`Select`、`Modal`、`Tabs`、`Badge`、`Progress`、`List` 等成熟组件替代粗糙手写组件。
  - 保留 `window.scrollX/scrollY` 与磁贴内部 scroll cursor，禁止整页刷新和整块重建。
  - 磁贴改为正方形，内部支持滚动，禁止文字覆盖。
  - 磁贴 header 改为单行短时间和纯文字任务按钮；不显示“在线”文字，不在 header 展示确认数，取消独立状态点。
  - `Confirm` 放入磁贴正文指标区，任务按钮放入 header 右侧。
  - `Seen` 时间在磁贴内使用 `YYYY/MM/DD HH:mm:ss`，禁止默认 datetime 在窄磁贴中断成多行。
  - 任务按钮只保留文字，不展示图标；按钮背景色承载状态语义，避免额外状态符号。
  - 磁贴最小宽度上调，避免 150px 宽度下 header 和 pid 信息被压缩。
  - `Coordinator` 卡片、磁贴时间等超长单行文本接入自适应字号缩放，优先避免溢出，再考虑省略。
  - Run UI 去掉右侧 `Trace` tab，改为左侧独立 `Trace` 卡片；每条 trace 展示时间、事件和 `task_id`。
  - 右侧结果区只承载 completion viewer，避免 trace/completion 共用 tab 内容层导致查看异常或状态无法恢复。
  - 调整 `结果查看` 卡片 head/body 留白和 completion 工具栏顶部间距，让标题区与格式化/拷贝按钮之间更舒展。
  - 顶部指标区改为等高卡片布局：列容器拉伸、卡片 `height: 100%`、`Statistic` 基线统一，避免 `Coordinator` 长值导致行内高度不齐。
  - tide worker 区改成轻量进程卡片，展示 `pid/cpu/user/sys/rss/mem/threads/port/version`，超出内容由 `.tile-scroll` 滚动承载。
  - agent 侧从 `/proc/$pid/stat` 计算 user/sys CPU percent，从 `/proc/$pid/status` 读取 `VmRSS` 和 `Threads`，环境变量字段保持不变。
  - 修复 Ant Design Card 内部 flex 高度，确保 `.tile-scroll` 是真实可滚动容器。
  - 不同 cluster 使用不同低饱和冷静色，避免紫色、红色等高刺激亮色。
  - cluster 内按固定窗口 `5min AVG` 降序排序。
  - `5min AVG` 越高磁贴色彩越重，并展示深色轨道和 cluster 深色填充的 load bar。
  - 卡片不展示瞬时 `Load`，只展示前端本地聚合的固定窗口 `5min AVG`。
  - `5min AVG` 在每个 5 分钟窗口开始时只采样计算一次，窗口内不继续累计、不更新展示值、不更新排序权重，避免频繁重排和视觉抖动。
  - 去掉额外交互动效，保持自然滚动，禁止 `jelly-scroll`、持续水波或扫光背景动态。
  - 去掉 UI 高光表达，禁止 `box-shadow`、`backdrop-filter`、`linear-gradient`、`radial-gradient` 等发光、模糊或渐变效果。
  - 任意可见位置只展示 IPv6；hostname、FQDN 和内部域名不得进入文案、DOM data key 或任务标题。
  - 页面描述保持心跳平台定位，精简表达任务、集群、资源、监控和告警能力，避免重复叙述。
  - 中文排版学习 Apple 官网的留白、克制字重和舒展字距，避免大标题拥挤。
  - 页面整体视觉重心必须均衡，避免顶部过轻、下方过重的“上细下粗”布局。
  - Run UI 按黄金分割组织：面板尺寸约为主页面视口的黄金比例，左侧信息区与右侧 completion 区约为 `1 : 1.618`。
  - Run UI 从 `state.async_tasks` 展示 agent `accepted/running` 状态，在 completion 结果返回前用结果区顶部提示条给出执行中反馈。
  - Run UI completion 输出必须展示；JSON 输出提供一键格式化、拷贝和基础高亮，并限制在结果框内部滚动，避免撑破 modal。
  - 任务按钮和工具栏按钮水平排列，禁止竖排、折行和压缩成不可读状态。
  - Run UI 去掉“执行任务”标题，由执行按钮表达语义；任务类型选择控件展开为操作栏主区域。
  - 关闭控件改为 macOS 风格窗口控制点，放在独立标题栏，不覆盖业务内容。

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
  - 验证 `/hosts` 包含正方形磁贴、内部滚动、`5min AVG` 排序和 keyed DOM refresh 相关 CSS/JS。
  - 验证 `/hosts` 不包含 `http-equiv="refresh"`。
  - 验证 `/hosts` 顶部文案和任务面板文案为简洁中文。
  - 验证 `/hosts` 包含 `PulseView`、`fetch('/api/hosts'` 和精简中文刷新文案。
  - 验证 `/hosts` 包含 `clusterSections`、`tiles`、`updateClusters`、`updateTiles`、`placeChild`、`restoreViewportScroll`、固定窗口 `5min AVG` 状态和低饱和 palette。
  - 验证 `/hosts` 不再展示 `Load` 字段，只展示 `5min AVG`。
  - 验证 `/hosts` 不包含 `jelly-scroll`、`liquid-flow`、`water-ripple`、`repeating-radial-gradient` 和白色 load bar 填充。
  - 验证 `/hosts` 不包含 `box-shadow`、`backdrop-filter`、`gradient`、`hostname`、`.byted.org`、`data-agent-id`、`data-coordinator-id`。
  - 验证 `/hosts` 包含 `normalizeAddress`、`data-agent-key`、`renderAgentTasks`、`activeAgentTask`、`task-progress-row`、`statusLabel`。
  - 验证 Run UI 包含黄金分割 CSS：`height: min(820px, 61.8vh)` 与 `grid-template-columns: minmax(300px, 1fr) minmax(0, 1.618fr)`。
  - 验证任务按钮包含 `white-space: nowrap` 与 `writing-mode: horizontal-tb`。
  - 验证 `recordLoadSamples` 仅在窗口开始时写入 `displayAvg`，窗口内不累计 `sum/count`。
  - 使用浏览器验证中文主标题不拥挤、任务标题与操作并排、macOS 风格关闭按钮不与内容重叠。
  - 使用 coordinator 线上浏览器验证磁贴 header 单行且含 date、无独立状态点、任务按钮承载状态色、pid 卡片可读、Run UI JSON completion 可展示/格式化/拷贝/高亮且内部滚动、agent 异步执行提示可见。
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
