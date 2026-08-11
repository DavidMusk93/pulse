# EventBus Pipeline UI rollout

## Context

- Operation date: 2026-08-11
- Delivery commit: `481f3cf2164efb8a9c2cf0b71c08cfd821cb5ef9`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `17d1aedb88b3f926e151b2e35f624c4f30b82fdf9ab2ed42d27390491259a4ac`
- Fleet commit: `c9818dc0e3381f4dc0d2df871a8df616e1f5f1a6`
- Fleet inventory SHA-256: `86988745914f93aad48ab075ba56e5a1d7bd636c6ef7343ebd9fc20a28de6072`
- Coordinator inventory SHA-256: `6a32b1b538d30099780403fc3f1cadedd07a7412b65f49949019d27a3ebc93d9`
- Evidence root: `.tmp/auto-ops/eventbus-pipeline-ui-20260811/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Inventory Scope

| Role | Inventory | Exact tag | `--max-hosts` | Dry-run evidence |
| --- | --- | --- | ---: | --- |
| Coordinator | `docs/ops/coordinators.hosts` | `coordinators` | 3 | `dry-run/coordinators.raw.log` |
| Doubao Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `doubao` | 15 | `dry-run/doubao.raw.log` |
| CDN Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `cdn_new` | 50 | `dry-run/cdn-new.raw.log` |

No inferred or merged CDN scope was used.

## Flow

1. Passed `npm run build`, `mvn -q test`, `mvn -q clean package`, and `git diff --check`.
2. Refreshed Orthrus access for all exact scope memberships.
3. Deployed one coordinator, one Doubao Agent, and one `cdn_new` Agent canary.
4. Verified the coordinator canary EventBus API and Pipeline UI static resource.
5. Rolled out coordinators, then Doubao Agents, then `cdn_new` Agents.
6. Re-ran independent per-host artifact SHA and service verification.
7. Verified EventBus API, Web entry, and Pipeline UI static resource on every coordinator.

## Canary Evidence

| Scope | Host | Result | Evidence |
| --- | --- | --- | --- |
| Coordinator | `fdbd:dc05:11:634::45` | updated; coordinator active; Agent start unchanged | `canary/coordinator.raw.log` |
| Coordinator API/UI | `fdbd:dc05:11:634::45` | EventBus API, Pipeline UI, SHA, service verified | `canary/coordinator-ui-rerun.raw.log` |
| Doubao Agent | `fdbd:dc02:e:137::47` | updated; Agent active | `canary/doubao.raw.log` |
| CDN Agent | `fdbd:dc05:11:634::45` | Agent restarted; coordinator start unchanged | `canary/cdn-new.raw.log` |

The first coordinator static-resource probe used `/pulse-hosts.js` and received HTTP 404. It is retained in `canary/coordinator-api.raw.log` as diagnostic evidence. The page-declared `/assets/pulse-hosts.js` path was then used successfully; only the rerun is completion evidence.

## Rollout Evidence

| Scope | Updated | Unchanged | Failed | Evidence |
| --- | ---: | ---: | ---: | --- |
| Coordinators | 2 | 1 | 0 | `rollout/coordinators.raw.log` |
| Doubao Agents | 14 | 1 | 0 | `rollout/doubao.raw.log` |
| `cdn_new` Agents | 49 | 1 | 0 | `rollout/cdn-new.raw.log` |

Coordinator deployment preserved `pulse-agent.service` start timestamps. Agent deployment preserved coordinator start timestamps, including the shared-JAR coordinator hosts.

## Final Verification

| Scope | Verified | Failed | Evidence |
| --- | ---: | ---: | --- |
| Coordinator SHA + service | 3 | 0 | `verify/coordinators.raw.log` |
| Doubao Agent SHA + service | 15 | 0 | `verify/doubao.raw.log` |
| `cdn_new` Agent SHA + service | 50 | 0 | `verify/cdn-new.raw.log` |
| Coordinator EventBus API + Pipeline UI | 3 | 0 | `verify/coordinator-api.raw.log` |

Every verified host reported the target SHA and an active target service. Every coordinator reported:

```text
EVENTBUS_API=verified
PLUGIN_TYPES=lark_webhook,periodic_digest,pulse_message,webhook_event
DEFAULT_SOURCE=pulse_message
WEB_ENTRY=verified
PIPELINE_UI=verified
```

## Rollback

- Previous artifact SHA: `aa47ac121a319e2e074b9615e6e930114f88e02dad2bab3e38a347de905c2395`
- Backup root: `/data24/otf/pulse/rollback/`
- Backup path: `disk-io-fanout-20260811-aa47ac121a319e2e074b9615e6e930114f88e02dad2bab3e38a347de905c2395`

On the three coordinator/Agent overlap hosts, restore the previous artifact once and restart both services in role order. The same-SHA Agent restart backup is not the release rollback point.

## Result

The EventBus Pipeline UI is live on all scoped coordinators. Coordinator and Agent artifacts are synchronized across the exact production scopes with zero final verification failures.
