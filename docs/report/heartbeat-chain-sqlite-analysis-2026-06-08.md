# Heartbeat Chain SQLite Analysis 2026-06-08

## Scope

- Data source: `/data24/otf/pulse/data/pulse-metrics.db` on the three production coordinators.
- Window: last 60 minutes at collection time.
- Coordinators:
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- Deployed build before analysis: `d9c7ddd5911f6860eec839c9d7820029da03d13151c9206b7c8d1519c116f061`.

## Rollout Evidence

- Full `cdn_new` rollout: `total=50 ok=50 failed=0 elapsed=166s`.
- Full `cdn_new` verify: `total=50 ok=50 failed=0 elapsed=2s`.
- The three coordinator hosts were included in the rollout and kept `pulse-coordinator.service` active.

## Coordinator-Level Summary

| Coordinator | Agents | Heartbeat rows | Arrival p95 | Arrival p99 | Arrival max | `seq_gap > 1` | Group rows | Group ok | Group partial | Group stale plan |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 471 | 111489 | 5101 ms | 9026 ms | 19996 ms | 20 | 9974 | 9733 | 126 | 115 |
| `fdbd:dc05:13:10c::40` | 471 | 111451 | 5106 ms | 9192 ms | 33247 ms | 34 | 9990 | 9761 | 92 | 137 |
| `fdbd:dc07:0:810::44` | 471 | 111282 | 5110 ms | 9047 ms | 15192 ms | 22 | 9953 | 9743 | 103 | 107 |

## `cdn_new` / `cdn2` Summary

| Coordinator | Agents | Heartbeat rows | Arrival p95 | Arrival p99 | Arrival max | `seq_gap > 1` | Modes | Group rows | Group ok | Group partial | Group stale plan |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 50 | 11923 | 5036 ms | 8488 ms | 11972 ms | 1 | follower 9766, leader 1577, direct 580 | 1545 | 1520 | 17 | 8 |
| `fdbd:dc05:13:10c::40` | 50 | 11904 | 5025 ms | 8855 ms | 14573 ms | 2 | follower 9708, leader 1572, direct 624 | 1548 | 1522 | 17 | 9 |
| `fdbd:dc07:0:810::44` | 50 | 11929 | 5027 ms | 9007 ms | 14441 ms | 1 | follower 9673, leader 1563, direct 693 | 1544 | 1522 | 11 | 11 |

Latest `cdn2` group samples showed zero missing and zero stale members for sampled groups such as `cdn2/hl/001`, `cdn2/yg/002`, `cdn2/yg/003`, `cdn2/yg/004`, `cdn2/yg/005`, `cdn2/yg/006`, and `cdn2/gl/000`.

## Findings

- No systemic `cdn_new` heartbeat failure is visible in SQLite. All three coordinators observed all 50 `cdn2` agents in the last hour.
- Arrival timing matches the 5s heartbeat design. `cdn2` p95 is about `5.03s`, p99 is `8.49s-9.01s`, and max stays below the 30s TTL.
- `seq_gap > 1` is rare for `cdn2`: only `1-2` samples per coordinator in roughly 11.9k rows. This looks compatible with restart/rollout or transient network delay, not a continuous sequence-loss defect.
- Group leader fan-in is mostly healthy. `cdn2` group status is about `98.3%-98.6% ok`, with `partial` and `stale_plan` present but low-volume.
- Group coverage is not perfect during the hour window. `missing_member_count` p95 is `0`, but p99 reaches `1-6` and max reaches `9-10`, so the design should continue treating partial group samples as normal degraded telemetry rather than as hard failure.
- Direct heartbeat still appears in `cdn2` rows: `580-693` direct samples per coordinator in the hour. This is acceptable as fallback, but if it persists after rollout cooldown it means some agents are bypassing group fan-in or group leadership is changing too often.

## Defects Or Gaps

- `agent_encode_ms` and `agent_send_ms` are always `0` in the sampled data. The schema reserves these fields, but the agent instrumentation is not yet measuring encode/send work, so the system cannot prove client-side send cost.
- `group_latency_ms` and `leader_collect_ms` are always `0` in group samples. This is an implementation observability gap: the schema promises leader collection and group latency diagnostics, but production data cannot currently distinguish “fast” from “not instrumented”.
- `agent_thread_count` and `agent_rss_kb` have many zero samples while p95 is non-zero. This suggests some heartbeat paths do not populate agent runtime metrics consistently, especially when data flows through group mode.
- `partial` and `stale_plan` are low-volume but still present. The current schema records status and missing/stale counts, but does not expose enough first-class columns for root cause, such as expected generation, received plan generation, plan lag, reject reason, and member-level lag.
- Cross-cluster coordinator data contains `unknown/unknown` groups. This is not a `cdn_new` blocker, but it shows the cluster/area detection path should stay visible in reports and UI filters.

