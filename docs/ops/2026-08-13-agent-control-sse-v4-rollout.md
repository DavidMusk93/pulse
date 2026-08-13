# Agent Control SSE V4 rollout

## Context

- Operation date: 2026-08-13
- Request: update production Agents after the Coordinator Control SSE V4
  rollout.
- Delivery commit:
  `54c8a14214847dbefe78a12325cc8f0459796e4c`
- Prior operation-log commit:
  `d3e9fb4 Record Coordinator control SSE V4 rollout`
- Artifact:
  `.worktrees/feat/nonblocking-sse/target/pulse-0.1.0-SNAPSHOT.jar`
- Artifact SHA-256:
  `9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275`
- Fleet inventory:
  `/Users/bytedance/Documents/01_Projects/fleet-ops/hosts`
- Fleet commit:
  `c9818dc fix(ssh): allow GSSAPI bootstrap when SSH_PRIVATE_FILE is set`
- Fleet inventory SHA-256:
  `86988745914f93aad48ab075ba56e5a1d7bd636c6ef7343ebd9fc20a28de6072`
- Evidence root:
  `.tmp/auto-ops/agent-control-sse-v4-20260813/`
- Task sync: not applicable; this delivery changed no file under `docs/task/`.

## Scope

Production `/api/hosts` from `fdbd:dc05:11:634::45` reported 486 alive
Agents before rollout. The rollout used exact inventory tags plus limit files
derived from that production view.

| Scope | Inventory | Tag | Limit file | Hosts |
| --- | --- | --- | --- | ---: |
| Doubao | fleet `hosts` | `doubao` | `scope/doubao.hosts` | 15 |
| CDN | fleet `hosts` | `cdn_new` | `scope/cdn_new.hosts` | 50 |
| TLB | fleet `hosts` | `tlb` | `scope/tlb.hosts` | 329 |
| OTel | fleet `conf/cn.ini` | `otel` | `scope/otel.hosts` | 87 |
| TLB mirror | fleet `hosts` | `tlbmirror` | `scope/tlbmirror.hosts` | 5 |

The `tlb` scope includes five IPv4 operation aliases for production Agents
that report IPv6 ids:

```text
fdbd:dc05:2:71c::34 -> 10.162.231.34
fdbd:dc05:2:71c::36 -> 10.162.231.36
fdbd:dc05:2:71c::37 -> 10.162.231.37
fdbd:dc05:2:71c::40 -> 10.162.231.40
fdbd:dc05:2:71c::41 -> 10.162.231.41
```

Evidence:

- `pre-hosts.json`
- `pre-agent-ids.txt`
- `scope/*.hosts`
- `scope/tlb-ipv4-aliases.tsv`
- `dryrun-deploy-*.console.log`

## Local Gates

The deployed artifact is the same artifact validated by the Coordinator V4
delivery:

- `mvn -q test`: passed.
- `mvn -q clean package`: passed.
- `npm run build`: passed.
- Node host scope and decoder tests: passed.
- `git diff --check`: passed.
- Code review receipt:
  `/tmp/compound-engineering-501/ce-code-review/20260813-195849-10a94db2/review.json`

## Access

Temporary root permissions were refreshed for every exact scope immediately
before the destructive dry-runs:

```text
doubao: total=15 ok=15 failed=0
cdn_new: total=50 ok=50 failed=0
tlb: total=329 ok=329 failed=0
otel: total=87 ok=87 failed=0
tlbmirror: total=5 ok=5 failed=0
```

Orthrus emitted intermittent Kerberos/CCache/401 noise, but every accepted
access gate is bound to an auto-ops `summary` with `ok=total`.

Evidence:

- `demand-*.console.log`
- `demand-*/summary.json`

## Canary

One host per scope was canaried and independently verified:

| Scope | Host | Result |
| --- | --- | --- |
| Doubao | `fdbd:dc02:e:137::47` | updated from `14be8b...d0c` |
| CDN | `fdbd:dc05:11:634::45` | target SHA already present, stale Agent process restarted |
| TLB | `fdbd:dc02:11:304::49` | updated from `267b10...f069` |
| OTel | `fdbd:dc02:27:79::21` | updated from `8154ff...604f` |
| TLB mirror | `fdbd:dc01:b:357::37` | updated from `8154ff...604f` |

The CDN canary is also a Coordinator host. The deploy callee verified
`COORDINATOR_START_BEFORE` equals `COORDINATOR_START_AFTER`; only
`pulse-agent.service` was restarted.

Evidence:

- `canary-*.console.log`
- `canary-*/summary.json`
- `canary-verify-*.console.log`
- `canary-verify-*/summary.json`

## Rollout

The main differential rollout results were:

| Scope | Initial result | Recovery result |
| --- | --- | --- |
| Doubao | `15 ok / 0 failed` | not needed |
| CDN | `50 ok / 0 failed` | not needed |
| TLB | `320 ok / 9 failed` | `7 ok`, then `2 repaired` |
| OTel | `87 ok / 0 failed` | not needed |
| TLB mirror | `5 ok / 0 failed` | not needed |

