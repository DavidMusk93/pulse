# UI card layout rollout

## Context

- Operation date: 2026-08-13
- Request: improve card content layout so text is not hidden and cards do not
  carry excessive blank space.
- Delivery commit:
  `f85d91abb5eeed4b814fb4407853e6f87605ee8e`
- Artifact:
  `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6`
- Previous production SHA-256:
  `9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275`
- Scope: 3 Coordinators from `docs/ops/coordinators.hosts`
- Evidence root:
  `.tmp/auto-ops/ui-card-layout-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.
- Agent rollout: not applicable; this operation targeted only
  `pulse-coordinator.service`.

## Change

- SSE traffic card meta changed from a single ellipsized row to compact
  label/value cells for channel, events, and total traffic.
- EventBus pipeline status changed from a full-width sparse row to
  content-sized adaptive cards.
- Summary text in the affected cards now wraps instead of relying on
  `text-overflow: ellipsis`.
- Added a source-level regression test that guards these layout markers.

## Local Gates

- `node --test tools/host-cluster-scope.test.mjs`: passed, 8/8.
- `node --test tools/host-cluster-scope.test.mjs tools/host-sse-v3-decoder.test.mjs`:
  passed.
- `npm run build`: passed and regenerated `pulse-hosts.{css,js}`.
- `mvn -q -DskipTests package`: passed.
- `git diff --check`: passed.
- Focused review pass: no actionable findings. Residual risk: no browser
  visual regression capture in this run.

## Deployment

Temporary permissions were refreshed for all three Coordinator hosts and the
destructive dry-run selected exactly:

```text
fdbd:dc05:11:634::45
fdbd:dc05:13:10c::40
fdbd:dc07:0:810::44
```

Canary:

```text
host=fdbd:dc05:11:634::45
previous_sha=9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275
new_sha=b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6
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
- `dryrun.console.log`
- `canary.console.log`
- `canary/summary.json`
- `rollout.console.log`
- `rollout/summary.json`
- `rollout/results.tsv`
- `rollout/failed-hosts.txt`

## Verification

Final independent verification reported:

```text
total=3 ok=3 failed=0
JAR_SHA=b6a442c56c33f4098c27f2eee2cc4cd9a2883d593b23b472a3f5a15443f796a6
pulse-coordinator.service=active
pulse-agent.service=active
root_http=200
asset_http=200
legacy_control_streams=410
```

Highest observed 40-request P99:

```text
hosts=85.641 ms
eventbus=43.649 ms
metrics catalog=43.555 ms
metrics storage=44.413 ms
```

Production static resources on all three Coordinators contained the new layout
markers:

```text
css_autofit=PASS
css_sse_meta=PASS
css_wrap=PASS
js_pipeline_meta=PASS
```

Evidence:

- `canary-verify.console.log`
- `canary-verify/summary.json`
- `final-verify.console.log`
- `final-verify/summary.json`
- `final-verify/results.tsv`
- `final-verify/failed-hosts.txt`
- `static-marker-verify-compact-v3.console.log`

## Rollback

Rollback target:

```text
SHA-256=9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275
```

Rollback only the three Coordinator hosts. Compare local rollback artifact to
remote SHA, upload only changed hosts, restart only
`pulse-coordinator.service`, then verify per-host SHA, service state, root and
asset HTTP, control stream events, and retired endpoint `410`.

## Result

The improved card layout is deployed to all production Coordinators. The
affected production static resources contain the new layout markers and all
Coordinator verification gates passed.
