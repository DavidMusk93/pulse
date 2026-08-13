# Coordinator Control SSE V4 rollout

## Context

- Operation date: 2026-08-13
- Delivery commit:
  `54c8a14214847dbefe78a12325cc8f0459796e4c`
- Artifact: `.worktrees/feat/nonblocking-sse/target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275`
- Previous artifact:
  `target/pulse-0.1.0-SNAPSHOT.jar`
- Previous SHA-256:
  `96550e19e815092152a049eb58adadcbba1e12ec3ca4e551e208425fef376adb`
- Inventory: `docs/ops/coordinators.hosts`
- Exact tag: `coordinators`
- Scope: 3 Coordinators, `--max-hosts 3`
- Evidence root:
  `.tmp/auto-ops/coordinator-control-sse-v4-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.
- Agent rollout: not included. Only `pulse-coordinator.service` was targeted.

## Change

The public Coordinator transport moved from blocking JDK `HttpServer` SSE to
Netty. Host, EventBus, metrics invalidation, storage health, and lightweight
task state now share `/api/control/stream`. Task output remains on independent
data streams. Retired control SSE endpoints return `410 Gone`.

The production capacity contract is:

```text
500 long-lived control connections + 50 concurrent output streams
initialization API P99 < 100 ms
metrics P99 queue increment < 50 ms
```

## Local Gates

- `mvn -q test`: passed.
- `mvn -q clean package`: passed.
- `npm run build`: passed.
- Host scope and V3 decoder Node tests: passed.
- `git diff --check`: passed.
- Combined `500 control + 50 output` integration gate: passed.
- Code review receipt:
  `/tmp/compound-engineering-501/ce-code-review/20260813-195849-10a94db2/review.json`

## Scope And Access

Permissions were refreshed immediately before deployment and verification.
The exact dry run selected:

```text
fdbd:dc05:11:634::45
fdbd:dc05:13:10c::40
fdbd:dc07:0:810::44
```

Evidence:

- `demand-coordinators.console.log`
- `dryrun.console.log`
- `scope/coordinators.hosts`
- `local-jar-sha.txt`

## Canary

Canary: `fdbd:dc05:11:634::45`.

Differential result:

```text
status=changed
remote_sha_before=96550e19e815092152a049eb58adadcbba1e12ec3ca4e551e208425fef376adb
status=updated
remote_sha_after=9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275
pulse-coordinator.service=active
```

The first read-only pre-snapshot failed in the auto-ops runtime because an
empty callee-tail array was treated as unbound. It is rejected as verification
evidence and preserved in `canary-before.console.log`. The fail-closed deploy
callee independently recorded the old and new SHA.

Accepted canary verification:

- target JAR SHA matched;
- Coordinator and Agent services were active;
- root and frontend asset returned HTTP 200;
- control events included `hosts.snapshot`, `eventbus.snapshot`,
  `storage.health`, `metric.invalidate`, and `ping`;
- Host, EventBus, metrics, and task-status legacy streams returned HTTP 410;
- 40-request short API P99 range was 35.419-46.599 ms.

Evidence:

- `canary-deploy.console.log`
- `canary-deploy/results.tsv`
- `canary-deploy/summary.json`
- `canary-verify.console.log`
- `canary-verify/results.tsv`
- `canary-verify/summary.json`

## Rollout

Per-host differential result:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | unchanged after canary |
| `fdbd:dc05:13:10c::40` | updated |
| `fdbd:dc07:0:810::44` | updated |

Result: `total=3 ok=3 failed=0`.

Evidence:

- `rollout.console.log`
- `rollout/results.tsv`
- `rollout/summary.json`
- `rollout/failed-hosts.txt`

## Final Verification

Independent verification reported `total=3 ok=3 failed=0`. Every Coordinator
had:

```text
JAR_SHA=9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275
pulse-coordinator.service=active
pulse-agent.service=active
root_http=200
asset_http=200
legacy_control_streams=410
```

The highest observed 40-request P99 values were:

```text
hosts=30.945 ms
eventbus=43.810 ms
metrics catalog=43.619 ms
metrics storage=46.789 ms
```

Evidence:

- `final-verify.console.log`
- `final-verify/results.tsv`
- `final-verify/summary.json`
- `final-verify/failed-hosts.txt`

## Capacity Gate

The accepted production canary probe held 500 real control SSE connections,
issued 50 concurrent output-route requests, and measured short requests before
and during saturation:

```text
control_connections=500
output_route_requests=50
output_route_results=404:50

hosts baseline P99=15.495 ms
hosts saturated P99=49.974 ms
hosts saturated P99 < 100 ms

metrics baseline P99=75.155 ms
metrics saturated P99=57.313 ms
metrics P99 queue increment=-17.842 ms
```

All 486 production task snapshots had no completion or running output at the
time of verification. The 50 requests therefore prove output-route isolation,
not production output encoding throughput. The complete 500-control plus
50-output encoding contract is covered by the local integration gate and must
be rechecked in production when genuine output streams are available.

The first capacity attempt is rejected because the host has Python 3.7 and the
probe used `asyncio.to_thread`. The compatible rerun used
`run_in_executor` and passed.

Evidence:

- `capacity.console.log` (rejected compatibility attempt)
- `capacity/summary.json` (failed attempt)
- `capacity-rerun.console.log`
- `capacity-rerun/results.tsv`
- `capacity-rerun/summary.json`
- `capacity-rerun/failed-hosts.txt`

## Post-Capacity Health

All three Coordinators remained active with zero matching JVM/runtime errors
in the post-deployment journal window. Metrics storage reported:

```text
status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
last_error=""
```

Post-probe FD counts were 110-145, proving the 500 test connections were
closed. Agent services remained active and were not restarted by the
Coordinator deployment.

Evidence:

- `post-capacity-health.console.log`
- `post-capacity-health/results.tsv`
- `post-capacity-health/summary.json`
- `post-capacity-health/failed-hosts.txt`

## Rollback

The exact previous JAR remains at:

```text
target/pulse-0.1.0-SNAPSHOT.jar
SHA-256=96550e19e815092152a049eb58adadcbba1e12ec3ca4e551e208425fef376adb
```

Rollback only the three Coordinator hosts:

1. Compare the rollback artifact SHA with each remote JAR.
2. Upload only changed hosts.
3. Replace `/data24/otf/pulse/bin/pulse.jar`.
4. Restart only `pulse-coordinator.service`.
5. Verify per-host SHA, service state, root HTTP, and metrics storage health.

Rollback trigger:

- initialization API P99 remains at or above 100 ms;
- metrics P99 queue increment remains at or above 50 ms;
- control clients repeatedly receive resync loops;
- service errors, storage failures, or unexpected connection growth appear.

## Result

Control SSE V4 is active on all three Coordinators. The production
500-control saturation gate passed without short-request starvation. No Agent
JAR or task script was deployed in this operation.
