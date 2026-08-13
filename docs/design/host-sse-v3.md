# Hosts SSE V3

## Scope

V3 is an opt-in, connection-local JSON stream. It reduces wire bytes while
remaining inspectable and allows the client to clone each changed entity at
most once per delta. V1 and V2 remain available without contract changes.

Clients negotiate V3 explicitly:

```text
GET /api/hosts/stream?v=3&clusters=cdn2
```

The collection UI always subscribes to exactly one cluster. It does not expose
an all-clusters option. Before the first catalog is known, it opens a
`clusters=__pulse_catalog__` bootstrap stream; that reserved scope matches no
real Host rows but still returns the global catalog. The UI selects the first
cluster deterministically, persists later user choices, and reconnects with the
selected cluster before rendering Host cards. The selector is a fixed-width,
searchable single-select with a virtualized option list, so layout and mounted
DOM remain bounded as the catalog grows.

The Coordinator emits the existing `hosts.snapshot` and `hosts.delta` SSE
event names. An older Coordinator returns the legacy V1 snapshot for an
unknown version; the frontend detects that array contract and reconnects once
with `v=2`.

The codec factory is:

```text
HostStreamV3Codec.session(mapper, revision, scope, hosts)
```

The returned public session exposes `snapshot()` and
`delta(revision, hosts)`. A session is stateful and belongs to one ordered SSE
revision chain.

## Fixed Dictionaries

The field dictionary is fixed, ordered, and complete:

```text
 0 agent_id                 12 group_mode
 1 epoch                    13 leader_agent_id
 2 seq                      14 leader_url
 3 ttl_ms                   15 group_size
 4 observed_at_ms           16 group_size_limit
 5 expire_at_ms             17 host
 6 last_observed_age_ms     18 ip
 7 heartbeat_confirmations  19 cluster
 8 status                   20 area
 9 source                   21 zone
10 coordinator_id           22 role
11 group_id                 23 load
```

Entity indexes are local to one connection. Initial entities are sorted by
`agent_id`. A removed entity leaves a tombstone; its index is never reused.
New and reappearing entities are sorted by `agent_id` within a delta and
appended to the dictionary.

The snapshot may also declare adaptive, field-scoped value dictionaries.
Bitmaps, numeric coercions, derived fields, dependencies, and opaque
abbreviated keys are not used.

## Value Dictionaries

The producer first builds raw columns after applying scope. It then constructs
a deterministic candidate dictionary for each field, with non-null values in
first entity-order occurrence. Starting with all candidates, it removes a
field whenever serializing the complete snapshot without that field is no
larger. Jackson's final UTF-8 JSON byte length is the cost function, including
the `dictionaries.values` property, declarations, references, commas, and
columns. If the resulting snapshot is not smaller than raw V3, all value
dictionaries are omitted. High-cardinality fields therefore remain raw
without a fixed cardinality heuristic.

Snapshot declarations have this shape:

```json
"values": [[8, ["alive", "draining"]], [21, ["zone-a", "zone-b"]]]
```

The outer array is ordered by field index. In a declared field, every non-null
cell is a non-negative integer reference into that field's values. In an
undeclared field, cells retain their public JSON value, including numeric
integers. JSON `null` is never dictionary-coded.

## Snapshot

The complete snapshot shape is:

```json
{
  "schema": "hosts.v3",
  "revision": 42,
  "scope": ["cdn2"],
  "catalog": ["cdn2", "cdn3"],
  "dictionaries": {
    "fields": ["status", "epoch"],
    "entities": ["agent-1", "agent-2"],
    "values": [[0, ["alive"]]]
  },
  "columns": [
    [0, 0],
    [1, 1]
  ]
}
```

The example abbreviates `fields` and `columns`; production output always has
all 24. Column `n` corresponds to field `n`, and every column has exactly one
cell for every initial entity. `dictionaries.values` is optional. JSON `null`
is an explicit cell value.

The cluster catalog is collected from the complete host input before scope is
applied. Scope and catalog are deduplicated and sorted. Only selected hosts are
placed in entity and column arrays.

## Delta

Every delta has this shape:

```json
{
  "schema": "hosts.v3",
  "base_revision": 42,
  "revision": 45,
  "values": [[8, ["draining"]]],
  "add": [[2, "agent-3", ["agent-3", 1]]],
  "remove": [0],
  "columns": [[8, [1], [1]]],
  "unset": [[21, [1]]],
  "catalog": ["cdn2", "cdn3"]
}
```

The example abbreviates an added row; each real added row contains 24 values.

- `add` entries are `[entity_index, agent_id, complete_field_values]`.
- `remove` contains tombstoned entity indexes.
- `columns` entries are `[field_index, entity_indexes, changed_values]`.
- `unset` entries are `[field_index, entity_indexes]`.
- `values` entries are `[field_index, appended_values]`.
- `catalog`, when present, replaces the complete previous catalog.

`add`, `remove`, `columns`, and `unset` are always present and may be empty.
`values` and `catalog` are emitted only when needed. A delta extends only
fields declared by the snapshot, at most once per field. New non-null values
are appended in deterministic changed-entity then added-entity order and may
be referenced by additions and columns in that same delta. Existing indexes
never change. Field groups and entity indexes are ascending, giving
deterministic output independent of host input order.

Current `HostView` records always contain all 24 fields, so codec-produced
`unset` groups are empty. A transition to JSON `null` is emitted in `columns`
and remains a value. Only an explicit `unset` operation removes a field.

## Client Apply

A client first validates:

```text
delta.base_revision == applied_revision
delta.revision > delta.base_revision
```

It then appends additions, applies removals, and collects all `columns` and
`unset` operations by entity index. The client clones each affected entity
once, applies all collected field operations to that clone, and publishes the
new entity map. This preserves explicit nulls and bounds entity allocation by
the number of changed entities rather than changed fields.

Before decoding additions or columns, the client validates and applies value
dictionary extensions. Invalid references, duplicate declarations or
extensions, empty extensions, duplicate values, undeclared-field extensions,
and non-append-only data reject the complete delta without changing rows,
revision, catalog, dictionaries, or subscription invalidations.

An invalid revision chain must not be applied. Recovery is an authoritative
snapshot on a new connection and replaces all connection-local value
dictionaries.

## Producer State

The producer rejects a non-advancing revision before changing session state.
For an accepted delta it:

1. Builds the complete sorted catalog from the unscoped input.
2. Filters by the fixed session scope and sorts by `agent_id`.
3. Finds removals, existing-entity field changes, and additions.
4. Copies selected value dictionaries and appends required new values.
5. Encodes complete additions and deterministic sparse field groups.
6. Advances dictionaries, active rows, catalog, and revision together.

The frontend decoder keeps connection-local dictionaries, entity tombstones,
rows, scope, catalog, and revision in one state object. It validates a complete
delta before publishing the new state. Empty or off-scope deltas advance the
revision without replacing the visible Host collection; changed deltas retain
unchanged Host object references so memoized Host tiles do not rerender.
