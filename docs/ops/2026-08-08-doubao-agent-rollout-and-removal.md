# 豆包集群合并、Agent 发布与移出节点卸载

## 变更摘要

| 项目 | 值 |
| --- | --- |
| 运行模式 | `central-runtime` |
| Auto Ops | `/Users/david/Documents/fleet-ops` |
| Fleet 提交 | `1187057 ops: merge doubao host inventories` |
| Inventory | `/Users/david/Documents/fleet-ops/hosts` |
| Inventory SHA-256 | `86988745914f93aad48ab075ba56e5a1d7bd636c6ef7343ebd9fc20a28de6072` |
| Pulse 提交 | `e348cdb Guard recent untracked block cleanup` |
| Agent JAR SHA-256 | `36705150ef9c9fd5106240be04cc1523edcb3b66a1a4bd81b47ea3fea7449d83` |
| Task SHA-256 | `721f9caeade2712293d11315752d48aa3079ce2885387e8a5b586fad773d8208` |
| Bundled JRE | OpenJDK `17.0.19` |
| 当前豆包范围 | 15 台 |
| 移出范围 | 1 台 |
| 最终结果 | 当前 15 台全部通过；移出节点 Agent 已卸载 |

Fleet 提交 `1187057` 将原 `doubao2` 合并到 `doubao`，同时从旧 `doubao`
范围移除 `fdbd:dc06:1:71e::42`。

## 当前 Agent 范围

| 主机 | 变更方式 |
| --- | --- |
| `fdbd:dc02:e:137::47` | 已安装节点差分更新 |
| `fdbd:dc02:e:289::41` | 已安装节点差分更新 |
| `fdbd:dc02:e:331::42` | 已安装节点差分更新 |
| `fdbd:dc02:e:333::21` | 已安装节点差分更新 |
| `fdbd:dc02:e:337::13` | 已安装节点差分更新 |
| `fdbd:dc02:e:385::19` | 已安装节点差分更新 |
| `fdbd:dc03:f:41a::48` | 已安装节点差分更新 |
| `fdbd:dc02:e:137::43` | 新节点初装 |
| `fdbd:dc03:14:600::32` | 新节点初装 |
| `fdbd:dc07:2:81c::37` | 新节点初装 |
| `fdbd:dc07:2:81c::38` | 新节点初装 |
| `fdbd:dc07:2:81c::39` | 新节点初装 |
| `fdbd:dc07:2:81e::37` | 新节点初装 |
| `fdbd:dc07:2:81e::38` | 新节点初装 |
| `fdbd:dc07:2:81e::39` | 新节点初装 |

## 发布前检查

1. 从 `origin/main` 拉取并确认 `HEAD=e348cdb`。
2. 执行 `mvn clean package`，结果为 `80/80` 测试通过。
3. 对 `doubao` 执行 deploy 和 task-only 两次 dry-run，均精确选择 15 台。
4. 使用 `--parallel 1` 串行执行 Orthrus demand，避免并发
   `kgetcred` 竞争。
5. 先执行单机 canary，再扩展到完整范围。

范围确认命令：

```bash
cd /Users/david/Documents/fleet-ops

bash scripts/call.sh \
  -f /Users/david/Documents/projects/pulse/.tmp/scripts/doubao-agent-jar-diff-deploy-20260808.sh \
  -t doubao \
  -i ./hosts \
  --parallel 8 \
  --timeout 180 \
  --max-hosts 15 \
  --dry-run \
  -- /Users/david/Documents/projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar \
  36705150ef9c9fd5106240be04cc1523edcb3b66a1a4bd81b47ea3fea7449d83
```

## Agent 发布

已安装的 7 台节点先比较远端
`/data24/otf/pulse/bin/pulse.jar` SHA。SHA 不一致时才上传 JAR、保留
`pulse.jar.rollback`、重启 `pulse-agent.service` 并验证服务状态。

新并入的 8 台节点经只读探测确认：

- `/data24/otf/pulse` 不存在；
- `pulse-agent.service` inactive；
- 系统 Java 为 OpenJDK 11，不满足运行要求。

这 8 台使用 `docs/script/pulse-cdn-new-deploy.sh` 初装，并部署 bundled
OpenJDK 17.0.19。初装采用 1 台 canary 加剩余 7 台的范围，避免重复上传
或重启 canary。

结果：

- 已安装节点：7 台更新成功；
- 新节点：8 台初装成功；
- 操作整体分类：15 `updated`、0 `unchanged`、0 `failed`；
- 最终均为 `PULSE_AGENT_CLUSTER=doubao`、`PULSE_AGENT_ROLE=agent`；
- `pulse-agent.service` 15/15 active。

