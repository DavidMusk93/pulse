# Host SSE V3 full rollout

## Context

- Operation date: 2026-08-13
- Delivery commit: `e034cdf76d3b105b7a98a6742eb53267a902c87b`
- Artifact: `target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256: `14be8b9840a348fae20ba056ed2d654a4e48549e4f4839cddf536d80002c4d0c`
- Fleet commit: `c9818dc0e3381f4dc0d2df871a8df616e1f5f1a6`
- Fleet inventory SHA-256: `86988745914f93aad48ab075ba56e5a1d7bd636c6ef7343ebd9fc20a28de6072`
- Coordinator inventory SHA-256: `6a32b1b538d30099780403fc3f1cadedd07a7412b65f49949019d27a3ebc93d9`
- Evidence root: `.tmp/auto-ops/host-sse-v3-full-rollout-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Scope

| Role | Inventory | Exact tag | `--max-hosts` | Dry-run evidence |
| --- | --- | --- | ---: | --- |
| Coordinator | `docs/ops/coordinators.hosts` | `coordinators` | 3 | `dryrun-coordinators.console.log` |
| Doubao Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `doubao` | 15 | `dryrun-doubao.console.log` |
| CDN Agent | `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts` | `cdn_new` | 50 | `dryrun-cdn-new.console.log` |

The dry-runs resolved exactly `3 + 15 + 50` hosts. `cdn_new` includes the
three Coordinator hosts; the differential deploy classified those overlap
hosts as unchanged after the Coordinator phase.

## Pre-deploy Baseline

- `/api/hosts` source: `http://[fdbd:dc05:11:634::45]:9966/`, fetched by SSH-local curl.
- Full agent list: `pre-hosts-fdbd-dc05-11-634-45.json`.
- Agent ids: `pre-agent-ids.txt`.
- Summary: `pre-hosts-summary.json`.
- Total agents: `486`.
- Alive agents: `486`.
- Cluster counts:
  - `cdn2`: 50
  - `doubao`: 15
  - `tlblog_stream_olap_separate`: 190
  - `tlblog_cneast`: 132
  - other clusters: 99
- Pre-deploy V3 probe: `pre-v3-bootstrap-fdbd-dc05-11-634-45.sse`.
  It returned the legacy array snapshot, so production had not yet switched to
  the V3 envelope.

## Local Validation

- `npm run build` succeeded.
- `mvn -q test` succeeded.
- `mvn -q -DskipTests package` succeeded.
- `git diff --check` succeeded.
- Local artifact SHA recorded in `local-jar-sha.txt`.

## Access

Initial scope-wide demand succeeded:

| Scope | Evidence | Result |
| --- | --- | --- |
| Coordinators | `demand-coordinators.console.log`, `demand-coordinators/summary.json` | 3/3 |
| Doubao | `demand-doubao.console.log`, `demand-doubao/summary.json` | 15/15 |
| `cdn_new` | `demand-cdn-new.console.log`, `demand-cdn-new/summary.json` | 50/50 |

Several hosts still rejected root SSH during the first rollout attempt. Those
were recovered with exact failed-host Orthrus demand logs:

- `demand-doubao-failed-hosts.console.log`
- `demand-cdn-new-failed-hosts.console.log`

## Canary

| Scope | Host | Accepted evidence | Result |
| --- | --- | --- | --- |
| Coordinator | `fdbd:dc05:11:634::45` | `canary-coordinator.console.log` | updated; Agent active; Coordinator active |
| Doubao Agent | `fdbd:dc02:e:137::47` | `canary-doubao.console.log` | updated; Agent active |
| CDN Agent | `fdbd:dc05:11:636::12` | `canary-cdn-new-rerun.console.log` | updated; Agent active |
| Coordinator V3 | `fdbd:dc05:11:634::45` | `canary-v3-bootstrap-fdbd-dc05-11-634-45.sse` | `hosts.v3` snapshot |

Rejected evidence:

- `canary-cdn-new.console.log` is not completion evidence. The first temporary
  callee did not propagate `scp`/`ssh` failures and produced a false summary.
  The callee was fixed to fail closed and the canary was rerun successfully.

## Differential Rollout

