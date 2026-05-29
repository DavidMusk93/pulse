# Pulse

Pulse is a lightweight distributed heartbeat and message coordination system.

## Design Direction

- Keep the runtime small unless a framework becomes necessary.
- Use `POST /heartbeat` as the primary Agent -> Coordinator session.
- Keep Agent outbound-only; Agent does not listen on a port.
- Use heartbeat responses to deliver Coordinator -> Agent messages.
- Use `POST /heartbeat_fwd` only for Coordinator peer state forwarding.

## Concepts

- Agent: managed process that actively publishes heartbeats and replies to messages.
- Coordinator: node that receives heartbeats, coordinates messages, and forwards state to peers.
- Pulse Group: optional aggregation proxy for heartbeat batching and response forwarding.

## APIs

- `POST /heartbeat`: primary heartbeat and message channel.
- `POST /heartbeat_fwd`: Coordinator-to-peer state forwarding channel.

## Development

This project intentionally starts with a minimal Java and Maven skeleton. Runtime framework choices will be added only when necessary.

```bash
mvn test
```
