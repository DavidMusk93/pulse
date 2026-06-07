# Coordinator UI Golden Ratio Deployment

## Summary

- Time: 2026-06-06 22:14 CST
- Runtime mode: `central-runtime`
- Runtime repository: `/Users/david/Documents/fleet-ops`
- Project repository: `/Users/david/Documents/projects/pulse`
- Scope: 3 coordinator hosts only
- Artifact root: `/Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-golden`
- Install root: `/data24/otf/pulse`
- Result: success

## Change

- Single-host task modal now uses compact golden-ratio width: `min(clamp(980px, 61.8vw, 1180px), calc(100vw - 32px))`.
- Single-host task shell now uses `minmax(300px, .618fr) minmax(0, 1fr)` and `height:min(720px, calc(100vh - 148px))`.
- Result header status chips now render horizontally with wrapping support.
- Cluster task modal keeps its existing larger layout.

## Local Validation

- `docs/script/setup-local-dev.sh`: success.
- `mvn package`: success.
- Tests: `40` run, `0` failures, `0` errors, `0` skipped.
- Jar SHA256: `eb031d20c4ab7172faff9d3347f9152f4df7d7162b109e8f0aa9713f4730555c`.
- Static JS SHA256: `fd5c0c290ddc1b37062b4d0d23b326bdc9b76daa5e66760be2c99ee235e9bbd1`.
- Static CSS SHA256: `4a29d08f938120f6b36827152f3a55ad622c28acf3712298a6b940d6580af9e1`.

## Scope

- `fdbd:dc05:11:634::45`
- `fdbd:dc05:13:10c::40`
- `fdbd:dc07:0:810::44`

Dry-run selected exactly these 3 hosts:

```text
1       fdbd:dc05:11:634::45
2       fdbd:dc05:13:10c::40
3       fdbd:dc07:0:810::44
# total=3
```

Access preflight:

```text
summary: total=3 ok=3 failed=0 elapsed=1s
```

## Deployment

Used a coordinator-only auto-ops callee:

- Replaced only `/data24/otf/pulse/bin/pulse.jar`.
- Restarted only `pulse-coordinator.service`.
- Did not rewrite agent env.
- Did not restart full agent fleet.

Canary:

```text
host=fdbd:dc05:11:634::45
VERIFY service=pulse-coordinator status=active
jar_sha=eb031d20c4ab7172faff9d3347f9152f4df7d7162b109e8f0aa9713f4730555c
summary: total=1 ok=1 failed=0 elapsed=6s
```

Full coordinator rollout:

```text
summary: total=3 ok=3 failed=0 elapsed=5s
```

## Verification

All 3 coordinators passed:

- `pulse-coordinator.service`: `active`.
- `/data24/otf/pulse/bin/pulse.jar`: SHA256 matched `eb031d20c4ab7172faff9d3347f9152f4df7d7162b109e8f0aa9713f4730555c`.
- `/assets/pulse-hosts.js`: contains `clamp(980px`.
- `/assets/pulse-hosts.css`: contains `height:min(720px`.
- `/assets/pulse-hosts.css`: contains `max-width:min(560px`.
- `/api/hosts`: `HOST_COUNT=471`.

## Residual Risk

- Browser-level visual confirmation was not run in this pass; verification used static resource keywords and coordinator HTTP checks.
- Because coordinator state is in memory, any later coordinator restart still needs normal agent heartbeat recovery.

## Follow-up: Viewport Golden Ratio

Time: 2026-06-06 22:37 CST.

Additional requirement:

- Single-host Run UI must keep both width and height at the golden-ratio scale relative to the main viewport.

Follow-up change:

- Single-host modal width changed from bounded `clamp(980px, 61.8vw, 1180px)` to direct `61.8vw` with viewport margin protection.
- Single-host modal content height changed from fixed `720px` cap to direct `61.8vh` with viewport margin protection.
- Sidebar/workspace internal split remains `0.618 : 1`.
- Cluster-run modal remains unchanged.

Local validation:

- `mvn package`: success.
- Tests: `40` run, `0` failures, `0` errors, `0` skipped.
- Jar SHA256: `ba0b6b4807606c68c653597b0bf75d37cf0e5c9a28216e0b3f8426c535bcfd45`.
- Static JS SHA256: `a61ac708952cd158172439100971cc399fd55a90868915a2d9c6edcf391f5caf`.
- Static CSS SHA256: `1b9d5783a35d8a9da5a231aa310822dac4aee3f13657858c85210ecde3036216`.

Auto-ops rollout:

- Dry-run: selected exactly the 3 coordinator hosts listed in this report.
- Canary: `fdbd:dc05:11:634::45`, `summary: total=1 ok=1 failed=0 elapsed=4s`.
- Full coordinator rollout: `summary: total=3 ok=3 failed=0 elapsed=4s`.

