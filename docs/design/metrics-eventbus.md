# Pulse Metrics EventBus

## Objective

Pulse is a metrics collection, event generation, policy gate, and delivery platform. Event generation and delivery are separate state machines:

```text
Agent Source plugin
  -> PulseMessage(type=event.publish)
  -> direct/group heartbeat transport
  -> coordinator peer forward
  -> EventBus ingress -> active incident state
  -> Route filter -> Gate plugin -> Delivery batch -> Sink plugin
```

A metric threshold is one agent source. A Lark custom-bot webhook is one sink. Neither is embedded into the EventBus core or implemented as a coordinator-side scan of heartbeat state.

## Stable Contracts

### Event type

An event type defines a stable semantic ID, display name, description, default severity, and enable state. Sources emit that ID; routes filter it.

### Source

A source binds a transport adapter, a stable source ID, and an event type. Built-ins:

- `agent_disk_io`: configures the Agent disk IO producer and consumes its heartbeat event envelopes.
- `pulse_message`: consumes `event.publish` messages transported by the existing heartbeat path.
- `webhook_event`: accepts external `firing` and `resolved` event payloads through the source ingress API.

Agent-side producers implement `AgentEventSourcePlugin` and are loaded through `ServiceLoader`. The default disk producer evaluates the locally collected `disks[]`, emits after `io_util_pct > threshold_pct` continuously for `sustain_seconds`, and emits one resolved message after recovery. Its default Source configuration is:

```text
threshold_pct    = 95
sustain_seconds  = 10
```

These are Source fields in the Web UI, not hidden deployment parameters. Control-plane durations are expressed in seconds; Agent sampling converts them to milliseconds only at the execution boundary. The Coordinator derives a stable generation from the active EventBus Source configuration and sends `cmd.event_source_config` in every heartbeat response. Agents apply only a changed generation and update the metric sampler and event state machine from one configuration snapshot. Disabling the Source or changing either field clears prior continuous-duration and incident state, so samples accumulated under an old policy cannot trigger under a new policy.

The numeric samples remain in `state.heartbeat` for time-series storage; only event transitions use `event.publish`.

`event.publish` is marked urgent, so group heartbeat collection flushes it without waiting for the normal periodic batch. Peer forwarding preserves both `state.*` and `event.publish` messages. Command and reply messages retain their existing behavior.

### Event envelope

Every generated event contains:

- `event_id`: identity of this firing or resolved transition;
- `incident_id`: stable identity shared by firing and resolved transitions;
- `event_type`, `source_id`, `subject`, `agent_id`;
- `severity`, `status`, `observed_at_ms`, `summary`;
- structured attributes.

Firing events enter the pending-delivery map. Each matching `route_id::sink_id` acknowledges the event independently. The event is removed immediately after every enabled matching target succeeds; a failed target remains pending and retries without redelivering to targets that already acknowledged it. Resolved events remove an event that has not yet been delivered. Every transition is also written to `host_event`.

### Route and gate

A route filters by source IDs and event types, then fans a batch out to one or more sinks. Gate state is isolated per `route_id::sink_id`, so one failed destination does not advance another destination.

The built-in `periodic_digest` gate:

- has a hard minimum interval of 5 minutes;
- defaults to 15 minutes;
- exposes `interval_seconds` in the control plane;
- publishes each pending event once to each target;
- retries only targets that have not acknowledged the event;
- advances attempt state only when a delivery is actually attempted.

### Sink

A sink binds a `plugin_type` and private configuration. The built-in `lark_webhook` sink:

- calls a Web-configured Lark custom-bot webhook directly;
- supports optional HMAC-SHA256 signing;
- renders an `interactive` card with severity color, `lark_md` sections, structured fact fields, folding, and an optional detail link;
- keeps events from multiple locations in one flat message and does not render cluster, area, or zone ownership;
- enforces the documented 20 KB body limit;
- treats HTTP success and Lark `code == 0` as separate success conditions;
- records delivery attempt, success, failure, format, and event count.

Webhook URLs and signing secrets are persisted but returned by the API only as `********`.
Every completed delivery also appends an `eventbus.delivery` event to `host_event`, preserving route, sink, delivery ID, event count, recovery flag, actual format, and failure reason without storing message bodies or credentials.

