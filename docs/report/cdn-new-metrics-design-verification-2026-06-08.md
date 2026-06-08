# cdn_new metrics design verification

## Scope

- Cluster: `cdn_new` / metrics label `cdn2`.
- Coordinators:
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- Runtime window: 5 minutes after full agent rollout and cooldown.
- Evidence sources:
  - coordinator-local SQLite via `group_leader_sample` and `heartbeat_sample`
  - coordinator API `/api/hosts`
  - coordinator API `/api/metrics/catalog`
  - coordinator API `/api/metrics/query_range`
  - fleet rollout SHA verification

## Result

The `cdn_new` runtime metrics match the intended design.

- Stable target selection works: local SQLite samples are distributed by sticky coordinator target, not by success-path round-robin.
- `/heartbeat_fwd` works: every coordinator API sees all `50/50` `cdn_new` hosts even though local SQLite writes are sticky-distributed.
- Group leader batching works: group samples are `ok`, accepted counts equal submitted counts, no missing/stale members, no direct fallback after rollout cooldown.
- Transport and collection latency are low: group send latency is single-digit milliseconds, leader collection is about `1ms`, agent send p99 remains under `26ms`.
- Local metric storage is healthy: storage status is `ok`, queue depth is `0`, dropped commands are `0`, failed commands are `0`.
- No implementation fix is required from this analysis. The agents were already fully rolled out and SHA-verified.

## Deployment Evidence

- Rollout summary: `50/50` hosts succeeded.
- Final SHA verification: `50/50` hosts matched.
- Expected JAR SHA:

```text
b6a8fc789541313bfef7de4a98a278346e8f5a9ffa14ff87cce5a1b93fb3c09a
```

- Agent service status: all verified hosts reported `agent=active`.

## Host Consistency

Each coordinator returns the full `cdn_new` host set through `/api/hosts`.

| Coordinator | API hosts | cdn_new hosts | Storage |
| --- | ---: | ---: | --- |
| `fdbd:dc05:11:634::45` | `471/471` | `50/50` | `ok`, queue `0`, dropped `0`, failed `0` |
| `fdbd:dc05:13:10c::40` | `471/471` | `50/50` | `ok`, queue `0`, dropped `0`, failed `0` |
| `fdbd:dc07:0:810::44` | `471/471` | `50/50` | `ok`, queue `0`, dropped `0`, failed `0` |

This proves the coordinator API view is eventually consistent across the three coordinators. It also proves group leaders do not need to round-robin coordinator writes just to make every coordinator UI see all hosts.

## Sticky Write Distribution

The local SQLite sample distribution is not expected to be `50/50/50` after sticky routing. It should match the chosen coordinator target distribution.

| Coordinator | Sticky local agents | Heartbeat rows | Group rows | Groups |
| --- | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | `17` | `1020` | `180` | `3` |
| `fdbd:dc05:13:10c::40` | `26` | `1552` | `180` | `3` |
| `fdbd:dc07:0:810::44` | `7` | `420` | `60` | `1` |

This is the expected post-fix shape: local storage records direct writes for sticky targets, while `/heartbeat_fwd` keeps coordinator state consistent.

## Heartbeat Metrics

| Coordinator | `arrival_gap_ms` p95 | `arrival_gap_ms` p99 | max | `seq_gap_gt_1` |
| --- | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | `5043ms` | `5062ms` | `5066ms` | `0` |
| `fdbd:dc05:13:10c::40` | `5045ms` | `5056ms` | `5064ms` | `0` |
| `fdbd:dc07:0:810::44` | `5050ms` | `5052ms` | `5052ms` | `0` |

Design match:

- The target heartbeat interval is `5s`; observed p95/p99 stays close to `5s`.
- `seq_gap_gt_1 = 0` means there is no evidence of dropped heartbeat intervals in the cooldown window.
- This validates the low-overhead steady heartbeat path after sticky routing.

## Agent Cost Metrics

