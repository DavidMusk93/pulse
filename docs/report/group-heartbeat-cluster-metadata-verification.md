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

- `mvn test`：8 个测试全部通过。
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
- 按 cluster 分组展示 Windows Phone 风格磁贴。
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

## 后续建议

- verify 脚本增加 systemd `ExecStart` 输出，避免 PATH Java 与实际运行 Java 混淆。
- coordinator 增加 `/healthz`，供部署脚本和负载均衡健康检查使用。
- 对 group heartbeat 增加压测，观察单请求大批量 `agents[]` 的延迟和内存占用。
- 后续如需更细分聚合，可在 `cluster -> area -> IPv6 prefix` 三层维度扩展。
