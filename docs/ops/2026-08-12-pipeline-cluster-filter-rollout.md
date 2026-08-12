# Pipeline cluster filter rollout

## Context

- Operation date: `2026-08-12`
- Implementation commit: `73b2562`
- Artifact SHA-256: `82211f69ed976c4ad7eb54d093dd885715cc5612a527c786435233ab96c52bab`
- Previous production SHA-256: `db9584385734f71f1d43b2d590ddde0e4fdc43ac87b9b40863c9514c187e1004`
- Scope: exact `coordinators` tag, 3 hosts, `--max-hosts 3`
- Evidence root: `.tmp/auto-ops/pipeline-cluster-filter-20260812/`
- Task sync: not applicable

## Change

EventBus Pipelines can now filter events by exact
`event.attributes.cluster` values:

```text
clusters: []                 -> all clusters
clusters: ["cdn2"]           -> cdn2 only
clusters: ["cdn2", "doubao"] -> either selected cluster
```

The same Route predicate is used for pending counts, Sink selection, delivery
target calculation, acknowledgments, and post-delivery cleanup.

The Pipeline editor receives cluster options from the existing Hosts SSE state.
No polling was added.

## Compatibility

Legacy persisted Routes without a `clusters` field deserialize to an empty list.
The existing production Route was verified after canary restart:

```text
ROUTE_CLUSTERS={"route-1786454758682":[]}
```

The rollout therefore did not change the existing Pipeline's delivery scope.
The other two Coordinators had no local Routes before or after the rollout.

## Local Validation

- `npm run build`
- focused `EventBusServiceTest` and `CoordinatorHttpServerTest`
- full `mvn -q test`
- `mvn -q clean package`
- `git diff --check`

Tests cover exact cluster matching, pending counts, exclusion of non-selected
clusters, filter changes for active incidents, delivery cleanup, API
serialization, and legacy configuration compatibility.

## Canary

- Host: `fdbd:dc05:11:634::45`
- Previous SHA: `db9584385734f71f1d43b2d590ddde0e4fdc43ac87b9b40863c9514c187e1004`
- Target SHA: `82211f69ed976c4ad7eb54d093dd885715cc5612a527c786435233ab96c52bab`
- Coordinator active.
- Agent active and start timestamp unchanged: `66431007189470`.
- Cluster filter UI markers present.
- Existing Route migrated to `clusters: []`.
- Host state converged to `486/486 alive`.

Evidence:

- `canary/coordinator.raw.log`
- `canary/verify.raw.log`
- `canary/verify-steady.raw.log`

## Differential Rollout

The rollout was serialized:

```text
unchanged: 1
updated:   2
failed:    0
```

Agent start timestamps remained unchanged:

```text
fdbd:dc05:11:634::45  66431007189470
fdbd:dc05:13:10c::40  66431200059454
fdbd:dc07:0:810::44   34629973814836
```

Evidence: `rollout/coordinators.raw.log`.

## Final Verification

- Artifact SHA verified on 3/3 Coordinators.
- `pulse-coordinator.service` active on 3/3.
- `pulse-agent.service` active and not restarted on 3/3.
- Host state `486/486 alive` on 3/3.
- `clusters` Route field verified on 3/3.
- Cluster filter UI verified on 3/3.
- Existing configured Route remained unfiltered.

Evidence:

- `verify/demand.raw.log`
- `verify/coordinators.raw.log`
- `verify/coordinators-steady.raw.log`

## Rollback

```text
/data24/otf/pulse/rollback/pipeline-cluster-filter-20260812-db9584385734f71f1d43b2d590ddde0e4fdc43ac87b9b40863c9514c187e1004
```

Restore the JAR and restart only `pulse-coordinator.service`.

## Result

Pipeline cluster filtering is available on all production Coordinators. No
cluster filter has been selected for the existing Pipeline, so current delivery
behavior is unchanged until a user saves a filter in the UI.
