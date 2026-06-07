# Local Metric Storage Testing Notes

## Wall-clock retention and fixed fixtures

- `AsyncLocalMetricStorage` runs retention cleanup from the writer thread using wall-clock time.
- Tests that use old fixed timestamps, for example `1710000000000`, can be deleted immediately when retention is configured with a short window.
- Prefer one of these patterns:
  - Use a retention window large enough to cover fixed fixture timestamps.
  - Use current wall-clock timestamps when testing storage open/query behavior.
  - Use intentionally old timestamps only when the test is asserting TTL deletion.

## Why this matters

- Production retention should be based on real time, not fixture time.
- Unit tests must make the retention horizon explicit, otherwise a successful cleanup can look like a lost write.

## Query budget and step correctness

- Query budget estimates are conservative because real heartbeat samples can be sparse.
- Do not use an estimated point budget to silently increase the actual SQL grouping step for every request.
- Increasing the actual step too early can merge distinct sparse samples and change query results.
- Keep the requested step as the default execution step; when the result is truncated, return a larger `suggested_step_ms` so the frontend can explicitly re-query or show a degraded/truncated state.
- Regression tests should cover both cases:
  - Small sparse windows keep the requested step and preserve separate points.
  - Truncated results return `truncated=true` and a larger `suggested_step_ms`.

## Heartbeat HTTP fixtures

- `CoordinatorHttpServer` heartbeat tests should put agent runtime fields inside `messages[].payload` for `state.heartbeat` messages.
- Do not rely on top-level `state` to populate metric columns such as `agent_thread_count`; it may still leave the storage metric value at the default and make TopN tests pass only because of stable label tie-break ordering.
- When testing TopN or aggregate behavior through HTTP, assert both ordering and numeric values so fixture mistakes cannot produce a false green result.