## Plugin SPI

Extensions implement one of:

```java
AgentEventSourcePlugin
EventPlugin.Source
EventPlugin.Gate
EventPlugin.Sink
```

Each plugin publishes a descriptor containing a stable type, kind, description, and typed configuration fields. The Web UI renders these fields without knowing plugin implementation classes. For a heartbeat-backed Agent Source, the Coordinator ingress descriptor mirrors the producer fields and passes their values through `cmd.event_source_config`; `pulse_message` remains only the transport adapter.

Extension JARs register implementations through Java `ServiceLoader`. `AgentEventSourcePlugin` runs beside metrics collection and returns normal `PulseMessage` instances. Coordinator `EventPlugin.Source` implementations are ingress adapters; current channels are `pulse_message` and `webhook`.

## APIs

```text
GET  /api/eventbus
GET  /api/eventbus/stream
PUT  /api/eventbus/config
POST /api/eventbus/sources/{source_id}/events
POST /api/eventbus/sinks/{sink_id}/test
```

Webhook source requests require:

```text
x-pulse-event-token: <source ingest_token>
```

The sink test endpoint is strict: it tests the configured sink itself and returns upstream delivery failure as HTTP 502. It does not silently fall back to another sink.

`/api/eventbus/stream` is the only live-update path for the EventBus UI. It sends `eventbus.snapshot` SSE events when the persisted EventBus revision changes. The frontend updates only the EventBus component state; it does not poll or refresh the full page.

`/api/hosts/stream` also remains SSE-only, but heartbeat fan-in must not become render fan-out. The Coordinator coalesces host revisions into at most one `hosts.snapshot` every five seconds by default (`PULSE_HOST_SSE_MIN_INTERVAL_SECONDS`). The initial snapshot is immediate. The frontend ignores identical snapshots, never restores scroll position after a live update, and isolates the Metrics panel from heartbeat-only host changes. This keeps control-plane feedback stable without polling or full-page refresh behavior.

After the initial `hosts.snapshot`, the Coordinator emits `hosts.delta` with recursive merge patches:

```json
{
  "upserts": [
    {
      "agent_id": "agent-1",
      "seq": 42,
      "load": "0.84",
      "state": {"load": "0.84"}
    }
  ],
  "removed": ["agent-2"]
}
```

Stable host and nested state fields are omitted. The frontend applies patches by stable `agent_id`, preserves object identity for unchanged hosts, and therefore limits React updates to changed clusters and tiles. Cluster and host surfaces use `content-visibility: auto` with intrinsic sizing so offscreen content skips layout and paint while preserving scroll geometry.

Every frontend SSE consumer records UTF-8 `MessageEvent.data` bytes and event count in a shared external store. The top status area shows rolling bytes per second, events per second, and cumulative payload bytes. Traffic state is rendered once per animation frame and expires from the rolling window with event-driven timers; it does not poll.

## Persistence and Security

The EventBus state file contains configuration plus per-route/sink delivery status. Updates use a temporary file and atomic rename. On POSIX filesystems the file mode is `0600`.

The API masks every plugin field declared `secret`. Sending the mask back during an edit preserves the stored value. Logs contain IDs, counts, phases, and errors, but never webhook URLs, tokens, signing secrets, or message bodies.

## Runtime Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PULSE_EVENTBUS_CONFIG_PATH` | metric DB directory plus `pulse-eventbus.json` | EventBus state |

If neither the explicit EventBus path nor metric storage path is configured, EventBus APIs remain disabled.

## Lark Custom Bot Constraints

The implementation follows the Lark custom bot contract:

- HTTPS `POST` with JSON;
- official webhook hosts and `/open-apis/bot/v2/hook/` path only;
- 100 requests/minute and 5 requests/second per tenant and bot;
- request body no larger than 20 KB;
- optional signature: Base64(HMAC-SHA256(key=`timestamp + "\n" + secret`, data=empty));
- rich card links are outbound URL actions only.

Periodic gates keep normal alert traffic far below Lark's rate limit. Additional retry/backoff gate plugins can be added without changing source or sink code.
