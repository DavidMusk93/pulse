# UI smoothness regression

## Goal

UI smoothness must be verified by runtime evidence, not by visual impression only. The Pulse coordinator page has two high-risk areas:

- full-page scrolling with hundreds of host cards
- metrics interactions that may trigger chart redraws and API queries

The regression check uses Chrome DevTools Protocol to launch a real browser, scroll the page, and collect:

- `requestAnimationFrame` frame interval distribution
- Long Task entries from `PerformanceObserver`
- page height, viewport height, and host tile count

## Command

From the frontend directory:

```bash
npm run perf:smoothness -- --url http://127.0.0.1:9966/
```

Against a coordinator through SSH tunnel:

```bash
npm run perf:smoothness -- --url http://127.0.0.1:18081/
```

Direct script form:

```bash
node tools/ui-smoothness-regression.mjs --url http://127.0.0.1:9966/ --duration-ms 8000
```

## Budgets

Default budgets:

- `frame.p95_ms <= 24`
- `long_tasks.count == 0`
- `long_tasks.max_ms <= 80`

These budgets are intentionally strict enough to catch obvious UI regression while allowing occasional rendering variance in CI or remote-browser environments.

## Output

The script prints JSON:

```json
{
  "frame": {
    "count": 480,
    "avg_ms": 16.7,
    "p95_ms": 17.2,
    "max_ms": 32.1,
    "over_24ms": 2,
    "over_50ms": 0
  },
  "long_tasks": {
    "count": 0,
    "max_ms": 0,
    "total_ms": 0
  }
}
```

## Required Fix Policy

If the regression check fails:

- First inspect whether a recent UI change added full-list rendering, responsive measurement, heavy filters, chart redraws, or CSS effects on many nodes.
- Prefer `content-visibility`, virtualization, deferred state, and explicit apply actions over implicit live recomputation.
- Avoid expensive per-card effects such as `backdrop-filter` on hundreds of tiles.
- Do not accept “looks fine on my machine” as evidence for large-list UI.

## Current Scroll Optimization

The host tile grid uses:

- `content-visibility: auto`
- `contain: layout paint`
- `contain-intrinsic-size`

The previous per-tile `backdrop-filter` was removed because blur on hundreds of cards is expensive during scroll and can cause visible jank.