| Coordinator | `agent_collect_ms` p95 | `agent_encode_ms` p95 | `agent_send_ms` p95 | `agent_send_ms` p99 | thread p95 | RSS p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | `12.5ms` | `1ms` | `6ms` | `24ms` | `12` | `175756KB` |
| `fdbd:dc05:13:10c::40` | `12.5ms` | `1ms` | `7ms` | `22.5ms` | `14` | `174214KB` |
| `fdbd:dc07:0:810::44` | `12ms` | `1ms` | `6.5ms` | `26ms` | `14` | `203814KB` |

Design match:

- Encode cost is effectively constant at about `1ms`.
- Send p99 is below `30ms`, so coordinator sticky routing does not create a send bottleneck.
- Thread count stays bounded.
- RSS is stable for the Java agent process in this runtime window.

## Group Leader Metrics

| Coordinator | Group status | `arrival_gap_ms` p95 | `group_latency_ms` p99 | `leader_collect_ms` p95 | missing max | fallback max | plan mismatch max |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | `ok=180` | `5043ms` | `3ms` | `1ms` | `0` | `0` | `0` |
| `fdbd:dc05:13:10c::40` | `ok=180` | `5045ms` | `4ms` | `1ms` | `0` | `0` | `0` |
| `fdbd:dc07:0:810::44` | `ok=60` | `5050ms` | `3ms` | `1ms` | `0` | `0` | `0` |

Design match:

- `group.arrival_gap_ms` is near `5s`, not `15s`. This proves the previous success-path coordinator round-robin behavior is gone for `cdn_new`.
- `group.group_latency_ms` stays in the low millisecond range. This proves group send-to-coordinator latency is healthy and should not be confused with local arrival gap.
- `missing_member_count = 0`, `stale_member_count = 0`, and `direct_fallback_count = 0` after cooldown prove group fan-in is complete.
- `plan_mismatch = 0` proves leaders and members agree on the active group plan.
- `accepted_agent_count = submitted_agent_count` across groups, so the coordinator accepts the group leader batches as designed.

## Full Catalog Metrics

The full `/api/metrics/catalog` was queried with `series_limit=200` and `top_n=200` for `cdn2`; no metric was truncated.

Important steady-state results:

| Metric family | Evidence | Design interpretation |
| --- | --- | --- |
| `heartbeat.*` | p95/p99 arrival near `5s`, `seq_gap` no dropped intervals | heartbeat cadence is correct |
| `agent.*` | bounded threads and stable RSS | agent overhead is bounded |
| `group.member_count` | stable group sizes `8`, `11`, `7` | group sharding is deterministic |
| `group.submitted_agent_count` | equals `member_count` | leaders submit complete batches |
| `group.accepted_agent_count` | equals `submitted_agent_count` | coordinators accept complete batches |
| `group.missing_member_count` | max `0` | no missing group members after cooldown |
| `group.stale_member_count` | max `0` | no stale group members after cooldown |
| `group.direct_fallback_count` | max `0` | members no longer bypass group leader |
| `group.status_unhealthy` | max `0` | group health is normal |
| `group.plan_mismatch` | max `0` | leader/member plans match |
| `group.plan_lag` | max `0` | compatibility indicator is healthy |
| `group.leader_collect_ms` | p95 about `1ms` | leader fan-in is cheap |
| `group.group_latency_ms` | p99 `2.5-4ms` | coordinator receive path is healthy |
| `group.arrival_gap_ms` | p95 near `5s` | sticky target behavior is correct |
| `tide_worker.*` | high workload process metrics, no truncation at limit `200` | these describe the workload process, not Pulse agent overhead |

## Rollout Transient

Immediately after the full agent rollout, the 5-minute window still contained restart-transition samples:

- some `group.status_unhealthy = 1`
- nonzero `missing_member_count`
- nonzero `direct_fallback_count`
- temporary `group.arrival_gap_ms` tail values above `10s`

After cooldown, all of these returned to zero or the expected `5s` cadence. The transient is consistent with agent restarts and group plan reformation, not an implementation defect.

## Final Assessment

`cdn_new` is consistent with the intended heartbeat and group leader design:

- Agents use sticky coordinator writes.
- Coordinator API state remains globally visible via `/heartbeat_fwd`.
- Group leader fan-in is complete and low-latency.
- Local SQLite time-series now expose the correct semantics for both local arrival gap and send latency.
- No further code fix or additional agent rollout is required from this verification pass.
