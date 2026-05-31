# TLB SSH Node Triage

## 背景

`tlb` 集群规模较大，批量部署 Pulse agent 时可能遇到 SSH 无响应、IPv6 网络异常或 SSH 响应慢。排查目标不是跳过失败节点，而是判断节点状态、获取可用登录路径，并给出可复现报告。

## 第一性原则

- 不把 SSH 失败直接归类为部署失败，需要区分网络不可达、权限缺失、sshd 慢、机器异常。
- 不做破坏性操作；诊断命令以只读为主。
- 先判断节点是否活着，再判断是否能登录，最后判断登录为什么慢。
- 所有异常节点必须沉淀报告，包含节点、现象、命令、耗时、结论和下一步。

## 快速分类

### 1. IPv6 ping 无响应

现象：

- `ping6 <ipv6>` 无响应或丢包严重。
- SSH 直接超时。

判断：

- 优先认为机器或网络路径异常，不继续反复部署。
- 记录为 `node_or_ipv6_network_unreachable`。

建议命令：

```bash
ping6 -c 3 -W 2 '<ipv6>'
ssh -6 -o ConnectTimeout=5 -o BatchMode=yes '<ipv6>' 'hostname'
```

报告字段：

- `host`
- `ping6_loss`
- `ssh_connect_timeout`
- `classification=node_or_ipv6_network_unreachable`

### 2. root/默认用户 SSH 无响应，但 tiger 可登录

现象：

- 默认 SSH 账号无响应、权限不足或超时。
- `tiger@host` 可以登录。

处理流程：

1. 使用 `tiger` 登录节点。
2. 在节点上获取 `eth0` 的 IPv4 地址。
3. 本地执行 `orthrus-cil demand <ipv4>` 获取权限。
4. 重新使用部署用户登录节点。
5. 权限恢复后继续部署或验证。

建议命令：

```bash
ssh -o ConnectTimeout=8 tiger@'<host>' 'ip -4 -o addr show dev eth0 | awk "{print \\$4}" | cut -d/ -f1'
orthrus-cil demand '<ipv4>'
ssh -o ConnectTimeout=8 '<host>' 'hostname; id; systemctl is-active pulse-agent.service'
```

报告字段：

- `host`
- `tiger_login=ok|failed`
- `eth0_ipv4`
- `orthrus_demand=ok|failed`
- `post_demand_ssh=ok|failed`
- `classification=permission_recovered_via_tiger|permission_recovery_failed`

### 3. SSH 可用但很慢

现象：

- SSH 最终成功，但连接建立或远端命令耗时明显偏高。
- 批量部署表现为长尾卡住。

排查视角：

- DNS 或反向解析慢。
- sshd MaxStartups、PAM、认证链路慢。
- 远端 CPU、load、IO wait 高。
- `systemctl` 或 Java/JRE 文件复制耗时。
- IPv6 路径可达但质量差。

建议命令：

```bash
time ssh -o ConnectTimeout=10 -o BatchMode=yes '<host>' 'true'
ssh -vvv -o ConnectTimeout=10 -o BatchMode=yes '<host>' 'true' 2>&1 | tail -80
ssh '<host>' 'uptime; vmstat 1 3; iostat -x 1 3 2>/dev/null || true'
ssh '<host>' 'systemctl is-active sshd 2>/dev/null || systemctl is-active ssh 2>/dev/null || true'
```

报告字段：

- `host`
- `ssh_connect_ms`
- `remote_command_ms`
- `loadavg`
- `iowait`
- `ssh_verbose_tail`
- `classification=slow_ssh_dns|slow_ssh_auth|slow_remote_load|slow_remote_io|unknown_slow_ssh`

## Pulse Agent 验证

权限与网络恢复后，使用只读验证确认 agent 状态：

```bash
ssh '<host>' '
set -e
test -x /data24/otf/pulse/jre/bin/java
test -f /data24/otf/pulse/bin/pulse.jar
test -x /data24/otf/pulse/tasks/prepare-disk-layout.sh
test -x /data24/otf/pulse/tasks/analyze-block-layout.py
grep -q "^PULSE_TASK_DIR=/data24/otf/pulse/tasks" /data24/otf/pulse/etc/pulse-agent.env
systemctl is-active --quiet pulse-agent.service
echo PULSE_AGENT_OK
'
```

## 报告模板

```text
host:
cluster: tlb
observed_at:
symptom:
ping6:
ssh_default:
ssh_tiger:
eth0_ipv4:
orthrus_demand:
post_demand_ssh:
ssh_connect_ms:
remote_health:
classification:
next_action:
```

## 部署策略建议

- 对已安装 JRE 的节点，优先使用轻量 jar/task 同步，避免每次重复上传大 JRE。
- 对 SSH 长尾节点，先生成异常清单，再按分类低并发补齐。
- 对 `ping6` 不通节点，不进入部署重试池，直接进入节点/网络异常报告。
- 对 `tiger` 可登录节点，先走权限恢复，再进入部署重试池。
