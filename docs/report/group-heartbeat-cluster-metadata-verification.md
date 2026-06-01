# Group Heartbeat 与多集群 Agent 验证报告

## 摘要

- 验证时间：2026-05-30
- coordinator 访问代理：`socks5h://127.0.0.1:6699`
- Git 推送代理：`socks5h://127.0.0.1:2080`
- coordinator：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- agent 范围：
  - `cdn_new`：50 台
  - `doubao`：8 台
  - `tlbmirror`：5 台
- 结论：符合预期。

## 设计与计划

- 设计文档：`docs/design/group-heartbeat-cluster-metadata.md`
- 执行计划：`docs/plan/group-heartbeat-cluster-metadata-plan.md`
- 调试文档：`docs/debug/arthas.md`

## 本地验证

已执行：

```bash
bash -n docs/script/pulse-cdn-new-deploy.sh docs/script/pulse-cdn-new-probe.sh docs/script/pulse-cdn-new-verify.sh docs/script/pulse-arthas-deploy.sh
mvn test
mvn package
```

结果：

- `mvn test`：13 个测试全部通过。
- `mvn package`：构建成功。
- 脚本语法检查：通过。

## 部署结果

### dry-run 范围

| 集群 | 机器数 |
| --- | ---: |
| `cdn_new` | 50 |
| `doubao` | 8 |
| `tlbmirror` | 5 |

### 重部署结果

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

### verify 结果

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

## Java 运行环境

部署过程中发现 `doubao` 与部分 `tlbmirror` 机器系统 Java 为 11，不能运行 Java 17 编译的 Pulse jar。

已修复：

- 部署脚本检测 Java 主版本。
- 系统 Java 小于 17 时打印 `JAVA_BIN_REJECTED`。
- 自动使用 bundled JRE：`/data24/otf/pulse/jre/bin/java`。
- 部署后显式 `restart` `pulse-agent.service` 与 `pulse-coordinator.service`，避免旧进程继续运行。

注意：

- `pulse-cdn-new-verify.sh` 中的 `JAVA_BIN=/usr/bin/java` 是 PATH 探测值。
- 判断实际运行 Java 以 systemd status/CGroup 或部署日志中 `ExecStart` 为准。
- 部署日志显示 Java 11 机器实际已通过 `/data24/otf/pulse/jre/bin/java` 启动。

## Tide Metadata

部署脚本从 `tide_worker` 进程读取：

- `_TIDELET_AREA` -> `PULSE_AGENT_AREA`
- `_TIDELET_CLUSTER_ID` -> `PULSE_AGENT_CLUSTER`

兜底：

- `tide_worker` 不存在时，`area=unknown`。
- `cluster` 优先使用 `_TIDELET_CLUSTER_ID`，否则使用部署参数，例如 `tlbmirror`。

线上观察到：

- `cdn_new` 多数机器上报为 `cdn2`。
- `doubao` 机器上报为 `doubao`。
- `tlbmirror` 部分机器上报为 `tlbmirror2`，一台无 tide cluster 时按部署参数上报为 `tlbmirror`。
- 少量机器 `area=unknown`，符合兜底策略。

## Coordinator API 验证

访问方式：

```bash
curl -g -sS --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/api/hosts"
```

三台 coordinator 验证结果一致：

| Coordinator | Host Count | Alive | Cluster Sections |
| --- | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 63 | 63 | 5 |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 5 |
| `fdbd:dc07:0:810::44` | 63 | 63 | 5 |

Cluster 分布：

| Cluster | Count |
| --- | ---: |
| `cdn2` | 49 |
| `cdn_new` | 1 |
| `doubao` | 8 |
| `tlbmirror` | 1 |
| `tlbmirror2` | 4 |

Area 分布：

| Area | Count |
| --- | ---: |
| `yg` | 38 |
| `hl` | 18 |
| `lf` | 2 |
| `gl` | 1 |
| `hj` | 1 |
| `lq` | 1 |
| `unknown` | 2 |

## Web 验证

访问方式：

```bash
curl -g -sS --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/hosts"
```

三台 coordinator 的 `/hosts` 页面均满足：

- 包含 `class="cluster-section"`。
- 包含 `cdn2`、`doubao`、`tlbmirror` 文案。
- 包含 `<span>Area</span>`。
- 按 cluster 分组展示 host 磁贴；该 UI 后续已重构为扁平化正方形 load-sorted 监控磁贴。
- 自动刷新仍为 5 秒。

## Group Heartbeat 验证

请求：

```json
{
  "group_id": "final-group-verify",
  "agents": [
    {
      "agent_id": "final-group-agent-1",
      "epoch": 1780125000000,
      "seq": 201,
      "ttl_ms": 30000,
      "messages": [
        {
          "message_id": "final-group-agent-1-201",
          "type": "state.heartbeat",
          "version": 1,
          "payload": {
            "host": "final-group-host-1",
            "ip": "fd00::201",
            "cluster": "group-test",
            "area": "area-test",
            "role": "group-agent",
            "zone": "area-test",
            "load": "0.10"
          }
        }
      ]
    },
    {
      "agent_id": "final-group-agent-2",
      "epoch": 1780125000000,
      "seq": 202,
      "ttl_ms": 30000,
      "messages": [
        {
          "message_id": "final-group-agent-2-202",
          "type": "state.heartbeat",
          "version": 1,
          "payload": {
            "host": "final-group-host-2",
            "ip": "fd00::202",
            "cluster": "group-test",
            "area": "area-test",
            "role": "group-agent",
            "zone": "area-test",
            "load": "0.20"
          }
        }
      ]
    }
  ]
}
```

响应：

```json
{
  "ok": true,
  "coordinator_id": "dc05-p11-t634-n045.byted.org",
  "accepted_seq": null,
  "messages": [],
  "agents": [
    {
      "agent_id": "final-group-agent-1",
      "accepted_seq": 201,
      "messages": []
    },
    {
      "agent_id": "final-group-agent-2",
      "accepted_seq": 202,
      "messages": []
    }
  ]
}
```

API 验证：

| Agent | Source | Cluster | Area | Status |
| --- | --- | --- | --- | --- |
| `final-group-agent-1` | `final-group-verify` | `group-test` | `area-test` | `alive` |
| `final-group-agent-2` | `final-group-verify` | `group-test` | `area-test` | `alive` |

## 已修复问题

- 修复 Java 11 误用问题：要求 Java 17+，低版本自动切换 bundled JRE。
- 修复部署后旧服务未重启问题：部署后显式 restart agent/coordinator。
- 修复 Web/API 缺少顶层 `cluster`、`area` 问题。
- 修复 Web 页面未按 cluster 分组问题。

## Dynamic Group 最终验证

验证时间：2026-05-30 16:55 CST。

设计约束：

- 不新增 `/api/groups` 或 `/api/agent-plan`。
- coordinator 通过 `/heartbeat` response message 下发 `cmd.group_plan`。
- group leader 监听 `/group/heartbeat`，接收 follower heartbeat。
- agent/group 每轮 heartbeat 只写一个 coordinator。
- coordinator 通过 `/heartbeat_fwd` 将 `state.*` lazy 同步给 peers。
- `/heartbeat_fwd` 不转发 `cmd.group_plan`。
- 非 leader 节点拒绝 follower `/group/heartbeat`，避免旧 plan 缓存继续传播。

部署结果：

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

Service verify：

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

Coordinator 收敛结果：

| Coordinator | Total | Alive | Expired | Direct | Groups | Max Group Source Count |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | 16 | 7 |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | 16 | 7 |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | 16 | 7 |

Cluster 分布：

| Cluster | Count |
| --- | ---: |
| `cdn2` | 49 |
| `cdn_new` | 1 |
| `doubao` | 8 |
| `tlbmirror` | 1 |
| `tlbmirror2` | 4 |

Group source 分布：

| Source | Count |
| --- | ---: |
| `cdn2/hl/000` | 7 |
| `cdn2/yg/000` | 7 |
| `cdn2/yg/001` | 7 |
| `cdn2/yg/002` | 7 |
| `cdn2/yg/003` | 7 |
| `cdn2/yg/004` | 7 |
| `doubao/hl/000` | 6 |
| `cdn2/hl/001` | 3 |
| `cdn2/yg/005` | 3 |
| 其余小 group | 1-2 |

Web 验证：

| Coordinator | `/hosts` 验证 |
| --- | --- |
| `fdbd:dc05:11:634::45` | 包含 `cluster-section`、`cdn2`、`doubao`、`tlbmirror`、`Area` |
| `fdbd:dc05:13:10c::40` | 包含 `cluster-section`、`cdn2`、`doubao`、`tlbmirror`、`Area` |
| `fdbd:dc07:0:810::44` | 包含 `cluster-section`、`cdn2`、`doubao`、`tlbmirror`、`Area` |

最终结论：

- `cdn_new`、`doubao`、`tlbmirror` 共 63 台 agent 全部 alive。
- 三台 coordinator 视图一致，均无 expired 和 direct。
- 最大 group source count 为 7，符合 group size 上限。
- group plan 获取链路完全走 heartbeat message，未新增 agent plan API。
- 更正：曾使用 leader batch heartbeat 广播到全部 coordinator 作为临时修复方向；该方向已废弃，正确设计是单点写入一个 coordinator，再由 coordinator 通过 `/heartbeat_fwd` 做最终一致同步。

## 后续建议

- verify 脚本增加 systemd `ExecStart` 输出，避免 PATH Java 与实际运行 Java 混淆。
- 对 group heartbeat 增加压测，观察单请求大批量 `agents[]` 的延迟和内存占用。
- 后续如需更细分聚合，可在 `cluster -> area -> IPv6 prefix` 三层维度扩展。

## Host Tiles UI 重构验证

验证时间：2026-05-30 18:34 CST。

设计来源：

- 参考 `https://github.com/nextlevelbuilder/ui-ux-pro-max-skill` 的 Flat Design、Real-Time Monitoring、Heat Map intensity、motion/reduced-motion 检查思路。

实现结果：

