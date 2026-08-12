# Lark Card 2.0 dense alert rollout

## Context

- Operation date: `2026-08-12`
- Implementation commit: `e7f5b9f`
- Artifact SHA-256: `550f074d36645f3beec18157946c83096f84f3b614339b16ff22aa59511ca3c9`
- Previous production SHA-256: `1f4109f32a75df569b61604a85585d2ce7eaf584c929f17c797360578bc2319b`
- Scope: exact `coordinators` tag, 3 hosts, `--max-hosts 3`
- Evidence root: `.tmp/auto-ops/lark-card-v2-20260812/`
- Task sync: not applicable; no file under `docs/task/` changed.

## Change

- Upgraded the Lark custom-bot payload from legacy Card JSON 1.0 to Schema 2.0.
- Added a compact four-value summary and a native table.
- Table fields: cluster, host/IP, device, IO utilization, threshold, duration, event time, and status.
- Sorted events by saturation duration descending.
- Replaced the fixed ten-event display cap with serialized UTF-8 size selection under the 20 KB webhook limit.
- Kept area and zone out of the message.

## Research And Validation

- Grok 4.5 researched the current official Feishu/Lark card documentation.
- Official documentation was fetched independently to verify:
  - custom-bot `interactive` support;
  - Card JSON Schema 2.0;
  - `body.elements`, `column_set`, and `table`;
  - 20 KB custom-bot request limit;
  - 200-component Schema 2.0 limit;
  - client compatibility requirements.
- Local validation:
  - `mvn -q test`
  - `mvn -q clean package`
  - `git diff --check`
- Focused tests cover Schema 2.0 shape, cluster visibility, area omission, duration sorting, table pagination, and 20 KB dynamic folding.

## Canary

- Host: `fdbd:dc05:11:634::45`
- Result: updated and active.
- Agent start timestamp before and after: `66431007189470`.
- A real Webhook compatibility probe was sent from the canary using the configured private sink:

```text
LARK_PROBE=verified code=0 body_bytes=947 schema=2.0 table=accepted
```

- The probe was clearly titled `Pulse 卡片 2.0 兼容性验证`.
- It bypassed EventBus delivery state and did not acknowledge or clear events.
- An earlier probe attempt failed before HTTP request construction because it referenced an incorrect storage environment variable. It produced no Lark message and is retained as rejected evidence in `canary/lark-webhook-probe.raw.log`.
- Accepted probe evidence: `canary/lark-webhook-probe-final.raw.log`.

## Differential Rollout

```text
updated:   2
unchanged: 1
failed:    0
```

Evidence: `rollout/coordinators.raw.log`.

## Final Verification

All three exact-scope hosts reported:

```text
REMOTE_SHA=550f074d36645f3beec18157946c83096f84f3b614339b16ff22aa59511ca3c9
COORDINATOR_ACTIVE=active
AGENT_ACTIVE=active
LARK_PLUGIN=registered
```

Summary:

```text
total=3 ok=3 failed=0
```

Evidence: `verify/coordinators.raw.log`.

## Invariants

- No Agent JAR was uploaded.
- `pulse-agent.service` was not restarted.
- Existing user files and `.trae/deepwiki/` were not modified.
- Webhook URL and signing secret were not printed or stored in evidence.

## Rollback

- Path: `/data24/otf/pulse/rollback/lark-card-v2-20260812-1f4109f32a75df569b61604a85585d2ce7eaf584c929f17c797360578bc2319b`
- Restore the JAR and restart only `pulse-coordinator.service`.

## Result

Production now emits dense Card JSON 2.0 disk IO alerts with cluster ownership and duration-ranked native table rows.
