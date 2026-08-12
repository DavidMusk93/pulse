# SSE delta and traffic observability rollout

## Context

- Operation date: `2026-08-12`
- Delivery commit: `0920d45e5bf74fea471d2e223bb0003524a7d916`
- Artifact SHA-256: `f0d9d3983c6377030b82baf69b16e4dac889fbb9d4fbfaf6361eac66b83764bc`
- Previous production SHA-256: `3ce7b27dd6f26f9cc0bf2737567117afb1ce73e8182111800f594c15a0d8987c`
- Coordinator inventory SHA-256: `6a32b1b538d30099780403fc3f1cadedd07a7412b65f49949019d27a3ebc93d9`
- Fleet commit: `c9818dc0e3381f4dc0d2df871a8df616e1f5f1a6`
- Evidence root: `.tmp/auto-ops/sse-delta-20260812/`
- Task sync: not applicable; no `docs/task/` file changed.

## Scope

| Role | Inventory | Exact tag | `--max-hosts` |
| --- | --- | --- | ---: |
| Coordinator | `docs/ops/coordinators.hosts` | `coordinators` | 3 |

Dry-run evidence: `dry-run/coordinators.raw.log`.

## Change

- `/api/hosts/stream` sends one initial `hosts.snapshot`, then recursive field-level `hosts.delta` events.
- Delta upserts omit unchanged top-level and nested `state` fields and carry removals by `agent_id`.
- The frontend merge preserves object identity for unchanged hosts.
- Every frontend EventSource records UTF-8 `MessageEvent.data` bytes and event count.
- The top status card displays rolling payload bytes per second, events per second, and cumulative payload.
- Cluster and Host surfaces use `content-visibility: auto` and intrinsic sizing to skip offscreen layout and paint.
- No polling was added.

## Local Validation

- `npm run build`
- `mvn -q test`
- `mvn -q clean package`
- `git diff --check`
- Browser status-card and computed-style verification
- Browser console: zero errors
- Static source check: six EventSource consumers are tracked; no `setInterval` or `clearInterval`

The controlled 100-host benchmark changed only dynamic load fields:

```text
SNAPSHOT_BYTES=74014
DELTA_BYTES=18726
REDUCTION_PCT=75
MERGE_MATCH=true
```

Stable cluster, area, zone, kernel, and hardware fields were absent from the delta.

## Production Baseline

Before deployment, canary `fdbd:dc05:11:634::45` reported:

```text
HOST_SNAPSHOT_COUNT_7S=2
HOST_SNAPSHOT_BYTES_7S=2446226
HOST_DELTA_COUNT_7S=0
```

Evidence: `baseline/coordinator.raw.log`.

An earlier identical read-only probe succeeded remotely but its local evidence directory was created from the wrong working directory and rejected by the sandbox. It is not accepted completion evidence.

## Flow

1. Captured the production payload baseline.
2. Resolved exactly three Coordinator hosts with dry-run.
3. Refreshed Orthrus access for the exact scope.
4. Deployed and verified canary `fdbd:dc05:11:634::45`.
5. Executed differential rollout across all Coordinators.
6. Refreshed Orthrus access again.
7. Independently verified per-host SHA, services, delta stream, payload bytes, status UI marker, and lazy-rendering marker.

## Differential Result

| Updated | Unchanged | Failed | Evidence |
| ---: | ---: | ---: | --- |
| 2 | 1 | 0 | `rollout/coordinators.raw.log` |

`pulse-agent.service` was not restarted. Updated hosts reported equal `AGENT_START_BEFORE` and `AGENT_START_AFTER`.

## Final Verification

| Verified | Failed | Evidence |
| ---: | ---: | --- |
| 3 | 0 | `verify/coordinators.raw.log` |

All three hosts reported:

```text
REMOTE_SHA=f0d9d3983c6377030b82baf69b16e4dac889fbb9d4fbfaf6361eac66b83764bc
COORDINATOR_ACTIVE=active
AGENT_ACTIVE=active
HOST_SNAPSHOT_COUNT_7S=1
HOST_DELTA_COUNT_7S=1
SSE_TRAFFIC_UI=verified
LAZY_RENDERING=verified
```

Final per-host payloads:

| Host | Snapshot bytes | Delta bytes | Delta reduction |
| --- | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 1,223,458 | 856,952 | 30.0% |
| `fdbd:dc05:13:10c::40` | 1,223,469 | 856,491 | 30.0% |
| `fdbd:dc07:0:810::44` | 1,223,469 | 856,209 | 30.0% |

The seven-second total fell from 2,446,226 bytes before deployment to about 2.08 MB after deployment, approximately 15% lower including the mandatory initial snapshot.

## Rollback

- Rollback path: `/data24/otf/pulse/rollback/sse-delta-20260812-3ce7b27dd6f26f9cc0bf2737567117afb1ce73e8182111800f594c15a0d8987c`
- Rollback artifact SHA: `3ce7b27dd6f26f9cc0bf2737567117afb1ce73e8182111800f594c15a0d8987c`

Restore the rollback JAR and restart only `pulse-coordinator.service`. Do not restart `pulse-agent.service`.

## Result

Hosts now update through lossless field-level deltas, all frontend SSE payload is observable in the status area, and offscreen cluster content skips rendering. Production verification completed with zero failures.
