# EventBus heartbeat rollout

## Context

- Operation date: 2026-08-11
- Delivery commit: `ede6a8799018fbd68d78b9a3d4595ab5e22986ba`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `aa47ac121a319e2e074b9615e6e930114f88e02dad2bab3e38a347de905c2395`
- Fleet commit: `c9818dc0e3381f4dc0d2df871a8df616e1f5f1a6`
- Fleet inventory SHA-256: `86988745914f93aad48ab075ba56e5a1d7bd636c6ef7343ebd9fc20a28de6072`
- Evidence root: `.tmp/auto-ops/eventbus-golden-20260811/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Inventory Scope

| Role | Inventory | Exact tag | `--max-hosts` | Dry-run evidence |
| --- | --- | --- | ---: | --- |
| Coordinator | `.tmp/auto-ops/disk-io-fanout-20260811/scope/coordinators.hosts` | `coordinators` | 3 | `dry-run/coordinators.raw.log` |
| Doubao Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `doubao` | 15 | `dry-run/doubao.raw.log` |
| CDN Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `cdn_new` | 50 | `dry-run/cdn-new.raw.log` |

The `cdn_new` dry-run resolved 50 hosts. No inferred or merged CDN scope was used.

## Flow

1. Passed `npm run build`, `mvn -q test`, `mvn -q clean package`, and `git diff --check`.
2. Refreshed Orthrus access for the exact `3 + 15 + 50` memberships.
3. Deployed one coordinator canary, one Doubao Agent canary, and one `cdn_new` Agent canary.
4. Verified the coordinator canary API before widening the rollout.
5. Rolled out coordinators first, then Doubao Agents, then `cdn_new` Agents.
6. Re-ran independent per-host SHA and service verification after rollout.
7. Verified the EventBus API and Web entry on all three coordinators through SSH-local curl.

## Evidence

### Canary

| Scope | Host | Result | Evidence |
| --- | --- | --- | --- |
| Coordinator | `fdbd:dc05:11:634::45` | updated, coordinator active, Agent start unchanged | `canary/coordinator.raw.log` |
| Coordinator API | `fdbd:dc05:11:634::45` | plugins and default `pulse_message` source verified | `canary/coordinator-api.raw.log` |
| Doubao Agent | `fdbd:dc02:e:137::47` | updated, Agent active | `canary/doubao.raw.log` |
| CDN Agent | `fdbd:dc05:11:634::45` | Agent restarted, coordinator start unchanged | `canary/cdn-new-rerun.raw.log` |

The first CDN canary classified the shared JAR as unchanged after the coordinator rollout. It was not accepted as Agent completion evidence. The Agent callee was tightened to require `Agent start epoch >= JAR mtime epoch`; the rerun restarted the stale Agent process without restarting the coordinator.

### Rollout

| Scope | Updated | Unchanged | Failed | Evidence |
| --- | ---: | ---: | ---: | --- |
| Coordinators | 2 | 1 | 0 | `rollout/coordinators.raw.log` |
| Doubao Agents | 14 | 1 | 0 | `rollout/doubao.raw.log` |
| `cdn_new` Agents | 49 | 1 | 0 | `rollout/cdn-new.raw.log` |

The coordinator deployment preserved `pulse-agent.service` start timestamps. The Agent deployment preserved coordinator start timestamps, including the three shared-JAR coordinator hosts.

### Final Verification

| Scope | Verified | Failed | Evidence |
| --- | ---: | ---: | --- |
| Coordinator SHA + service | 3 | 0 | `verify/coordinators.raw.log` |
| Doubao Agent SHA + service | 15 | 0 | `verify/doubao.raw.log` |
| `cdn_new` Agent SHA + service | 50 | 0 | `verify/cdn-new.raw.log` |
| Coordinator EventBus API + Web entry | 3 | 0 | `verify/coordinator-api.raw.log` |

Every verified host reported the target SHA and an active target service. Each coordinator reported:

```text
PLUGIN_TYPES=lark_webhook,periodic_digest,pulse_message,webhook_event
DEFAULT_SOURCE=pulse_message
WEB_ENTRY=verified
```

## Rollback

- Coordinator previous SHA: `53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3`
- Doubao previous SHA: `36705150ef9c9fd5106240be04cc1523edcb3b66a1a4bd81b47ea3fea7449d83`
- CDN previous SHA: `7b8cdd36e8407c779043089142304e6ded14469fcb91e62b5e30bd2db19c7cd5`
- Backup root: `/data24/otf/pulse/rollback/`
- Coordinator backup: `disk-io-fanout-20260811-53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3`
- Doubao backup: `disk-io-fanout-20260811-36705150ef9c9fd5106240be04cc1523edcb3b66a1a4bd81b47ea3fea7449d83`
- CDN backup: `disk-io-fanout-20260811-7b8cdd36e8407c779043089142304e6ded14469fcb91e62b5e30bd2db19c7cd5`

On the three coordinator/Agent overlap hosts, restore the coordinator backup and restart both services in role order. Do not use the same-SHA Agent restart backup as the release rollback artifact.

## Result

The heartbeat-native EventBus and golden-ratio configuration UI are live on all scoped coordinators, Doubao Agents, and `cdn_new` Agents. Final raw verification has zero failures.
