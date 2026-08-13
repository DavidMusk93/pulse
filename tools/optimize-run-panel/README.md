# Optimize Run Panel

Local, SSE-only review surface for a `ce-optimize` run. It reads the run spec,
experiment log, optional active worktree, and immutable evaluator without
calling Coordinator or modifying production assets.

```bash
OPTIMIZE_RUN_ROOT=.context/compound-engineering/ce-optimize/host-sse-core-v3-value-refs \
node tools/optimize-run-panel/server.mjs
```

Optional environment:

```text
OPTIMIZE_REPO_ROOT       repository root, defaults to cwd
OPTIMIZE_WORKTREE        active experiment worktree; omit after integration
OPTIMIZE_RUN_UI_BIND     defaults to 127.0.0.1
OPTIMIZE_RUN_UI_PORT     defaults to 19876
```

The panel persists ad-hoc benchmark state by `run_id` and source commit under
`.tmp/data/optimize-run-panel/`. An older run or commit can never override the
current experiment log's `best` metrics.

Benchmark output follows the lossless Run UI contract:

- stdout and stderr chunks are reassembled into complete logical lines;
- no tail, preview, byte, or line-count truncation is allowed;
- live lines arrive through `run.log` SSE deltas;
- reconnect recovery loads the complete log once from `/api/log`;
- only the visible line window is mounted in the DOM;
- logical lines never soft-wrap and overflow scrolls inside the code block;
- search and copy operate on the complete line collection.

Verification:

```bash
node --test \
  tools/optimize-run-panel/model.test.mjs \
  tools/optimize-run-panel/log-model.test.mjs
node --check tools/optimize-run-panel/server.mjs
node --check tools/optimize-run-panel/public/app.js
```
