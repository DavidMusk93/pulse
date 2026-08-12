# Lark padding and Pipeline error UI fix

## Context

- Operation date: `2026-08-12`
- Fix commit: `e83d225`
- Artifact SHA-256: `dde33a3f82707a27793f86fa5c2edcc5d966b058b1e0a1d8eb7caae3bd85fb3a`
- Previous production SHA-256: `550f074d36645f3beec18157946c83096f84f3b614339b16ff22aa59511ca3c9`
- Scope: exact `coordinators` tag, 3 hosts, `--max-hosts 3`
- Evidence root: `.tmp/auto-ops/lark-padding-ui-fix-20260812/`
- Task sync: not applicable.

## Incident

The production Lark route failed with:

```text
code=11246
ErrCode: 10002
ErrMsg: invalid padding
```

The UI displayed the same route error twice: inside the Pipeline status row and in a separate full-width error strip.

## Root Cause

- The renderer emitted two-value CSS-style padding shorthand in the Card JSON 2.0 header and KPI columns.
- The earlier compatibility probe did not include the production padding fields and therefore was not equivalent evidence.
- The frontend rendered `last_error` in both the owning Pipeline row and a global error strip.

## Fix

- Header padding changed from `10px 12px` to `10px 12px 10px 12px`.
- KPI column padding changed from `6px 8px` to `6px 8px 6px 8px`.
- The global `eventbus-error-strip` was removed.
- Pipeline errors remain in the owning status row, use error color, truncate safely, and expose the full message through the native `title`.

## Validation

- `npm run build`
- `mvn -q test`
- `mvn -q clean package`
- `git diff --check`
- Static assets contain `eventbus-pipeline-error` and no `eventbus-error-strip`.
- A full-shape preflight card containing header, body, column padding, KPI, and table was accepted:

```text
LARK_PROBE=verified code=0 body_bytes=1272 schema=2.0 table=accepted
```

- The current renderer generated a payload from the six real production pending events. That exact payload was accepted:

```text
LARK_PRODUCTION_PAYLOAD=verified code=0 body_bytes=3860 rows=6
```

No Webhook URL or signing secret was copied into local evidence.

## Canary

- Host: `fdbd:dc05:11:634::45`
- SHA and Coordinator service verified.
- Agent start timestamp remained `66431007189470`.
- UI marker: `PIPELINE_ERROR_UI=inline_only`.
- The normal five-minute Gate retried without bypass or configuration mutation.

Successful retry evidence:

```text
PENDING_BY_ROUTE={"route-1786454758682":0}
ROUTE_ERROR_COUNT=0
last_attempt_at_ms=1786516058350
last_success_at_ms=1786516058350
last_delivered_events=23
```

The successful delivery cleared all pending events.

## Differential Rollout

```text
updated:   2
unchanged: 1
failed:    0
```

Evidence: `rollout/coordinators.raw.log`.

## Final Verification

- Artifact SHA verified on 3/3 Coordinators.
- `pulse-coordinator.service`: active on 3/3.
- `pulse-agent.service`: active on 3/3 and start timestamps unchanged.
- Inline-only Pipeline error UI verified on 3/3.
- Route error count: zero on 3/3.
- Configured Pipeline pending count: zero.

Evidence:

- `verify/coordinators.raw.log`
- `verify/delivery-state.raw.log`

## Rollback

```text
/data24/otf/pulse/rollback/lark-padding-ui-fix-20260812-550f074d36645f3beec18157946c83096f84f3b614339b16ff22aa59511ca3c9
```

Restore the JAR and restart only `pulse-coordinator.service`.

## Result

Lark Card JSON 2.0 delivery succeeds with production event data. Pipeline errors are shown once, inside the owning Pipeline status row.
