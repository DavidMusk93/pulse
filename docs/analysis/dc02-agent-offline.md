# `fdbd:dc02:11:c::43` 易离线运行时分析

分析时间：2026-06-01 20:30-20:40 CST。

## 现象

节点：

- IPv6：`fdbd:dc02:11:c::43`
- agent：`dc02-p11-tc-n043.byted.org`
- cluster：`tlblog_stream_olap_separate`
- area：`hl`

线上表现：

- 状态在 `alive` 与 `warming` 间反复切换。
- `20s确认` 经常从 `4 -> 3 -> 2 -> 1` 下降，然后通过 direct heartbeat 又恢复。
- `group_id` 在 `tlblog_stream_olap_separate/hl/000` 与 `direct` 间来回切换。

## 关键证据

### 1. 三台 coordinator 连续采样复现抖动

采样间隔：5s，连续 10 轮。

摘录：

| sample | status | seq | confirmations | age | group_id | group_mode | leader |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| `0` | `alive` | `3899` | `3` | `3.7s-4.0s` | `tlblog_stream_olap_separate/hl/000` | `follower` | `fdbd:dc02:11:c::14` |
| `1` | `alive` | `3900` | `4` | `4.2s-4.6s` | `tlblog_stream_olap_separate/hl/000` | `follower` | `fdbd:dc02:11:c::14` |
| `2` | `warming` | `3900` | `2` | `9.8s-10.2s` | mixed：部分 coordinator 仍为 group，部分已 direct | mixed | mixed |
| `3` | `warming` | `3900` | `1` | `15.4s-15.9s` | `direct` | `direct` | `-` |
| `4` | `warming` | `3904` | `1` | `0.8s-1.2s` | `direct` | `direct` | `-` |
| `6` | `alive` | `3906` | `3` | `1.8s-2.2s` | `tlblog_stream_olap_separate/hl/000` | `follower` | `fdbd:dc02:11:c::14` |
| `7` | `alive` | `3907` | `4` | `2.2s-2.6s` | `tlblog_stream_olap_separate/hl/000` | `follower` | `fdbd:dc02:11:c::14` |
| `9` | `warming` | `3907` | `2` | `13.4s-13.9s` | `direct` | `direct` | `-` |

结论：

- 不是单个 coordinator 观测异常，三台 coordinator 同步看到抖动。
- `seq` 会停住约 `10-15s`，随后跳跃恢复，说明心跳不是稳定进入当前 group aggregation。

### 2. 目标 agent 没有频繁重启

目标节点：

```text
Active: active (running) since Mon 2026-06-01 15:00:58 CST; 5h 31min ago
Main PID: 682499
PULSE_HEARTBEAT_INTERVAL_MS=5000
PULSE_TTL_MS=30000
PULSE_GROUP_MODE=dynamic
PULSE_GROUP_PORT=9977
```

结论：

- 排除“agent/systemd 频繁重启导致 epoch/seq 重置”。

### 3. 目标 agent 日志大量出现 `not_group_member`

目标节点 `/data24/otf/pulse/logs/pulse-agent.err`：

```text
heartbeat status=bad_response coordinator=http://[fdbd:dc02:11:c::14]:9977 code=409 body={"ok":false,"error":"not_group_member"}
```

近段日志中该错误大量重复，并夹杂少量 coordinator direct fallback：

```text
heartbeat status=ok target=http://[fdbd:dc05:13:10c::40]:9966 seq=3911
heartbeat status=ok target=http://[fdbd:dc07:0:810::44]:9966 seq=3912
heartbeat status=ok target=http://[fdbd:dc05:11:634::45]:9966 seq=3913
heartbeat status=ok target=http://[fdbd:dc05:13:10c::40]:9966 seq=3914
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=3915
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=3916
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=3917
heartbeat status=ok target=http://[fdbd:dc07:0:810::44]:9966 seq=3918
```

结论：

- 目标节点不是完全无法发 heartbeat。
- 问题集中在 follower 向 leader `fdbd:dc02:11:c::14:9977` 上报时被 leader 拒绝。
- 被拒绝后 agent fallback direct，direct 成功后又短暂恢复。

### 4. leader 运行时成员集排除 `n043`

leader：

- IPv6：`fdbd:dc02:11:c::14`
- agent：`dc02-p11-tc-n014.byted.org`

Arthas watch `PulseAgentApp$GroupHeartbeatReceiver#handleHeartbeat`：

```text
target.acceptingFollowers=true
target.acceptedMembers=[
  dc02-p11-tc-n018.byted.org,
  dc02-p11-tc-n016.byted.org,
  dc02-p11-tc-n014.byted.org,
  dc02-p11-tc-n017.byted.org,
  dc02-p11-t304-n049.byted.org,
  dc02-p11-tc-n020.byted.org,
  dc02-p11-tc-n015.byted.org
]
```

该成员集中没有：

```text
dc02-p11-tc-n043.byted.org
```

结论：

- leader 返回 `409 not_group_member` 是按自身 `acceptedMembers` 校验执行，不是 leader HTTP 逻辑误判。

### 5. leader 成员集会在 `n043` 与 `n304` 之间抖动

Arthas watch `PulseAgentApp$GroupHeartbeatReceiver#setAcceptingFollowers`：

