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
