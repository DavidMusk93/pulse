# Metrics wide-query rollup fix

## Context

- Operation date: 2026-08-13
- Symptom: Coordinator UI "刷新时序" could stay loading for a long time when
  querying wide time windows.
- Root cause: recent wide-window `query_range` requests scanned raw SQLite
  shards and built temporary `GROUP BY`/`ORDER BY` B-trees before returning any
  HTTP bytes. A 6h fleet query on `fdbd:dc05:11:634::45` produced about 8 MiB
  and more than 20k points; a first cold sample timed out at 20s with zero
  bytes.
- Delivery commit: `3538eea Fix wide metrics queries using rollups`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `4949ae551b26daf140630f58adab2d8320ab49cce4a7cdde8341d77d9aafe105`
- Previous Coordinator SHA-256: `14be8b9840a348fae20ba056ed2d654a4e48549e4f4839cddf536d80002c4d0c`
- Evidence root: `.tmp/auto-ops/metrics-wide-query-fix-20260813/`
- Scope: three Coordinators from `docs/ops/coordinators.hosts`.
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Code Change

- `SegmentedMetricStorage` routes non-legacy wide queries (`>= 1h`) through
  the existing 1m rollup store instead of scanning current raw shards.
- The rollup query uses at least 60s step.
- If the target rollup window has no series, the query falls back to the raw
  shard path to avoid false empty results on cold or newly-created windows.
- The frontend sends bounded query budgets:
  - `>= 60m`: `step_ms=60000`, `point_limit=8000`
  - `>= 360m`: `step_ms=60000`, `point_limit=6000`
  - shorter windows keep `step_ms=10000`, `point_limit=20000`
- Regression coverage: `SegmentedMetricStorageTest#wideRecentQueryUsesMinuteRollupInsteadOfRawShardScan`.

## Local Validation

- `mvn -q -Dtest=SegmentedMetricStorageTest#wideRecentQueryUsesMinuteRollupInsteadOfRawShardScan test`
- `mvn -q -Dtest=SegmentedMetricStorageTest,CoordinatorHttpServerTest test`
- `npm run build`
- `mvn -q test`
- `mvn -q -DskipTests package`
- `git diff --check`

## Pre-fix Evidence

Accepted raw evidence:

- `metrics-query-sample-634-45.log`
- `metrics-query-range-cross-coordinator.log`
- `metrics-6h-payload-634-45.log`
- `metrics-6h-payload-10c-40.log`
- `metrics-sqlite-plan-634-45.log`
- `metrics-step-sensitivity-634-45.log`
- `metrics-preset-latency-634-45.log`
- `metrics-local-vs-fanout-634-45.log`

Key observations:

```text
::45 default single 15m: 0.018s / 34 KiB
::45 fleet topN 30m: 0.837s / 463 KiB
::45 fleet topN 6h: first sample timed out at 20s with 0 bytes
::45 fleet topN 6h hot sample: 5.86-6.06s / ~8 MiB / ~20k points
::45 disk.io_util_pct 6h: 17.72s / 1.22 MiB
```

SQLite query plan for the raw 6h path:

```text
SEARCH TABLE heartbeat_sample USING INDEX sqlite_autoindex_heartbeat_sample_1 (observed_at_ms>? AND observed_at_ms<?)
USE TEMP B-TREE FOR GROUP BY
USE TEMP B-TREE FOR ORDER BY
```

## Canary

Canary host: `fdbd:dc05:11:634::45`.

Accepted deployment evidence:

- `canary-coordinator.console.log`
- `canary-coordinator/summary.json`
- `canary-service-storage.console.log`
- `canary-query-6h.console.log`

Result:

```text
status=updated
remote_sha=4949ae551b26daf140630f58adab2d8320ab49cce4a7cdde8341d77d9aafe105
pulse-coordinator.service=active
metrics storage status=ok
```

Canary 6h query after fix:

```text
heartbeat.agent_collect_ms: 1.53s / 963 KiB
disk.io_util_pct: 2.66s / 260 KiB
heartbeat.agent_send_ms: 1.39s / 727 KiB
agent.thread_count: 1.41s / 623 KiB
```

## Rollout

Accepted rollout evidence:

- `dryrun-coordinators.console.log`
- `rollout-coordinators.console.log`
- `rollout-coordinators/summary.json`

Differential result:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | unchanged after canary |
| `fdbd:dc05:13:10c::40` | updated |
| `fdbd:dc07:0:810::44` | updated |

Only `pulse-coordinator.service` was restarted. Agents were not redeployed.

## Final Verification

Accepted final evidence:

- `final-query-6h-coordinators.console.log`
- `final-storage-634-45.json`

All three Coordinators reported:

```text
SHA=4949ae551b26daf140630f58adab2d8320ab49cce4a7cdde8341d77d9aafe105
ACTIVE=active
```

The same 6h fleet query after full rollout:

```text
fdbd:dc05:11:634::45  0.672s  270107 bytes  3334 points  suggested_step=60000
fdbd:dc05:13:10c::40  0.587s  270107 bytes  3334 points  suggested_step=60000
fdbd:dc07:0:810::44   0.574s  270107 bytes  3334 points  suggested_step=60000
```

Storage health after rollout:

```text
status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
last_error=""
```

## Rollback

Restore the previous Coordinator artifact only on the three Coordinator hosts:

```text
previous_sha=14be8b9840a348fae20ba056ed2d654a4e48549e4f4839cddf536d80002c4d0c
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

The metrics module no longer serves wide UI time windows from the current raw
SQLite shard path. Coordinator UI refreshes for 6h fleet time series now use
1m rollup data and return in under 1s on all three Coordinators for the
validated `heartbeat.agent_collect_ms` case, with response size reduced from
multi-MiB to about 270 KiB.