- `/hosts` 页面改为扁平化实时监控风格。
- 磁贴改为正方形，使用 `aspect-ratio: 1 / 1`。
- 磁贴内部使用 `tile-scroll`，内容过多时在磁贴内滚动。
- 不同 cluster 使用不同 `--cluster-hue`。
- cluster 内按 `load` 降序渲染。
- `load` 越高颜色越重，并展示底部 `load-bar`。
- 提供 `liquid-flow` 流水高光动效。
- 支持 `prefers-reduced-motion`。
- 页面不再包含旧的 `Windows Phone` 文案。

本地验证：

- `mvn test`：17 个测试全部通过。
- `mvn package`：构建成功。

部署结果：

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

Service verify：

| 集群 | 结果 |
| --- | --- |
| `cdn_new` | `summary: total=50 ok=50 failed=0` |
| `doubao` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | `summary: total=5 ok=5 failed=0` |

Coordinator 收敛与 UI 验证：

| Coordinator | Total | Alive | Expired | Direct | Groups | Max Group Source Count | UI Checks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | 16 | 7 | pass |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | 16 | 7 | pass |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | 16 | 7 | pass |

UI check 项：

- `flat square host tiles`
- `aspect-ratio: 1 / 1`
- `tile-scroll`
- `load-bar`
- `liquid-flow`
- `prefers-reduced-motion`
- `--cluster-hue`
- 不包含 `Windows Phone`

附带修复：

- 部署验证时发现 stale follower 仍可向旧 leader 上报，导致部分 follower 被 group batch 截断。
- 已修复为 leader 只接受当前 `cmd.group_plan.members` 内 follower。
- 非成员返回 `not_group_member`，触发 follower fallback 到 coordinator 获取新 plan。

## Host UI 前端框架化刷新验证

验证时间：2026-05-30 18:40 CST。

问题：

- 旧版 `/hosts` 使用 `<meta http-equiv="refresh" content="5">`，每 5 秒重新下载和渲染完整 HTML。
- 该方式会丢失页面滚动上下文，也会让 coordinator 重复生成完整页面。

实现结果：

- `/hosts` 改为现代前端 app shell。
- 页面内嵌轻量 reactive runtime `PulseView`，不依赖公网 CDN。
- `PulseView` 每 5 秒 fetch `/api/hosts`。
- 刷新只更新 `#pulse-app` 与 `#pulse-status`，不再整页刷新。
- 保留扁平化正方形磁贴、cluster 色彩、load 排序、磁贴内滚动和 `liquid-flow` 动效。

本地验证：

- `mvn test`：17 个测试全部通过。
- `mvn package`：构建成功。

部署结果：

| 集群 | 并发 | 结果 |
| --- | ---: | --- |
| `cdn_new` | 8 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 8 | `summary: total=5 ok=5 failed=0` |

Service verify：

| 集群 | 并发 | 结果 |
| --- | ---: | --- |
| `cdn_new` | 8 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 8 | `summary: total=5 ok=5 failed=0` |

Coordinator 收敛与 UI 验证：

| Coordinator | Total | Alive | Expired | Direct | Groups | Max Group Source Count | UI Checks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | 16 | 7 | pass |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | 16 | 7 | pass |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | 16 | 7 | pass |

UI check 项：

- `PulseView reactive dashboard`
- `data-framework="PulseView"`
- `fetch('/api/hosts'`
- `JSON diff refresh`
- `window.PulseView = PulseView`
- 不包含 `http-equiv="refresh"`
- `aspect-ratio: 1 / 1`
- `liquid-flow`

部署约束：

- 后续 auto-ops 部署和验证统一使用 `--parallel 8`。

## Host UI 滚动保持与柔和配色验证

验证时间：2026-05-30 18:59 CST。

问题：

- `PulseView` 每轮 JSON refresh 后重绘磁贴区域，导致用户在某个磁贴内部滚动时 cursor 回到顶部。
- 旧 palette 包含紫色、红色等高刺激色，长时间观察容易晃眼。

实现结果：

- `PulseView` 增加 `scrollPositions: new Map()`。
- render 前通过 `captureTileScroll` 按 `data-agent-id` 记录 `.tile-scroll` 的 `scrollTop/scrollLeft`。
- render 后通过 `restoreTileScroll` 恢复对应磁贴内部滚动 cursor。
- cluster palette 调整为低饱和冷静色：`[205, 188, 168, 146, 126, 95, 48, 215, 200, 178]`。
- 磁贴 HSL 饱和度从高饱和降为 `42%~44%`。
- 错误提示色从红色改为柔和琥珀/石板色。

本地验证：

- `mvn test`：17 个测试全部通过。
- `mvn package`：构建成功。
- 代码扫描确认不再包含旧紫/红 hue：`265`、`338`。
- 代码扫描确认不再包含红色错误提示：`#fee2e2`、`#7f1d1d`、`#fecaca`。

部署结果：

| 集群 | 并发 | 结果 |
| --- | ---: | --- |
| `cdn_new` | 8 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 8 | `summary: total=5 ok=5 failed=0` |

Service verify：

| 集群 | 并发 | 结果 |
| --- | ---: | --- |
| `cdn_new` | 8 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 8 | `summary: total=5 ok=5 failed=0` |

Coordinator 收敛与 UI 验证：

| Coordinator | Total | Alive | Expired | Direct | Groups | Max Group Source Count | UI Checks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | 17 | 7 | pass |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | 17 | 7 | pass |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | 17 | 7 | pass |

UI check 项：

- `scrollPositions: new Map()`
- `captureTileScroll`
- `restoreTileScroll`
- `data-agent-id`
- 低饱和 palette
- 不包含旧紫/红 hue
- 不包含红色错误提示
- 不包含 `http-equiv="refresh"`

## Host UI 水波动效与 Load Bar 可读性验证

验证时间：2026-05-30 19:15 CST。

问题：

- 旧 `liquid-flow` 扫光式高光会抢占视觉焦点。
- 旧 load bar 使用白色填充，在浅色磁贴或白色视觉背景下对比度不足。

实现结果：

- 移除 `liquid-flow` 扫光式高光。
- 改为低透明度 `water-ripple` 水波滚动纹理。
- 水波纹基于 `repeating-radial-gradient`，只在磁贴底部低透明度滚动。
- load bar 轨道改为 `rgba(15, 23, 42, .24)`。
- load bar 填充改为 `hsl(var(--cluster-hue) 48% 24%)`，不再使用白色填充。
- 保留 `prefers-reduced-motion`，关闭动效时不播放水波动画。

本地验证：

- `mvn test`：17 个测试全部通过。
- `mvn package`：构建成功。
- 代码扫描确认不再包含 `liquid-flow`。
- 代码扫描确认不再包含白色 load 填充 `rgba(255,255,255,.86)`。

部署结果：

| 集群 | 并发 | 结果 |
| --- | ---: | --- |
| `cdn_new` | 8 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 8 | `summary: total=5 ok=5 failed=0` |

Coordinator 收敛与 UI 验证：

| Coordinator | Total | Alive | Expired | Direct | Groups | Max Group Source Count | UI Checks |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | 16 | 7 | pass |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | 16 | 7 | pass |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | 16 | 7 | pass |

UI check 项：

- `water-ripple`
- `repeating-radial-gradient`
- 深色 load track
- 深色 cluster load fill
- 不包含 `liquid-flow`
- 不包含白色 load fill
- `prefers-reduced-motion`

## Host UI 果冻滚动纠偏

问题：

- 上一版把“滑动有果冻抖动效果”误解为磁贴底部持续 `water-ripple` 水波背景动态。
- 实际需求是用户滚动磁贴内容时，当前磁贴产生短暂果冻抖动反馈。

实现结果：

- 移除持续播放的 `water-ripple` 背景动画与 `repeating-radial-gradient` 水波纹。
- 新增滚动触发的 `jelly-scroll` 动画，只在 `.tile-scroll` 发生滚动时短暂作用于当前磁贴。
- `restoreTileScroll` 恢复滚动位置时通过 `suppressJelly` 抑制动画，避免自动刷新导致磁贴自己抖动。
- 增加 `jellyLastPlayedAt` 节流，避免连续滚动事件造成过度抖动。
- 保留 `prefers-reduced-motion`，用户关闭动效时不触发果冻动画。

本地校验：

- VS Code diagnostics：`HostTilesPage.java` 与 `CoordinatorHttpServerTest.java` 无错误。
- 代码扫描确认源码不再包含 `water-ripple`、`repeating-radial-gradient` 和 `liquid-flow` 实现。
- 当前本机环境无 `mvn` 且项目无 `mvnw`，未能执行 Maven 单测。

### Coordinator-only 升级验证

升级时间：2026-05-30 22:00 CST。

范围：

- 仅升级 3 台 coordinator：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- 使用 central-runtime：`/Users/david/Documents/fleet-ops/scripts/call.sh`
- Project 仓库：`/Users/david/Documents/projects/pulse`
- 产物：`target/pulse-0.1.0-SNAPSHOT.jar`
- Jar SHA256：`02c78ef4e75afbd4be97560f3d355967ae19f597bf8172b5bdd9544cc136d72a`

本地准备：

- 通过 Homebrew 安装 Maven 与 GNU coreutils，补齐 `setup-local-dev.sh` 和 auto-ops runtime 依赖。
- 修复 `setup-local-dev.sh` 的 Java 版本判断，支持 `21.0.11` 这类完整版本号。
- `bash docs/script/setup-local-dev.sh && mvn package` 通过。
- `mvn test`：17 个测试全部通过。

部署修正：

- `pulse-cdn-new-deploy.sh` 增加复用远端已有 `${install_root}/jre/bin/java` 的兜底逻辑。
- 原因：coordinator 机器已安装 bundled JRE，UI-only 升级不需要重新上传 JRE tarball。

Dry-run：

```bash
PATH="/opt/homebrew/opt/coreutils/libexec/gnubin:$PATH" \
AUTO_OPS_ARTIFACT_ROOT=/Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-jelly \
AUTO_OPS_REPORT_DIR=/Users/david/Documents/projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --limit-file /Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-jelly/coordinators.txt \
    --parallel 3 \
    --timeout 240 \
    --max-hosts 3 \
    --dry-run \
    --yes \
    -- /Users/david/Documents/projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar \
       'fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44' \
       /data24/otf/pulse - cdn_new
```

