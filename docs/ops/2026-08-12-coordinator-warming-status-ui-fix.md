# Coordinator warming status UI fix

## Context

- Operation date: `2026-08-12`
- Fix commit: `e29b13e`
- Artifact SHA-256: `db9584385734f71f1d43b2d590ddde0e4fdc43ac87b9b40863c9514c187e1004`
- Previous production SHA-256: `dde33a3f82707a27793f86fa5c2edcc5d966b058b1e0a1d8eb7caae3bd85fb3a`
- Scope: exact `coordinators` tag, 3 hosts, `--max-hosts 3`
- Evidence root: `.tmp/auto-ops/warming-status-ui-fix-20260812/`
- Task sync: not applicable

## Incident

After refreshing the Coordinator page following a Coordinator-only rollout, the UI reported a fleet-wide node anomaly and expanded all affected clusters.

Production inspection before changing code showed:

```text
HOST_TOTAL=486
STATUS={'alive': 486}
AGE_MS max=8838 over_30s=0 over_60s=0 over_300s=0
```

All three Coordinators had the same target SHA, were active, and returned an identical host set. No persistent Agent or heartbeat outage existed.

## Root Cause

Coordinator restart clears the in-memory 20-second heartbeat confirmation window. Until three recent confirmations arrive, every host is correctly reported as `warming`.

The frontend incorrectly grouped `warming` and `expired` together as health failures. A refresh during that confirmation window therefore rendered all warming hosts as `异常节点` and forcibly expanded their clusters.

## Fix

- Only `expired` is classified as a node health failure.
- `warming` is rendered separately as `状态确认中`.
- Confirming status uses a blue processing tone.
- Confirming hosts do not trigger `异常展开`.
- Confirming hosts do not overwrite the user's cluster collapse preference.
- Existing task activity remains independently visible.

## Local Validation

- `npm run build`
- `mvn -q -Dtest=CoordinatorHttpServerTest test`
- `mvn -q test`
- `mvn -q clean package`
- `git diff --check`

The static bundle test verifies the stable `状态确认中` and `cluster-status-confirming` production markers.

## Canary

Host: `fdbd:dc05:11:634::45`

Immediately after restart, the canary reproduced the incident precondition:

```text
HOST_TOTAL=486
HOST_STATUS={"warming":486}
WARMING_UI=confirming_not_failure
```

The new bundle rendered this state as confirmation in progress, not failure. After one complete confirmation window:

```text
HOST_TOTAL=486
HOST_STATUS={"alive":486}
```

The Agent service remained active and its start timestamp remained `66431007189470`.

## Differential Rollout

The rollout was serialized across the exact three-host inventory:

```text
unchanged: 1
updated:   2
failed:    0
```

Agent start timestamps were unchanged:

```text
fdbd:dc05:11:634::45  66431007189470
fdbd:dc05:13:10c::40  66431200059454
fdbd:dc07:0:810::44   34629973814836
```

Evidence: `rollout/coordinators.raw.log`.

## Final Verification

After the full confirmation window:

- Artifact SHA verified on 3/3 Coordinators.
- `pulse-coordinator.service` active on 3/3.
- `pulse-agent.service` active and not restarted on 3/3.
- Host view identical on 3/3.
- Host state `486/486 alive` on 3/3.
- Warming UI contract present on 3/3.

Evidence:

- `verify/demand.raw.log`
- `verify/coordinators.raw.log`
- `verify/coordinators-steady.raw.log`

## Rollback

```text
/data24/otf/pulse/rollback/warming-status-ui-fix-20260812-dde33a3f82707a27793f86fa5c2edcc5d966b058b1e0a1d8eb7caae3bd85fb3a
```

Restore the JAR and restart only `pulse-coordinator.service`.

## Result

There was no persistent Coordinator or Agent regression. The fleet-wide anomaly was a frontend classification bug exposed by the normal post-restart confirmation window. Production now distinguishes `warming` from `expired`, and all Coordinators report `486/486 alive`.
