# Pulse Metrics EventBus

## Objective

Pulse is a metrics collection, event generation, policy gate, and delivery platform. Event generation and delivery are separate state machines:

```text
Input -> Source plugin -> Event envelope -> active incident state
      -> Route filter -> Gate plugin -> Delivery batch -> Sink plugin
```

A metric threshold is one source type. A Lark custom-bot webhook is one sink type. Neither is embedded into the EventBus core.

## Stable Contracts

### Event type

An event type defines a stable semantic ID, display name, description, default severity, and enable state. Sources emit that ID; routes filter it.

### Source

A source binds a `plugin_type`, an event type, and plugin configuration. Built-ins:

- `metric_threshold`: evaluates numeric collections in accepted heartbeat state.
- `webhook_event`: accepts external `firing` and `resolved` event payloads through the source ingress API.

The default disk source reads `disks[]`, compares `io_util_pct > 95`, and requires `saturated_for_ms >= 10000`.

### Event envelope

Every generated event contains:

- `event_id`: identity of this firing or resolved transition;
- `incident_id`: stable identity shared by firing and resolved transitions;
- `event_type`, `source_id`, `subject`, `agent_id`;
- `severity`, `status`, `observed_at_ms`, `summary`;
- structured attributes.

Firing events enter the active incident map. Resolved events remove the same incident. Every transition is also written to `host_event`.

### Route and gate

A route filters by source IDs and event types, then fans a batch out to one or more sinks. Gate state is isolated per `route_id::sink_id`, so one failed destination does not advance another destination.

The built-in `periodic_digest` gate:

- has a hard minimum interval of 5 minutes;
- defaults to 15 minutes;
- publishes active incidents once per interval;
- publishes one recovery digest after the final active incident resolves;
- advances attempt state only when a delivery is actually attempted.

### Sink

A sink binds a `plugin_type` and private configuration. The built-in `lark_webhook` sink:

- calls a Web-configured Lark custom-bot webhook directly;
- supports optional HMAC-SHA256 signing;
- renders an `interactive` card with severity color, event facts, folding, and optional detail link;
- enforces the documented 20 KB body limit;
- treats HTTP success and Lark `code == 0` as separate success conditions;
- records delivery attempt, success, failure, format, and event count.

Webhook URLs and signing secrets are persisted but returned by the API only as `********`.
Every completed delivery also appends an `eventbus.delivery` event to `host_event`, preserving route, sink, delivery ID, event count, recovery flag, actual format, and failure reason without storing message bodies or credentials.

## Plugin SPI

Extensions implement one of:

```java
EventPlugin.Source
EventPlugin.Gate
EventPlugin.Sink
```

Each plugin publishes a descriptor containing a stable type, kind, description, and typed configuration fields. The Web UI renders these fields without knowing plugin implementation classes.

Extension JARs register implementations through Java `ServiceLoader`. Source plugins declare supported input channels; current channels are `heartbeat` and `webhook`.

## APIs

```text
GET  /api/eventbus
PUT  /api/eventbus/config
POST /api/eventbus/sources/{source_id}/events
POST /api/eventbus/sinks/{sink_id}/test
```

Webhook source requests require:

```text
x-pulse-event-token: <source ingest_token>
```

The sink test endpoint is strict: it tests the configured sink itself and returns upstream delivery failure as HTTP 502. It does not silently fall back to another sink.

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
