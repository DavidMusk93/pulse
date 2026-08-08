# Coordinator 过期节点自动清理

## 背景

豆包节点 `fdbd:dc06:1:71e::42` 的 Agent 卸载后，三个 coordinator
仍永久保留该节点的 `expired` 内存快照，导致 `/api/hosts` 和 UI 持续显示
`doubao=16`。

根因是 TTL 只把 `NodeState` 状态切换为 `expired`，没有从 coordinator 的
当前态容器中删除。原设计也明确要求 expired host 永久保留在 host 视图。

## 机制

- 新增 `PULSE_EXPIRED_HOST_RETENTION_MS`，默认 `300000ms`；
- TTL 到期后先保留 expired 状态 5 分钟，供故障观察；
- retention 到期后，从 host 当前态、group plan、group metric 基线和内存
  task 状态中清理该 Agent；
- 历史 SQLite metrics 不删除；
- 后续收到新心跳时，该 Agent 可以作为新节点重新加入；
- 清理时输出结构化日志 `host_state_cleanup status=removed ...`。

实现提交：

```text
1c565b2 Prune retired agents from coordinator state
```

## 验证与发布

- 聚焦测试：`CoordinatorServiceTest` 26/26 通过；
- 全量测试：81/81 通过；
- 构建：`mvn clean package` 通过；
- 目标 JAR SHA：
  `c1e30f58051cf146d4bf1a159de0cfe7860944831d7d116929f05f24c62820c5`；
- coordinator dry-run：3 台；
- canary：1 台 `updated`；
- full rollout：1 台 `unchanged`、2 台 `updated`、0 台 `failed`；
- 三台均保留 `/data24/otf/pulse/bin/pulse.jar.rollback`。

发布前基线，三台 coordinator 一致：

```text
DOUBAO_TOTAL=16 REMOVED_PRESENT=1 REMOVED_STATUS=expired
```

发布后逐机强校验，三台 coordinator 一致：

```text
DOUBAO_TOTAL=15 REMOVED_PRESENT=0 ALIVE=15
JAR_SHA=c1e30f58051cf146d4bf1a159de0cfe7860944831d7d116929f05f24c62820c5
COORDINATOR_ACTIVE=active
```

## 证据

- `.tmp/auto-ops/coordinator-host-prune-20260808/baseline-retry.raw.log`
- `.tmp/auto-ops/coordinator-host-prune-20260808/canary.raw.log`
- `.tmp/auto-ops/coordinator-host-prune-20260808/canary-verify.raw.log`
- `.tmp/auto-ops/coordinator-host-prune-20260808/full-deploy.raw.log`
- `.tmp/auto-ops/coordinator-host-prune-20260808/final-verify.raw.log`

## 回滚

回滚时必须先校验 `pulse.jar.rollback` SHA，再替换当前 JAR并重启
`pulse-coordinator.service`。
