# Hosts SSE V3 Core Codec

## Scope

V3 is a connection-local JSON codec experiment. It does not change the HTTP
endpoint, V1, V2, or the frontend. The fixed contract is intended to reduce
wire bytes while remaining inspectable and allowing a client to clone each
changed entity at most once per delta.

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

There are no value dictionaries, bitmaps, numeric coercions, derived fields,
dependencies, or opaque abbreviated keys.

## Snapshot

The complete snapshot shape is:

```json
{
  "schema": "hosts.v3",
  "revision": 42,
  "scope": ["cdn2"],
  "catalog": ["cdn2", "cdn3"],
  "dictionaries": {
    "fields": ["agent_id", "epoch"],
    "entities": ["agent-1"]
  },
  "columns": [
    ["agent-1"],
    [1]
  ]
}
```

The example abbreviates `fields` and `columns`; production output always has
all 24. Column `n` corresponds to field `n`, and every column has exactly one
cell for every initial entity. JSON `null` is an explicit cell value.

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
  "add": [[2, "agent-3", ["agent-3", 1]]],
  "remove": [0],
  "columns": [[2, [1], [99]]],
  "unset": [[21, [1]]],
  "catalog": ["cdn2", "cdn3"]
}
```

The example abbreviates an added row; each real added row contains 24 values.

- `add` entries are `[entity_index, agent_id, complete_field_values]`.
- `remove` contains tombstoned entity indexes.
- `columns` entries are `[field_index, entity_indexes, changed_values]`.
- `unset` entries are `[field_index, entity_indexes]`.
- `catalog`, when present, replaces the complete previous catalog.

`add`, `remove`, `columns`, and `unset` are always present and may be empty.
`catalog` is emitted only when it changes. Field groups and entity indexes are
ascending, giving deterministic output independent of host input order.

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

An invalid revision chain must not be applied. Recovery is an authoritative
snapshot on a new connection.

## Producer State

The producer rejects a non-advancing revision before changing session state.
For an accepted delta it:

1. Builds the complete sorted catalog from the unscoped input.
2. Filters by the fixed session scope and sorts by `agent_id`.
3. Finds removals, existing-entity field changes, and additions.
4. Builds deterministic sparse field groups.
5. Advances dictionaries, active rows, catalog, and revision.

This core experiment deliberately has no HTTP or frontend integration.
