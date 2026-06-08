# Debug group latency spike [READY FOR CONFIRMATION]

## Symptom

- `group.group_latency_ms` sometimes reaches about `15s`.
- Expected: group leader batch heartbeat should normally arrive at coordinator within low milliseconds to a few seconds; 15s is abnormal.

## Session

- Session id: `group-latency-spike`
- Start: 2026-06-08
- Constraint: no business logic changes before runtime evidence is collected.

## Hypotheses

1. Group leader send path stalls after stamping `group_sent_at_ms`, so `group_latency_ms` includes blocked HTTP send/network time.
2. Group leader and coordinator clocks are skewed, so `observed_at_ms - group_sent_at_ms` reports false latency.
3. Coordinator HTTP worker/backlog is saturated, so requests arrive late or are handled late.
4. Specific leaders/clusters have tail latency due to group size, stale member fan-in, or fallback behavior.
5. The metric calculation mixes old/partial samples or JSON fields, causing false spikes.

## Evidence plan

- Query SQLite for top `group_latency_ms` samples and their leader/group/cluster/status/debug JSON.
- Compare spike windows with `heartbeat.agent_send_ms`, `heartbeat.agent_encode_ms`, `group.leader_collect_ms`, `arrival_gap_ms`, `seq_gap`, storage queue health, and coordinator logs.
- Check whether spikes cluster by leader, coordinator, wall-clock window, or cluster.
- If existing metrics are insufficient, add instrumentation-only logs before any fix.

## Evidence

- 6h SQLite probe across 3 coordinators:
  - `group_latency_ms` p99: `2ms`, `2ms`, `3ms`.
  - `group_latency_ms` max: `88ms`, `37ms`, `281ms`.
  - samples above `10000ms`: `0`.
- 24h SQLite probe across 3 coordinators:
  - `group_latency_ms` p99: `2ms`, `2ms`, `3ms`.
  - `group_latency_ms` max: `314ms`, `84ms`, `281ms`.
  - samples above `10000ms`: `0`.
- 7d SQLite probe across 3 coordinators:
  - `group_latency_ms` p99: `2ms`, `2ms`, `3ms`.
  - `group_latency_ms` max: `314ms`, `84ms`, `281ms`.
  - samples above `10000ms`: `0`.
- Metrics API `/api/metrics/query_range?metric=group.group_latency_ms`:
  - 1h/24h/7d max API points stayed below `100ms`.
  - `cluster=cdn2` 24h max stayed below `100ms`.
- Neighbor metrics:
  - `group_leader_sample.arrival_gap_ms` p95/p99 across coordinators is about `15262ms/15320ms`.
  - `heartbeat_sample.arrival_gap_ms` p95/p99 is about `5078ms/5110ms`.
  - Top group arrival gaps can be much higher on transient leader/group changes, while `group_latency_ms=0-3ms`.
- Code evidence:
  - `PULSE_HEARTBEAT_INTERVAL_MS` default is `5000`.
  - `HeartbeatClient.sendForResponse` rotates `nextCoordinatorIndex` after successful sends.
  - With three coordinator URLs, one coordinator locally observes the same leader about every `3 * 5000ms`.
- Post-fix deployment:
  - Commit `5b547d7` changed agent coordinator selection to stable sticky target with failover only on failure.
  - Commit `c1859ca` corrected Metrics UI wording so `group.arrival_gap_ms` no longer normalizes 3-node round-robin as healthy.
  - JAR SHA `6b22032bc2742df0e25a5971522e5b862ab74b74718afa28a2f275c2e762149d` deployed to `cdn_new`.
  - First rollout updated `16/50`; SSH permission failures were recovered with auto-ops demand, then failed-host retry updated `34/34`.
  - Final SHA verification: `50/50` hosts matched the new JAR SHA and `pulse-agent.service` was active.
- Post-fix SQLite short-window probe, `cluster=cdn2`, 3-minute window:
  - `fdbd:dc05:11:634::45`: `group.arrival_gap_ms` p95 `5056ms`, p99 `5065ms`, max `5071ms`, `>=14000ms` count `0`; `group_latency_ms` p99 `3ms`.
  - `fdbd:dc05:13:10c::40`: `group.arrival_gap_ms` p95 `5044ms`, p99 `5051ms`, one restart-transition max `11394ms`, `>=14000ms` count `0`; `group_latency_ms` p99 `5ms`.
  - `fdbd:dc07:0:810::44`: `group.arrival_gap_ms` p95 `5057ms`, p99 `5066ms`, max `5066ms`, `>=14000ms` count `0`; `group_latency_ms` p99 `3ms`.
- Post-fix Metrics API probe, `cluster=cdn2`, 3-minute window:
  - `group.arrival_gap_ms` max: `5064ms`, `5048ms`, `5060.5ms` across the three coordinators.
  - `group.group_latency_ms` max: `3.5ms`, `4.5ms`, `3.0ms` across the three coordinators.

## Findings

- The 15s value is not transport latency. It is per-coordinator local `arrival_gap_ms` for group leader samples.
- Root cause is not a need for group leader coordinator polling. It is the old agent success-path round-robin strategy fighting the intended `/heartbeat_fwd` eventual-consistency design.
- With three coordinator URLs, the old success-path round-robin produced a local gap of `heartbeat_interval_ms * coordinator_count`, about `15s`; this was a design defect, not an acceptable steady state.
- After sticky target deployment, `group.arrival_gap_ms` converged to the heartbeat interval, about `5s`, while `group.group_latency_ms` remained low milliseconds.
- UI/metric naming must distinguish:
  - `group.group_latency_ms`: batch send-to-coordinator latency, currently healthy.
  - `group.arrival_gap_ms`: local coordinator observation gap; under sticky + `/heartbeat_fwd` it should stay close to heartbeat interval, not coordinator-count fanout.

## Fix

- Implemented locally:
  - `HeartbeatClient` uses stable sticky coordinator selection by agent id.
  - Successful sends no longer rotate coordinator.
  - Failure still tries the next coordinator and sticks to the successful fallback.
  - `group.arrival_gap_ms` is exposed as a separate metric from `group.group_latency_ms`.
  - UI threshold text explains sticky behavior: single coordinator view should be close to heartbeat interval.
- Deployed and verified:
  - `cdn_new` final SHA verification passed on `50/50` hosts.
  - Runtime SQLite and HTTP API probes show no post-fix 15s `group.arrival_gap_ms` in the `cdn2` short window.
  - Debug cleanup is intentionally pending user confirmation.
