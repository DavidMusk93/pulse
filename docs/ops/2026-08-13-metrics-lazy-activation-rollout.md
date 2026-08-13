# Metrics lazy activation rollout

## Context

- Operation date: 2026-08-13
- Goal: make the metrics panel options-first and issue no time-series query
  until the user explicitly starts it.
- Feature commit: `bb95764 Make metrics queries explicitly lazy`
- Deployment safety commit:
  `639c504 Make Coordinator deployments fail closed`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `96550e19e815092152a049eb58adadcbba1e12ec3ca4e551e208425fef376adb`
- Previous Coordinator SHA-256:
  `42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df`
- Inventory: `docs/ops/coordinators.hosts`
- Exact tag: `coordinators`
- Scope: 3 Coordinators, `--max-hosts 3`
- Evidence root:
  `.tmp/auto-ops/metrics-lazy-activation-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Behavior

The metrics panel now follows this contract:

```text
mount
  -> load catalog, storage health, and SSE
  -> show metric/scope/host/range controls
  -> zero query_range requests

explicit Start
  -> issue one query for the selected parameters
  -> activate the result only after a successful response

inactive result
  -> metric.invalidate does not issue compensation queries
```

Full queries and compensation patches carry query-generation, selection-key,
and compensation-sequence guards. These guards reject:

- stale full-query responses;
- `A -> B -> A` selection ABA responses;
- compensation from an old selection or generation;
- older same-generation compensation responses;
- late callbacks after unmount.

The metrics SSE connection remains stable across activation and selection
changes. Relevant invalidations received during the first load or a refresh
are buffered and compensated only after the full result commits.

## Local Validation

- Red proof:
  `tools/host-cluster-scope.test.mjs` initially failed because
  `activeQueryKey` and explicit activation did not exist.
- Frontend behavior/source contract: 7 tests passed.
- Host SSE V3 decoder: 7 tests passed.
- Combined Node suite: 14 tests passed.
- Vite production build passed.
- Full Maven suite: 129 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -q -DskipTests package` passed.
- `git diff --check` passed.
- Code review artifact:
  `/tmp/compound-engineering-501/ce-code-review/20260813-171330-a244a774`
- Final review verdict: `Ready to merge`; all discovered P0-P2 race findings
  were fixed.

The repository does not currently include a React DOM test harness. Runtime
state transitions are covered by source-contract tests, production bundle
assertions, controlled race review, full frontend build, and Java static asset
tests. Adding a DOM test runtime is deferred rather than adding a new
dependency inside this focused patch.

## Deployment Safety Finding

The first canary attempt produced SSH/SCP `Connection closed` errors but the
old callee continued and printed `status=updated`. This output is rejected:

- `canary-coordinator.console.log`

Direct verification after restoring SSH access proved the failed attempt did
not change production:

```text
remote_sha=42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df
pulse-coordinator.service=active
```

Accepted evidence:

- `canary-state-before-rerun.console.log`

Commit `639c504` made every remote SHA, service check, prepare, SCP, and
install failure return non-zero. It also validates remote SHA output and
classifies a missing or unknown checksum as changed, as required by the
differential deployment policy.

Local fail-closed tests cover:

- remote SSH failure;
- malformed/unknown SHA;
- service check failure;
- remote prepare failure;
- SCP failure;
- install failure;
- unchanged active success;
- changed upload/install success.

## Access And Scope

The first access window expired because the company jump host required
interactive MFA. The user established `ssh j` ControlMaster in a real
terminal. Permissions were then refreshed immediately before the accepted
canary and rollout.

Accepted evidence:

- `demand-coordinators-rerun.console.log`
- `dryrun-coordinators.console.log`

The dry run selected exactly:

```text
fdbd:dc05:11:634::45
fdbd:dc05:13:10c::40
fdbd:dc07:0:810::44
```

## Canary

Canary: `fdbd:dc05:11:634::45`.

Accepted evidence:

- `canary-coordinator-rerun.console.log`
- `canary-verify-rerun-v2.console.log`

Result:

```text
status=updated
remote_sha_before=42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df
remote_sha_after=96550e19e815092152a049eb58adadcbba1e12ec3ca4e551e208425fef376adb
pulse-coordinator.service=active
pulse-agent.service=active
storage.status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
```

`canary-verify-rerun.console.log` is partially rejected because it used the
wrong asset URL (`/static/pulse-hosts.js`) and received 404 after the valid
SHA/service/storage checks. The accepted marker rerun used
`/assets/pulse-hosts.js`.

## Rollout

Accepted evidence:

- `rollout-coordinators.console.log`
- `rollout-coordinators/results.tsv`
- `rollout-coordinators/summary.json`

Per-host differential result:

| Host | Result |
| --- | --- |
| `fdbd:dc05:11:634::45` | unchanged after accepted canary |
| `fdbd:dc05:13:10c::40` | updated |
| `fdbd:dc07:0:810::44` | updated |

Only `pulse-coordinator.service` was restarted. Agent service start timestamps
were unchanged.

## Final Verification

Accepted evidence:

- `final-verify.console.log`
- `final-verify/results.tsv`
- `final-verify/summary.json`

All three Coordinators reported:

```text
JAR_SHA=96550e19e815092152a049eb58adadcbba1e12ec3ca4e551e208425fef376adb
pulse-coordinator.service=active
pulse-agent.service=active
storage.status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
last_error=""
```

All three served bundles contained:

```text
开始查询
选择指标、范围与 Host 后开始查询
metric.invalidate
/api/metrics/query_range
```

All three served bundles excluded the removed automatic-query state marker:

```text
paused-hidden
```

Coordinator pages require an SSH proxy, so production browser navigation was
not used. UI artifact verification used Coordinator-local HTTP through remote
SSH, as required by the workspace runbook.

## Rollback

Restore the previous Coordinator artifact only on the three Coordinator
hosts:

```text
previous_sha=42ac4d5365dc43313bccdd0b3841882cba03a296d7548ee2030f5e0b2485a0df
target=/data24/otf/pulse/bin/pulse.jar
service=pulse-coordinator.service
```

After rollback, verify per host:

```text
sha256sum /data24/otf/pulse/bin/pulse.jar
systemctl is-active pulse-coordinator.service
systemctl is-active pulse-agent.service
curl -g -fsS --max-time 5 http://[::1]:9966/api/metrics/storage
```

## Result

Metrics no longer query time series on panel mount or parameter changes. The
user sees the complete option set first and explicitly starts the query.
Live invalidations cannot trigger compensation before activation, and stale
or out-of-order asynchronous responses cannot replace the selected result.
The deployment completed on all three Coordinators with raw per-host SHA,
service, storage, and served-asset evidence.
