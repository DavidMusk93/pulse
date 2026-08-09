# Arthas 调试 Pulse

## 目标

使用 [Arthas](https://github.com/alibaba/arthas) 在远端机器上诊断 Pulse coordinator 或 agent 的 Java 进程。

## 部署位置

- 安装根目录：`/data24/otf/pulse`
- Arthas boot jar：`/data24/otf/pulse/tools/arthas/arthas-boot.jar`
- 完整包目录：`/data24/otf/pulse/tools/arthas`
- Java：必须使用包含 `jdk.attach` 的现有 JDK

Pulse 自带的 `/data24/otf/pulse/jre` 是裁剪 JRE，不包含 `jdk.attach`，不能用于
Arthas attach。当前 coordinator host 可复用：

```text
/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java
```

JDK 11 attach 到 JRE 17 target 会打印版本不一致 warning，但现场已验证 Arthas
4.2.0 可正常 attach、执行 batch command 并 `stop`。

## 部署脚本

`docs/script/pulse-arthas-deploy.sh` 负责把 `arthas-boot.jar` 上传到远端。

该脚本只适用于远端可访问 Arthas repository 的环境。隔离环境仅上传
`arthas-boot.jar` 不够，boot 会报：

```text
Can not find Arthas under local: /root/.arthas/lib
Unable to download arthas from remote server
```

必须离线上传官方完整 `arthas-bin.zip`，并至少验证：

```text
arthas-boot.jar
arthas-core.jar
arthas-agent.jar
```

2026-08-09 使用 Arthas 4.2.0 官方 release：

```text
release tag: arthas-all-4.2.0
arthas-bin.zip SHA-256:
3252a1531ee3a79910e5602de693297e75f4487a6f5a5c2159e25c3c9d132f88
```

示例：

```bash
AUTO_OPS_ARTIFACT_ROOT=/Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/arthas \
AUTO_OPS_REPORT_DIR=/Users/bytedance/Documents/01_Projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/bytedance/Documents/01_Projects/pulse/docs/script/pulse-arthas-deploy.sh \
    -t cdn_new \
    --limit-file /Users/bytedance/Documents/01_Projects/pulse/.tmp/auto-ops/cdn-new-pulse/coordinators.txt \
    --parallel 3 \
    --timeout 120 \
    --max-hosts 3 \
    -- /Users/bytedance/Documents/01_Projects/pulse/.tmp/runtime/arthas-boot.jar /data24/otf/pulse
```

## 常用命令

在目标机器上执行：

```bash
cd /data24/otf/pulse
/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java \
  -jar tools/arthas/arthas-boot.jar
```

选择 Pulse 进程后可执行：

```bash
dashboard
thread -n 5
jvm
sysprop
logger
sc -d com.bytedance.pulse.*
sm com.bytedance.pulse.CoordinatorService
watch com.bytedance.pulse.CoordinatorService handleHeartbeat '{params, returnObj}' -x 2
trace com.bytedance.pulse.CoordinatorService handleHeartbeat
```

## 低侵入 JVM 快照

常规 runtime feedback 禁止先用 `trace/watch/profiler`。使用固定 30 秒窗口：

```bash
pid=$(systemctl show pulse-coordinator.service -p MainPID --value)

/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java \
  -jar /data24/otf/pulse/tools/arthas/arthas-boot.jar \
  --telnet-port 3658 \
  --http-port -1 \
  --session-timeout 120 \
  -c 'dashboard -n 6 -i 5000; jvm; thread -n 5; stop' \
  "$pid"
```

执行前后必须验证：

- coordinator PID 和 `ExecMainStartTimestampMonotonic` 不变；
- agent `ExecMainStartTimestampMonotonic` 不变；
- Pulse JAR SHA 不变；
- `pulse-coordinator.service=active`；
- storage dropped/failed 为 0；
- `3658`、`8563` 无残留 listener。

`stop` 输出应包含：

```text
Resetting all enhanced classes
Arthas Server is going to shutdown
```

## 验证 Group Heartbeat

`CoordinatorService#handleHeartbeat` 支持批量请求 `agents[]`。调试时可用 Arthas 观察：

```bash
watch com.bytedance.pulse.CoordinatorService handleHeartbeat '{params[0].groupId, params[0].agents, returnObj}' -x 3
```

期望：

- `groupId` 不为空时，响应包含 `agents[]`。
- 每个 agent 返回对应 `acceptedSeq`。
- host 视图中的 `source` 等于 `groupId`。

## 退出

```bash
stop
```

## 注意

- Arthas 仅用于临时诊断，不作为常驻服务。
- 生产机器调试时避免长时间开启高开销 `trace/watch`。
- 不要在高频路径上使用过大的 `-x` 展开深度。
- 只做 heap/GC/thread 基线时不要执行 `profiler`、heap dump 或强制 GC。
- 不要使用 `--telnet-port 0`；boot client 会尝试连接端口 0 并失败。
