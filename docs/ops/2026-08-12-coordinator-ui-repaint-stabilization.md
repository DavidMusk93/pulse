# Coordinator UI repaint stabilization

## Context

- Operation date: `2026-08-12`
- Delivery commit: `bcc67ca9bda918212e368c0571c04b7d56032d35`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `3ce7b27dd6f26f9cc0bf2737567117afb1ce73e8182111800f594c15a0d8987c`
- Previous production SHA-256: `73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742`
- Fleet commit: `c9818dc0e3381f4dc0d2df871a8df616e1f5f1a6`
- Coordinator inventory SHA-256: `6a32b1b538d30099780403fc3f1cadedd07a7412b65f49949019d27a3ebc93d9`
- Evidence root: `.tmp/auto-ops/ui-repaint-20260812/`
- Task sync: not applicable; no file under `docs/task/` changed.

## Root Cause

The production canary emitted 54 complete `hosts.snapshot` events in three seconds and transferred about 65 MB. Every accepted heartbeat incremented the host revision, so heartbeat fan-in became full host-list render fan-out. The frontend then replaced root `hosts` state and forced `window.scrollTo` after every snapshot.

The EventBus card also relied on CSS Grid auto-placement. Its full-width Pipeline status row occupied the second row before the actions element, pushing actions into a third row and leaving a large blank region.

## Change

- Coordinator coalesces host revisions into at most one `hosts.snapshot` every five seconds by default.
- Initial SSE snapshot remains immediate; no polling was introduced.
- Frontend ignores identical host payloads and no longer forces scroll restoration.
- Metrics ignores heartbeat-only host changes.
- EventBus actions are explicitly placed in row 1, column 3; Pipeline statuses occupy row 2.

## Inventory Scope

| Role | Inventory | Exact tag | `--max-hosts` | Dry-run evidence |
| --- | --- | --- | ---: | --- |
| Coordinator | `docs/ops/coordinators.hosts` | `coordinators` | 3 | `dry-run/coordinators.raw.log` |

The dry-run resolved exactly three hosts:

```text
fdbd:dc05:11:634::45
fdbd:dc05:13:10c::40
fdbd:dc07:0:810::44
```

## Validation Before Deployment

- `npm run build`
- `mvn -q test`
- `mvn -q clean package`
- `git diff --check`
- Static check: no `setInterval`, `clearInterval`, or `window.scrollTo` in frontend source
- Local burst: 60 heartbeats produced two snapshots in seven seconds; final SSE event ID was 60
- Browser geometry with a production-shaped Pipeline row:
  - card height: `171px`
  - grid rows: `61.7px 61.3px`
  - bottom inset after Pipeline row: `15px`
  - actions remained in the first row

## Deployment

1. Refreshed Orthrus access for all three exact hosts.
2. Updated canary `fdbd:dc05:11:634::45`.
3. Verified canary SHA, Coordinator and Agent services, compact layout markers, absence of forced scroll, and Hosts SSE rate.
4. Executed differential rollout for all three Coordinators.
5. Refreshed Orthrus access again before independent verification.
6. Verified raw per-host SHA, services, layout markers, and SSE rate.

The first canary invocation was rejected locally because `--yes` was omitted; it did not reach a host. The first canary verifier used unminified CSS markers and was rejected. Only `canary/coordinator-verify-rerun.raw.log` is accepted canary completion evidence.

## Differential Result

| Scope | Updated | Unchanged | Failed | Evidence |
| --- | ---: | ---: | ---: | --- |
| Coordinators | 2 | 1 | 0 | `rollout/coordinators.raw.log` |

`pulse-agent.service` was not restarted. Raw output records equal `AGENT_START_BEFORE` and `AGENT_START_AFTER` on every updated host.

## Final Verification

| Scope | Verified | Failed | Evidence |
| --- | ---: | ---: | --- |
| Coordinator SHA + services + UI + SSE | 3 | 0 | `verify/coordinators.raw.log` |

Every Coordinator reported:

```text
REMOTE_SHA=3ce7b27dd6f26f9cc0bf2737567117afb1ce73e8182111800f594c15a0d8987c
COORDINATOR_ACTIVE=active
AGENT_ACTIVE=active
EVENTBUS_LAYOUT=compact_two_row
FORCED_SCROLL=absent
HOST_SNAPSHOTS_7S=2
```

The observed production rate changed from 54 snapshots in three seconds to two snapshots in seven seconds, including the immediate initial snapshot. No Agent artifact was separately uploaded and no Agent service was restarted.

## Rollback

- Rollback root: `/data24/otf/pulse/rollback/`
- Release rollback path: `ui-repaint-20260812-73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742`
- Rollback artifact SHA: `73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742`

Restore the rollback JAR and restart only `pulse-coordinator.service`. Do not restart `pulse-agent.service`.

## Result

The compact two-row EventBus layout and coalesced Hosts SSE stream are live on all three production Coordinators. Final verification has zero failures.
