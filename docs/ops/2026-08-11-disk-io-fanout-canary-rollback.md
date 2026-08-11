# Disk IO Fanout Canary Rollback

## Operation

- Date: 2026-08-11
- Delivery commit: `a20d1c2a18ab9e171c3ca4622b5a5cb4dd5d3ee2`
- Candidate JAR SHA-256: `7d87c47fcb846d5902aa1dba8ec3cbf5ba356af147b1d0089b74602df88c8150`
- Coordinator canary: `fdbd:dc05:11:634::45`
- Agent canary: `fdbd:dc01:b:357::37`
- Full coordinator scope: 3 hosts
- Refreshed agent scope: 486 hosts
- Agent inventory SHA-256: `33b657a878f732a5a2201dc7ac38400fcb4f219ee0d8c11f07531c1882b98ecb`
- Final state: both canaries rolled back; no full rollout occurred

## Intent

Canary the disk IO metric, event generation, periodic fanout, and Web UI registration implementation before a differential rollout.

## Canary Evidence

The coordinator API check reached the candidate implementation and observed:

```text
CATALOG_METRICS=31
FANOUT_SOURCES=0
HOST_COUNT=486
CANARY_DISKS=28
DISK_SERIES=4
BYTEDCLI_PATH=
```

Disk collection, catalog exposure, storage query, and UI assets were present. The final delivery prerequisite was not:

```text
BYTEDCLI_PATH=
NODE_PATH=
```

Raw prerequisite evidence:

```text
.tmp/auto-ops/disk-io-fanout-20260811/bytedcli-prerequisite.raw.log
```

The candidate depended on coordinator-local `bytedcli` identity state to locate and send to a Lark group. Because that runtime and identity did not exist, the operation stopped at two canaries. The candidate update commands reached the target state, but the first update attempt's wrapper result was ambiguous; the operation therefore does not claim complete update-process evidence.

## Rollback

Rollback used persisted pre-operation artifacts and explicit one-host scopes.

Coordinator:

```text
host=fdbd:dc05:11:634::45
jar_sha256=53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3
service=pulse-coordinator.service
service_state=active
pulse-agent.service start time unchanged
```

Agent:

```text
host=fdbd:dc01:b:357::37
jar_sha256=8154ffbe4b121193fe595592e28f28efdf425d2b2e51bf42b0d831ffb9c4604f
service=pulse-agent.service
service_state=active
coordinator service absent and not restarted
```

Per-host rollback results:

```text
.tmp/auto-ops/disk-io-fanout-20260811/coordinator-rollback/results.tsv
.tmp/auto-ops/disk-io-fanout-20260811/agent-rollback/results.tsv
```

Both result sets report `1/1 ok`; both `failed-hosts.txt` files are empty.

## Final Verification

Independent post-rollback verification:

```text
RESULT status=verified service=pulse-coordinator.service service_state=active expected_sha=53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3 actual_sha=53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3

RESULT status=verified service=pulse-agent.service service_state=active expected_sha=8154ffbe4b121193fe595592e28f28efdf425d2b2e51bf42b0d831ffb9c4604f actual_sha=8154ffbe4b121193fe595592e28f28efdf425d2b2e51bf42b0d831ffb9c4604f
```

Raw final evidence:

```text
.tmp/auto-ops/disk-io-fanout-20260811/coordinator-rollback-verify.raw.log
.tmp/auto-ops/disk-io-fanout-20260811/agent-rollback-verify.raw.log
.tmp/auto-ops/disk-io-fanout-20260811/coordinator-rollback-verify/results.tsv
.tmp/auto-ops/disk-io-fanout-20260811/agent-rollback-verify/results.tsv
```

Each verification reports `1/1 ok`; each final `failed-hosts.txt` is empty.

## Rollback Point

- Coordinator JAR: `53e732f57a4d99f4d8bcb4ed8587d6a0927011d0b675c4f571d0b148f770d0e3`
- Agent JAR: `8154ffbe4b121193fe595592e28f28efdf425d2b2e51bf42b0d831ffb9c4604f`

## Outcome

The production fleet remained on the prior versions. No coordinator beyond the one canary and no agent beyond the one canary received the candidate.

The implementation direction was subsequently superseded: Lark delivery is now modeled as a configurable EventBus sink using a Web-configured custom-bot webhook, not a coordinator-local `bytedcli` dependency. That replacement requires a new build, new canary, and new operation log; this operation must not be reused as deployment evidence for it.