Dry-run 结果：

- `total=3`
- 选中 host：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

执行结果：

- 第一次执行未传 `cluster_fallback`，导致 3 台 coordinator 本机 agent env 的 `PULSE_AGENT_ROLE` 短暂变为 `unknown`。
- 已立即用相同 3 台范围补传 `cdn_new` 重新执行，恢复 `PULSE_AGENT_ROLE=cdn_new`。
- 最终部署结果：`summary: total=3 ok=3 failed=0`。
- 3 台 coordinator 均使用 `/data24/otf/pulse/jre/bin/java`，Java 版本为 `17.0.19`。

Service/API 验证：

| Coordinator | Agent | Coordinator | Host Count | Role |
| --- | --- | --- | ---: | --- |
| `fdbd:dc05:11:634::45` | active | active | 63 | `cdn_new` |
| `fdbd:dc05:13:10c::40` | active | active | 63 | `cdn_new` |
| `fdbd:dc07:0:810::44` | active | active | 63 | `cdn_new` |

Web UI 验证：

| Coordinator | `jelly-scroll` | `bindJellyScroll` | `playJelly` | `suppressJelly` | Forbidden 动效 |
| --- | --- | --- | --- | --- | --- |
| `fdbd:dc05:11:634::45` | yes | yes | yes | yes | no |
| `fdbd:dc05:13:10c::40` | yes | yes | yes | yes | no |
| `fdbd:dc07:0:810::44` | yes | yes | yes | yes | no |

Forbidden 动效确认不包含：

- `water-ripple`
- `repeating-radial-gradient`
- `liquid-flow`
- `http-equiv="refresh"`

## Tide Worker 指标与三次确认存活

需求：

- agent 磁贴不展示 hostname、`Seq`、`Rank`。
- cluster 只作为 section/group 表达，不在单个 agent 磁贴中重复展示。
- `PULSE_AGENT_CLUSTER` 是 cluster 唯一来源；缺失或空值统一为 `unknown`。
- agent 上报每个 `tide_worker` 的 `pid`、`cpu_percent`、`mem_percent`、`PORT1`、`TIDELET_COMPONENT_VERSION`。
- `pid` 变化作为 `tide_worker` 进程重启/替换信号。
- host 存活判断改为最近 `20s` 内至少收到 `3` 个不同 `epoch/seq` 心跳确认。

本地验证：

```bash
mvn test
mvn package
```

结果：

