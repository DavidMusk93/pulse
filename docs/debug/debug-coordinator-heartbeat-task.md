# Debug Session: coordinator-heartbeat-task

Status: [OPEN]

## Problem

- Coordinator 心跳疑似整体异常。
- Metrics 无数据。
- Run UI 任务无法执行。
- 关注 block task：`task-c91bd71b-2603-4189-907a-9f4fe72405be`。

## Hypotheses

1. Coordinator 服务进程存活但 HTTP/heartbeat 处理线程池被阻塞，导致 `/api/hosts`、metrics query 和 task dispatch 都无法正常推进。
2. Coordinator 内部 remote task 队列或 completion/stream 状态异常，指定 task 已入队但没有被 agent heartbeat 拉取或确认。
3. Metrics 写入或查询链路异常，例如 SQLite writer/SSE/查询线程故障，导致 UI metrics 无数据，但 heartbeat 当前态可能仍可用。
4. 最新部署后的 coordinator 静态资源/API 版本与运行态不一致，前端发起的 metrics/task 请求命中了不兼容 endpoint。
5. Agent 心跳到 coordinator 的路径异常，导致任务无法下发、metrics 样本无更新、host 当前态逐渐失效。

## Evidence Plan

- 读取 `docs/debug/arthas.md`，确认 attach 与诊断命令。
- 对 3 台 coordinator 收集服务状态、进程、HTTP API、日志摘要。
- 使用 Arthas 观察 `CoordinatorHttpServer`、`CoordinatorService`、`RemoteTaskService`、metrics storage 相关运行态。
- 针对 task id `task-c91bd71b-2603-4189-907a-9f4fe72405be` 查询队列/trace/stream 状态。

## Timeline

- Created session and hypotheses.
- Read `docs/debug/arthas.md`; attempted Arthas attach on `fdbd:dc05:11:634::45`.
- Runtime probe on 3 coordinators:
  - all `pulse-coordinator.service` are active;
  - `/api/hosts` returns 471 alive hosts on all 3;
  - `/api/metrics/storage` status is `ok`, queue depth is 0, failed commands are 0;
  - `/api/metrics/query_range?metric=heartbeat.arrival_gap_ms` returns series and points on all 3;
  - SQLite `heartbeat_sample` has recent 5m samples on all 3.
- Target task probe:
  - `task-c91bd71b-2603-4189-907a-9f4fe72405be` exists only on coordinator `fdbd:dc05:11:634::45`;
  - target agent is `dc05-p13-t46-n044.byted.org`;
  - task state is `queued`, `delivered_at_ms=null`, `accepted_at_ms=null`;
  - task deadline was `1781154021447`, already expired by the time of probing.
- Agent routing evidence:
  - target agent env has coordinator URLs ordered as `45,40,44`;
  - Java hash for `dc05-p13-t46-n044.byted.org` gives preferred index `2`;
  - preferred coordinator is `fdbd:dc07:0:810::44`, not `fdbd:dc05:11:634::45`.
- Arthas limitation:
  - coordinator `fdbd:dc05:11:634::45` has `arthas-boot.jar`, PID `119132` found;
  - Arthas boot failed because the full Arthas package is absent and the host cannot download from `arthas.aliyun.com`;
  - local full Arthas package was not found, so JVM watch evidence could not be collected with Arthas.

## Current Conclusion

- Coordinator heartbeat is not globally broken based on API and SQLite evidence.
- Metrics storage is receiving and querying data; the "metrics no data" symptom is not reproduced from coordinator localhost APIs.
- The blocked task is queued on a coordinator that the target agent does not directly heartbeat to.
- Current heartbeat forwarding synchronizes host state to peers, but task queues are local in-memory state and are not routed/replicated to the coordinator that owns the agent heartbeat response.

## Fix Applied

- `CoordinatorService` now records the coordinator that directly handled each agent heartbeat.
- `HostView` exposes `coordinatorId` for observability.
- `CoordinatorHttpServer` routes task snapshot GET, task enqueue POST, completion POST, and single-agent task SSE to the recorded owner coordinator when the current coordinator is not the owner.
- Task route loop protection uses header `x-pulse-task-routed: 1`.
- Default route base is `http://<coordinatorId>:${PULSE_PORT:-9966}` and can be overridden via `PULSE_TASK_ROUTE_TEMPLATE`.

## Verification

- `mvn test -Dskip.frontend.build=true` passed: 70 tests, 0 failures.
- Coordinator hostnames are reachable between coordinators via `http://<coordinatorId>:9966/api/hosts`.
- Commit `37951d6 Route task APIs to heartbeat owner coordinator` was pushed to `origin/main`.
- Deployed JAR SHA `f1432597bf21026fa8f7574b42fa5f707765e42afd17de0e67cae715991079d4` to all 3 coordinators.
- Final deploy verify: all 3 coordinators active, SHA matched, `/api/hosts` returned 471 hosts.
- Post-fix E2E from previous wrong entry coordinator `fdbd:dc05:11:634::45`:
  - target agent `dc05-p13-t46-n044.byted.org`;
  - new task `task-01cdf9cc-4d6e-4bcd-8395-5e69b7ec7b75`;
  - observed `queued` first, then routed snapshot briefly empty while command was in-flight;
  - completion observed at poll attempt 12 with status `completed`, exit code `0`.

## Notes

- The original task `task-c91bd71b-2603-4189-907a-9f4fe72405be` was already expired and was lost on coordinator restart because task queues are in-memory.
- A temporary invalid E2E probe created an unrelated task for agent `--`; this was caused by a temporary script argument-passing bug, not production code.

## Confirmed / Rejected Hypotheses

- H1 HTTP/heartbeat thread pool blocked: rejected by fast `/api/hosts`, `/api/metrics/*`, active services, fresh host ages.
- H2 task queued but not pulled by agent heartbeat: confirmed; queued only on `fdbd:dc05:11:634::45`, agent prefers `fdbd:dc07:0:810::44`.
- H3 metrics write/query chain broken: rejected by storage health and recent query points.
- H4 API/static version mismatch: not supported by collected evidence.
- H5 agent heartbeat path abnormal: partially rejected; target agent is alive and heartbeats are reflected on all coordinators.
