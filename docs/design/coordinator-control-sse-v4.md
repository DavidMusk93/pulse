# Coordinator Control SSE V4

## Purpose

Coordinator Control SSE V4 replaces one blocking SSE connection per feature
with one non-blocking control connection per browser. Host state, EventBus
state, metric invalidation, storage health, and lightweight task state share
the control stream. Task output remains on a separate data stream.

The capacity contract is:

- 500 concurrent control streams and 50 concurrent output streams per
  Coordinator;
- initialization API P99 below 100 ms under that load;
- short-request P99 queueing increase below 50 ms relative to the unloaded
  baseline;
- no polling and no unbounded per-client event queue.

## Transport Architecture

The public port is owned by Netty:

```text
client
  -> Netty public transport
       -> /api/control/stream
       -> task output streams
       -> async streaming proxy
            -> loopback legacy business handlers
```

Netty event loops only parse requests, schedule writes, and bridge asynchronous
responses. Blocking task-owner routing runs on a bounded task-snapshot
executor with per-owner exponential failure backoff. Output UTF-8 encoding runs
on a separate bounded executor. Existing short-request handlers remain on a
loopback-only legacy server during migration and cannot consume public SSE
workers.

Every channel uses a 64 KiB low and 256 KiB high write-buffer watermark.
Output publishers request the next upstream batch only after the previous
Netty write completes.

## Control Stream

Clients subscribe with:

```text
GET /api/control/stream?clusters=<cluster>&agents=<agent-id,...>
```

The stream may emit:

- `hosts.snapshot`
- `hosts.delta`
- `eventbus.snapshot`
- `storage.health`
- `metric.invalidate`
- `task.snapshot`
- `ping`
- `control.resync_required`

Host frames retain the connection-local Host SSE V3 dictionary and revision
session. `task.snapshot` is metadata-only: every running output entry omits
`output` and contains an `output_stream_url`.

Initial Host, EventBus, storage, and metric frames never wait for remote task
routing. Task snapshots follow asynchronously. A remote owner failure retains
the last good task snapshot and emits no false local-empty replacement. Host V3
dictionary selection is computed once per revision and scope; each connection
forks independent mutable delta state from that template.

The event `id` is a globally monotonic control cursor. It orders events but is
not a replay-log position. Reconnection starts from authoritative state.

## Slow Clients

The server never builds an unbounded per-client queue. When a channel crosses
its high watermark, new control updates are coalesced by discarding
intermediate client-local state. After the channel becomes writable, the
server emits:

```text
event: control.resync_required
data: {"reason":"slow_client", ...}
```

It then resets the Host V3 session and sends authoritative snapshots before
resuming deltas. A client must treat the resync marker as invalidation of all
connection-local revision and dictionary state.

Output streams suspend one pending write on the Netty writability event. They
do not poll. A channel that remains unwritable for 30 seconds is closed and can
resume from its UTF-8 byte cursor.

## Output Streams

Completion output:

```text
GET /api/agents/{agent}/tasks/completions/{task}/output_stream
```

Running output:

```text
GET /api/agents/{agent}/tasks/{task}/output_stream
```

Output event IDs and `offset` fields are UTF-8 byte offsets. Chunks end only at
a UTF-8 code-point boundary. `Last-Event-ID` takes precedence over the
`offset` query parameter, so EventSource reconnection requests only bytes not
yet applied.

Completion streams terminate with `completion.output_end`. Running streams
emit `task.output_cursor` and close; EventSource reconnects to observe later
append-only output. On the Agent, all pending `reply.task_output_append`
messages are queued before the terminal `reply.task_result`.

## Atomic Migration

The following legacy control endpoints return `410 Gone`:

- `/api/hosts/stream`
- `/api/eventbus/stream`
- `/api/metrics/stream`
- `/api/tasks/stream`
- `/api/agents/{agent}/tasks/stream`

There is no dual-write or long-term compatibility mode. The frontend owns one
`EventSource` for `/api/control/stream` and fans typed events into local panel
subscribers. Task output owns a separate EventSource only while output is
visible.

## Operational Gates

A rollout is complete only when raw evidence proves:

- local tests, frontend build, package build, and diff checks pass;
- 500 control plus 50 output streams meet both P99 limits;
- all retired endpoints return 410;
- the unified stream carries every lightweight event type;
- the deployed JAR SHA matches on every Coordinator;
- `pulse-coordinator.service` is active and its start time changed only on
  hosts whose artifact changed;
- production short-request and metrics latency remain within the capacity
  contract.