- `mvn test`：`18 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。
- Jar SHA256：`25528f5bc9dee26a0f50782b6cc0f5861cd6f022cd5b0d89ef53139147055cc1`。

部署范围：

| 集群 | 范围 | Dry-run | 部署结果 | Verify |
| --- | ---: | --- | --- | --- |
| `cdn_new` | 50 | `total=50` | `summary: total=50 ok=50 failed=0` | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `total=8` | `summary: total=8 ok=8 failed=0` | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 5 | `total=5` | `summary: total=5 ok=5 failed=0` | `summary: total=5 ok=5 failed=0` |

三台 coordinator API 验证：

| Coordinator | Total | Alive | Warming | Expired | Unknown Cluster | Confirmations | Tide Workers | Workers With PID |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | 2 | `3..4` | 291 | 291 |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | 2 | `3..4` | 291 | 291 |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | 2 | `3..4` | 291 | 291 |

Web UI 验证：

- 包含：`tide_worker`。
- 包含：`pid ${escapeHtml(worker.pid`。
- 包含：`host.heartbeat_confirmations`。
- 包含：`data-field="ip_title"`。
- 不包含：`data-field="host"`。
- 不包含：`<span>Seq</span>`。
- 不包含：`<span>Rank</span>`。
- 不包含：`jelly-scroll`、`water-ripple`、`http-equiv="refresh"`。

说明：

- 本次改动包含 agent 侧采集逻辑、coordinator 侧 alive 规则和 UI 展示，因此已全量同步 3 个集群的 coordinator & agent。
- `.tmp/verify_tide_worker_alive.py` 是本次手工验证脚本，位于已忽略的 `.tmp/` 下，不纳入提交。

## Host Tile Header Seen 调整

需求：

- 卡片 header 展示 `Seen` datetime。
- 磁贴正文不展示 `Role`。
- 磁贴正文不展示 `Source`。

本地验证：

```bash
mvn test
mvn package
```

结果：

- `mvn test`：`18 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。
- Jar SHA256：`fdf5afd7c3029d4fadb99cfb129cceac7084e4fb373cc4f2f86b34229916026d`。

部署范围：

- 本次仅修改 coordinator 内嵌 `/hosts` 页面渲染和设计文档，不涉及 agent 采集或心跳协议。
- 仅升级 3 台 coordinator：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

部署结果：

- Dry-run：`total=3`。
- 部署：`summary: total=3 ok=3 failed=0`。

验证结果：

| Coordinator | Total | Alive | Warming | Expired | Required UI | Forbidden UI |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | `seen`, `formatSeen`, `ip_title` | none |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | `seen`, `formatSeen`, `ip_title` | none |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | `seen`, `formatSeen`, `ip_title` | none |

Forbidden UI 确认不包含：

- `data-field="role"`
- `data-field="source"`
- `data-field="identity"`
- `<span>Role</span>`
- `<span>Source</span>`
- `<span>Seq</span>`
- `<span>Rank</span>`

## Host Tile 去 Zone 与圆角

需求：

- `Area` 和 `Zone` 展示重复，磁贴只保留 `Area`。
- 矩形或长方形 UI 元素增加圆润边角。

本地验证：

```bash
mvn test
mvn package
```

结果：

- `mvn test`：`18 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。
- Jar SHA256：`683463e10d3978f9d7f04fa0f275443cda1d17d029bbcc157ce92e286a3af4bf`。

部署范围：

- 本次仅修改 coordinator 内嵌 `/hosts` 页面渲染和设计文档，不涉及 agent 采集或心跳协议。
- 仅升级 3 台 coordinator：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

部署结果：

- Dry-run：`total=3`。
- 部署：`summary: total=3 ok=3 failed=0`。

验证结果：

| Coordinator | Total | Alive | Warming | Expired | Required UI | Forbidden UI |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 | `border-radius: 22px`, `border-radius: 14px`, `border-radius: 999px`, `area` | none |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 | `border-radius: 22px`, `border-radius: 14px`, `border-radius: 999px`, `area` | none |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 | `border-radius: 22px`, `border-radius: 14px`, `border-radius: 999px`, `area` | none |

Forbidden UI 确认不包含：

- `data-field="zone"`
- `<span>Zone</span>`
- `data-field="role"`
- `data-field="source"`
- `<span>Seq</span>`
- `<span>Rank</span>`

## Host UI 去动效与 Keyed DOM 刷新

问题：

- 果冻滚动反馈实际交互体验不自然，滚动时额外抖动会干扰监控阅读。
- 旧 `PulseView` 每轮刷新使用 `app.innerHTML = renderApp(...)` 重建 `#pulse-app`，即使保存并恢复 `scrollTop/scrollLeft`，仍会重新创建 `.tile-scroll`，导致滚动条和滚动状态有明显刷新感。
- 直接引入 React 会带来前端构建链路或内嵌大体积运行时代码；当前页面只需要 keyed DOM 复用，不需要复杂组件生态。

实现结果：

- 删除 `jelly-scroll` 果冻动画、滚动监听、动画节流和 `prefers-reduced-motion` 相关逻辑。
- 保留无额外交互动效的自然滚动。
- `PulseView` 改为 keyed DOM refresh：
  - `clusterSections: new Map()` 按 cluster 复用 section。
  - `tiles: new Map()` 按 `agent_id` 复用 host tile。
  - `updateClusters` 只移动、增删 cluster section，不整块重建 app。
  - `updateTiles` 只移动、增删 tile，不重建已有 `.tile-scroll`。
  - `updateTile` 只更新文字、状态 class、load style 和 rank。
- 增加 `placeChild`，只有节点顺序确实变化时才移动 DOM，避免每轮 refresh 重复 append 已有 section/tile。
- 增加 `restoreViewportScroll`，每次 render 前记录 `window.scrollX/scrollY`，DOM 更新后通过 `window.scrollTo` 恢复页面级滚动位置，避免刷新后回到顶部。
- `.tile-scroll` DOM 节点持续存在，因此滚动条和滚动 cursor 由浏览器自然保留。
- `.tmp/` 已加入 `.gitignore`，auto-ops 临时产物不再显示为未跟踪文件。

本地校验：

- VS Code diagnostics：`HostTilesPage.java` 与 `CoordinatorHttpServerTest.java` 无错误。
- 单元测试需覆盖：
  - 包含 `PulseView keyed dashboard`。
  - 包含 `Keyed DOM refresh`。
  - 包含 `clusterSections: new Map()`、`tiles: new Map()`、`updateClusters`、`updateTiles`、`getOrCreateTile`、`placeChild`、`restoreViewportScroll`。
  - 不包含 `jelly-scroll`、`playJelly`、`water-ripple`、`repeating-radial-gradient`、`liquid-flow`、整页 refresh。

补充纠偏：

- 线上验证发现 keyed DOM 版本仍会在自动刷新后滚到页面顶部。
- 原因是刷新过程中仍对已有 section/tile 执行 `appendChild`，触发浏览器滚动锚点变化；同时没有保护页面级 viewport scroll。
- 已补充 `placeChild` 与 `restoreViewportScroll` 解决该问题。

Coordinator-only 升级验证：

- 升级时间：2026-05-30 22:12 CST。
- Jar SHA256：`4f352e17967dc84d3eb83ad64df0ef10bfe327a2e505915724b8980472d4a9d8`。
- Dry-run 结果：`total=3`，仅包含 3 台 coordinator。
- 部署结果：`summary: total=3 ok=3 failed=0`。
- Service/API 验证：3 台 `pulse-coordinator.service` 均 active，`HOST_COUNT=63`。
- Web 内容验证：
  - 3 台均包含 `restoreViewportScroll`、`window.scrollTo(viewport.left, viewport.top)`、`placeChild`、`Keyed DOM refresh`。
  - 3 台均不包含 `jelly-scroll`、`playJelly`、`water-ripple`、`repeating-radial-gradient`、`liquid-flow`、`http-equiv="refresh"`。

### Coordinator-only 升级验证

升级时间：2026-05-30 22:09 CST。

范围：

- 仅升级 3 台 coordinator：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- 产物：`target/pulse-0.1.0-SNAPSHOT.jar`
- Jar SHA256：`4c717dd0a80335deac446d7722882f463742ff6e4ccd6146823a9e3c70f05533`

本地验证：

- `mvn test`：17 个测试全部通过。
- `mvn package`：构建成功。

Dry-run 结果：

- 使用 `--limit-file .tmp/auto-ops/coordinator-ui-keyed/coordinators.txt`。
- 选中 host 数：`total=3`。
- 选中 host 与预期 3 台 coordinator 一致。

部署结果：

- `summary: total=3 ok=3 failed=0`
- 3 台 coordinator 均使用 `/data24/otf/pulse/jre/bin/java`。
- Java 版本：`openjdk version "17.0.19" 2026-04-21`。

Service/API 验证：

| Coordinator | Agent | Coordinator | Host Count | Role |
| --- | --- | --- | ---: | --- |
| `fdbd:dc05:11:634::45` | active | active | 63 | `cdn_new` |
| `fdbd:dc05:13:10c::40` | active | active | 63 | `cdn_new` |
| `fdbd:dc07:0:810::44` | active | active | 63 | `cdn_new` |

Web UI 验证：

| Coordinator | Keyed Title | Keyed Mode | Clusters Map | Tiles Map | `updateTiles` | Forbidden 动效 |
| --- | --- | --- | --- | --- | --- | --- |
| `fdbd:dc05:11:634::45` | yes | yes | yes | yes | yes | no |
| `fdbd:dc05:13:10c::40` | yes | yes | yes | yes | yes | no |
| `fdbd:dc07:0:810::44` | yes | yes | yes | yes | yes | no |

Forbidden 动效确认不包含：

- `jelly-scroll`
- `playJelly`
- `water-ripple`
- `repeating-radial-gradient`
- `liquid-flow`
- `http-equiv="refresh"`

## Heartbeat Platform UI 与 Run UI 验证

验证时间：2026-06-01 11:38 CST。

需求：

- 页面描述保持心跳平台定位，简洁表达任务、集群、资源、监控和告警能力。
- UI 去掉高光、模糊、渐变、水波、扫光和果冻动效。
- 任意可见位置只展示 IPv6，不展示 hostname 或内部域名。
- Run UI 展示 agent heartbeat 中的 `async_tasks` 执行中状态。
- 任务按钮文字水平排列。
- Run UI 使用黄金分割布局，右侧 completion 区与左侧信息区约为 `1.618 : 1`。
- 使用真实浏览器确认。

本地验证：

```bash
mvn test
mvn package
```

结果：

- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。

浏览器验证：

- 启动本地 coordinator：`PULSE_PORT=9967`。
- 注入测试心跳：
  - `host=demo-host.byted.org`
  - `ip=fdbd:dc05:11:634::45`
  - `state.async_tasks[0].status=running`
- 使用 headless Chrome + CDP 打开 `http://127.0.0.1:9967/hosts`，点击首个 `任务` 按钮并测量 DOM。

浏览器测量结果：

| 项目 | 结果 |
| --- | --- |
| `modalOpen` | `true` |
| `aliveTileCount` | `1` |
| `taskAgent` | `fdbd:dc05:11:634::45` |
| `taskCurrent` | `执行中` |
| `agentTaskStatusVisible` | `true` |
| `visibleTextHasHostname` | `false` |
| `visibleTextHasIPv6` | `true` |
| `htmlHasForbiddenStyle` | `false` |
| `htmlHasForbiddenData` | `false` |
| `workspaceToSidebar` | `1.618` |
| `panelToViewportHeight` | `0.621` |
| `outputBelowCompletion` | `false` |
| `outputBelowPanel` | `false` |
| `horizontal` | `false` |

## Run UI 标题移除与选项展开验证

验证时间：2026-06-01 12:16 CST。

需求：

- 去掉 Run UI 中的 `执行任务` 标题。
- 由 `执行` 按钮表达动作语义。
- 展开任务类型选项卡，避免操作区拥挤。

实现结果：

- 移除 `id="task-title"` 与 `id="task-trace"` DOM。
- `task-panel` 改用 `aria-label="任务面板"`。
- `.task-hero` 改为单行操作区。
- `#task-type` 使用 `flex: 1 1 0` 与 `min-width: 180px`，作为操作栏主区域展开。

本地验证：

```bash
mvn test
mvn package
```

结果：

- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。
- Jar SHA256：`e322950d3c552f73a14c5bd4733f1aadcf369e3b64c5b823743a12a334a0f67b`。

浏览器验证：

- 启动本地 coordinator：`PULSE_PORT=9972`。
- 使用 headless Chrome + CDP 打开 `http://127.0.0.1:9972/hosts`，点击首个 `任务` 按钮并测量 DOM。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `modalOpen` | `true` |
| `hasTaskTitle` | `false` |
| `hasTaskTrace` | `false` |
| `htmlHasTitleId` | `false` |
| `selectShare` | `0.598` |
| `controlsSameRow` | `true` |
| `htmlHasForbiddenStyle` | `false` |

控件宽度：

| 控件 | 宽度 |
| --- | ---: |
| 操作栏 | `392px` |
| 任务类型选择 | `235px` |
| `执行` | `57px` |
| `弹出结果` | `84px` |

线上部署与验证：

- 升级时间：2026-06-01 12:16 CST。
- 仅升级 3 台 coordinator。
- Jar SHA256：`e322950d3c552f73a14c5bd4733f1aadcf369e3b64c5b823743a12a334a0f67b`。
- `summary: total=3 ok=3 failed=0 elapsed=2s`。
- 三台 `pulse-coordinator.service` 均为 `active`。

远端浏览器验证：

- 访问：`http://[fdbd:dc05:11:634::45]:9966/hosts`，Chrome 通过 `socks5://127.0.0.1:6699` 代理。

| 项目 | 结果 |
| --- | --- |
| `tileCount` | `471` |
| `aliveTileCount` | `471` |
| `modalOpen` | `true` |
| `hasTaskTitle` | `false` |
| `hasTaskTrace` | `false` |
| `htmlHasTitleId` | `false` |
| `selectShare` | `0.598` |
| `controlsSameRow` | `true` |
| `visibleTextHasHostname` | `false` |
| `htmlHasForbiddenStyle` | `false` |

远端控件宽度：

| 控件 | 宽度 |
| --- | ---: |
| 操作栏 | `392px` |
| 任务类型选择 | `235px` |
| `执行` | `57px` |
| `弹出结果` | `84px` |

按钮测量：

| 按钮 | `white-space` | `writing-mode` | 尺寸 |
| --- | --- | --- | --- |
| `任务` | `nowrap` | `horizontal-tb` | `38x21` |
| `执行 dry-run` | `nowrap` | `horizontal-tb` | `103x43` |
| `弹出结果` | `nowrap` | `horizontal-tb` | `84x43` |
| `关闭` | `nowrap` | `horizontal-tb` | `57x43` |

执行状态可见内容：

```text
agent 执行中
执行中
prepare_disk_layout_dry_run
trace: trace-1
```

源码与测试门禁：

- `/hosts` HTML 不包含 `box-shadow`、`backdrop-filter`、`gradient`、`hostname`、`.byted.org`、`data-agent-id`、`data-coordinator-id`。
- `/hosts` HTML 包含 `normalizeAddress`、`data-agent-key`、`renderAgentTasks`、`activeAgentTask`、`task-progress-row`、`statusLabel`。
- Run UI 包含 `height: min(820px, 61.8vh)` 与 `grid-template-columns: minmax(300px, 1fr) minmax(0, 1.618fr)`。

后续部署范围：

- 本次只修改 coordinator 内嵌 `/hosts` 页面、测试和文档，不涉及 agent 心跳采集逻辑。
- 线上更新继续只升级 3 台 coordinator：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

### Coordinator-only 线上升级验证

升级时间：2026-06-01 11:45 CST。

范围：

- 仅升级 3 台 coordinator。
- 使用 auto-ops central-runtime：`/Users/bytedance/Documents/gitlab/olap-toolbox/scripts/call.sh`。
- 使用临时 callee 只替换 `/data24/otf/pulse/bin/pulse.jar` 并重启 `pulse-coordinator.service`。
- 未重写 agent env，未重启全量 agent。

Dry-run：

- `total=3`。
- 选中 host：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

部署结果：

- Jar SHA256：`b19db3d6529d4a6e126fbacefb2fcaac89ebb63553c8f7efe3e6d94d2504dbe0`。
- `summary: total=3 ok=3 failed=0 elapsed=3s`。
- 三台 `pulse-coordinator.service` 均为 `active`。

远端 API 与 HTML 验证：

| Coordinator | Total | Alive | Required UI | Forbidden UI |
| --- | ---: | ---: | --- | --- |
| `fdbd:dc05:11:634::45` | 471 | 468 | yes | no |
| `fdbd:dc05:13:10c::40` | 471 | 468 | yes | no |
| `fdbd:dc07:0:810::44` | 471 | 468 | yes | no |

Required UI 包含：

- `心跳驱动运维闭环`
- `data-agent-key`
- `renderAgentTasks`
- `activeAgentTask`
- `grid-template-columns: minmax(300px, 1fr) minmax(0, 1.618fr)`
- `height: min(820px, 61.8vh)`
- `writing-mode: horizontal-tb`

Forbidden UI 确认不包含：

- `box-shadow`
- `backdrop-filter`
- `gradient`
- `hostname`
- `.byted.org`
- `data-agent-id`
- `data-coordinator-id`
- `liquid-flow`
- `water-ripple`
- `jelly-scroll`

远端浏览器验证：

- 访问：`http://[fdbd:dc05:11:634::45]:9966/hosts`，Chrome 通过 `socks5://127.0.0.1:6699` 代理。
- `modalOpen=true`。
- `taskAgent=fdbd:dc02:1a:34::18`，任务面板目标只显示 IPv6。
- `visibleTextHasHostname=false`。
- `htmlHasForbiddenStyle=false`。
- `htmlHasForbiddenData=false`。
- `workspaceToSidebar=1.618`。
- `panelToViewportHeight=0.621`。
- `outputBelowCompletion=false`。
- `outputBelowPanel=false`。
- `horizontal=false`。
- 任务按钮与工具栏按钮均为 `nowrap/horizontal-tb`。

## Apple 风格排版与 Run UI 纠偏验证

验证时间：2026-06-01 11:56 CST。

需求：

- 中文展示学习 Apple 官网，减少拥挤感。
- 去掉“任务执行/执行任务”重复表达。
- 任务类型选择与执行按钮和标题并排，避免任务框压缩。
- 关闭按钮改为类似 macOS 软件窗口控制点，不覆盖业务 UI。
- `5min AVG` 只在窗口开始时计算一次，窗口内不频繁更新。
- 继续通过浏览器调试检查其他问题。

实现结果：

- Hero 标题改为 `心跳平台，连接运维现场`，放宽字距、行高和留白。
- 副标题改为 `任务、资源、监控与告警，沿一条消息链自然流动。`。
- Run UI 标题区改为两列：左侧 `执行任务`，右侧任务类型选择和操作按钮。
- 任务类型 option 展示为短中文：`磁盘布局 dry-run`、`块分布 dry-run`，value 保持原 allowlist。
- 移除工具栏 `关闭` 按钮，保留标题栏左侧 macOS 风格关闭点。
- `recordLoadSamples` 改为每个固定 5 分钟窗口开始时只写入一次 `displayAvg` 与 `sampledAtMs`，窗口内不再维护 `sum/count`。

本地验证：

```bash
mvn test
mvn package
```

结果：

- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。
- Jar SHA256：`dccdc07a1bb7015ea7801e8fb2183ce217ead066cc671515047fd741307a9c8d`。

浏览器验证：

- 启动本地 coordinator：`PULSE_PORT=9970`。
- 注入测试心跳，初始 `load=1.20`，随后同一 5 分钟窗口内更新为 `load=9.90`。
- 使用 headless Chrome + CDP 打开 `http://127.0.0.1:9970/hosts`，点击首个 `任务` 按钮并测量 DOM。

浏览器测量结果：

| 项目 | 结果 |
| --- | --- |
| `h1` | `心跳平台，连接运维现场` |
| `h1Style.letterSpacing` | `-1.856px` |
| `h1Style.fontWeight` | `780` |
| `subtitle` | `任务、资源、监控与告警，沿一条消息链自然流动。` |
| `titleToolbarSameRow` | `true` |
| `closeOverlapsHero` | `false` |
| `taskAgent` | `fdbd:dc05:11:634::45` |
| `taskCurrent` | `执行中` |
| `agentTaskStatusVisible` | `true` |
| `visibleTextHasHostname` | `false` |
| `htmlHasForbiddenStyle` | `false` |
| `htmlHasForbiddenData` | `false` |
| `workspaceToSidebar` | `1.618` |
| `panelToViewportHeight` | `0.621` |
| `outputBelowCompletion` | `false` |
| `outputBelowPanel` | `false` |
| `horizontal` | `false` |
| `firstLoad` | `1.20` |
| `secondLoadAfterInputChange` | `1.20` |
| `loadStableWithinWindow` | `true` |

按钮测量：

| 按钮 | 结果 |
| --- | --- |
| 磁贴任务按钮 | `nowrap/horizontal-tb/38x21` |
| `执行` | `nowrap/horizontal-tb/57x43` |
| `弹出结果` | `nowrap/horizontal-tb/84x43` |

结论：

- 中文标题不再使用过密负字距。
- 任务标题与任务操作已并排。
- macOS 风格关闭点位于标题栏，不覆盖任务卡片。
- `5min AVG` 在窗口内保持稳定。
- 未发现新的溢出、横向滚动、hostname 泄漏或高光样式回归。

### `5min AVG` 跨 Cluster 复测

浏览器调试追加发现：

- 初版修复把 `recordLoadSamples(hosts)` 放在每个 cluster 渲染入口内。
- 由于 `recordLoadSamples` 会清理本轮未出现的 agent，不同 cluster 轮流渲染会误删其他 cluster 的窗口状态。
- 结果是部分 tile 在下一轮刷新时重新采样，看起来仍在窗口内频繁更新。

修复：

- 将采样移动到 dashboard 入口：`recordLoadSamples(this.state.hosts)`。
- `updateTiles(grid, hosts)` 只负责排序和 DOM 更新，不再执行采样。
- 每轮 refresh 只对全量 hosts 采样一次，避免跨 cluster 互相清理。

复测：

- 本地 coordinator：`PULSE_PORT=9971`。
- 两个固定 IPv6，分属不同 cluster：
  - `fdbd:dc05:11:634::101` 初始 `1.20`，随后输入 `9.90`
  - `fdbd:dc05:11:634::102` 初始 `2.20`，随后输入 `8.80`
- 同一 5 分钟窗口内等待 5.8s 后再次读取同一 DOM key。

结果：

| IPv6 | First | Second | Stable |
| --- | ---: | ---: | --- |
| `fdbd:dc05:11:634::101` | `1.20` | `1.20` | yes |
| `fdbd:dc05:11:634::102` | `2.20` | `2.20` | yes |

### 线上二次升级与最终浏览器验证

升级时间：2026-06-01 12:04 CST。

部署结果：

- 仅升级 3 台 coordinator。
- Jar SHA256：`00d30a71173e25a860d4e347c2fe2e8dab7c2e505ccc973382a65cb41fecb129`。
- `summary: total=3 ok=3 failed=0 elapsed=2s`。
- 三台 `pulse-coordinator.service` 均为 `active`。

远端浏览器验证：

- 访问：`http://[fdbd:dc05:11:634::45]:9966/hosts`，Chrome 通过 `socks5://127.0.0.1:6699` 代理。
- 样本 tile：`fdbd:dc02:1a:34::16`。
- 同一 5 分钟窗口内读取两次：
  - 第一次：`274.24`，窗口位置 `150675ms`。
  - 第二次：`274.24`，窗口位置 `156482ms`。
  - `loadStableSameTileWithinWindow=true`。
- 页面源码确认：
  - `hasGlobalSampling=true`。
  - `hasTileSampling=false`。

UI 指标：

| 项目 | 结果 |
| --- | --- |
| `tileCount` | `471` |
| `aliveTileCount` | `471` |
| `h1` | `心跳平台，连接运维现场` |
| `subtitle` | `任务、资源、监控与告警，沿一条消息链自然流动。` |
| `titleToolbarSameRow` | `true` |
| `closeOverlapsHero` | `false` |
| `taskAgent` | `fdbd:dc02:1a:34::16` |
| `visibleTextHasHostname` | `false` |
| `htmlHasForbiddenStyle` | `false` |
| `htmlHasForbiddenData` | `false` |
| `workspaceToSidebar` | `1.618` |
| `panelToViewportHeight` | `0.621` |
| `outputBelowCompletion` | `false` |
| `outputBelowPanel` | `false` |
| `horizontal` | `false` |

## React + Ant Design UI 迁移验证

验证时间：2026-06-01 12:47 CST。

需求：

- 当前 UI 视觉重心“上细下粗”，需要学习 Apple 网站设计，形成更优雅的留白和布局。
- 当前 reactive 前端组件使用粗糙，需要引入 Ant Design 成熟组件库，为后续复杂功能提供组件基础。
- Ant Design 必须写入 `docs/design`，并替代当前 UI 中的手写组件。

实现结果：

- `/hosts` 改为最小 React app shell。
- 新增本地前端工程：`src/main/frontend`。
- 使用 React + Ant Design 组件重建页面：`Card`、`Statistic`、`Button`、`Select`、`Modal`、`Tabs`、`Badge`、`Progress`、`List`、`Typography`。
- 前端构建产物写入 `src/main/resources/static`：
  - `/assets/pulse-hosts.js`
  - `/assets/pulse-hosts.css`
- `CoordinatorHttpServer` 新增 classpath 静态资源服务，运行时不依赖公网 CDN。
- `HostTilesPage` 只输出 app shell，不再内嵌大型 HTML/CSS/JS 组件。

本地构建：

```bash
cd src/main/frontend
npm install
npm run build
cd /Users/bytedance/Documents/01_Projects/pulse
mvn test
mvn package
```

结果：

- `npm install`：`found 0 vulnerabilities`。
- `npm run build`：成功生成本地静态资源。
- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。

SHA256：

| 文件 | SHA256 |
| --- | --- |
| `target/pulse-0.1.0-SNAPSHOT.jar` | `f3edb6a0eb03b776192e0b6376b01751c3b5f2b2ff0318214b596fdbb83053f0` |
| `src/main/resources/static/pulse-hosts.js` | `f900dedcf9745dc53772746d5f73dbfa8d83ee4e52e7f82d6f3c879da100aaa4` |
| `src/main/resources/static/pulse-hosts.css` | `1fad0576214eea8bc687f9005b032b43e35a98cf1eeca2b7c78b55bed86c2439` |

浏览器验证：

- 启动本地 coordinator：`PULSE_PORT=9973`。
- 使用 headless Chrome + CDP 打开 `http://127.0.0.1:9973/hosts`。
- 注入两个 cluster 的测试心跳，并打开 Run UI。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `h1` | `心跳平台，连接运维现场` |
| `antCards` | `25` |
| `antButtons` | `8` |
| `tiles` | `4` |
| `heroBalance` | `1.0` |
| `workspaceToSidebar` | `1.618` |
| `modalOpen` | `true` |
| `hasTaskTitle` | `false` |
| `selectShare` | `0.526` |
| `buttonFlow` | `nowrap/horizontal-tb` |
| `agentStatusVisible` | `true` |
| `visibleTextHasHostname` | `false` |
| `htmlHasExternalCdn` | `false` |
| `htmlHasForbiddenData` | `false` |

结论：

- 页面已从手写大段 DOM/CSS 迁移为 React + Ant Design 本地组件应用。
- Hero 与右侧能力区高度平衡，视觉重心不再“上细下粗”。
- Run UI 保持黄金比例分栏、无标题、按钮水平和 agent 执行中状态反馈。
- 可见 UI 未出现 hostname，静态资源不依赖外部 CDN。

### 线上 Ant Design 页面验证

升级时间：2026-06-01 12:50 CST。

部署结果：

- 仅升级 3 台 coordinator。
- Jar SHA256：`f3edb6a0eb03b776192e0b6376b01751c3b5f2b2ff0318214b596fdbb83053f0`。
- `summary: total=3 ok=3 failed=0 elapsed=5s`。
- 三台 `pulse-coordinator.service` 均为 `active`。

远端浏览器验证：

- 访问：`http://[fdbd:dc05:11:634::45]:9966/hosts`，Chrome 通过 `socks5://127.0.0.1:6699` 代理。
- 本地静态资源由 coordinator 服务：`/assets/pulse-hosts.js`、`/assets/pulse-hosts.css`。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `h1` | `心跳平台，连接运维现场` |
| `antCards` | `497` |
| `antButtons` | `475` |
| `tiles` | `471` |
| `heroBalance` | `1.0` |
| `modalOpen` | `true` |
| `workspaceToSidebar` | `1.618` |
| `hasTaskTitle` | `false` |
| `selectShare` | `0.526` |
| `buttonFlow` | `nowrap/horizontal-tb` |
| `visibleTextHasHostname` | `false` |
| `htmlHasExternalCdn` | `false` |
| `htmlHasForbiddenData` | `false` |

`5min AVG` 窗口内稳定性：

| IPv6 | First | Second | Stable |
| --- | ---: | ---: | --- |
| `fdbd:dc02:1a:34::18` | `255.20` | `255.20` | yes |

结论：

- 线上 `/hosts` 已切换到 React + Ant Design 本地组件应用。
- 主视觉左右高度平衡，不再出现顶部轻、下方重的布局问题。
- Run UI 保持黄金比例、无标题、操作控件展开。
- 静态资源不依赖外部 CDN，可见 UI 仍保持 IPv6-only。

## Ant Design Host Tile Header And Scroll Fix

验证时间：2026-06-01 14:11 CST。

需求：

- 在线状态展示绿点即可，不显示文字。
- `Confirm` 不放在 header。
- `任务` 按钮放在 header。
- 修复卡片无法滚动。

实现结果：

- `HostTile` header 改为：`Seen` 时间、在线状态点、`任务` 按钮。
- 移除 header 内的 `在线` 文案和 `N 确认` 标签。
- `Confirm` 保留在正文 `Statistic` 指标区。
- `.tile-scroll` 改为 `flex: 1 1 0`、`min-height: 0`、`overflow-y: auto`，并保留 `overscroll-behavior: contain`。

本地验证：

```bash
cd src/main/frontend
npm run build
cd /Users/bytedance/Documents/01_Projects/pulse
mvn test
mvn package
```

结果：

- `npm run build`：成功。
- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。

SHA256：

| 文件 | SHA256 |
| --- | --- |
| `target/pulse-0.1.0-SNAPSHOT.jar` | `13dcc0f5f5b15ff5ea2fc3decc8c0534014366e689c486f44c5c85f45f7f463f` |
| `src/main/resources/static/pulse-hosts.js` | `caa795d9438794e4c753a02d5930cc59fdde43e9c0bef484ac12406ffa65eed4` |
| `src/main/resources/static/pulse-hosts.css` | `393f5b3d448c9c5f694060b8c9dd9e5d7e1af3a15f7a2d8d2c558dca1ed089fe` |

浏览器验证：

- 启动本地 coordinator：`PULSE_PORT=9974`。
- 注入包含 16 个 worker 的测试节点，确保内容超过卡片高度。
- 使用 headless Chrome + CDP 打开 `http://127.0.0.1:9974/hosts`。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `headerText` | `2026/6/1 14:11:33\n任务` |
| `headerHasOnlineText` | `false` |
| `headerHasConfirm` | `false` |
| `tileHasConfirm` | `true` |
| `runInHeader` | `true` |
| `dotOnly` | `true` |
| `scrollClientHeight` | `64` |
| `scrollHeight` | `336` |
| `scrollMoved` | `true` |
| `scrollOverflow` | `true` |
| `overflowY` | `auto` |

结论：

- 在线状态只显示绿点，不显示文字。
- `Confirm` 已移出 header。
- `任务` 按钮已放入 header。
- 磁贴内部滚动恢复正常。

线上部署与验证：

- 升级时间：2026-06-01 14:16 CST。
- 仅升级 3 台 coordinator，未重启 agent。
- Jar SHA256：`13dcc0f5f5b15ff5ea2fc3decc8c0534014366e689c486f44c5c85f45f7f463f`。
- `summary: total=3 ok=3 failed=0 elapsed=3s`。
- 三台 `pulse-coordinator.service` 均为 `active`。

远端浏览器验证：

- 访问：`http://[fdbd:dc05:11:634::45]:9966/hosts`，Chrome 通过 `socks5://127.0.0.1:6699` 代理。
- 本地静态资源由 coordinator 服务：`/assets/pulse-hosts.js`、`/assets/pulse-hosts.css`。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `tileCount` | `471` |
| `headerText` | `2026/6/1 14:16:23\n任务` |
| `headerHasOnlineText` | `false` |
| `headerHasConfirm` | `false` |
| `runInHeader` | `true` |
| `dotText` | `` |
| `dotOnly` | `true` |
| `tileHasConfirm` | `true` |
| `scrollClientHeight` | `65` |
| `scrollHeight` | `424` |
| `scrollBefore` | `0` |
| `scrollAfter` | `359` |
| `scrollMoved` | `true` |
| `scrollOverflow` | `true` |
| `overflowY` | `auto` |
| `hasHostnameLiteral` | `false` |
| `hasExternalAsset` | `false` |

结论：

- 线上 header 已只保留时间、在线绿点和 `任务` 按钮。
- 线上 header 不再展示 `在线` 文案和 `Confirm`。
- `Confirm` 保留在磁贴正文指标区。
- `.tile-scroll` 在真实线上数据下存在 overflow 且可滚动。
- 页面继续保持 IPv6-only 和本地静态资源，无外部 CDN。

## Apple 式磁贴信息密度修复

验证时间：2026-06-01 14:30 CST。

需求：

- 沉淀 `.tmp/` 使用规范，避免临时脚本和数据污染项目结构。
- datetime 展示不能在窄磁贴中断成多行。
- tide worker `pid` 及其属性展示更多。
- `任务` 按钮不需要图标。
- 在线状态绿点放在角落里，参考 Apple 的克制状态提示。

实现结果：

- 新增 `.skill/tmp.md`，后续临时脚本、日志、数据、报告统一放入 `.tmp/`，且 `.tmp/` 已由 `.gitignore` 忽略。
- `Seen` 时间从浏览器默认 datetime 改为 `MM/DD HH:mm:ss`，并通过 `white-space: nowrap` 保持单行。
- 状态点改为 `.corner-status-dot`，绝对定位在磁贴左上角，只保留点，不显示文字。
- `任务` 按钮移除播放图标，并关闭 Ant Design 中文按钮自动插空。
- tide worker 展示从最多 4 行 `pid/cpu` 扩展为最多 12 行 `pid/cpu/mem/port/version`，由 `.tile-scroll` 承载溢出滚动。

本地构建：

```bash
cd src/main/frontend
npm run build
cd /Users/bytedance/Documents/01_Projects/pulse
mvn test
mvn package
```

结果：

- `npm run build`：成功。
- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。

SHA256：

| 文件 | SHA256 |
| --- | --- |
| `target/pulse-0.1.0-SNAPSHOT.jar` | `7a9725adef1c8e25fc0d14544b9f2de580c2310d8826f19e8ed040e3ceeb47ba` |
| `src/main/resources/static/pulse-hosts.js` | `da2f67ba6b6319ec5d569873a1f836d7fbc5de1823ca76f12ae3df86b941e417` |
| `src/main/resources/static/pulse-hosts.css` | `6dc6bce47e0a6671dac2e3a395f20960ee9cdcbe1bbe2b94e1253acdc9087057` |

浏览器验证：

- 启动本地 coordinator：`PULSE_PORT=9975`。
- 使用 `.tmp/scripts/check-host-tile-apple.js` 通过 Chrome DevTools Protocol 检查 DOM 与布局。
- 验证报告保留在 `.tmp/reports/check-host-tile-apple.json`，不进入提交。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `headerText` | `06/01 14:30:24\n任务` |
| `seenText` | `06/01 14:30:24` |
| `seenLines` | `1` |
| `seenNoWrap` | `nowrap` |
| `runText` | `任务` |
| `runHasIcon` | `false` |
| `cornerDotExists` | `true` |
| `dotNearCorner` | `true` |
| `headerHasOnlineText` | `false` |
| `headerHasConfirm` | `false` |
| `workerRowCount` | `12` |
| `workerHasMem` | `true` |
| `workerHasPort` | `true` |
| `workerHasVersion` | `true` |
| `scrollHeight` | `924` |
| `scrollMoved` | `true` |
| `overflowY` | `auto` |

线上部署与验证：

- 升级时间：2026-06-01 14:32 CST。
- 仅升级 3 台 coordinator，未重启 agent。
- Jar SHA256：`7a9725adef1c8e25fc0d14544b9f2de580c2310d8826f19e8ed040e3ceeb47ba`。
- `summary: total=3 ok=3 failed=0 elapsed=2s`。
- 三台 `pulse-coordinator.service` 均为 `active`。

远端浏览器验证：

- 访问：`http://[fdbd:dc05:11:634::45]:9966/hosts`，Chrome 通过 `socks5://127.0.0.1:6699` 代理。
- 使用 `.tmp/scripts/check-remote-host-tile-apple.js` 通过 Chrome DevTools Protocol 检查 DOM 与布局。
- 验证报告保留在 `.tmp/reports/check-remote-host-tile-apple.json`，不进入提交。

测量结果：

| 项目 | 结果 |
| --- | --- |
| `tileCount` | `471` |
| `headerText` | `06/01 14:32:28\n任务` |
| `seenText` | `06/01 14:32:28` |
| `seenLines` | `1` |
| `seenNoWrap` | `nowrap` |
| `runText` | `任务` |
| `runHasIcon` | `false` |
| `cornerDotExists` | `true` |
| `dotNearCorner` | `true` |
| `headerHasOnlineText` | `false` |
| `headerHasConfirm` | `false` |
| `workerRowCount` | `4` |
| `firstWorkerText` | `pid 1005155\ncpu 1467.04\nmem 5.64\nport 7511\n1.1.0.6371` |
| `workerHasMem` | `true` |
| `workerHasPort` | `true` |
| `workerHasVersion` | `true` |
| `scrollHeight` | `452` |
| `scrollMoved` | `true` |
| `overflowY` | `auto` |
| `hasHostnameLiteral` | `false` |
| `hasExternalAsset` | `false` |

结论：

- 线上 datetime 已压缩为单行短格式，不再断成 4 行。
- `任务` 按钮无图标且无中文自动插空。
- 在线绿点已固定到磁贴角落，header 不再承担状态展示。
- tide worker 区展示更多属性，线上可见 `pid/cpu/mem/port/version`。
- `.tile-scroll` 继续支持真实滚动，页面保持 IPv6-only 和本地静态资源。

## 进程指标与任务 JSON 展示修复

验证时间：2026-06-01 15:13 CST。

需求：

- 磁贴 header 不能丑陋换行，datetime 不能拆成两行，任务按钮和状态绿点不能挤在一起。
- pid 信息不能拥挤，查看体验必须可读。
- 任务执行 JSON completion 必须展示，并支持一键格式化、拷贝和高亮。
- agent 侧为 tide worker pid 增加 user/sys CPU percent，从 `/proc/$pid/status` 获取 memory 与 threads 信息，环境变量字段保持不变。
- UI 验证必须在 coordinator 线上页面完成，并实际执行任务确认展示。

实现结果：

- `HostTile` header 改为三段式：左侧独立状态点、中间 `HH:mm:ss` 单行短时间、右侧纯文字 `任务` 按钮。
- 磁贴最小宽度从 `150px` 上调到 `210px`，避免 header 与 pid 信息被挤压。
- tide worker 展示改为轻量进程卡片，包含 `pid/cpu/usr/sys/rss/mem/thr/port/version`。
- agent 侧从 `/proc/$pid/stat` 拆分 `user_cpu_percent` 与 `sys_cpu_percent`，从 `/proc/$pid/status` 读取 `VmRSS` 和 `Threads`，保留 `PORT1`、`TIDELET_COMPONENT_VERSION`。
- Run UI completion 改为 `CompletionViewer`，当输出为 JSON 时提供格式化、拷贝和 `.json-key/.json-string/.json-number` 基础高亮。

本地构建：

```bash
cd src/main/frontend
npm run build
cd /Users/bytedance/Documents/01_Projects/pulse
mvn test
mvn package
```

结果：

- `npm run build`：成功。
- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：构建成功。

SHA256：

| 文件 | SHA256 |
| --- | --- |
| `target/pulse-0.1.0-SNAPSHOT.jar` | `6cace3194bff18d542a02d8eee640b42cec9bf802b5d3b2b7d94cdae363f2657` |

部署：

- `orthrus-cli demand` 初始并发授权：`total=471 ok=397 failed=74`，失败原因为 `kgetcred`。
- 对失败列表降并发重试：`total=74 ok=73 failed=1`。
- 单台最终重试 `fdbd:dc01:b:357::39`：成功。
- 全量部署 471 台 agent/coordinator，`--parallel 8`。
- 部署结果：`summary: total=471 ok=471 failed=0 elapsed=254s`。
- 远端 `/data24/otf/pulse/bin/pulse.jar` SHA 均为 `6cace3194bff18d542a02d8eee640b42cec9bf802b5d3b2b7d94cdae363f2657`。

线上 agent 字段验证：

从 coordinator `/api/hosts` 读取真实上报，样例 agent：

```json
{
  "agent_id": "dc02-p1a-t34-n013.byted.org",
  "ip": "fdbd:dc02:1a:34::13",
  "cluster": "cdn2",
  "worker": {
    "pid": 834623,
    "cpu_percent": "1705.65",
    "user_cpu_percent": "1218.58",
    "sys_cpu_percent": "487.07",
    "rss_kb": 59432560,
    "mem_percent": "5.63",
    "threads": 5921,
    "port1": "7511",
    "component_version": "1.1.0.6371"
  }
}
```

线上任务验证：

- 对 `dc02-p1a-t34-n013.byted.org` 执行真实 `analyze_block_layout_dry_run`。
- completion 返回 `exit_code=0`，`output` 为 JSON，包含 `report_type=block_layout_analysis`。
- 使用 Chrome DevTools Protocol 在 coordinator 线上页面 `http://127.0.0.1:9996/hosts` 检查 Run UI。
- `127.0.0.1:9996` 是到 `fdbd:dc05:11:634::45:9966` 的 SSH tunnel，因为本地 `127.0.0.1:6699` 代理当时未监听。

线上 UI 测量：

| 项目 | 结果 |
| --- | --- |
| `tileCount` | `471` |
| `headerText` | `15:11:03\n任务` |
| `seenText` | `15:11:03` |
| `seenLines` | `1` |
| `seenNoWrap` | `nowrap` |
| `runText` | `任务` |
| `runHasIcon` | `false` |
| `statusSeparate` | `true` |
| `workerReadable` | `true` |
| `workerText` | `pid 834623\n1.1.0.6371\ncpu 1573.05\nusr 1130.99\nsys 442.06\nrss 67021.2MB\nmem 6.50\nthr 5921\nport 7511` |
| `modalOpen` | `true` |
| `viewerExists` | `true` |
| `outputLength` | `118553` |
| `hasJsonReport` | `true` |
| `hasJsonHighlight` | `true` |
| `hasFormatControl` | `true` |
| `hasCopyButton` | `true` |
| `hasHostnameLiteral` | `false` |
| `scrollOverflow` | `true` |

JSON 格式化确认：

```json
{
  "firstLines": [
    "{",
    "  \"report_type\": \"block_layout_analysis\",",
    "  \"report_time\": \"2026-06-01 15:09:39 CST\",",
    "  \"mode\": \"dry-run\",",
    "  \"tide_home\": \"/opt/tiger/tide\",",
    "  \"block_layout\": {",
    "    \"summary\": {",
    "      \"metadata_dbs\": 2,"
  ],
  "hasIndentedReportType": true,
  "hasIndentedNested": true
}
```

结论：

- 线上磁贴 header 不再出现 datetime 换行，状态点与任务按钮已分离。
- pid 信息已从拥挤 inline 文本变成进程卡片，可读性提升，且展示 user/sys/rss/threads。
- 真实任务 JSON completion 已展示，支持格式化、拷贝和高亮。
- 验证在 coordinator 线上服务完成，并实际执行任务确认展示。

## 文本自适应缩放修复

验证时间：2026-06-01 15:35 CST。

需求：

- `Coordinator` 卡片中的 IPv6 文本过长，当前会直接溢出。
- 长时间文本和长 IPv6 应优先缩小字号，避免把卡片撑破。

实现结果：

- 新增前端 `AutoFitText` 组件，使用 `ResizeObserver` 在容器变化后重新测量文本宽高。
- `Coordinator` 指标卡使用 `AutoFitText`，在 `14px ~ 28px` 范围内自适应缩小字号。
- 磁贴 `Seen` 时间同样接入 `AutoFitText`，在 `9px ~ 11px` 范围内自适应缩小。
- 对超长单行文本保留 `nowrap`，优先缩小，再做省略，避免主体内容直接溢出。

本地构建：

```bash
cd src/main/frontend
npm run build
cd /Users/bytedance/Documents/01_Projects/pulse
mvn test
mvn package
```

结果：

- `npm run build`：成功。
- `mvn test`：`22 tests, 0 failures, 0 errors`。
- `mvn package`：成功。

SHA256：

| 文件 | SHA256 |
| --- | --- |
| `target/pulse-0.1.0-SNAPSHOT.jar` | `b9265cfcc695a7d8a9b82944df638a5892070e7c729cc055e9b6aad2e03848ec` |
| `src/main/resources/static/pulse-hosts.js` | `757cc960eb51fd5fb6d51d79041026aa5353d4faa103d074f52ccb49a80c94e6` |
| `src/main/resources/static/pulse-hosts.css` | `dc57877d3ca12505f7f8f02aecd8d17ff97466df302e7c2075dc03a6155434c6` |

## Trace 左置与结果保留修复

验证时间：2026-06-01 16:10 CST。

需求：

- `trace` 查看异常，且切换后无法恢复。
- `trace` 日志移到左侧。
- `trace` 展示补充 `task_id`。

实现结果：

- Run UI 去掉右侧 `Trace` tab，左侧新增独立 `Trace` 卡片，避免与 completion 共用结果容器。
- `当前任务`、`执行队列` 和 `Trace` 每条记录都补充 `task_id`，并使用可换行的等宽文本展示。
- “弹出结果”从 `/pop` 改为 `/keep`，不再移除 completion；trace 中新增 `task.completion_kept`，不再出现 `task.completion_popped`。

线上验证：

- 在 `dc02-p1a-t34-n013.byted.org` 上执行 `analyze_block_layout_dry_run`。
- completion 返回 `task_id=task-bb5f66ba-e753-42ff-9dae-71becfabe461`，`output_len=117245`。
- 浏览器检查结果：

| 检查项 | 结果 |
| --- | --- |
| `traceOnLeft` | `true` |
| `traceHasTaskId` | `true` |
| `currentTaskHasTaskId` | `true` |
| `outputLength` | `117240` |
| `poppedShown` | `false` |
| `traceHasKept` | `true` |
| `traceHasPopped` | `false` |

trace 首屏：

```text
06/01 16:10:29 · task.result_received
task_id: task-bb5f66ba-e753-42ff-9dae-71becfabe461

06/01 16:10:29 · task.accepted_by_agent
task_id: task-bb5f66ba-e753-42ff-9dae-71becfabe461

06/01 16:10:14 · task.dequeued_for_delivery
task_id: task-bb5f66ba-e753-42ff-9dae-71becfabe461

06/01 16:10:06 · task.enqueued
task_id: task-bb5f66ba-e753-42ff-9dae-71becfabe461
```

点击“弹出结果”后新增：

```text
06/01 16:10:51 · task.completion_kept
task_id: task-bb5f66ba-e753-42ff-9dae-71becfabe461
```

## Run UI 标题与工具栏留白微调

验证时间：2026-06-01 16:23 CST。

需求：

- `结果查看` 与下方格式化/拷贝按钮距离过近，缺少 Apple 风格的舒展留白。

实现结果：

- `task-workspace` 的 card head 提升到 `min-height: 60px`，左右 padding 调整为 `18px`。
- `task-workspace` body 顶部 padding 调整为 `16px`，不再让工具栏贴着标题分隔线。
- `completion-toolbar` 增加底部内边距，保持标题、工具栏与结果框的节奏分离。

线上浏览器测量：

| 检查项 | 结果 |
| --- | --- |
| `gapFromHeadToToolbar` | `15` |
| `gapFromTitleToToolbar` | `18` |
| `headMinHeight` | `60px` |
| `headPaddingLeft` | `18px` |
| `bodyPaddingTop` | `16px` |
| `toolbarPaddingBottom` | `4px` |
| `titleText` | `结果查看` |

## 顶部指标卡高度对齐修复

验证时间：2026-06-01 16:35 CST。

需求：

- 顶部 `5min AVG`、`Coordinator`、`刷新` 等指标卡高度不一致，视觉基线错位。

实现结果：

- 指标区列容器改为 stretch，卡片 `height: 100%`。
- 指标卡 body 统一为固定最小高度，并让 `Statistic` 以纵向 flex 统一标题和数值基线。
- `Coordinator` 长值继续走自适应字号，避免通过增加卡片高度来容纳内容。

线上浏览器测量：

| 卡片 | `cardHeight` | `bodyHeight` | `statisticHeight` |
| --- | --- | --- | --- |
| `5min AVG` | `91` | `89` | `61` |
| `Coordinator` | `91` | `89` | `61` |
| `刷新` | `91` | `89` | `61` |

结论：

- `allCardHeightsEqual=true`
- `allBodyHeightsEqual=true`

## 顶部指标卡字体基线对齐修复

验证时间：2026-06-01 17:18 CST。

需求：

- 顶部指标区虽然已经等高，但 `Coordinator` 的数值字体仍比 `5min AVG`、`刷新` 更大，且顶部位置上移约 `2px`，用户反馈“这么大的空间，为什么字体没有对齐”。

问题定位：

- 线上浏览器测量发现 `5min AVG` 与 `刷新` 为 `24px / 26.88px / top=570.34`。
- `Coordinator` 因 `AutoFitText` 默认从 `28px` 起算，实际渲染为 `26px / 29.12px / top=568.11`。
- 这说明问题已经从“卡片高度不齐”收敛为“默认字号策略不一致”。

实现结果：

- `Coordinator` 指标卡的 `AutoFitText` 默认字号改为与其他 `Statistic` 一致的 `24px`。
- metric value 容器统一 `line-height`、`min-height` 和 `align-items: flex-end`，让三张卡共用同一垂直对齐链。
- 自适应缩放逻辑保留，但只在真实 overflow 时递减字号。

线上浏览器复测：

| 卡片 | `fontSize` | `lineHeight` | `top` | `height` |
| --- | --- | --- | --- | --- |
| `5min AVG` | `24px` | `26.88px` | `568.11` | `27` |
| `Coordinator` | `24px` | `26.88px` | `568.11` | `27` |
| `刷新` | `24px` | `26.88px` | `568.11` | `27` |

结论：

- `allMetricFontSizesEqual=true`
- `allMetricLineHeightsEqual=true`
- `allMetricTopsEqual=true`

## 集群折叠恢复

验证时间：2026-06-01 18:07 CST。

需求：

- 恢复集群折叠功能。
- 默认不折叠监控主视图。
- 页面刷新后保留用户上一次的折叠选择。
- 异常集群不能被默认折叠隐藏。

实现结果：

- cluster section 标题区恢复折叠/展开按钮，默认所有集群展开。
- 折叠状态写入本地 `localStorage`，key 为 `pulse.cluster-collapse.v1`。
- 当 cluster 中存在 `warming`、`expired`、失败任务或执行中任务时，前端会清除该集群的折叠标记并强制展开。
- 异常 cluster 的按钮文案固定为 `异常展开`，避免用户把异常直接折叠藏起来。

本地验证：

- `npm run build`：通过。
- `mvn -Dtest=CoordinatorHttpServerTest test`：通过。
- `mvn package`：通过，22 个测试全部通过。

Coordinator-only 线上升级验证：

- 目标：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- 部署结果：`summary: total=3 ok=3 failed=0`
- 远端静态资源校验：
  - 三台 `/assets/pulse-hosts.js` 均包含 `pulse.cluster-collapse.v1` 与 `异常展开`
  - 三台 `/assets/pulse-hosts.css` 均包含 `cluster-section-collapsed` 与 `cluster-toggle-button`
  - 三台 `/hosts` 均继续加载 `/assets/pulse-hosts.js` 与 `/assets/pulse-hosts.css`

## Group 调试字段展示

验证时间：2026-06-01 20:20 CST。

需求：

- 按 group leader 聚合行为分析建议，为 host view 增加只读调试字段。
- 让 UI 能解释 transient warming、group leader 聚合和 lazy sync，而不是只展示一个容易误解的确认数。

实现结果：

- `/api/hosts` 顶层新增：
  - `last_observed_age_ms`
  - `group_id`
  - `group_mode`
  - `leader_agent_id`
  - `leader_url`
  - `group_size`
  - `group_size_limit`
- 前端磁贴新增 `调试` 区，展示 `age`、`mode`、`group`、`size`、`leader`。
- 可见 leader 使用 `leader_url` 中的 IPv6 归一化展示，避免 hostname/FQDN 进入 UI。
- `Confirm` 文案改为 `20s确认`，降低被误解为 coordinator 数或 group size 的风险。

本地验证：

- `npm run build`：通过。
- `mvn test`：通过，22 个测试全部通过。
- `mvn package`：通过。

Coordinator-only 线上升级验证：

- 目标：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- 部署结果：`summary: total=3 ok=3 failed=0`
- 远端 `/api/hosts` 校验：三台均返回完整调试字段。

远端样本：

| coordinator | host_count | sample `group_id` | sample `group_mode` | sample `leader_url` | sample `group_size` |
| --- | ---: | --- | --- | --- | --- |
| `fdbd:dc05:11:634::45` | `471` | `cdn2/hl/000` | `leader` | `http://[fdbd:dc02:1a:34::13]:9977` | `7/7` |
| `fdbd:dc05:13:10c::40` | `471` | `cdn2/hl/000` | `leader` | `http://[fdbd:dc02:1a:34::13]:9977` | `7/7` |
| `fdbd:dc07:0:810::44` | `471` | `cdn2/hl/000` | `leader` | `http://[fdbd:dc02:1a:34::13]:9977` | `7/7` |

远端静态资源校验：

- 三台 `/assets/pulse-hosts.js` 均包含 `20s确认`、`debug-panel`、`last_observed_age_ms`、`group_id`、`leader_url`、`调试`。
- 三台 `/assets/pulse-hosts.css` 均包含 `.debug-panel` 与 `.debug-grid`。

## Group Membership Grace 修复

验证时间：2026-06-01 20:50-20:53 CST。

背景：

- `fdbd:dc02:11:c::43` 容易在 `alive/warming` 间切换。
- 运行时分析显示它被动态 group plan 在 `tlblog_stream_olap_separate/hl/000` 与 `direct` 间反复移动。
- 目标 agent 会继续按旧 follower plan 向 leader `fdbd:dc02:11:c::14:9977` 上报，而 leader 已不再接受它，返回 `409 not_group_member`。

修复：

- coordinator 动态分组引入 membership grace/hysteresis。
- 新成员仍必须达到 `alive` 才能进入 group。
- 已有 group member 只要未 `expired`，即使短暂 `warming` 也保留原 membership。
- leader 选择优先使用 `alive` member。
- `expired` member 退出 group，不长期保留失联节点。

本地验证：

- `mvn -Dtest=CoordinatorServiceTest test`：通过，11 个测试通过。
- `mvn test`：通过，23 个测试通过。
- `mvn package`：通过。

Coordinator-only 线上升级验证：

- 目标：
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- 部署结果：`summary: total=3 ok=3 failed=0`

`fdbd:dc02:11:c::43` 线上复测：

- 采样方式：三台 coordinator `/api/hosts` 连续 18 轮，每 5 秒一轮。
- 初始 coordinator 重启收敛阶段：
  - sample 0：`warming`、source=`direct`、`20s确认=1`
  - sample 1-2：source 已恢复为 `tlblog_stream_olap_separate/hl/000`
- 稳定阶段：
  - sample 3 起三台 coordinator 均为 `alive`
  - sample 4 起多数采样 `20s确认=4`
  - sample 1-17 持续保持 `group_id=tlblog_stream_olap_separate/hl/000`
  - sample 1-17 持续保持 `group_mode=follower`
  - sample 1-17 未再回到 `direct`

目标 agent 日志尾部：

```text
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=4137
...
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=4161
```

结论：

- 修复后目标节点不再进入 `not_group_member -> direct fallback -> 重新入组` 的循环。
- group heartbeat 降压路径保留，目标节点稳定由 leader 聚合上报。
