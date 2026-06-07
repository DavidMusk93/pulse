# Coordinator Timeseries Agent Loop

## Goal

Complete `docs/design/coordinator-local-storage-timeseries.md` to a production-usable state.

## Loop Condition

Do not stop after listing next steps. Continue the loop until one of these conditions is true:

- The implementation satisfies the current design doc's first-version requirements.
- A blocker requires a user decision, credential, or unavailable external system.
- A deploy or verification step fails in a way that cannot be safely retried without changing production state.

## Per-Iteration Contract

Each iteration must produce evidence:

- Pick the highest-impact incomplete item from the design gap list.
- Implement the smallest coherent production change.
- Run focused tests and then full relevant tests.
- Commit the change with a specific message.
- Deploy when the change affects production behavior or static assets.
- Verify using clean `.tmp` scripts, not heredoc or long inline commands.
- Update `docs/report/coordinator-local-storage-timeseries-implementation-progress.md`.
- If a mistake, environment issue, or repeatable pitfall appears, write it to `docs/debug`.

## Current Exit Criteria

The loop is not complete until these are done:

- Metrics frontend supports live invalidation merge, range cache, and compensation query.
- Metrics frontend separates QueryController, SeriesStore, RenderScheduler, and ChartAdapter enough to allow ECharts/uPlot replacement.
- Metrics backend applies step aggregation, series budget, and top-N selection consistently for heartbeat, tide worker, and group leader metrics.
- Metrics SSE supports resumable delivery semantics or clearly bounded reconnect compensation.
- The latest coordinator build is deployed and verified on the `cdn_new` coordinator set.

## Reporting

- Report concise progress after each meaningful action.
- Report failures with root cause, fix, and documentation path.
- Final handoff only after the loop reaches an exit condition.