Differential behavior:

- SHA-equal Agents were classified as unchanged only when the process start
  time was not older than the JAR mtime.
- Coordinator overlap hosts were protected by checking Coordinator start
  monotonic timestamp before and after Agent restart.
- Changed hosts uploaded the JAR, verified the installed SHA, and restarted
  `pulse-agent.service`.

Evidence:

- `rollout-doubao.console.log`
- `rollout-cdn_new.console.log`
- `rollout-tlb.console.log`
- `rollout-tlb-retry.console.log`
- `rollout-otel.console.log`
- `rollout-tlbmirror.console.log`
- `rollout-*/summary.json`
- `rollout-*/results.tsv`
- `rollout-*/failed-hosts.txt`

## Recovery

The TLB rollout had transient and host-local failures:

- SSH probe failures on several hosts recovered after targeted Orthrus
  refresh and verify retry.
- `fdbd:dc02:11:322::14` had `/tmp` and `/` at 100%, causing upload and
  systemd restart failures.
- `fdbd:dc05:2:71c::27` and `fdbd:dc02:11:322::14` were missing the
  `pulse-agent.service` EnvironmentFile after a failed restart path.

Recovery actions:

1. Retried the 9 failed TLB hosts with `agent-control-v4-diff-deploy.sh`,
   which stages under `/data24/otf/pulse/tmp` and creates `bin` before install.
2. Repaired `fdbd:dc05:2:71c::27` by restoring the Agent env/unit and bundled
   JRE; it then reported target SHA and active Agent.
3. On `fdbd:dc02:11:322::14`, removed only `/root/.debug` after disk audit
   showed it occupied 35G on the full root filesystem. Root usage dropped from
   100% to 73%.
4. Repaired `fdbd:dc02:11:322::14` with bundled JRE, Agent env/unit, and
   target JAR.

Accepted recovery evidence:

- `demand-tlb-failed.console.log`
- `rollout-tlb-retry.console.log`
- `audit-tlb-failed-final.console.log`
- `cleanup-322-14-root-debug.console.log`
- `repair-tlb-final-2-jre-v2.console.log`
- `repair-tlb-322-14-final.console.log`
- `audit-tlb-322-14-final.console.log`

## Final Verification

Final per-host verification passed after the TLB SSH-only retry:

```text
doubao: total=15 ok=15 failed=0
cdn_new: total=50 ok=50 failed=0
tlb: total=316 ok, then retry total=13 ok=13 failed=0
otel: total=87 ok=87 failed=0
tlbmirror: total=5 ok=5 failed=0
```

Every verified host reported:

```text
JAR_SHA=9f1719caf7fd789f5e6514bcb9e996562fda5fd355fb5cbb38cb085b804c3275
AGENT_ACTIVE=active
AGENT_START_EPOCH >= JAR_MTIME_EPOCH
```

Coordinator overlap hosts additionally reported:

```text
COORDINATOR_EXPECTED=1
COORDINATOR_ACTIVE=active
```

Post-rollout production view from `fdbd:dc05:11:634::45`:

```text
pre_total=486
post_total=486
pre_alive=486
post_alive=486
missing_count=0
added_count=0
```

Evidence:

- `final-verify-doubao.console.log`
- `final-verify-cdn_new.console.log`
- `final-verify-tlb.console.log`
- `final-verify-tlb-retry.console.log`
- `final-verify-otel.console.log`
- `final-verify-tlbmirror.console.log`
- `final-verify-*/summary.json`
- `post-hosts.json`
- `post-hosts-summary.json`
- `post-agent-ids.txt`
- `agent-id-missing-after.txt`
- `agent-id-added-after.txt`

## Rollback

Rollback is per host because previous SHA varied by scope:

- Doubao and non-overlap CDN Agents:
  `14be8b9840a348fae20ba056ed2d654a4e48549e4f4839cddf536d80002c4d0c`
- TLB Agents:
  `267b101f271e5be4f2a963b581334570aad6b2f52a8929f10e70fb554d17f069`
- OTel and TLB mirror Agents:
  `8154ffbe4b121193fe595592e28f28efdf425d2b2e51bf42b0d831ffb9c4604f`
- Coordinator overlap hosts already had target JAR before Agent restart;
  rollback must follow the Coordinator rollback plan only if the Coordinator
  V4 artifact itself is being rolled back.

Use the raw rollout logs to identify each host's `previous_sha`. Restore
`/data24/otf/pulse/bin/pulse.jar`, restart `pulse-agent.service`, and rerun
the same final verifier. Do not restart `pulse-coordinator.service` for an
Agent-only rollback unless the host is also undergoing Coordinator rollback.

## Result

The production Agent fleet visible to the Coordinator is updated to the
Control SSE V4 artifact. All 486 pre-existing Agent ids are still present and
alive after rollout. The AgentTaskRunner output-ordering fix is now active on
the production Agent processes.
