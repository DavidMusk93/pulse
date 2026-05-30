# Group Heartbeat 与集群元数据设计

## 背景

Pulse coordinator 已支持单机 agent 心跳和批量 `agents[]` group heartbeat。当前新需求要求：

- 在 coordinator 中验证 group heartbeat 功能是否正常。
- 使用 Arthas 作为 Java 线上调试手段，并沉淀部署与使用文档。
- agent 从本机 `tide_worker` 进程环境变量中采集集群元数据。
- 前端展示按集群名称分组。
- 将 auto-ops 中的 `doubao`、`tlbmirror` 集群也接入 agent 列表。
- 后续每个新需求都必须先补充 `docs/design` 与 `docs/plan` 文档，再进入开发、部署、验证。

## 目标

- Group heartbeat 协议可用：一次请求可上报多个 agent，coordinator 返回每个 agent 的 `accepted_seq`。
- Host 视图具备集群维度：`cluster`、`area` 作为一等字段返回给 API 与 Web。
- Web 页面按 `cluster` 分组展示 host 磁贴。
- 部署脚本自动采集 `tide_worker` 环境变量：
  - `_TIDELET_AREA` 映射为 `PULSE_AGENT_AREA`
  - `_TIDELET_CLUSTER_ID` 映射为 `PULSE_AGENT_CLUSTER`
- 如果 `tide_worker` 不存在或变量为空：
  - `cluster` 使用部署参数兜底，最终兜底为 `unknown`
  - `area` 兜底为 `unknown`
- `cdn_new`、`doubao`、`tlbmirror` agent 共用同一组三台 coordinator。
- Java 运行环境必须满足 Java 17+，低版本系统 Java 自动切换到 `/data24/otf/pulse/jre/bin/java`。

## 非目标

- 不在本阶段实现 coordinator 间强一致复制。
- 不在本阶段实现长期持久化存储。
- 不在本阶段把 Arthas 做成常驻 systemd 服务。
- 不在本阶段实现复杂的 IPv6 距离调度，只保留后续扩展点。

## 协议设计

### 单机心跳

单机心跳保持现有格式：

```json
{
  "agent_id": "host-a",
  "epoch": 1,
  "seq": 42,
  "ttl_ms": 15000,
  "messages": [
    {
      "message_id": "msg-host-a-42",
      "type": "state.heartbeat",
      "version": 1,
      "payload": {
        "host": "host-a",
        "ip": "fdbd::1",
        "cluster": "cdn2",
        "area": "yg",
        "zone": "yg",
        "role": "cdn_new",
        "load": "0.42"
      }
    }
  ]
}
```

### Group Heartbeat

Group heartbeat 使用同一个 `/heartbeat` endpoint，通过 `agents[]` 判定为批量请求：

```json
{
  "group_id": "manual-group-verify",
  "agents": [
    {
      "agent_id": "agent-1",
      "epoch": 1,
      "seq": 101,
      "ttl_ms": 15000,
      "messages": []
    }
  ]
}
```

响应包含每个 agent 的 accepted sequence：

```json
{
  "ok": true,
  "coordinator_id": "coordinator-a",
  "accepted_seq": null,
  "messages": [],
  "agents": [
    {
      "agent_id": "agent-1",
      "accepted_seq": 101,
      "messages": []
    }
  ]
}
```

## 状态合并

- coordinator 以内存态保存 `agent_id -> NodeState`。
- 对同一个 `agent_id`：
  - 更高 `epoch` 覆盖旧状态。
  - 相同 `epoch` 下更高 `seq` 覆盖旧状态。
  - 旧 `epoch` 或旧 `seq` 不覆盖新状态。
- group heartbeat 的 `source` 为 `group_id`，为空时为 `group`。
- 单机 heartbeat 的 `source` 为 `direct`。

## 集群元数据

### 采集来源

部署脚本在远端查找 `tide_worker`：

```bash
pgrep -f tide_worker | head -n 1
```

读取 `/proc/$pid/environ`：

- `_TIDELET_AREA`
- `_TIDELET_CLUSTER_ID`

### 映射关系

| tide_worker 变量 | Pulse 环境变量 | HostView 字段 |
| --- | --- | --- |
| `_TIDELET_CLUSTER_ID` | `PULSE_AGENT_CLUSTER` | `cluster` |
| `_TIDELET_AREA` | `PULSE_AGENT_AREA` | `area` |
| `_TIDELET_AREA` | `PULSE_AGENT_ZONE` | `zone` |
| 部署参数 | `PULSE_AGENT_ROLE` | `role` |

### 兜底策略

- `tide_worker` 不存在时：
  - `cluster` 使用部署参数，例如 `doubao` 或 `tlbmirror`
  - 如果部署参数为空，则为 `unknown`
  - `area` 为 `unknown`
- 读取 `/proc/$pid/environ` 失败时，按不存在处理。

## Web 分组展示

Web 页面按 `cluster` 进行一级分组：

- 每个 cluster 渲染一个 `cluster-section`。
- 组标题展示 cluster 名称与 host 数。
- 组内继续使用 Windows Phone 风格磁贴。
- 磁贴展示 `IP`、`Area`、`Role`、`Zone`、`Load`、`Seq`、`Source`、`Seen`。

## 部署设计

### 机器范围

- coordinator：仍使用 `cdn_new` 中选出的三台 IPv6 机器。
- agent：`cdn_new`、`doubao`、`tlbmirror` 全部目标机器。
- 安装目录：`/data24/otf/pulse`。
- coordinator 监听：IPv6 `::`，端口 `9966`。

### Java 版本

- Pulse 编译目标为 Java 17。
- 部署脚本先检测可用 `java` 主版本。
- 如果系统 Java 小于 17，则拒绝使用，并解压随包 JRE 到 `/data24/otf/pulse/jre`。

### systemd

- agent：`pulse-agent.service`
- coordinator：`pulse-coordinator.service`
- 每次部署后必须显式 `restart`，确保新 jar 与新环境变量生效。

## Arthas 调试设计

- Arthas boot jar 放置在 `/data24/otf/pulse/tools/arthas/arthas-boot.jar`。
- 只作为临时诊断工具，不常驻。
- 推荐观测点：
  - `CoordinatorService#handleHeartbeat`
  - `CoordinatorService#hosts`
  - `AgentHeartbeatFactory#nextHeartbeat`

## 验证标准

- 本地 `mvn test` 全部通过。
- 三组部署验证 `ok=total failed=0`。
- coordinator `/api/hosts` 返回 host 数等于 agent 数加临时验证 agent 数。
- `/api/hosts` 顶层字段包含 `cluster` 与 `area`。
- `/hosts` 页面包含 `cluster-section`，并展示 `cdn2`、`doubao`、`tlbmirror` 或对应 tide cluster。
- group heartbeat 响应包含 `agents[]` 和每个 agent 的 `accepted_seq`。

## 风险

- coordinator 仍是内存态，重启后需要等待 agent 下一轮心跳恢复视图。
- 部分机器 `tide_worker` 变量可能与 auto-ops tag 不完全一致，例如 `tlbmirror2`。
- 远端验证脚本打印的 `JAVA_BIN` 可能来自系统 PATH，不一定等于 systemd 实际 `ExecStart`，最终以 systemd status 为准。
