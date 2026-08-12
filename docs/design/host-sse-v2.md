# Hosts SSE V2

## Compatibility

`GET /api/hosts/stream` remains the V1 stream. Without `v=2`, its snapshot is
the existing `HostView[]` and its deltas retain the existing shape.

V2 is negotiated explicitly:

```text
GET /api/hosts/stream?v=2&clusters=cdn2
```

The optional comma-separated `clusters` value is applied to `HostView` records
before summary conversion and JSON serialization. An omitted value means all
clusters.

## Snapshot

Every connection starts with an authoritative `hosts.snapshot`:

```json
{
  "schema": "hosts.v2",
  "revision": 42,
  "scope": ["cdn2"],
  "available_clusters": ["cdn2", "cdn3"],
  "hosts": []
}
```

`available_clusters` is the complete catalog for the selector. `hosts` contains
only records in `scope`. Each host is transparent JSON with exactly these
summary fields:

```text
agent_id, epoch, seq, ttl_ms, observed_at_ms, expire_at_ms,
last_observed_age_ms, heartbeat_confirmations, status, source,
coordinator_id, group_id, group_mode, leader_agent_id, leader_url,
group_size, group_size_limit, host, ip, cluster, area, zone, role, load
```

Only the nested `state` field is omitted. Task queues, runtime output, and other
detail remain available through the existing on-demand agent APIs and SSE
streams.

## Delta And Recovery

After the snapshot, `hosts.delta` carries recursive JSON merge patches:

```json
{
  "schema": "hosts.v2",
  "base_revision": 42,
  "revision": 45,
  "upserts": [{"agent_id": "agent-1", "seq": 43, "load": "0.84"}],
  "removed": ["agent-2"]
}
```

Global revisions may jump and a scoped delta may be empty. A client accepts a
delta only when `base_revision` equals its current revision and `revision` is
greater. Any mismatch closes the stream and reconnects for a new authoritative
snapshot. Recursive patch application preserves object identity for hosts that
were not changed.

## UI Scope

The host page uses one EventSource with `v=2` and the selected cluster query.
The first visit defaults to `All`; later user selections are stored under
`pulse.host-cluster-scope.v1`. Changing scope opens a new stream, whose initial
snapshot replaces the previous scope. No polling or periodic HTTP refresh is
used.
