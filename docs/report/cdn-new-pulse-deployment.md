# cdn_new Pulse 部署报告

## 摘要

- 运行模式：`central-runtime`
- Runtime 仓库：`/Users/bytedance/Documents/gitlab/olap-toolbox`
- Project 仓库：`/Users/bytedance/Documents/01_Projects/pulse`
- 集群 tag：`cdn_new`
- 目标规模：50 台
- Coordinator：3 台
- Agent：50 台
- 安装目录：`/data24/otf/pulse`
- Coordinator 监听：IPv6 `::`，端口 `9966`
- 部署结果：成功
- 验证结果：50 台 agent active，3 台 coordinator active，coordinator API `HOST_COUNT=50`

## Coordinator 节点

- `fdbd:dc05:11:634::45`
- `fdbd:dc05:13:10c::40`
- `fdbd:dc07:0:810::44`

## 代码版本

- `a71f7ab Improve cdn deployment verification`
- `0f94ce0 Include architecture in cdn probe`
- `b1ce3b0 Support bundled JRE in cdn deployment`
- `08d849e Fix IPv6 SSH handling in deployment scripts`
- `73c5a0e Add pulse agent and cdn deployment scripts`

## 执行记录

### Scope

```bash
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-cdn-new-probe.sh \
    -t cdn_new \
    --max-hosts 500 \
    --dry-run
```

- 结果：`total=50`

### Probe

```bash
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-cdn-new-probe.sh \
    -t cdn_new \
    --parallel 16 \
    --timeout 60 \
    --max-hosts 50
```

- 结果：`total=50 ok=50 failed=0`
- 发现：远端 `java` 不在 PATH 中。
- 发现：远端为 `x86_64` 架构。
- 处理：下载 Linux x64 Temurin 17 JRE，并通过部署脚本安装到 `/data24/otf/pulse/jre`。

### Deploy Dry-Run

```bash
COORDINATORS='fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44'
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --parallel 8 \
    --timeout 120 \
    --max-hosts 50 \
    --dry-run \
    --yes \
    -- /Users/bytedance/Documents/01_Projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar "$COORDINATORS" /data24/otf/pulse -
```

- 结果：`total=50`
- 风险级别：`destructive`
- 说明：dry-run 未执行远端变更。

### Staged Deploy

```bash
COORDINATORS='fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44'
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --limit-file /Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse/coordinators.txt \
    --parallel 3 \
    --timeout 240 \
    --max-hosts 3 \
    --yes \
    -- /Users/bytedance/Documents/01_Projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar "$COORDINATORS" /data24/otf/pulse /Users/bytedance/Documents/01_Projects/pulse/.tmp/runtime/temurin17-jre-linux-x64.tar.gz
```

- 结果：`total=3 ok=3 failed=0`
- 服务：`pulse-agent.service` active
- 服务：`pulse-coordinator.service` active

### Full Deploy

```bash
COORDINATORS='fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44'
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --parallel 8 \
    --timeout 300 \
    --max-hosts 50 \
    --yes \
    -- /Users/bytedance/Documents/01_Projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar "$COORDINATORS" /data24/otf/pulse /Users/bytedance/Documents/01_Projects/pulse/.tmp/runtime/temurin17-jre-linux-x64.tar.gz
```

- 结果：`total=50 ok=50 failed=0`

### Verify

```bash
COORDINATORS='fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44'
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-cdn-new-verify.sh \
    -t cdn_new \
    --parallel 16 \
    --timeout 60 \
    --max-hosts 50 \
    -- "$COORDINATORS"
```

- 结果：`total=50 ok=50 failed=0`
- Java：`/data24/otf/pulse/jre/bin/java`
- Java 版本：`openjdk version "17.0.19" 2026-04-21`
- Agent：50 台 `pulse-agent.service` active
- Coordinator：3 台 `pulse-coordinator.service` active
- Coordinator API：`HOST_COUNT=50`

## 残余风险

- 当前 coordinator 状态在内存中维护，进程重启后需要等待 agent 下一轮心跳恢复视图。
- 当前 agent 使用多个 coordinator URL 轮询上报，不做强一致复制。
- 本次部署使用 systemd 管理，回滚需要停止并 disable `pulse-agent.service` 与 `pulse-coordinator.service`，再删除 `/data24/otf/pulse`。

## 回滚参考

```bash
systemctl disable --now pulse-agent.service || true
systemctl disable --now pulse-coordinator.service || true
rm -rf /data24/otf/pulse
rm -f /etc/systemd/system/pulse-agent.service /etc/systemd/system/pulse-coordinator.service
systemctl daemon-reload
```
