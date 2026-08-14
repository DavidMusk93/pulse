# Coordinator upgrade verification

## Context

- Operation date: 2026-08-14
- Request: upgrade Coordinator.
- Current commit:
  `98fa50d Record EventBus layout tighten rollout`
- Effective runtime artifact commit:
  `edd591b Tighten EventBus summary layout`
- Artifact:
  `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3`
- Scope: 3 Coordinators from `docs/ops/coordinators.hosts`
- Evidence root:
  `.tmp/auto-ops/coordinator-upgrade-20260814/`
- Task sync: not applicable; no `docs/task/` file changed.
- Agent rollout: not applicable; only Coordinator scope was checked.

## Deployment Gate

Temporary root permissions were refreshed for all three Coordinators:

```text
summary: total=3 ok=3 failed=0
```

The destructive dry-run selected exactly:

```text
fdbd:dc05:11:634::45
fdbd:dc05:13:10c::40
fdbd:dc07:0:810::44
```

Evidence:

- `local-jar-sha.txt`
- `demand-coordinators.console.log`
- `demand-coordinators/summary.json`
- `dryrun.console.log`

## Differential Result

All three hosts already had the target SHA:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | unchanged |
| `fdbd:dc05:13:10c::40` | unchanged |
| `fdbd:dc07:0:810::44` | unchanged |

Raw result:

```text
summary: total=3 ok=3 failed=0
```

Because every remote SHA matched the target SHA, the deploy callee took the
`unchanged` branch. No upload or install event was emitted, and
`pulse-coordinator.service` was not restarted.

Evidence:

- `rollout.console.log`
- `rollout/summary.json`
- `rollout/results.tsv`
- `rollout/failed-hosts.txt`

## Verification

Independent final verification reported:

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
hosts=31.730 ms
eventbus=2.336 ms
metrics catalog=41.972 ms
metrics storage=42.161 ms
```

Evidence:

- `final-verify.console.log`
- `final-verify/summary.json`
- `final-verify/results.tsv`
- `final-verify/failed-hosts.txt`

## Rollback

No rollback action is required because this operation made no runtime change.
If rollback is needed for the active artifact, use the previous UI-card-layout
artifact:

```text
SHA-256=b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6
```

Rollback only the three Coordinator hosts, compare SHA before upload, restart
only changed hosts, and rerun the same final verification.

## Result

Coordinator upgrade completed as a differential no-op: all three production
Coordinators were already on the requested artifact, all service and HTTP
verification gates passed, and no Coordinator restart was performed.
