# Agent Java 25 memory rollout

## Context

- Operation date: 2026-08-14
- Request: upgrade Pulse Agent to the latest stable Java runtime, apply low-memory JVM settings, bound task execution memory spikes, and roll the Agent update to production.
- Delivery commits:
  - `65f8869` Bound agent task memory spikes
  - `b78d1a1` Configure low-memory agent JVM
  - `839880a` Prevent silent agent output loss
  - `aa0fa60` Add ByteDance network proxy skill
- Evidence root: `.tmp/auto-ops/agent-memory-upgrade-20260814/`

## Artifacts

| Artifact | SHA-256 |
| --- | --- |
| `target/pulse-0.1.0-SNAPSHOT.jar` | `a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115` |
| `.tmp/runtime/java25/OpenJDK25U-jre_x64_linux_hotspot_25.0.4_7.tar.gz` | `aed3915f8facc0c80733ab2448bb0df4b494a36a2c5759e9a6e1eb979720f2b3` |
| `.tmp/runtime/pulse-agent-ops/uv-0.12.4-x86_64-unknown-linux-gnu.tar.gz` | `c8c60f47e6f88d18dbf6f33d7279fb1fbf7ae76631768152cf5578c3d65729b4` |
| `.tmp/runtime/pulse-agent-ops/pyenv-v2.8.4.tar.gz` | `6f80750a10d20f1b74252d81d543f0543c8f49ba9ea5804de8a82afedb4e3b8c` |

## Runtime Changes

- Agent JRE upgraded to Temurin `25.0.4` LTS under `/data24/otf/pulse/jre`.
- Agent JVM options set to:

```text
-Xms16m -Xmx128m
-XX:MaxMetaspaceSize=64m
-XX:CompressedClassSpaceSize=16m
-XX:ReservedCodeCacheSize=32m
-XX:MaxDirectMemorySize=32m
-Xss256k
-XX:+UseSerialGC
-XX:ActiveProcessorCount=2
-XX:CICompilerCount=2
-XX:+ExitOnOutOfMemoryError
-Djava.awt.headless=true
```

- Later rollout batches also include `-XX:-UsePerfData` to avoid HotSpot writing `/tmp/hsperfdata_*` on hosts with full root filesystems.
- Agent execution memory boundaries:
  - `PULSE_AGENT_PENDING_REPLY_MAX=512`
  - `PULSE_AGENT_PENDING_REPLY_BYTES_MAX=4194304`
  - `PULSE_AGENT_CAPTURED_OUTPUT_CHARS_MAX=262144`
  - `PULSE_AGENT_STREAM_QUEUE_BYTES_MAX=8388608`
  - `PULSE_AGENT_FILE_BYTES_MAX=1048576`
- Remote deployment helper used Pulse-local tool root `/data24/.tmp/pulse-agent-ops`.
- ByteDance external proxy and internal Python package index were standardized in `.trae/skills/bytedance-network-proxy/SKILL.md`.

## Scope And Result

| Scope | Inventory | Result |
| --- | --- | --- |
| `doubao` | `fleet-ops/hosts [doubao]` | `15/15` completed |
| `cdn_new` | `fleet-ops/hosts [cdn_new]` | `50/50` completed |
| `tlb` | `fleet-ops/hosts [tlb]` | `329/330` completed |
| `otel` | `fleet-ops/conf/cn.ini [otel]` | `87/87` completed |
| `tlbmirror` | `fleet-ops/hosts [tlbmirror]` | `5/5` completed |

Overall production Agent scope: `486/487` completed.

Remaining host:

```text
fdbd:dc02:11:724::46
```

This host failed root SSH, `tiger@IPv6`, hostname SSH, and a 1 MiB SCP probe with banner timeout / connection closed. It was not mutated by the final rollout stage and is recorded as an access/network blocker, not an Agent regression.

## Notable Recovery

Host `fdbd:dc05:2:71c::27` had root filesystem `/` at `100%`, with large historical `tide_worker` logs under `/tmp`. The Agent JAR, JRE, and env had already been updated, but the service was still running with the old command line. A direct `systemctl restart pulse-agent.service` completed successfully after access was refreshed.

Post-restart evidence:

```text
jar=a6500e3b404d7414dd4a324b5dc38291b13ddb887d935dca29169e5094363115
openjdk version "25.0.4" 2026-07-21 LTS
cmd includes -Xms16m -Xmx128m -XX:-UsePerfData
VmRSS=81232 kB
Threads=20
```

Evidence: `.tmp/auto-ops/agent-memory-upgrade-20260814/manual-tlb-71c27-restart.console.log`

## Verification

Representative final samples:

| Host | Scope | Result |
| --- | --- | --- |
| `fdbd:dc02:e:137::47` | `doubao` | active, JAR `a6500...`, Java `25.0.4`, RSS `113680 kB`, threads `19` |
| `fdbd:dc05:11:634::45` | `cdn_new` | active, JAR `a6500...`, Java `25.0.4`, RSS `107256 kB`, threads `19` |
| `fdbd:dc02:11:724::23` | `tlb` | active, JAR `a6500...`, Java `25.0.4`, RSS `101944 kB`, threads `19` |
| `fdbd:dc05:13:104::46` | `otel` | active, JAR `a6500...`, Java `25.0.4`, RSS `99108 kB`, threads `19` |
| `fdbd:dc01:b:357::37` | `tlbmirror` | active, JAR `a6500...`, Java `25.0.4`, RSS `89360 kB`, threads `19` |

Canary task verification:

- Normal shell/file task smoke completed on canary `fdbd:dc05:11:63c::24`.
- Large output canary produced explicit failed terminal state without Agent restart:

```text
status=failed
exit_code=0
runner_error=agent output exceeded memory limit; partial output was streamed
```

Focused local verification before rollout:

```text
mvn -q -Dtest=AgentTaskRunnerTest test
mvn -q test
mvn -q clean package
git diff --check
```

## Safety And Rollback

- Rollout was Agent-only. No Coordinator deployment was performed.
- For Coordinator-overlap hosts, `pulse-coordinator.service` start timestamp was checked during rollout and did not change.
- Each updated host wrote a rollback directory under:

```text
/data24/otf/pulse/rollback/agent-memory-upgrade-<UTC timestamp>
```

Rollback point includes prior `pulse.jar`, `pulse-agent.env`, and `pulse-agent.service` when present.

## Source Binding

- Rollout evidence root: `.tmp/auto-ops/agent-memory-upgrade-20260814/`
- Final completed/remaining host lists:
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/scope/doubao-completed.hosts`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/scope/cdn_new-completed.hosts`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/scope/tlb-completed.hosts`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/scope/tlb-remaining.hosts`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/scope/otel-completed.hosts`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/scope/tlbmirror-completed.hosts`
- Problem-host evidence:
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/tlb-72446-tiger-probe.log`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/tlb-72446-hostname-probe.log`
  - `.tmp/auto-ops/agent-memory-upgrade-20260814/manual-tlb-71c27-restart.console.log`

## Next Action

- Recover `fdbd:dc02:11:724::46` only after SSH/banner timeout is resolved.
- Consider a follow-up consistency-only env sync to add `-XX:-UsePerfData` to early batches that were upgraded before that flag was added, if `/tmp` pressure appears on those hosts.
- Native-image or non-JVM Agent remains the path for a true `<10 MiB` idle target; this HotSpot rollout reduced idle RSS from roughly `660-710 MiB` to roughly `90-115 MiB`.