## Task-Only 差分同步

相对上次豆包发布，`docs/task/analyze-block-layout.py` 已变化，因此使用
`docs/script/pulse-cdn-new-task-diff-sync.sh` 对同一 `doubao` 范围执行独立
task-only 同步：

```bash
cd /Users/david/Documents/fleet-ops

TASK_SCRIPT_PATH=/Users/david/Documents/projects/pulse/docs/task/analyze-block-layout.py \
REMOTE_TASK_PATH=/data24/otf/pulse/tasks/analyze-block-layout.py \
  bash scripts/call.sh \
    -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-task-diff-sync.sh \
    -t doubao \
    -i ./hosts \
    --parallel 4 \
    --timeout 180 \
    --max-hosts 15
```

结果：

- 7 台旧节点 `updated`；
- 8 台新节点 `unchanged`；
- 0 台 `failed`；
- 任务 SHA 15/15 一致；
- JAR SHA 和 mtime 未被 task-only 操作改变；
- 旧节点均满足 `service_start_epoch < task_mtime`，证明同步任务脚本后
  Agent 未重启。

## 移出节点卸载

旧、新 inventory 集合差只得到：

```text
fdbd:dc06:1:71e::42
```

卸载前只读探测确认：

- `pulse-agent.service` active/enabled；
- `PULSE_AGENT_CLUSTER=doubao`；
- 该节点没有 coordinator unit，`pulse-coordinator.service` inactive；
- 原 JAR SHA 为
  `7b8cdd36e8407c779043089142304e6ded14469fcb91e62b5e30bd2db19c7cd5`。

卸载使用单机临时 inventory、`--max-hosts 1`、dry-run 和 destructive
确认。执行顺序：

1. 备份 Agent unit；
2. disable 并停止 `pulse-agent.service`；
3. 确认 Agent 进程退出；
4. 将整个 `/data24/otf/pulse` 原子移动到回滚目录；
5. 删除 Agent unit 并执行 `systemctl daemon-reload`；
6. 强校验 service、unit、进程和安装目录均不存在。

卸载结果：

- `pulse-agent.service` inactive 且 unit absent；
- Agent 进程 absent；
- `/data24/otf/pulse` absent；
- coordinator 未被修改；
- 回滚备份完整保留在：
  `/data24/otf/pulse-agent-uninstall-backup-20260808T150306Z`。

## 最终验证

卸载后重新对当前 `doubao` 15 台执行权限刷新和逐机强校验：

- Agent JAR SHA：15/15 匹配；
- Task SHA：15/15 匹配；
- `pulse-agent.service`：15/15 active；
- `cluster=doubao`、`role=agent`：15/15 匹配；
- 失败：0。

移出节点独立校验结果：

- `classification=removed`；
- service inactive；
- unit、进程和安装目录 absent；
- backup present，backup JAR SHA 与卸载前一致。

## 证据

原始证据保存在项目 `.tmp/`，不提交到 Git：

- 发布：
  `.tmp/auto-ops/doubao-agent-update-20260808/`
- 发布最终校验：
  `.tmp/auto-ops/doubao-agent-update-20260808/final-verify.raw.log`
- Task-only 审计：
  `.tmp/auto-ops/doubao-agent-update-20260808/task-sync-audit.raw.log`
- 卸载：
  `.tmp/auto-ops/doubao-agent-uninstall-20260808/uninstall.raw.log`
- 移出节点最终校验：
  `.tmp/auto-ops/doubao-agent-uninstall-20260808/removed-verify.raw.log`
- 当前 15 台回归校验：
  `.tmp/auto-ops/doubao-agent-uninstall-20260808/current-cluster-verify.raw.log`

## 回滚

已安装节点的 JAR 差分更新保留
`/data24/otf/pulse/bin/pulse.jar.rollback`。回滚前必须先核对该文件 SHA，
再替换当前 JAR并重启服务。

移出节点恢复命令：

```bash
backup=/data24/otf/pulse-agent-uninstall-backup-20260808T150306Z

mv "$backup/pulse" /data24/otf/pulse
install -m 0644 \
  "$backup/pulse-agent.service" \
  /etc/systemd/system/pulse-agent.service
systemctl daemon-reload
systemctl enable --now pulse-agent.service
systemctl is-active pulse-agent.service
```

只有在确认该主机不会重新加入任何 Pulse inventory 且观察期结束后，才能
删除远端回滚备份。
