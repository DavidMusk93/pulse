# EventBus IPv6 egress fix

## Context

- Operation date: 2026-08-11
- Trigger: Pipeline `route-1786454758682` matched active events but Lark Sink delivery did not arrive.
- Deployed artifact SHA-256: `17d1aedb88b3f926e151b2e35f624c4f30b82fdf9ab2ed42d27390491259a4ac`
- Coordinator inventory: `docs/ops/coordinators.hosts`
- Coordinator inventory SHA-256: `6a32b1b538d30099780403fc3f1cadedd07a7412b65f49949019d27a3ebc93d9`
- Exact tag: `coordinators`
- Exact `--max-hosts`: 3
- Evidence root: `.tmp/auto-ops/eventbus-ipv6-20260811/`
- Task sync: not applicable; no file under `docs/task/` changed.

## Diagnosis

The Pipeline had already triggered:

- Source: `disk-io-saturation`
- Event type: `disk.io_saturation`
- Gate interval: `300000ms`
- Active events at diagnosis: 2494
- Last Sink error: `Network is unreachable`
- Last successful delivery: none

The coordinator had no IPv4 route. DNS for `open.larkoffice.com` returned both IPv4 and IPv6 addresses. Production probes proved:

```text
curl -4: connection failed
curl -6: HTTP 200
```

The JVM selected an unreachable IPv4 address for the Lark Webhook. The Pipeline and Gate were functioning; outbound address-family selection blocked delivery.

## Change

Added the following coordinator environment setting:

```text
JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true
```

Only `pulse-coordinator.service` was restarted. `pulse-agent.service` start timestamps were verified unchanged on every modified host. The JAR was not uploaded or changed.

## Flow

1. Dry-ran exact tag `coordinators` with 3 hosts.
2. Applied the setting to canary `fdbd:dc05:11:634::45`.
3. Verified coordinator active and Agent start timestamp unchanged.
4. Executed a real Lark Sink test: HTTP 200, interactive format, one delivered event.
5. Rolled out the setting to all 3 coordinators.
6. Verified target JAR SHA and coordinator service on all 3 hosts.
7. Waited for the existing 5-minute Gate interval instead of bypassing suppression.
8. Verified the next automatic Pipeline delivery succeeded.

## Evidence

| Stage | Result | Raw evidence |
| --- | --- | --- |
| Dry-run | 3 exact coordinator hosts | `dry-run/coordinators.raw.log` |
| Canary config | updated; coordinator active; Agent unchanged | `canary/coordinator.raw.log` |
| Canary Sink test | HTTP 200; interactive; one event | `canary/sink-test.raw.log` |
| Full rollout | 2 updated, 1 unchanged, 0 failed | `rollout/coordinators.raw.log` |
| Final SHA/service | 3 verified, 0 failed | `verify/coordinators.raw.log` |
| IPv6 network | Lark HTTP 200 over IPv6 | `verify/canary-network.raw.log` |
| Route state | success timestamp set; error cleared | `verify/route-status.raw.log` |
| Delivery log | 2494 events, interactive, succeeded | `verify/delivery.raw.log` |

Final automatic delivery:

```text
delivery_id=delivery-381580dfd460df7b07b17f96
events=2494
format=interactive
last_attempt_at_ms=1786455697262
last_success_at_ms=1786455697262
last_error=""
```

## Rollback

- Backup: `/data24/otf/pulse/rollback/eventbus-ipv6-20260811/pulse-coordinator.env`
- Restore the backup to `/data24/otf/pulse/etc/pulse-coordinator.env`.
- Restart only `pulse-coordinator.service`.
- Verify `pulse-agent.service` start timestamp remains unchanged.

## Result

The user-defined Pipeline was already matching events. After correcting coordinator outbound address-family selection, the Lark Sink test and the next scheduled Pipeline delivery both succeeded.
