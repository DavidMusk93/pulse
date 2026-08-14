# EventBus layout tighten rollout

## Context

- Operation date: 2026-08-14
- Request: the previous EventBus card result was visually poor. The flow cards
  wrapped words vertically and the pipeline status card became a left-bottom
  island with excessive blank space.
- Delivery commit:
  `edd591b Tighten EventBus summary layout`
- Artifact:
  `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3`
- Previous production SHA-256:
  `b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6`
- Scope: 3 Coordinators from `docs/ops/coordinators.hosts`
- Evidence root:
  `.tmp/auto-ops/eventbus-layout-tighten-20260814/`
- Task sync: not applicable; no `docs/task/` file changed.
- Agent rollout: not applicable; only `pulse-coordinator.service` was targeted.

## Change

- EventBus flow changed from grid-sized mini cards to an inline flex stepper.
- Source/Pipeline/Sink count labels now use `white-space: nowrap`, preventing
  the bad vertical wrapping seen in production.
- Pipeline delivery status changed from a compact isolated card to a
  full-width status strip with grouped route text, counters, and timestamp.
- The source-level layout test now guards the corrected contract.

## Local Gates

- `node --test tools/host-cluster-scope.test.mjs`: passed, 8/8.
- `node --test tools/host-cluster-scope.test.mjs tools/host-sse-v3-decoder.test.mjs`:
  passed.
- `npm run build`: passed and regenerated `pulse-hosts.css`.
- `mvn -q -DskipTests package`: passed.
- `git diff --check`: passed.

## Deployment

Temporary permissions were refreshed for all three Coordinators.

Canary:

```text
host=fdbd:dc05:11:634::45
previous_sha=b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6
new_sha=5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3
result=updated
pulse-coordinator.service=active
```

Full rollout:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | unchanged after canary |
| `fdbd:dc05:13:10c::40` | updated |
| `fdbd:dc07:0:810::44` | updated |

Rollout result: `total=3 ok=3 failed=0`.

Evidence:

- `demand-coordinators.console.log`
- `canary.console.log`
- `canary/summary.json`
- `canary-verify.console.log`
- `rollout.console.log`
- `rollout/summary.json`
- `rollout/results.tsv`
- `rollout/failed-hosts.txt`

## Verification

Final independent verification:

```text
total=3 ok=3 failed=0
JAR_SHA=5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3
pulse-coordinator.service=active
pulse-agent.service=active
root_http=200
asset_http=200
legacy_control_streams=410
```

Highest observed 40-request P99:

```text
hosts=52.083 ms
eventbus=46.539 ms
metrics catalog=3.689 ms
metrics storage=7.753 ms
```

Production static resources on all three Coordinators contained the corrected
layout markers:

```text
flow_flex=PASS
node_max_content=PASS
flow_nowrap=PASS
status_full=PASS
status_strip=PASS
```

Evidence:

- `final-verify.console.log`
- `final-verify/summary.json`
- `final-verify/results.tsv`
- `final-verify/failed-hosts.txt`
- `static-marker-verify.console.log`

## Rollback

Rollback target:

```text
SHA-256=b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6
```

Rollback only the three Coordinator hosts. Compare local rollback artifact to
remote SHA, upload only changed hosts, restart only
`pulse-coordinator.service`, then verify per-host SHA, service state, root and
asset HTTP, control stream events, and retired endpoint `410`.

## Result

The corrected EventBus summary layout is active on all three production
Coordinators. The production bundle now uses an inline stepper and full-width
status strip instead of the previous vertically wrapped cards and left-bottom
status island.
