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