## Design Assessment

- The heartbeat chain design is fundamentally sound for `cdn_new`: bounded local storage, group fan-in, direct fallback, and query budgets are all producing useful evidence.
- The remaining risk is observability completeness, not obvious heartbeat delivery collapse. The next implementation work should focus on filling missing timing fields and plan-generation diagnostics.
- The UI should surface `partial`, `stale_plan`, direct fallback rate, and group missing p99 as first-class health indicators, because averages hide these tail cases.

## Recommended Follow-Ups

1. Add first-class columns or normalized event details for `agent_plan_generation`, `expected_generation`, `plan_lag`, and `reject_reason`.
2. Re-run the `cdn2` subset analysis after rollout cooldown and alert if direct samples or `stale_plan` do not decay.
3. Add a Metrics Panel preset for `cdn2` group health: arrival p99, seq gaps, group missing p99, direct fallback, and stale plan count.

## Post-Fix Verification

Instrumentation commits:

- `d62a649 Instrument heartbeat timing metrics`
- `88fc018 Make heartbeat timing metrics visible`

Deployment:

- Full `cdn_new` rollout: `total=50 ok=50 failed=0 elapsed=187s`.
- Full `cdn_new` verify: `total=50 ok=50 failed=0 elapsed=2s`.
- JAR SHA: `5e82cd6908f7c470af59fa8feedc9da808d419e52eb2fe3374f21ea27e63f194`.

Short-window SQLite verification after rollout:

| Coordinator | Agent encode p99 | Agent send p95 | Agent send p99 | Leader collect p95 | Group latency p95 | `cdn2` agents | `cdn2` seq gaps |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 1 ms | 3 ms | 7 ms | 1 ms | 2 ms | 50 | 0 |
| `fdbd:dc05:13:10c::40` | 1 ms | 3 ms | 6 ms | 1 ms | 2 ms | 50 | 0 |
| `fdbd:dc07:0:810::44` | 1 ms | 3 ms | 5 ms | 1 ms | 2 ms | 50 | 0 |

Result:

- The previous “always zero” timing gaps are fixed for `agent_encode_ms`, `agent_send_ms`, `leader_collect_ms`, and `group_latency_ms`.
- `cdn2` remains fully visible on all coordinators.
- The short-window `cdn2` sequence gap count is `0` on all coordinators after the latest rollout.

## Latest Health Metrics Review

Verification scope:

- Online API/UI verification passed on all three coordinators: `/api/metrics/catalog` contains the heartbeat health metrics, the static frontend assets contain the health presets, and `/api/metrics/query_range` returns points for each preset metric.
- Verified metrics: `group.status_unhealthy`, `group.stale_member_count`, `group.direct_fallback_count`, `group.plan_lag`, `heartbeat.agent_collect_ms`, and `heartbeat.agent_send_ms`.
- Window: latest 60 minutes collected from coordinator-local SQLite.

Coordinator-level latest results:

| Coordinator | Agents | Heartbeat rows | Arrival p95 | Arrival max | `seq_gap > 1` | Group rows | Group ok | Group partial | Group stale plan | Group plan lag p95 | Agent send p95 | Group latency p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 471 | 111868 | 5106 ms | 19996 ms | 28 | 10161 | 9935 | 88 | 138 | 3010807002 | 2 ms | 1 ms |
| `fdbd:dc05:13:10c::40` | 471 | 111868 | 5109 ms | 31074 ms | 23 | 10122 | 9905 | 75 | 142 | 2894767122 | 2 ms | 1 ms |
| `fdbd:dc07:0:810::44` | 471 | 111868 | 5113 ms | 15184 ms | 21 | 10124 | 9917 | 99 | 108 | 2809445064 | 2 ms | 2 ms |

`cdn2` latest results:

| Coordinator | Agents | Arrival p95 | Arrival p99 | Arrival max | `seq_gap > 1` | Group ok | Group partial | Group stale plan | Direct fallback p95 | Direct fallback max | Plan lag p95 | Plan lag max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 50 | 5036 ms | 8887 ms | 15041 ms | 4 | 1522 | 11 | 18 | 0 | 7 | 0 | 3977717525 |
| `fdbd:dc05:13:10c::40` | 50 | 5064 ms | 9315 ms | 14505 ms | 1 | 1534 | 26 | 9 | 0 | 10 | 0 | 2717142775 |
| `fdbd:dc07:0:810::44` | 50 | 5042 ms | 9576 ms | 14244 ms | 2 | 1534 | 25 | 14 | 0 | 5 | 0 | 4010505202 |

