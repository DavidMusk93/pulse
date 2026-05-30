# Arthas 调试 Pulse

## 目标

使用 [Arthas](https://github.com/alibaba/arthas) 在远端机器上诊断 Pulse coordinator 或 agent 的 Java 进程。

## 部署位置

- 安装根目录：`/data24/otf/pulse`
- Arthas boot jar：`/data24/otf/pulse/tools/arthas/arthas-boot.jar`
- Java：优先使用系统 `java`，否则使用 `/data24/otf/pulse/jre/bin/java`

## 部署脚本

`docs/script/pulse-arthas-deploy.sh` 负责把 `arthas-boot.jar` 上传到远端。

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
/data24/otf/pulse/jre/bin/java -jar tools/arthas/arthas-boot.jar
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
