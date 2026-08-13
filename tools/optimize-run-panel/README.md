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

Verification:

```bash
node --test tools/optimize-run-panel/model.test.mjs
node --check tools/optimize-run-panel/server.mjs
node --check tools/optimize-run-panel/public/app.js
```
