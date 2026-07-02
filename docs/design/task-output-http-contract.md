# Task Output HTTP Contract

## Decision

Pulse keeps final task completion output lossless in coordinator memory, but HTTP task snapshots must stay compact.

- `GET /api/agents/{agent_id}/tasks` returns completion metadata and at most a small output preview.
- Large completion entries set `output_inline=false`, `output_preview=true`, `output_chars`, `output_bytes`, and `output_url`.
- `GET /api/agents/{agent_id}/tasks/completions/{task_id}/output` returns the full completion output.
- `GET /api/agents/{agent_id}/tasks/stream` and `GET /api/tasks/stream` use the same compact snapshot contract.

## Rationale

Lossless final output is required for operational correctness, but embedding multi-MiB output in every snapshot makes polling, SSE diffing, cross-coordinator routing, and browser rendering fragile. The snapshot is a state index. Full output retrieval is an explicit, per-task read.

## UI Behavior

The Run UI loads full output lazily when the selected completion is not inline. The viewer may render only a bounded prefix for very large output to keep the browser responsive, while copy operations use the full loaded output.
