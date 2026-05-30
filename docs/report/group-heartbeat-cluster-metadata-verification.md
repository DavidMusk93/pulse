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