Current assessment:

- Healthy signal: all coordinators still observe `471` agents overall and `50/50` `cdn2` agents, so there is no fleet-wide heartbeat visibility collapse.
- Healthy signal: heartbeat arrival p95 remains close to the 5s target; `cdn2` p99 stays below 10s and max stays below the 30s TTL in this window.
- Healthy signal: agent send and group latency instrumentation is now visible and low; p95 is `1-2 ms`, which supports the low-resource hot-path design.
- Tail risk: low-volume `partial` and `stale_plan` remain visible in group samples. The architecture should continue treating these as degraded telemetry instead of hiding them behind averages.
- Tail risk: `direct_fallback_count` p95 is `0`, but max reaches `5-10` in `cdn2`; UI presets and alerting should use max/topN or tail buckets, not only average.
- Tail risk: `seq_gap > 1` is rare but non-zero. It does not indicate continuous loss, but it should stay in the health view because rollout, restart, network retry, or coordinator delay can all surface here.

Design or implementation defects exposed by metrics:

- `group.plan_lag` is currently not a trustworthy arithmetic lag metric. The implementation uses an unsigned hash as `plan_generation`, while `plan_lag = max(0, expected_generation - agent_plan_generation)` assumes monotonic generations. This creates huge values such as `2.8B-3.0B` p95 overall and multi-billion max values in `cdn2`, which can falsely imply severe convergence lag.
- Zero or old `agent_plan_generation` samples after rollout are mixed with normal samples. Metrics need a generation epoch or rollout-aware cold-start state so initial unknown generation does not look like multi-billion lag.
- `group.plan_lag` should either become a boolean/categorical mismatch metric for hash generations, or `plan_generation` must change to a monotonic coordinator-side version before arithmetic lag is used.
- `plan_lag` is still stored through `debug_json` extraction instead of a first-class column. This is acceptable for verification but too fragile for a health preset and alerting path.
- `partial` and `stale_plan` lack enough structured root-cause detail. The next schema/API iteration should expose `expected_generation`, `agent_plan_generation`, `reject_reason_json`, and member-level lag/reject counters as first-class queryable data.

Required design correction:

- Treat `group.plan_lag` as experimental until generation semantics are fixed.
- Prefer `group.plan_mismatch` or `group.generation_mismatch` when generation is hash-like and only equality is meaningful.
- If operators need “lag” severity, replace hash generation with a monotonic plan version scoped by coordinator, cluster, area, and group, and include an epoch to distinguish rollout/cold-start from true delayed convergence.

## Plan Metric Correction

Implementation correction after the metrics review:

- Added `group.plan_mismatch` as the primary health metric for plan convergence while generation is hash-based.
- Changed coordinator group debug output so `agent_plan_generation <= 0` is treated as `unknown/cold_start`, not as a huge lag.
- Kept `group.plan_lag` as a compatibility metric, but it now follows bounded mismatch semantics (`0/1`) instead of subtracting unsigned hash values.
- Updated the Metrics Panel “计划收敛” preset to query `group.plan_mismatch`.
- Verified the correction with `mvn test -Dtest=LocalMetricStorageTest,CoordinatorServiceTest,CoordinatorHttpServerTest`.

## Additional Defects From Current Metrics

Latest 60-minute metrics feedback shows the main heartbeat chain is healthy, but exposes two more design defects:

- `heartbeat_path` is overloaded with concrete group ids such as `tlblog_stream_olap_separate/lq/004` and `cdn2/hl/001`. This prevents direct aggregation of `direct` / `fallback_direct` / `group_leader_batch` and makes architecture health depend on parsing labels.
- `unknown/unknown/*` group paths are visible, which means agents without reliable cluster metadata can be grouped. Grouping unknown-cluster hosts is unsafe because the planner cannot prove those hosts belong to the same scheduling domain.

Current evidence:

- All three coordinators show `471` heartbeat agents, zero `seq_gap > 1`, and all group samples are `ok` in the latest window.
- `cdn2` is clean: `50/50` agents, `group_status=ok`, `direct_fallback_count=0`, and `plan_lag=0`.
- The remaining huge global `plan_lag` comes from non-`cdn2` groups with `agent_plan_generation=0` and `status=ok`, confirming an observability/version-mixing problem rather than fan-in failure.

Implementation correction:

- Normalize heartbeat sample `heartbeat_path` to categorical values: `direct`, `fallback_direct`, `group_leader_batch`, or `unknown`.
- Preserve the original group id in metadata as `source_group_id`, and preserve expected group details as `expected_group_id` / `expected_group_mode`.
- Exclude `cluster=unknown` from group planning; unknown-cluster agents stay direct until metadata is available.
