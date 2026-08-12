# EventBus SSE bounded-delivery rollout

## Context

- Operation date: 2026-08-12
- Delivery commit: `d3a61a945c096003ebafc688a1089c8ec8c5a510`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742`
- Previous production SHA-256: `17d1aedb88b3f926e151b2e35f624c4f30b82fdf9ab2ed42d27390491259a4ac`
- Fleet commit: `c9818dc0e3381f4dc0d2df871a8df616e1f5f1a6`
- Fleet inventory SHA-256: `86988745914f93aad48ab075ba56e5a1d7bd636c6ef7343ebd9fc20a28de6072`
- Coordinator inventory SHA-256: `6a32b1b538d30099780403fc3f1cadedd07a7412b65f49949019d27a3ebc93d9`
- Evidence root: `.tmp/auto-ops/eventbus-sse-20260812/`
- Task sync: not applicable; the delivery changed no file under `docs/task/`.

## Inventory Scope

| Role | Inventory | Exact tag | `--max-hosts` | Dry-run evidence |
| --- | --- | --- | ---: | --- |
| Coordinator | `docs/ops/coordinators.hosts` | `coordinators` | 3 | `dry-run/coordinators.raw.log` |
| Doubao Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `doubao` | 15 | `dry-run/doubao.raw.log` |
| CDN Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `cdn_new` | 50 | `dry-run/cdn-new.raw.log` |

The dry-runs resolved exactly `3 + 15 + 50` hosts. No inferred or merged CDN scope was used.

## Flow

1. Used the already validated and pushed delivery commit. Its full tests, clean package, frontend build, diff check, and zero-frontend-polling source check had passed before deployment.
2. Recomputed the local artifact SHA and verified the exact inventory hashes.
3. Refreshed Orthrus permissions for every exact scope.
4. Deployed and verified one Coordinator, one Doubao Agent, and one `cdn_new` Agent canary.
5. Rolled out Coordinators first, then Doubao and `cdn_new` Agents.
6. Refreshed Orthrus permissions again immediately before final SSH verification.
7. Independently verified per-host JAR SHA, service state, Agent process age, overlap-host Coordinator state, EventBus API, SSE streams, and UI asset markers.

SSH multiplexing and known-host writes were disabled for execution because the sandbox cannot write `~/.ssh/cm` or temporary `~/.ssh/known_hosts.*` files.

## Canary Evidence

| Scope | Host | Result | Accepted evidence |
| --- | --- | --- | --- |
| Coordinator | `fdbd:dc05:11:634::45` | updated; Coordinator active; Agent start unchanged | `canary/coordinator-rerun.raw.log` |
| Coordinator API/SSE/UI | `fdbd:dc05:11:634::45` | SHA, API, seconds config, both SSE streams, Web entry and Pipeline UI verified | `canary/coordinator-api-final.raw.log` |
| Doubao Agent | `fdbd:dc02:e:137::47` | updated; Agent active | `canary/doubao.raw.log` |
| CDN Agent | `fdbd:dc05:11:634::45` | Agent restarted for the shared JAR; Coordinator start unchanged | `canary/cdn-new.raw.log` |

The initial Coordinator attempt in `canary/coordinator.raw.log` did not reach the host because the sandbox blocked the SSH ControlMaster socket. The first two API verifier runs were also rejected: one did not propagate a remote pipeline failure and the next exposed `SIGPIPE` from `grep -q`. The verifier was corrected to consume full responses and propagate the remote return code. Only `coordinator-api-final.raw.log` is completion evidence.

## Differential Rollout

| Scope | Updated | Unchanged | Failed | Evidence |
| --- | ---: | ---: | ---: | --- |
| Coordinators | 2 | 1 | 0 | `rollout/coordinators.raw.log` |
| Doubao Agents | 14 | 1 | 0 | `rollout/doubao.raw.log` |
| `cdn_new` Agents | 49 | 1 | 0 | `rollout/cdn-new.raw.log` |

Coordinator deployment preserved `pulse-agent.service` start timestamps. Agent deployment preserved Coordinator start timestamps on all three overlap hosts. SHA-equal Agents were classified as unchanged only when active and when `Agent start epoch >= JAR mtime epoch`; stale shared-JAR Agent processes were restarted without restarting the Coordinator.

## Final Verification

| Scope | Verified | Failed | Evidence |
| --- | ---: | ---: | --- |
| Coordinator SHA + service + API/SSE/UI | 3 | 0 | `verify/coordinators.raw.log` |
| Doubao Agent SHA + service + process age | 15 | 0 | `verify/doubao.raw.log` |
| `cdn_new` Agent SHA + service + process age | 50 | 0 | `verify/cdn-new.raw.log` |

Every Agent reported:

```text
REMOTE_SHA=73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742
AGENT_ACTIVE=active
AGENT_START_EPOCH >= JAR_MTIME_EPOCH
```

All three overlap hosts additionally reported `COORDINATOR_ACTIVE=active`. Every Coordinator reported:

```text
EVENTBUS_API=verified
PLUGIN_TYPES=agent_disk_io,lark_webhook,periodic_digest,pulse_message,webhook_event
DEFAULT_SOURCE=agent_disk_io
SOURCE_SECONDS=verified
PENDING_BY_ROUTE=verified
EVENTBUS_SSE=verified
HOSTS_SSE=verified
WEB_ENTRY=verified
PIPELINE_UI=verified
REMOTE_SHA=73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742
COORDINATOR_ACTIVE=active
```

## Rollback

- Rollback root: `/data24/otf/pulse/rollback/`
- Release rollback path: `eventbus-sse-20260812-17d1aedb88b3f926e151b2e35f624c4f30b82fdf9ab2ed42d27390491259a4ac`
- Rollback artifact SHA: `17d1aedb88b3f926e151b2e35f624c4f30b82fdf9ab2ed42d27390491259a4ac`

On the three Coordinator/Agent overlap hosts, restore the previous artifact once and restart both services in role order. Do not use the same-SHA Agent restart backup as the release rollback point.

## Result

The bounded-delivery EventBus, seconds-based control configuration, SSE-only live UI, Pipeline delivery feedback, and revised Lark rendering are live on all scoped production Coordinators, Doubao Agents, and `cdn_new` Agents. Final raw verification has zero failures.
