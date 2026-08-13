# Host SSE V3 Diff Protocol

## Purpose

Host SSE V3 is the default Coordinator UI host stream protocol. It sends an
authoritative snapshot first, then sparse diffs for the same scoped connection.
The design goal is low wire cost without hiding the data model behind opaque
binary frames or client-side polling.

The client requests V3 explicitly:

```text
GET /api/hosts/stream?v=3&clusters=<cluster>
```

Each UI connection subscribes to exactly one cluster. The first catalog-only
bootstrap connection uses `clusters=__pulse_catalog__`, receives no Host rows,
selects a real cluster from the returned catalog, and reconnects with that
cluster.

## Snapshot Contract

`hosts.snapshot` is authoritative for one connection-local revision chain:

```json
{
  "schema": "hosts.v3",
  "revision": 42,
  "scope": ["cdn2"],
  "catalog": ["cdn2", "doubao"],
  "dictionaries": {
    "fields": ["agent_id", "epoch", "seq"],
    "entities": ["agent-1", "agent-2"],
    "values": [[8, ["alive", "warming"]]]
  },
  "columns": [
    ["agent-1", "agent-2"],
    [1, 1],
    [100, 101]
  ]
}
```

Production snapshots always use the full 24-field `HostView` dictionary defined
in `docs/design/host-sse-v3.md`. The example above is abbreviated.

Snapshot invariants:

- `revision` starts the connection-local diff chain.
- `scope` is the exact server-side cluster filter for the connection.
- `catalog` is built from all current hosts before scope filtering.
- `dictionaries.fields` is fixed, ordered, and complete.
- `dictionaries.entities` is ordered by `agent_id`.
- `dictionaries.values` is optional and field-scoped.
- `columns[n]` maps to `dictionaries.fields[n]` and has one cell per entity.

## Delta Contract

`hosts.delta` advances the same revision chain:

```json
{
  "schema": "hosts.v3",
  "base_revision": 42,
  "revision": 43,
  "values": [[8, ["expired"]]],
  "add": [[2, "agent-3", ["agent-3", 1, 102]]],
  "remove": [0],
  "columns": [[8, [1], [2]]],
  "unset": [],
  "catalog": ["cdn2", "doubao", "tlbmirror"]
}
```

Delta invariants:

- `base_revision` must equal the client's applied revision.
- `revision` must be greater than `base_revision`.
- `add` entries are `[entity_index, agent_id, complete_field_values]`.
- `remove` tombstones entity indexes; indexes are never reused in a connection.
- `columns` entries are sparse field patches:
  `[field_index, entity_indexes, changed_values]`.
- `unset` entries explicitly remove fields:
  `[field_index, entity_indexes]`.
- `values` appends to existing field-scoped dictionaries before `add` and
  `columns` are decoded.
- `catalog`, when present, replaces the complete catalog.

`add`, `remove`, `columns`, and `unset` are always present. `values` and
`catalog` are emitted only when needed.

## Client Apply

The client validates the complete delta before mutating visible state:

```text
delta.schema == hosts.v3
delta.base_revision == state.revision
delta.revision > state.revision
```

Apply order:

1. Validate dictionary extensions.
2. Append dictionary values.
3. Append complete added entities.
4. Apply removals as tombstones.
5. Group sparse field and unset operations by entity.
6. Clone each changed entity at most once.
7. Publish the new revision, rows, hosts, catalog, dictionaries, and tombstones
   together.

Invalid references, duplicate dictionary extensions, non-append-only
dictionaries, revision gaps, and malformed sparse groups reject the whole
delta. Recovery is a new authoritative snapshot on a new connection.

## UI Scope Swap

Changing the selected cluster is not a clear-and-refetch operation.

The UI keeps the currently applied Host DOM mounted while the next scoped
snapshot is pending. During this state:

- `hostScopePending = appliedHostClusterScope !== hostClusterScope`.
- The stale Host region is `inert` and `aria-busy=true`.
- Pointer actions are disabled locally.
- Existing page geometry is preserved, so the browser does not clamp scroll.
- Snapshot and delta callbacks from superseded scope requests are ignored.

Only an accepted snapshot for the current requested scope may replace the Host
collection and applied scope. No code path may call `setHosts([])` merely
because the user selected another cluster.

## Compatibility

V1 and V2 remain available for old clients and fallback:

- No `v=3`: legacy V1 behavior.
- `v=2`: scoped V2 snapshot and merge-patch delta.
- `v=3` against an older Coordinator: frontend detects the legacy array
  snapshot and reconnects once with `v=2`.

V3 does not change the Agent heartbeat protocol. Agents still publish complete
host state through heartbeat messages. The Coordinator transforms current
`HostView` snapshots into V3 columnar diff frames for UI subscribers only.

## Operational Checks

A production V3 rollout is complete only when raw evidence proves:

- the deployed JAR SHA matches on every coordinator and agent host;
- `pulse-coordinator.service` is active on all coordinators;
- `pulse-agent.service` is active on all agent hosts;
- `/api/hosts/stream?v=3&clusters=<cluster>&once=true` returns
  `event: hosts.snapshot` with `"schema":"hosts.v3"`;
- the Coordinator UI bundle requests `v=3`;
- no deployment evidence relies only on aggregate summaries.