```text
20:37:21 acceptedMembers includes dc02-p11-t304-n049, excludes dc02-p11-tc-n043
20:37:26 acceptedMembers includes dc02-p11-t304-n049, excludes dc02-p11-tc-n043
20:37:31 acceptedMembers includes dc02-p11-t304-n049, excludes dc02-p11-tc-n043
20:37:37 acceptedMembers includes dc02-p11-tc-n043, excludes dc02-p11-t304-n049
20:37:42 acceptedMembers includes dc02-p11-tc-n043, excludes dc02-p11-t304-n049
20:37:47 acceptedMembers includes dc02-p11-tc-n043, excludes dc02-p11-t304-n049
20:37:57 acceptedMembers includes dc02-p11-t304-n049, excludes dc02-p11-tc-n043
20:38:17 acceptedMembers includes dc02-p11-tc-n043, excludes dc02-p11-t304-n049
```

结论：

- `hl/000` group 的最后一个成员在 `n043` 和 `n304` 间周期性切换。
- 这会造成 follower plan 与 leader 接受列表短时间不一致。

## 代码语义

coordinator 当前 group recompute 只纳入 `alive` host：

```java
for (HostView host : buildHosts(now, false)) {
    if (!"alive".equals(host.status())) {
        continue;
    }
    ...
}
```

这意味着：

- `n043` 一旦 `20s确认 < 3`，状态变成 `warming`。
- `warming` 节点会从动态 group plan 中移除。
- 被移除后，它收到或保留的 follower plan 可能短时间落后于 leader 的成员集。
- follower 向旧 leader 上报时，leader 已不再接受它，返回 `not_group_member`。
- agent fallback 到 direct 后，又通过 direct 恢复确认数。
- 恢复成 `alive` 后再次被纳入 group，循环继续。

## 假设判定

| 假设 | 判定 | 证据 |
| --- | --- | --- |
| agent/systemd 频繁重启 | 否定 | systemd 显示运行 5h+，epoch 未重置 |
| direct 轮询和 lazy sync 单独导致 | 部分相关但不是主因 | direct fallback 存在，但主错误是 leader 409 |
| 网络到 coordinator 失败 | 非主因 | direct heartbeat 大多成功，仅少量 `header parser received no bytes` |
| heartbeat 构造/采集卡顿 | 未发现直接证据 | 进程稳定，关键 seq 停顿与 `not_group_member` 周期吻合 |
| coordinator 窗口和 group plan 抖动 | 成立 | `alive/warming` 切换触发 group member 在 `n043` 与 `n304` 间抖动 |

## 根因

`fdbd:dc02:11:c::43` 易被判定离线的直接原因是：

```text
动态 group plan 只纳入 alive 节点，n043 的确认数短暂下降后被踢出 group；
agent 与 leader 的 group plan 更新存在一轮到数轮延迟；
目标 agent 继续按 follower plan 向 n014 上报；
n014 的 acceptedMembers 已不包含 n043，返回 409 not_group_member；
agent fallback direct 后短暂恢复，再次进入 group，形成循环。
```

这是一个 group membership 抖动/迟滞不足问题，不是单机进程重启，也不是 coordinator 完全收不到心跳。

## 修复建议

建议不要立即改成 agent 广播所有 coordinator，这会违背当前设计内核。

更小的修复方向：

- coordinator group recompute 不应只看瞬时 `alive`，应对刚从 group 中掉出的节点设置 grace/hysteresis。
- 对已有 group member，如果仍未 `expired` 且最近 direct/follower 心跳可见，可以在短时间内保留 group membership，避免 `alive/warming` 边界来回切换。
- leader 收到 `not_group_member` 场景可以更温和：如果 request 的 `group_id/leader` 与当前 plan 接近，可返回 direct plan 或 stale-plan hint，减少 follower 盲目重试旧 leader。
- UI 应把这类状态标为 `group plan flapping` 或显示 `not_group_member` 计数，便于快速定位。

## 已实施修复

修复时间：2026-06-01 20:48-20:50 CST。

修复策略：

- 保持 group 降压设计，不让 agent/group leader fanout 到所有 coordinator。
- 在 coordinator 动态分组中引入 membership grace/hysteresis：
  - 新成员仍必须 `alive` 才能进入 group。
  - 已有 group member 只要未 `expired`，即使短暂 `warming` 也保留原 membership。
  - leader 选择优先使用 `alive` member，避免短暂 warming 节点成为 leader。
  - `expired` member 退出 group，不长期保留失联节点。

验证结果：

- `mvn test`：23 个测试通过。
- `mvn package`：通过。
- coordinator-only 部署：3 台全部成功。
- 对目标节点连续 18 轮、约 90 秒线上采样：
  - 初始重启收敛后从第 3 轮起稳定 `alive`。
  - source 持续为 `tlblog_stream_olap_separate/hl/000`。
  - `group_id` 持续为 `tlblog_stream_olap_separate/hl/000`。
  - `group_mode` 持续为 `follower`。
  - 第 4 轮后大多数采样 `20s确认=4`。
- 目标 agent 日志显示修复后连续向 leader 成功上报：

```text
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=4137
...
heartbeat status=ok target=http://[fdbd:dc02:11:c::14]:9977 seq=4161
```

## 采集产物

临时产物位于：

```text
.tmp/analysis/dc02-agent-offline/
```

主要文件：

- `host-timeseries.jsonl`
- `agent-43-status-journal.txt`
- `agent-43-process-inspect.txt`
- `agent-43-file-logs.txt`
- `agent-43-heartbeat-counts.txt`
- `leader-14-status-logs.txt`
- `leader-14-arthas-accepted-members-2.txt`
- `leader-14-arthas-set-accepting.txt`
