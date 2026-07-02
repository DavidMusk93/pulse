# Task Output HTTP Contract

## Decision

Pulse keeps final task completion output lossless in coordinator memory, but HTTP task snapshots must stay compact.

- `GET /api/agents/{agent_id}/tasks` returns completion metadata and at most a small output preview.
- Large completion entries set `output_inline=false`, `output_preview=true`, `output_chars`, `output_bytes`, and `output_stream_url`.
- `GET /api/agents/{agent_id}/tasks/completions/{task_id}/output_stream` is the primary full-output read path. It streams append-only `completion.output_chunk` SSE events with `offset`, `next_offset`, and `chunk`.
- SSE event ids are the next output offset. Browser reconnect via `Last-Event-ID` resumes from that offset.
- `GET /api/agents/{agent_id}/tasks/completions/{task_id}/output` may remain as a compatibility fallback, but the UI must not use it as the primary path.
- `GET /api/agents/{agent_id}/tasks/stream` and `GET /api/tasks/stream` use the same compact snapshot contract.

## Rationale

Lossless final output is required for operational correctness, but embedding multi-MiB output in every snapshot makes polling, SSE diffing, cross-coordinator routing, and browser rendering fragile. The snapshot is a state index. Full output is append-only data, so transfer must be incremental by offset rather than repeatedly fetching the whole value.

## UI Behavior

The Run UI subscribes to `output_stream_url` when the selected completion is not inline and appends chunks as they arrive. The viewer may render only a bounded prefix for very large output to keep the browser responsive, while copy operations use the fully accumulated output.