| Scope | Updated | Unchanged | Failed | Accepted evidence |
| --- | ---: | ---: | ---: | --- |
| Coordinators | 2 | 1 | 0 | `rollout-coordinators.console.log`, `rollout-coordinators-retry.console.log` |
| Doubao Agents | 14 | 1 | 0 | `rollout-doubao.console.log`, `rollout-doubao-retry.console.log` |
| `cdn_new` Agents | 46 | 4 | 0 | `rollout-cdn-new.console.log`, `rollout-cdn-new-retry.console.log` |

The deploy callee compared remote and local SHA before every host update.
Hosts with matching SHA and active services were classified as `unchanged` and
did not receive a JAR upload. Changed hosts uploaded the JAR, verified the
remote SHA, and restarted `pulse-agent.service`; Coordinator hosts also
restarted `pulse-coordinator.service`.

Rejected evidence:

- `rollout-coordinators.console.log` contains an initial 2-host SSH permission
  failure. The accepted completion evidence for those hosts is
  `rollout-coordinators-retry.console.log`.
- `rollout-doubao.console.log` contains an initial 11-host SSH permission
  failure. The accepted completion evidence for those hosts is
  `rollout-doubao-retry.console.log`.
- `rollout-cdn-new.console.log` contains an initial 46-host SSH permission
  failure. The accepted completion evidence for those hosts is
  `rollout-cdn-new-retry.console.log`.

## Final Verification

The first final verifier used `ssh -n` with a heredoc and produced only
summaries, so these files are rejected as completion evidence:

- `final-verify-coordinators.console.log`
- `final-verify-doubao.console.log`
- `final-verify-cdn-new.console.log`

The verifier was fixed and rerun. Accepted final evidence:

| Scope | Verified | Failed | Evidence |
| --- | ---: | ---: | --- |
| Coordinators | 3 | 0 | `final-verify-coordinators-rerun.console.log` |
| Doubao Agents | 15 | 0 | `final-verify-doubao-rerun.console.log` |
| `cdn_new` Agents | 50 | 0 | `final-verify-cdn-new-rerun.console.log` |

Accepted verifier summaries:

```text
final-verify-coordinators-rerun: total=3 ok=3 failed=0
final-verify-doubao-rerun: total=15 ok=15 failed=0
final-verify-cdn-new-rerun: total=50 ok=50 failed=0
```

Every verified host reported:

```text
JAR_SHA=14be8b9840a348fae20ba056ed2d654a4e48549e4f4839cddf536d80002c4d0c
AGENT_ACTIVE=active
```

Every Coordinator additionally reported:

```text
COORDINATOR_ACTIVE=active
HOST_COUNT=486
V3_BOOTSTRAP_SCHEMA="schema":"hosts.v3"
V3_CDN2_SCHEMA="schema":"hosts.v3"
UI_JS_V3_MARKER=hosts.v3
```

Post-deploy `/api/hosts` from `fdbd:dc05:11:634::45`:

- Full JSON: `post-hosts-fdbd-dc05-11-634-45.json`
- Summary: `post-hosts-summary.json`
- Total agents: `486`
- Alive agents: `486`
- Agent id diff vs pre-deploy: `agent-id-diff.txt`, 0 lines

## Rollback

Rollback must restore the correct previous SHA for the host role:

- Coordinator and Coordinator/Agent overlap hosts previous SHA:
  `82211f69ed976c4ad7eb54d093dd885715cc5612a527c786435233ab96c52bab`
- Non-Coordinator Doubao and `cdn_new` Agent previous SHA:
  `73bb883d116861d794b6241c0d73e494a0cc6bd723ca4d2dfca0fb63f9e84742`

Use the raw rollout logs to identify each host's `previous_sha`. Restore
`/data24/otf/pulse/bin/pulse.jar` to that SHA and restart
`pulse-agent.service`; restart `pulse-coordinator.service` only on the three
Coordinator hosts. After rollback, rerun the same final verifier with the
rollback SHA.

## Result

Host SSE V3 is live in production. The Coordinator UI requests V3, all three
Coordinators serve `hosts.v3` snapshots, and all scoped Agent/Coordinator hosts
run the target JAR SHA with active services. The final production host view
contains the same 486 agent ids as before deployment, all alive.
