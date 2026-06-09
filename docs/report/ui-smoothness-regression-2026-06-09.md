# UI smoothness regression verification

## Scope

- Page: coordinator host overview with `471` host tiles.
- URL under test: coordinator page through SSH tunnel.
- Browser: headless Chrome through Chrome DevTools Protocol.
- Tool: `tools/ui-smoothness-regression.mjs`.
- Test action: continuous full-page scroll for `6000ms`.

## Regression Method

Smoothness is verified by runtime browser data:

- `requestAnimationFrame` frame interval distribution.
- Long Task entries during the scroll window.
- Page scroll height, viewport height, and `.host-tile` count.

The default budget is:

- p95 frame interval should stay near one frame budget.
- Long Tasks should be zero in the scroll window.
- No frame should exceed `50ms` in the normal scrolling path.

## Evidence

### Before scroll-specific refresh deferral

```json
{
  "host_tiles": 471,
  "frame": {
    "avg_ms": 17,
    "p95_ms": 25.1,
    "max_ms": 1049.9,
    "over_24ms": 50,
    "over_50ms": 1
  },
  "long_tasks": {
    "count": 2,
    "max_ms": 979,
    "total_ms": 1061
  }
}
```

This matches the user-visible jank report. A `~1s` Long Task during scroll is unacceptable.

### After host tile paint containment

```json
{
  "host_tiles": 471,
  "frame": {
    "avg_ms": 11.5,
    "p95_ms": 16.7,
    "max_ms": 1108.3,
    "over_24ms": 8,
    "over_50ms": 1
  },
  "long_tasks": {
    "count": 1,
    "max_ms": 1099,
    "total_ms": 1099
  }
}
```

Paint containment improved steady frame rate, but one large Long Task remained.

### After deferring `/api/hosts` commits while scrolling

```json
{
  "host_tiles": 471,
  "frame": {
    "avg_ms": 8.9,
    "p95_ms": 15.8,
    "max_ms": 41.7,
    "over_24ms": 5,
    "over_50ms": 0
  },
  "long_tasks": {
    "count": 0,
    "max_ms": 0,
    "total_ms": 0
  }
}
```

This passes the smoothness target for the current large-list UI.

## Root Cause

The page was not a backend ANR. It was frontend main-thread jank caused by two factors:

- Hundreds of host tiles used expensive per-card visual effects during scroll.
- The 5s `/api/hosts` refresh could commit a full `471`-host React state update while the user was scrolling.

## Fix

- Host tiles now use `contain: layout paint` and `content-visibility: auto`.
- Per-tile `backdrop-filter` was removed.
- `/api/hosts` refresh now caches data while scrolling and applies the state update after scroll idle.
- Host detail selection had already been changed to draft selection plus explicit `应用 Host`, preventing chart redraw on every click.

## Follow-up Guardrail

Use this command before shipping UI changes that affect the overview page:

```bash
npm run perf:smoothness -- --url http://127.0.0.1:18084/
```

Any regression with Long Tasks during the scroll window should block the change until fixed.
