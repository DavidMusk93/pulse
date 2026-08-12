# SSE traffic card UI fix

## Context

- Operation date: `2026-08-12`
- Delivery commit: `aa011cd`
- Artifact SHA-256: `1f4109f32a75df569b61604a85585d2ce7eaf584c929f17c797360578bc2319b`
- Previous production SHA-256: `f0d9d3983c6377030b82baf69b16e4dac889fbb9d4fbfaf6361eac66b83764bc`
- Scope: exact `coordinators` tag, 3 hosts, `--max-hosts 3`
- Evidence root: `.tmp/auto-ops/sse-traffic-ui-20260812/`
- Task sync: not applicable.

## Root Cause

The shared `.hero-metrics .ant-card-body` rule used a row flex direction. The custom SSE traffic card inherited it, placing title, value, event rate, and cumulative bytes on one line. Real production values overflowed the 157px-wide metric card.

## Fix

- Explicit column layout for the SSE card body.
- Separate title, primary bytes-per-second value, and two metadata labels.
- `min-width: 0` and overflow containment at card and body boundaries.
- Responsive `clamp()` font size for the primary value.
- Ellipsis protection on all variable-width fields.

## Validation

- `npm run build`
- `mvn -q test`
- `git diff --check`
- Browser test at 157px card width with:
  - `239.3 KB/s`
  - `1.4 events/s`
  - `累计 1.2 MB`
- Card, body, primary value, and both metadata labels all reported `scrollWidth <= clientWidth`.
- Browser console had no errors.

## Deployment

- Dry-run resolved exactly 3 Coordinators.
- Orthrus access was refreshed before canary and final verification.
- Differential result: 2 updated, 1 unchanged, 0 failed.
- `pulse-agent.service` was not restarted; updated hosts reported equal Agent start timestamps.

## Final Verification

All three hosts reported:

```text
REMOTE_SHA=1f4109f32a75df569b61604a85585d2ce7eaf584c929f17c797360578bc2319b
COORDINATOR_ACTIVE=active
AGENT_ACTIVE=active
SSE_TRAFFIC_LAYOUT=contained_column
```

Evidence: `verify/coordinators.raw.log`.

## Rollback

- Path: `/data24/otf/pulse/rollback/sse-traffic-ui-20260812-f0d9d3983c6377030b82baf69b16e4dac889fbb9d4fbfaf6361eac66b83764bc`
- Restore the JAR and restart only `pulse-coordinator.service`.

## Result

The SSE traffic metric is vertically structured and contained at production card widths. No text crosses the card boundary.
