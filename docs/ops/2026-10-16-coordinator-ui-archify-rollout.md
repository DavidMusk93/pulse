# Coordinator UI Archify modernization and card runtime hydration rollout

## Context

- Operation date: 2026-10-16
- Request: modernize the Coordinator frontend UI referencing `https://archify.si/zh`, restore missing host card runtime details, refine page and card layout hierarchy, deploy to Coordinator nodes, and validate rendered UI in the browser.
- Delivery commit:
  `1aae75a Modernize Coordinator UI in Archify style and hydrate host card runtime`
- Artifact:
  `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90`
- Previous production SHA-256:
  `a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115`
- Scope: 3 Coordinators from `docs/ops/coordinators.hosts`
- Evidence root:
  `.tmp/auto-ops/ui-archify-modernization-20261016/`
- Task sync: not applicable; no `docs/task/` file changed.
- Agent rollout: not applicable; only `pulse-coordinator.service` was targeted.

## Change

- Added `HostCardProjection` and extended `GET /api/hosts?clusters=<scope>&view=card` on `LegacyCoordinatorHttpServer` to return a bounded per-host runtime summary (`agent_id`, `agent_rss_kb`, `agent_thread_count`, `agent_collect_ms`, `agent_send_ms`, `leader_collect_ms`, `disks: {count, max_io_util_pct, hottest_device, saturated}`, `tide_workers`, `async_tasks`) without bloating the columnar Host SSE V3 stream.
- Hydrated `displayHosts` in `src/main/frontend/src/main.tsx` for the active cluster scope while preserving object reference identity via a `WeakMap` cache.
- Modernized the Coordinator page and cards in an Archify-inspired warm technical paper aesthetic (`#f5f6f3` canvas, crisp `#ffffff`/`#fbfbf9` plates, `1px` hairline borders, monospace kickers, semantic node chips):
  - Sticky frosted top navigation bar (`.pulse-topbar`) with live SSE status and active cluster badge.
  - Hero section with numbered hairline capability grid (`01 任务` .. `04 告警`) and 2x2 hairline metric cards.
  - Metrics overview restructured into two semantic receipt strips (`拓扑 · TOPOLOGY` and `存储 · STORAGE`) replacing the uneven 11-card wall.
  - Quick cluster pill switcher (`.host-cluster-pills`) and per-cluster topology receipt strip (`Leader / Follower / Direct / Groups / AVG / MAX / Area / Role / IO 饱和`).
  - Redesigned `.host-tile` cards (`aspect-ratio: auto`, semantic top accent border by `group_mode` / `status`, 2x2 hairline metric grid for `Area`, `5min AVG`, `磁盘 IO`, `Agent 运行时`, contract metadata `seq`/`TTL`, and CPU-sorted single-column Tide worker cards).

## Local Gates

- `npm --prefix src/main/frontend run build`: passed and regenerated `src/main/resources/static/pulse-hosts.{css,js}`.
- `node --test tools/host-cluster-scope.test.mjs tools/host-stream-v3.test.mjs`: passed (15/15).
- `mvn -q test`: passed (142/142, including `hostsEndpointSupportsClusterFilterAndCompactCardView` and `hostsPageRendersFlatSquareChineseHeartbeatConsole`).
- `mvn -q -DskipTests package`: passed.
- `git diff --check`: passed.

## Browser Debug Evidence

Created a local SSH port forward `127.0.0.1:19967 -> fdbd:dc05:11:634::45:[::1]:9966` and inspected `http://127.0.0.1:19967/hosts` at `1728x1117` (`deviceScaleFactor=2`) using Chrome CDP (`inspect-ui.mjs`).

Baseline before changes:

```text
viewport=1728x1117, scrollW=1728
firstTileRect=249x249 (aspect-ratio: 1/1)
firstTileScroll={clientH: 168, scrollH: 201} (clipped by 33px)
workerCardCount=0 (host.state omitted from SSE V3 without card hydration)
```

After rollout (`final-browser.json`):

```text
viewport=1728x1117, scrollW=1728
topbarRect=1632x48
heroRect=1632x330, capGridRect=1005x80
metricsPanelRect=1632x786
receiptGroups=[353x96 (拓扑 · TOPOLOGY), 929x96 (存储 · STORAGE)]
eventbusCardRect=1586x114
clusterMetaStrip="LEADER 7 FOLLOWER 43 DIRECT 0 GROUPS 7 AVG 126.60 MAX 168.82 AREA gl · hl · yg ROLE cdn_new IO 饱和 3"
tileCount=50, workerCardCount=257, clippedTiles=0
leaderTileRect=388x650
leaderTileScroll={clientH: 545, scrollH: 545}
```

Captured screenshots verified zero text truncation, zero scroll clipping on standard 4-worker hosts, clear visual distinction between `LEADER` (emerald) and `FOLLOWER` (blue) cards, and full visibility of disk IO, Agent runtime, and Tide worker metrics.

## Deployment

Canary (`fdbd:dc05:11:634::45`) and full 3-Coordinator rollout via `docs/ops/deploy-coordinator.sh`:

| Host | Previous SHA-256 | New SHA-256 | Result | Service |
| --- | --- | --- | --- | --- |
| `fdbd:dc05:11:634::45` | `a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115` | `942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90` | updated | `active` |
| `fdbd:dc05:13:10c::40` | `a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115` | `942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90` | updated | `active` |
| `fdbd:dc07:0:810::44` | `a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115` | `942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90` | updated | `active` |

Rollout summary: `total=3 ok=3 failed=0`.

Evidence files under `.tmp/auto-ops/ui-archify-modernization-20261016/`:

- `demand-coordinators.console.log`
- `canary.console.log`
- `canary/summary.json`
- `canary-browser.json`
- `rollout.console.log`
- `rollout/summary.json`
- `rollout/results.tsv`
- `final-browser.json`
- `final-verify.console.log`
- `final-verify/summary.json`
- `final-verify/results.tsv`

## Verification

Independent post-rollout verification across all 3 Coordinators (`final-verify.console.log`):

```text
RESULT host=fdbd:dc05:11:634::45 sha=942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90 coord_svc=active agent_svc=active hosts_http=200 css_http=200 js_http=200 card_http=200 card_count=50 legacy_http=410
RESULT host=fdbd:dc05:13:10c::40 sha=942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90 coord_svc=active agent_svc=active hosts_http=200 css_http=200 js_http=200 card_http=200 card_count=50 legacy_http=410
RESULT host=fdbd:dc07:0:810::44 sha=942d4ca73044c4eab2edbe69eb78d166b508cf62222efb42e9581b8e02cccd90 coord_svc=active agent_svc=active hosts_http=200 css_http=200 js_http=200 card_http=200 card_count=50 legacy_http=410
summary: total=3 ok=3 failed=0 elapsed=0s
```

## Rollback

Rollback target:

```text
SHA-256=a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115
```

To roll back, checkout commit `d97e0d7`, package `target/pulse-0.1.0-SNAPSHOT.jar`, verify its SHA-256 matches `a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115`, and run `docs/ops/deploy-coordinator.sh` against `docs/ops/coordinators.hosts` (`--max-hosts 3`).
