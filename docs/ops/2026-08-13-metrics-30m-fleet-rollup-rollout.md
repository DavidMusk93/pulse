# Metrics 30-minute fleet rollup rollout

## Context

- Operation date: 2026-08-13
- Goal: stop serving 30-minute fleet/TopN chart queries from raw SQLite
  shards while preserving raw detail for explicitly selected agents.
- Delivery commit: `015e8b3 Route 30min fleet metrics through rollups`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df`
- Previous Coordinator SHA-256:
  `61a7fbf74ed79419b764a43edc051d204bc6dbbe04f1adcefd6e2d46f9684d96`
- Inventory: `docs/ops/coordinators.hosts`
- Exact tag: `coordinators`
- Scope: 3 Coordinators, `--max-hosts 3`
- Evidence root:
  `.tmp/auto-ops/metrics-30m-fleet-rollup-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Change

`SegmentedMetricStorage` now uses 1-minute rollups when all of these are true:

- query window is at least 30 minutes;
- `agentIds` is empty;
- `topN > 0`;
- rollup shards exist.

The existing `>= 1h` rollup rule remains unchanged. A 29-minute fleet query
and a 30-minute query with an explicit agent stay on raw storage.

## Local Validation

- Red proof:
  `SegmentedMetricStorageTest#thirtyMinuteFleetTopNQueryUsesMinuteRollup`
  failed before implementation with expected step `60000`, actual `10000`.
- Focused regression tests passed after implementation.
- Full Maven suite: 129 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -q -DskipTests package` passed.
- `git diff --check` passed.
- Code review run:
  `/tmp/compound-engineering-501/ce-code-review/20260813-164753-318af6e9`
- Code review verdict: `Ready to merge`; no actionable findings.

## Access And Scope

Accepted evidence:

- `demand-coordinators.console.log`
- `dryrun-coordinators.console.log`

SSH temporary root permission was refreshed immediately before deployment.
The dry run selected exactly:

```text
fdbd:dc05:11:634::45
fdbd:dc05:13:10c::40
fdbd:dc07:0:810::44
```

## Canary

Canary: `fdbd:dc05:11:634::45`.

Accepted evidence:

- `canary-coordinator.console.log`
- `canary-verify-local-rerun.console.log`

Differential deployment result:

```text
status=changed
remote_sha_before=61a7fbf74ed79419b764a43edc051d204bc6dbbe04f1adcefd6e2d46f9684d96
remote_sha_after=42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df
pulse-coordinator.service=active
```

The canary verification used `x-pulse-metric-routed: 1` so two old peers
could not contaminate the result:

```text
agent.thread_count          0.011s  2505 bytes  18 points  step=60000  rollup=1m
heartbeat.agent_collect_ms  0.003s  2548 bytes  18 points  step=60000  rollup=1m
```

`canary-verify-local.console.log` is rejected evidence. Its first script
version read camelCase API fields, calculated an invalid `[-1800000, 0]`
window, and timed out. The corrected rerun above uses snake_case fields.

## Rollout

Accepted evidence:

- `rollout-coordinators.console.log`
- `rollout-coordinators/results.tsv`
- `rollout-coordinators/summary.json`

Per-host differential result:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | unchanged after canary |
| `fdbd:dc05:13:10c::40` | updated |
| `fdbd:dc07:0:810::44` | updated |

The callee uploaded only the changed JAR and restarted only
`pulse-coordinator.service`. It did not deploy or restart Agent services.

## Final Verification

Accepted evidence:

- `final-verify.console.log`
- `final-verify/results.tsv`
- `final-verify/summary.json`
- `metrics-comparison.json`

All three Coordinators reported:

```text
JAR_SHA=42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df
pulse-coordinator.service=active
pulse-agent.service=active
storage.status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
last_error=""
```

Post-rollout P50 across the three Coordinator entry points:

| Metric | P50 latency | Bytes | Points | Step |
| --- | ---: | ---: | ---: | ---: |
| `agent.thread_count` | 0.110s | 12,281 | 152 | 60s |
| `heartbeat.agent_collect_ms` | 0.106s | 24,842 | 292 | 60s |
| `tide_worker.cpu_pct` | 0.356s | 23,947 | 266 | 60s |
| `disk.io_util_pct` | 0.238s | 26,410 | 320 | 60s |

All final points reported `rollup=1m`.

Paired before/after comparison on `fdbd:dc05:11:634::45`:

| Metric | Latency reduction | Byte reduction | Point reduction |
| --- | ---: | ---: | ---: |
| `agent.thread_count` | 64.53% | 98.74% | 92.98% |
| `heartbeat.agent_collect_ms` | 68.98% | 96.51% | 81.86% |
| `tide_worker.cpu_pct` | 80.85% | 82.82% | 87.72% |
| `disk.io_util_pct` | 76.20% | 79.60% | 85.25% |

PSI is not reported because the pre-deploy evidence contains one observation
per metric, which is insufficient for a statistically defensible
distribution-shift estimate. `metrics-comparison.json` preserves the raw
paired values instead of manufacturing false precision.

## Rollback

Restore the previous JAR only on the three Coordinator hosts:

```text
previous_sha=61a7fbf74ed79419b764a43edc051d204bc6dbbe04f1adcefd6e2d46f9684d96
target=/data24/otf/pulse/bin/pulse.jar
service=pulse-coordinator.service
```

After rollback, verify per host:

```text
sha256sum /data24/otf/pulse/bin/pulse.jar
systemctl is-active pulse-coordinator.service
systemctl is-active pulse-agent.service
curl -g -fsS --max-time 5 http://[::1]:9966/api/metrics/storage
```

## Result

The 30-minute fleet/TopN chart path now serves minute rollups instead of raw
SQLite detail. Production payloads fell by 79.60%-98.74%, returned points fell
by 81.86%-92.98%, and the measured same-entry latency fell by 64.53%-80.85%.
Explicit-agent and sub-30-minute queries retain raw-detail behavior.
