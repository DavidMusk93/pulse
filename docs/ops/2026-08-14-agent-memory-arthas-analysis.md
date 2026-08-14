# Agent memory Arthas analysis

## Context

- Operation date: 2026-08-14
- Request: pull Coordinator metrics, verify whether Agent thread count is controlled, analyze high Agent memory with Arthas, and judge whether Java is suitable for an idle Agent target below 10 MiB.
- Coordinator source: `fdbd:dc05:11:634::45`
- Evidence root: `.tmp/auto-ops/agent-memory-20260814/`
- Runtime scope:
  - all 486 alive Agents from `/api/hosts`
  - high-RSS sample `fdbd:dc05:13:36::45`
  - low-RSS sample `fdbd:dc05:11:63c::24`
- Production mutation: uploaded the full Arthas package to the two sampled Agent hosts under `/data24/otf/pulse/tools/arthas`; no service restart, no JAR change.

## Fleet Metrics

Current `/api/hosts` reported 486 alive Agents. Agent RSS and threads are tight distributions:

```text
agent_rss_kb min=661256 p50=675824 p90=685496 p95=689028 p99=696432 max=708080
agent_threads min=12 p50=13 p90=15 p95=15 p99=17 max=19
```

`/api/metrics/storage` was healthy:

```text
status=ok
queue_depth=0
dropped_commands=0
failed_commands=0
capacity_dropped_commands=0
retention_lag_ms=0
```

## OS Baseline

High-RSS sample `fdbd:dc05:13:36::45`:

```text
PID=557531
CMDLINE=/data24/otf/pulse/jre/bin/java -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -cp /data24/otf/pulse/bin/pulse.jar com.bytedance.pulse.PulseAgentApp
THREADS=25
VmRSS=708088 kB
RssAnon=689848 kB
RssFile=18240 kB
Private_Dirty=689968 kB
```

Low-RSS sample `fdbd:dc05:11:63c::24`:

```text
PID=278602
CMDLINE=/data24/otf/pulse/jre/bin/java -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -cp /data24/otf/pulse/bin/pulse.jar com.bytedance.pulse.PulseAgentApp
THREADS=20
VmRSS=661256 kB
RssAnon=648608 kB
RssFile=12648 kB
Private_Dirty=649996 kB
```

Threads are controlled. RSS is almost entirely private anonymous memory, not file cache.

## Arthas Evidence

Arthas 4.2.0 attached successfully using `/usr/lib/jvm/java-11-byteopenjdk-amd64/bin/java` to the JRE 17 target process. Both hosts were stopped cleanly with no `3658`/`8563` listener left.

High-RSS sample:

```text
INPUT-ARGUMENTS: -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2
PROCESSORS-COUNT=2
HEAP init=2.0 GiB used=79.9 MiB committed=1.9 GiB max=29.0 GiB
NONHEAP used=52.6 MiB committed=56.8 MiB
memory: heap used 81-95 MiB, total 1979 MiB, max 29694 MiB
memory: direct 4 MiB
vmoption MaxHeapSize=32210157568 ORIGIN=ERGONOMIC
vmoption InitialHeapSize=2147483648 ORIGIN=ERGONOMIC
vmoption MaxRAMPercentage=25.0 DEFAULT
thread count=23, deadlock=0
```

Low-RSS sample:

```text
INPUT-ARGUMENTS: -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2
PROCESSORS-COUNT=2
HEAP init=2.0 GiB used=72.4 MiB committed=1.9 GiB max=29.0 GiB
NONHEAP used=47.7 MiB committed=53.0 MiB
memory: heap used 73-91 MiB, total 1979 MiB, max 29694 MiB
memory: direct 4 MiB
vmoption MaxHeapSize=32210157568 ORIGIN=ERGONOMIC
vmoption InitialHeapSize=2147483648 ORIGIN=ERGONOMIC
vmoption MaxRAMPercentage=25.0 DEFAULT
thread count=21, deadlock=0
```

## smaps Breakdown

High-RSS sample:

```text
[anon] Size=35143220 kB Rss=710308 kB Private_Dirty=710308 kB Anonymous=710308 kB
jre_file Rss=25084 kB
so_file Rss=4328 kB
largest anon mapping: Size=699136 kB Rss=572392 kB Private_Dirty=572392 kB
```

Low-RSS sample after Arthas attach:

```text
[anon] Size=34928116 kB Rss=689984 kB Private_Dirty=689984 kB Anonymous=689984 kB
jre_file Rss=23236 kB
so_file Rss=2348 kB
largest anon mapping: Size=699136 kB Rss=570184 kB Private_Dirty=570184 kB
```

The dominant resident memory is anonymous heap/native JVM space. JRE/JAR/shared library mappings are minor.

## Conclusion

The high idle RSS is not caused by Agent business objects, output queues, direct buffers, or thread explosion. The fleet distribution and Arthas evidence point to JVM ergonomics:

```text
InitialHeapSize=2 GiB
MaxHeapSize=29 GiB
heap used only ~73-95 MiB
heap committed ~1.9 GiB
RSS ~650-710 MiB mostly anonymous/private
```

Java HotSpot is acceptable for the Coordinator, but it is a poor fit for an ultra-light per-host idle Agent target of `<10 MiB`. With aggressive JVM tuning, a Java Agent can likely be reduced substantially from ~670 MiB to a much lower bound, but `<10 MiB` is not a realistic steady-state target for a normal HotSpot JVM with HTTP client/server, class metadata, code cache, JRE, and management runtime. That target points toward a native Agent implementation such as Go/Rust/C++ or GraalVM native-image, while keeping the Coordinator in Java remains reasonable.

## Recommended Next Step

Run a one-host Agent canary with a single variable: explicit small heap and native limits, for example:

```text
-Xms16m -Xmx128m
-XX:MaxMetaspaceSize=64m
-XX:CompressedClassSpaceSize=16m
-XX:ReservedCodeCacheSize=32m
-Xss256k
```

Gate it on:

```text
RSS p50/p95 over 30-60 minutes
heartbeat success and latency
GC count/time
task execution smoke test
no coordinator-visible status regression
```

If the canary remains far above the desired idle budget, stop optimizing HotSpot and plan a native Agent.

## Verification / Safety

- `pulse-agent.service` remained active on both sampled hosts.
- Agent PID/start timestamp and JAR SHA were unchanged before/after Arthas.
- Arthas executed `stop` and left no listener on `3658` or `8563`.
- No Coordinator or Agent deployment was performed in this operation.

## Source Binding

- Raw hosts snapshot: `.tmp/auto-ops/agent-memory-20260814/hosts.json`
- Fleet summary: `.tmp/auto-ops/agent-memory-20260814/agent-memory-summary.json`
- OS baselines: `.tmp/auto-ops/agent-memory-20260814/os-baseline-*.log`
- Arthas deploy logs: `.tmp/auto-ops/agent-memory-20260814/arthas-deploy-*.log`
- Arthas snapshots: `.tmp/auto-ops/agent-memory-20260814/arthas-snapshot-*.log`
- smaps breakdowns: `.tmp/auto-ops/agent-memory-20260814/smaps-breakdown-*.log`
