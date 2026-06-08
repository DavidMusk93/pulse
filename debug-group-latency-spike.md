# Debug group latency spike [OPEN]

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

## Findings

- The 15s value is not transport latency. It is per-coordinator local `arrival_gap_ms` for group leader samples.
- Root cause is a metrics semantics/design gap: local SQLite is per coordinator, but `arrival_gap_ms` is currently interpreted as if a single coordinator should see every 5s heartbeat.
- For round-robin multi-coordinator sending, the expected local gap is `heartbeat_interval_ms * coordinator_count`, about `15s`.
- UI/metric naming must distinguish:
  - `group.group_latency_ms`: batch send-to-coordinator latency, currently healthy.
  - `group.arrival_gap_ms`: local coordinator observation gap, expected to scale with coordinator fanout.

## Fix

- Implemented locally:
  - `HeartbeatClient` uses stable sticky coordinator selection by agent id.
  - Successful sends no longer rotate coordinator.
  - Failure still tries the next coordinator and sticks to the successful fallback.
  - `group.arrival_gap_ms` is exposed as a separate metric from `group.group_latency_ms`.
  - UI threshold text explains `group.arrival_gap_ms` as local coordinator observation, not send latency.
- Pending deployment and post-fix runtime verification.