Final verification:

- All 3 coordinators: `pulse-coordinator.service` active.
- All 3 coordinators: jar SHA matched `ba0b6b4807606c68c653597b0bf75d37cf0e5c9a28216e0b3f8426c535bcfd45`.
- All 3 coordinators: `/assets/pulse-hosts.js` contains `min(61.8vw`.
- All 3 coordinators: `/assets/pulse-hosts.css` contains `height:min(61.8vh`.
- All 3 coordinators: `/api/hosts` returned `HOST_COUNT=471`.

## Follow-up: Centered Position

Time: 2026-06-06 22:41 CST.

Additional requirement:

- Single-host Run UI position must be centered relative to the main page.

Follow-up change:

- Task modal now uses Ant Design `centered`.
- Custom `.task-modal` top offset changed from `top: 12px` to `top: auto`.
- Width remains `61.8vw`.
- Height remains `61.8vh`.
- Cluster-run modal sizing remains unchanged.

Local validation:

- `mvn package`: success.
- Tests: `40` run, `0` failures, `0` errors, `0` skipped.
- Jar SHA256: `45edb6ff810576d1241a4f00dccfc65af972b006eab06418cfd5bc90e9ab9503`.
- Static JS SHA256: `2f0a7614028c69be2da67d40bd08ecc8be9e7afde2eaa3b3227211cbc804db69`.
- Static CSS SHA256: `424056baa0ec4a3888d9e4d7e177f53aefe020e829c6ee35a5b95493f4439645`.

Auto-ops rollout:

- Dry-run: selected exactly the 3 coordinator hosts listed in this report.
- Canary: `fdbd:dc05:11:634::45`, `summary: total=1 ok=1 failed=0 elapsed=4s`.
- Full coordinator rollout: `summary: total=3 ok=3 failed=0 elapsed=4s`.

Final verification:

- All 3 coordinators: `pulse-coordinator.service` active.
- All 3 coordinators: jar SHA matched `45edb6ff810576d1241a4f00dccfc65af972b006eab06418cfd5bc90e9ab9503`.
- All 3 coordinators: `/assets/pulse-hosts.js` contains `centered:!0`.
- All 3 coordinators: `/assets/pulse-hosts.css` contains `top:auto!important`.
- All 3 coordinators: `/api/hosts` reachable; observed `HOST_COUNT=460/461` during this verification window.

## Follow-up: Cluster Run Same Size

Time: 2026-06-06 23:16 CST.

Additional requirement:

- Cluster Run UI must keep the same size as Host Run UI.

Follow-up change:

- Cluster Run modal now uses the same width as Host Run modal: `min(61.8vw, calc(100vw - 32px))`.
- Removed the cluster-only task shell override that used `height:min(920px, ...)`.
- Removed the cluster-only wider column override that used `minmax(340px, 1fr) minmax(0, 1.618fr)`.
- Cluster Run and Host Run now share the same task shell height: `min(61.8vh, calc(100vh - 148px))`.
- Cluster Run and Host Run now share the same internal split: `minmax(300px, .618fr) minmax(0, 1fr)`.

Local validation:

- `mvn package`: success.
- Tests: `41` run, `0` failures, `0` errors, `0` skipped.
- Jar SHA256: `8638b12d4df4df1eb43f77be01779b757049830523f981a0fcbd7952da02a63d`.
- Static JS SHA256: `0b2901738ef837f16cb60e7b149d502d2e94f905d3ef77c63474bd868abb0d4d`.
- Static CSS SHA256: `8412a3650525877388de167134c99ffb70c499bdadcddc725ab326cc7aedda6a`.

Auto-ops rollout:

- Dry-run: selected exactly the 3 coordinator hosts listed in this report.
- Canary: `fdbd:dc05:11:634::45`, `summary: total=1 ok=1 failed=0 elapsed=3s`.
- Full coordinator rollout: `summary: total=3 ok=3 failed=0 elapsed=4s`.

Final verification:

- All 3 coordinators: `pulse-coordinator.service` active.
- All 3 coordinators: jar SHA matched `8638b12d4df4df1eb43f77be01779b757049830523f981a0fcbd7952da02a63d`.
- All 3 coordinators: `/assets/pulse-hosts.js` contains `61.8vw, calc`.
- All 3 coordinators: `/assets/pulse-hosts.js` does not contain `min(1480px`.
- All 3 coordinators: `/assets/pulse-hosts.css` does not contain `height:min(920px`.
- All 3 coordinators: `/assets/pulse-hosts.css` does not contain `minmax(340px`.
- All 3 coordinators: `/api/hosts` returned `HOST_COUNT=471`.
