# Coordinator HTTP Performance Analysis Around 500 Hosts

## 背景

`otel` 集群接入后，coordinator 需要承载接近 500 台 agent 的心跳、peer forward、`/api/hosts` 轮询和 `/hosts` 页面访问。当前系统使用 JDK `HttpServer`，每台 agent 默认 5 秒心跳一次，页面默认 5 秒刷新一次 `/api/hosts`。

本分析基于 `docs/debug/arthas.md` 中的 Arthas 诊断入口，结合当前代码热路径完成。

## 诊断入口

在 coordinator 节点上使用 Arthas 观察：

```bash
cd /data24/otf/pulse
/data24/otf/pulse/jre/bin/java -jar tools/arthas/arthas-boot.jar
```

推荐观察点：

```bash
dashboard
thread -n 10
trace com.bytedance.pulse.CoordinatorHttpServer handle '#cost > 20'
trace com.bytedance.pulse.CoordinatorService handleHeartbeat '#cost > 20'
trace com.bytedance.pulse.CoordinatorService hosts '#cost > 20'
trace com.bytedance.pulse.CoordinatorService recomputeGroups '#cost > 20'
watch com.bytedance.pulse.CoordinatorService hosts '{returnObj.size()}' -x 1
```

注意事项：

- `trace/watch` 只短时间使用，避免在高频心跳路径上长期开启。
- 优先用 `#cost` 条件过滤，避免把每次心跳都展开输出。
- 如果 HTTP 线程增长明显，配合 `thread -n 10` 看是否集中在 JSON 序列化、group 重算或 peer forward。

## 当前热路径

`POST /heartbeat` 路径：

```text
CoordinatorHttpServer.handle
  -> CoordinatorService.handleHeartbeat
    -> merge state
    -> taskService.handleReplies
    -> recomputeGroups
      -> hosts
        -> states.values().stream()
        -> NodeState.toHostView(now)
        -> global sort by cluster/status/agentId
      -> bucket alive hosts
      -> per bucket sort by ipSortKey/agentId
    -> responseMessages
  -> peerForwarder.forward
  -> writeJson
```

`GET /api/hosts` 路径：

```text
CoordinatorHttpServer.handle
  -> CoordinatorService.hosts
    -> build HostView for every agent
    -> sort every agent
  -> writeJson
```

## 主要问题

- `recomputeGroups()` 在每次 heartbeat 后执行，接近 500 台、5 秒心跳时约 100 次/秒全量重算。
- `recomputeGroups()` 内部调用 `hosts()`，会为所有 agent 构建 `HostView` 并做全局排序，然后 group 内又再次排序。
- `/api/hosts` 每次轮询也会全量构建和排序 host 列表；多个浏览器页面会放大 JSON 序列化和对象分配。
- 当前 `HttpServer` 使用 `newCachedThreadPool()`，瞬时慢请求可能让线程数无上限增长，不利于 coordinator 稳态。
- Group plan 不是强一致控制面，允许亚秒级刷新延迟；当前每次心跳重算属于过度工作。

## 优化目标

- heartbeat 快路径只做状态合并、task reply 处理和响应消息生成。
- group plan 全量重算节流到固定间隔，默认 1 秒一次，并只在有 state 变更后触发。
- `/api/hosts` 使用短 TTL 快照，默认 1 秒，降低多页面轮询和 JSON 请求导致的重复构建。
- group 重算直接从当前 states 构建 alive host bucket，不再通过 `hosts()` 的全局排序中转。
- HTTP executor 改为有界线程池，避免异常慢请求导致线程无限扩张。

## 预期收益

- 500 台 agent、5 秒心跳时，group 全量重算从约 100 次/秒降到最多 1 次/秒。
- `/api/hosts` 在 1 秒内复用同一份 `HostView` 快照，多个页面同时刷新时不重复排序。
- heartbeat P99 应主要由 JSON decode/encode 和 task reply 处理决定，不再被全量 group 计算放大。
- coordinator 线程数变得可控，慢请求对整体稳定性的影响降低。

## 验证方式

本地验证：

```bash
mvn test
mvn package
```

远端验证：

```bash
curl -s "http://[fdbd:dc05:11:634::45]:9966/api/hosts" | python3 -m json.tool >/tmp/hosts.json
curl -s -o /tmp/hosts.html -w '%{http_code} %{time_total}\n' "http://[fdbd:dc05:11:634::45]:9966/hosts"
```

Arthas 验证：

```bash
trace com.bytedance.pulse.CoordinatorService handleHeartbeat '#cost > 20'
trace com.bytedance.pulse.CoordinatorService hosts '#cost > 20'
trace com.bytedance.pulse.CoordinatorService recomputeGroups '#cost > 20'
```

上线后期望：

- `/api/hosts` 返回总 host 数包含 `otel` 新增 agent。
- coordinator 日志无 HTTP executor 拒绝请求。
- `handleHeartbeat` 慢调用中不再频繁出现 `recomputeGroups`。
- 三台 coordinator host 总量接近一致，peer forward 仍正常。
