# Coordinator Host 磁贴展示执行计划

## 背景

参考 `docs/design/distributed-heartbeat-management.md`，本轮推进 Pulse 到一个可运行的最小最终态：

- Coordinator 提供 `POST /heartbeat` 和 `POST /heartbeat_fwd`。
- Coordinator 维护 agent host 状态视图。
- Coordinator 提供网页展示 host 信息，视觉类似 Windows Phone 磁贴卡片。
- 本地开发环境通过脚本配置，不使用 `sudo`，脚本沉淀到 `docs/script`。
- 采用测试驱动方式，先补测试，再实现核心逻辑。

## 技术选择

- 语言与构建：Java 17 + Maven。
- HTTP 服务：JDK 内置 `com.sun.net.httpserver.HttpServer`，避免过早引入大型 Web 框架。
- JSON：Jackson，支持协议字段的 snake_case。
- 测试：JUnit Jupiter，覆盖状态合并、批量心跳、peer 转发过滤和网页渲染。
- 打包：Maven Shade Plugin 生成可运行 jar。

## 交付范围

1. 本地开发脚本
   - `docs/script/setup-local-dev.sh`
   - 不使用 `sudo`。
   - 检查 Java 17、Maven、项目目录。
   - 创建本地 `.dev` 工作目录。
   - 执行 `mvn test` 验证环境。

2. Coordinator 核心协议
   - `POST /heartbeat`
   - 支持单 agent 心跳。
   - 支持 group 批量 `agents[]` 心跳。
   - 使用 `epoch + seq` 幂等合并规则。
   - 从 `state.*` 消息 payload 中汇聚 host 信息。

3. Peer 状态转发
   - `POST /heartbeat_fwd`
   - 只接受并合并 `state.*` 消息。
   - 不传播 `cmd.*` 与 `reply.*`。
   - 返回 accepted 与 merged 统计。

4. Host 网页展示
   - `GET /`
   - `GET /hosts`
   - 磁贴展示 agent、host、ip、zone、role、load、seq、source、最后观测时间和存活状态。
   - 提供 Windows Phone 风格的彩色矩形卡片、响应式布局和自动刷新。

5. 可观测数据接口
   - `GET /api/hosts`
   - 返回当前 host 视图 JSON，便于页面和后续工具复用。

## 测试计划

- `CoordinatorServiceTest`
  - 新心跳写入 host 状态。
  - 旧 `seq` 不覆盖新状态。
  - 更高 `epoch` 覆盖旧 epoch。
  - 批量心跳逐个返回 accepted seq。
  - `/heartbeat_fwd` 只合并 `state.*`。

- `CoordinatorHttpServerTest`
  - `/heartbeat` 接收 JSON 并返回 `ok=true`。
  - `/api/hosts` 返回已写入 host。
  - `/hosts` 返回包含磁贴样式和 host 信息的 HTML。

## 执行步骤

1. 补 Maven 依赖与测试插件。
2. 编写 service 层测试，明确协议和合并行为。
3. 实现 model、store、service。
4. 编写 HTTP 层测试，明确端点行为。
5. 实现 HTTP server、HTML 渲染与 main 入口。
6. 编写本地开发环境脚本。
7. 执行 `docs/script/setup-local-dev.sh` 和 `mvn test`。
8. 更新 README，记录启动、接口和测试方式。

## 验收标准

- `mvn test` 全部通过。
- `docs/script/setup-local-dev.sh` 可在无 sudo 权限下完成环境检查与测试。
- `java -jar target/pulse-0.1.0-SNAPSHOT.jar` 可启动 coordinator。
- `POST /heartbeat` 可写入 host 状态。
- `GET /hosts` 可看到 Windows Phone 风格 host 磁贴。
- `GET /api/hosts` 可返回 JSON host 列表。
