# Metrics Event Fanout

## Goal

Pulse turns sustained host metrics into durable events and periodically fans active incidents out to registered destinations.
The first event rule detects a physical disk whose IO utilization remains above 95% for at least 10 seconds.

## Data Flow

```text
/proc/diskstats
  -> agent interval sample
  -> heartbeat disks[]
  -> coordinator disk_sample
  -> DiskIoEventDetector
  -> host_event
  -> active incident snapshot
  -> periodic FanoutService digest
  -> registered Lark chat
```

The boundaries are intentional:

- The agent computes utilization from adjacent `io_time_ms` samples. A first sample is only a baseline.
- The coordinator creates one `firing` event after the sustained threshold and one `resolved` event after recovery.
- `incident_id` is derived from agent, device, and saturation start time, so replay after restart is idempotent.
- Event persistence uses the existing bounded metric writer queue and never writes SQLite on a heartbeat handler thread.
- Fanout is periodic, not heartbeat-driven. A source has a minimum interval of 5 minutes and defaults to 15 minutes.
- An active incident is repeated once per source interval. Transition to zero active incidents produces one recovery digest.
- Every Lark send uses a source/time-bucket idempotency key.

## Disk Metric Contract

Each agent heartbeat can contain:

```json
{
  "disks": [
    {
      "device": "nvme0n1",
      "io_util_pct": 97.5,
      "sample_interval_ms": 5000,
      "busy_ms": 4875,
      "saturated_for_ms": 10000
    }
  ]
}
```

Pseudo devices and partitions are excluded. Device utilization is:

```text
min(delta io_time_ms, sample_interval_ms) / sample_interval_ms * 100
```

The coordinator exposes `disk.io_util_pct` and `disk.saturated_for_ms` through the existing metric catalog and range query.

## Fanout Registration

The frontend registers a `lark_chat` source with a human-readable group name and interval. The coordinator resolves the name once:

```text
bytedcli --json lark im chat-search --as <identity> --query <group> --page-all
```

The resolved `chat_id` is persisted and subsequent sends use:

```text
bytedcli --json lark im messages-send --as <identity> \
  --chat-id <chat_id> --text <digest> --idempotency-key <bucket_key>
```

REST endpoints:

- `GET /api/fanout/sources`
- `POST /api/fanout/sources`
- `DELETE /api/fanout/sources/{source_id}`

## Runtime Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PULSE_DISK_IO_THRESHOLD_PCT` | `95` | Agent and coordinator threshold |
| `PULSE_DISK_IO_SUSTAIN_MS` | `10000` | Coordinator sustained duration |
| `PULSE_FANOUT_CONFIG_PATH` | metric DB directory plus `pulse-fanout.json` | Source registry |
| `PULSE_BYTEDCLI_PATH` | `bytedcli` | CLI executable |
| `PULSE_BYTEDCLI_LARK_AS` | `user` | `user` or `bot` identity |
| `PULSE_BYTEDCLI_TIMEOUT_MS` | `30000` | Resolve/send timeout |

If neither a fanout config path nor metric DB path is configured, fanout remains disabled.
