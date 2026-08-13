# Metrics Arthas trace and compensation budget rollout

## Context

- Operation date: 2026-08-13
- Request: use Arthas on Coordinators to debug remaining metrics time-series
  refresh stalls.
- Prior delivery: `3538eea Fix wide metrics queries using rollups`
- New delivery commit: `c0ddb4a Fix metrics live compensation query budget`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `61a7fbf74ed79419b764a43edc051d204bc6dbbe04f1adcefd6e2d46f9684d96`
- Previous Coordinator SHA-256: `4949ae551b26daf140630f58adab2d8320ab49cce4a7cdde8341d77d9aafe105`
- Scope: three Coordinators from `docs/ops/coordinators.hosts`
- Evidence roots:
  - `.tmp/auto-ops/metrics-arthas-20260813/`
  - `.tmp/auto-ops/metrics-compensation-budget-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Arthas Preflight

Evidence:

- `demand-coordinators.console.log`
- `preflight-coordinators.console.log`

All three Coordinators had:

- `pulse-coordinator.service=active`
- `pulse-agent.service=active`
- complete Arthas package present under `/data24/otf/pulse/tools/arthas`
- usable JDK: `/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java`

The first JAR SHA line in `preflight-coordinators.console.log` is ignored
because its inline shell quoting broke the `awk` command. Later per-host
snapshots and deployment verification recorded SHA correctly.

## Low-intrusion Snapshots

Accepted evidence:

- `arthas-snapshot-fdbd_dc05_11_634__45.console.log`
- `arthas-snapshot-fdbd_dc05_13_10c__40.console.log`
- `arthas-snapshot-fdbd_dc07_0_810__44.console.log`

Each snapshot ran:

```text
dashboard -n 3 -i 2000
jvm
thread -n 5
stop
```

All snapshots ended with:

```text
Resetting all enhanced classes
Arthas Server is going to shutdown
```

Post-snapshot checks showed:

- Coordinator PID unchanged
- Coordinator start timestamp unchanged
- Agent start timestamp unchanged
- `pulse-coordinator.service=active`
- `pulse-agent.service=active`
- no `3658` or `8563` listener remained
- metrics storage health `status=ok`, `queue_depth=0`, `dropped_commands=0`,
  `failed_commands=0`

## Trace Evidence

Accepted evidence:

- `arthas-trace-query-634-45.console.log`
- `arthas-stop-cleanup-634-45.console.log`
- `arthas-trace-rollup-634-45.console.log`

The first trace session captured the request path but was interrupted after
the evidence was collected. It left a `3658` listener and Arthas boot process.
`arthas-stop-cleanup-634-45.console.log` is the accepted cleanup evidence:
Arthas `stop` ran, enhanced classes were reset, Coordinator stayed active, and
no Arthas ports remained.

Key trace result for a 6h `heartbeat.agent_collect_ms` query:

```text
CoordinatorHttpServer.queryMetrics: 76.06 ms
  CoordinatorService.queryMetrics: 24.78 ms
  peer HttpClient.send: 40.15 ms total across 2 peers
  mergeMetricResults: 5.79 ms

SegmentedMetricStorage.queryRange: 367.49 ms
  RollupMetricStorage.query: 366.06 ms
```

The second trace isolated rollup:

```text
RollupMetricStorage.query: 410.70 ms
  collectV2: 390.49 ms
  applySeriesBudget: 15.55 ms

curl: 200, 0.668 s, 241068 bytes, 2972 points, suggested_step=60000
```

This proved the deployed wide-window fix now uses rollup and is no longer on
the raw-shard scan path.

## Additional Runtime Finding

Evidence:

- `metrics-stream-12s-634-45.sse`
- source lines in `src/main/frontend/src/main.tsx`

The metrics SSE stream emits `metric.invalidate` roughly every 5 seconds.
The manual refresh path used the new range budget, but the live compensation
path still used hardcoded:

```text
stepMs: 10_000
pointLimit: 20_000
```

That means a user-visible manual refresh could overlap with an automatic
compensation query using the old budget. This matched the remaining "卡住迹象"
after the first rollout.

## Code Change

Commit `c0ddb4a` changed the compensation query to use the same budget as
manual refresh:

```text
stepMs: metricQueryStepMs(rangeMinutes)
pointLimit: metricQueryPointLimit(rangeMinutes)
```

Regression coverage:

```text
tools/host-cluster-scope.test.mjs
metrics live compensation uses the same range budget as manual refresh
```

Local validation:

- `node --test tools/host-cluster-scope.test.mjs tools/host-stream-v3.test.mjs`
- `mvn -q -Dtest=SegmentedMetricStorageTest,CoordinatorHttpServerTest test`
- `npm run build`
- `mvn -q -DskipTests package`
- `git diff --check`

## Rollout

Accepted evidence:

- `local-jar-sha.txt`
- `demand-coordinators.console.log`
- `dryrun-coordinators.console.log`
- `rollout-coordinators.console.log`
- `rollout-coordinators/summary.json`
- `final-storage-634-45.json`

Differential result:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | updated |
| `fdbd:dc05:13:10c::40` | updated |
| `fdbd:dc07:0:810::44` | updated |

Only `pulse-coordinator.service` was restarted. Agents were not redeployed.

Final deployment verification:

```text
rollout-coordinators: total=3 ok=3 failed=0
JAR_SHA=61a7fbf74ed79419b764a43edc051d204bc6dbbe04f1adcefd6e2d46f9684d96
pulse-coordinator.service=active
metrics storage status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
last_error=""
```

## Rollback

Rollback only the three Coordinator hosts:

```text
previous_sha=4949ae551b26daf140630f58adab2d8320ab49cce4a7cdde8341d77d9aafe105
target=/data24/otf/pulse/bin/pulse.jar
service=pulse-coordinator.service
```

After rollback, verify:

```text
sha256sum /data24/otf/pulse/bin/pulse.jar
systemctl is-active pulse-coordinator.service
curl -g -sS --max-time 5 http://[::1]:9966/api/metrics/storage
```

## Result

Arthas confirmed that the first fix moved wide queries to rollup, with
`RollupMetricStorage.query` taking about 410 ms for the traced 6h request.
The remaining frontend issue was an automatic live compensation query path
that still used the old wide-window budget. That path is now fixed and
deployed to all three Coordinators.
