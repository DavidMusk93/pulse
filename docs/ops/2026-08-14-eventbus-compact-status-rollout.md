# EventBus compact status rollout

## Context

- Operation date: 2026-08-14
- Request: debug the production UI with the built-in browser instead of relying
  only on curl/static markers.
- Delivery commit:
  `296e691 Compact EventBus summary status`
- Artifact:
  `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `43647f7af35ba6aa0f2cc5f6d03e5725b6058f210c083a184076707d7a26f722`
- Previous production SHA-256:
  `5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3`
- Scope: 3 Coordinators from `docs/ops/coordinators.hosts`
- Evidence root:
  `.tmp/auto-ops/eventbus-compact-status-20260814/`
- Task sync: not applicable; no `docs/task/` file changed.
- Agent rollout: not applicable; only `pulse-coordinator.service` was targeted.

## Change

- Moved EventBus pipeline status into the center flow column instead of
  spanning the whole card.
- Replaced the full-width status strip with a compact status pill.
- Fixed flow label placement by explicitly placing `b` and `em` in the second
  grid column, preventing count text from being squeezed under the icon column.
- Updated the source-level layout regression test to guard compact status and
  non-wrapping flow labels.

## Local Gates

- `node --test tools/host-cluster-scope.test.mjs`: passed, 8/8.
- `node --test tools/host-cluster-scope.test.mjs tools/host-sse-v3-decoder.test.mjs`:
  passed.
- `npm run build`: passed and regenerated `pulse-hosts.{css,js}`.
- `mvn -q -DskipTests package`: passed.
- `git diff --check`: passed.

## Browser Debug Evidence

Direct built-in browser navigation to
`http://[fdbd:dc05:11:634::45]:9966/` failed with
`chrome-error://chromewebdata/`, matching the known Coordinator access
constraint. A local SSH tunnel was created:

```text
127.0.0.1:19966 -> fdbd:dc05:11:634::45:[::1]:9966
```

The built-in browser successfully loaded `http://127.0.0.1:19966/` through the
tunnel. Before this patch, browser DOM measurement showed:

```text
card=1603x159
status=1569x58
flow meta widths=18px
```

The 18px flow meta widths proved the count labels were in the icon column even
though they reported `white-space: nowrap`.

After canary deployment, built-in browser DOM measurement showed:

```text
viewport=1757x1282
card=1603x115
flow=507x46
status=375x32
flow meta widths=59px / 105px / 44px
flow meta wraps=false
status wraps=false
```

The screenshot tool itself timed out, so the accepted browser evidence for this
operation is DOM geometry and computed CSS from the built-in browser, not an
image artifact.

## Deployment

Canary:

```text
host=fdbd:dc05:11:634::45
previous_sha=5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3
new_sha=43647f7af35ba6aa0f2cc5f6d03e5725b6058f210c083a184076707d7a26f722
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
JAR_SHA=43647f7af35ba6aa0f2cc5f6d03e5725b6058f210c083a184076707d7a26f722
pulse-coordinator.service=active
pulse-agent.service=active
root_http=200
asset_http=200
legacy_control_streams=410
```

Highest observed 40-request P99:

```text
hosts=53.814 ms
eventbus=43.889 ms
metrics catalog=43.980 ms
metrics storage=44.793 ms
```

Evidence:

- `final-verify.console.log`
- `final-verify/summary.json`
- `final-verify/results.tsv`
- `final-verify/failed-hosts.txt`

## Rollback

Rollback target:

```text
SHA-256=5f82748cde8a30e8f3b414fae355ee734aa40db7fee0506637b413bdef284ab3
```

Rollback only the three Coordinator hosts. Compare local rollback artifact to
remote SHA, upload only changed hosts, restart only
`pulse-coordinator.service`, then verify per-host SHA, service state, root and
asset HTTP, control stream events, and retired endpoint `410`.

## Result

The EventBus summary is deployed as a compact center-column flow with status
metadata embedded as a small pill. Built-in browser DOM measurement confirmed
the previous icon-column text squeeze and full-width status strip were removed.
