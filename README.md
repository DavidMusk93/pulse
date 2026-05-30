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

This project uses a lightweight Java runtime. The coordinator is implemented with the JDK HTTP server and keeps framework choices minimal.

```bash
mvn test
```

Set up the local development environment without sudo:

```bash
docs/script/setup-local-dev.sh
```

Build and start the coordinator:

```bash
mvn package
java -jar target/pulse-0.1.0-SNAPSHOT.jar
```

Optional runtime variables:

```bash
PULSE_PORT=8080
PULSE_COORDINATOR_ID=coordinator-local
PULSE_BIND_HOST=127.0.0.1
```

Start the agent:

```bash
PULSE_COORDINATOR_URLS=http://127.0.0.1:8080 \
  java -cp target/pulse-0.1.0-SNAPSHOT.jar com.bytedance.pulse.PulseAgentApp
```

## Coordinator Runtime

- `POST /heartbeat`: accepts single agent heartbeats or group batch heartbeats.
- `POST /heartbeat_fwd`: accepts peer coordinator forwarded state messages.
- `GET /api/hosts`: returns current host state as JSON.
- `GET /hosts`: renders host information as Windows Phone style tiles.

## cdn_new Deployment

Cluster deployment follows the auto-ops central runtime contract in `/Users/bytedance/Documents/gitlab/olap-toolbox/docs/skill/auto-ops.md`.

- Install root: `/data24/otf/pulse`
- Coordinator bind: IPv6 `::`
- Coordinator port: `9966`
- Coordinator service: `pulse-coordinator.service`
- Agent service: `pulse-agent.service`
- Scripts: `docs/script/pulse-cdn-new-*.sh`

Coordinator runs with:

```bash
PULSE_BIND_HOST=::
PULSE_PORT=9966
java -jar /data24/otf/pulse/bin/pulse.jar
```

Agent runs with:

```bash
PULSE_COORDINATOR_URLS=http://[coordinator-ipv6]:9966
java -cp /data24/otf/pulse/bin/pulse.jar com.bytedance.pulse.PulseAgentApp
```

Example heartbeat:

```bash
curl -X POST http://127.0.0.1:8080/heartbeat \
  -H 'content-type: application/json' \
  -d '{
    "agent_id": "agent-1",
    "epoch": 1,
    "seq": 42,
    "ttl_ms": 15000,
    "messages": [
      {
        "message_id": "msg-agent-1-42",
        "type": "state.heartbeat",
        "version": 1,
        "payload": {
          "host": "host-a",
          "ip": "10.0.0.1",
          "zone": "az-a",
          "role": "worker",
          "load": "0.42"
        }
      }
    ]
  }'
```
