# Agent Offline Root Cause Fix

## Summary

- Time: 2026-06-06 23:47 CST
- Target: `fdbd:dc05:11:634::45`
- Symptom: agent frequently appears offline / expired.
- Result: fixed and verified on target host.

## Root Cause

The agent had two resource problems in the heartbeat hot path:

- Dynamic follower mode created a new `HeartbeatClient` on every heartbeat loop.
- Each `HeartbeatClient` created a Java `HttpClient`, which created selector/worker threads.
- The target process accumulated many `HttpClient-*` threads and eventually stopped producing heartbeats while systemd still showed the process as active.
- `AgentHeartbeatFactory.tideWorkers()` scanned all `/proc` entries every heartbeat and read `cmdline`, `environ`, `stat`, `status`, and `/proc/meminfo`, making heartbeat cost proportional to host process count.
- JVM default ergonomics on this large host created many GC threads for a small agent process.

## Evidence

Pre-fix:

- `pulse-agent.service`: active, no repeated systemd restart loop.
- Coordinator API: target row was `status=expired`, `last_observed_age_ms=554433`.
- Agent log mtimes stopped at `23:22:29` while the process was still alive.
- Process `527048`: `NLWP=117`.
- Thread list contained many `HttpClient-xx-SelectorManager` threads.
- Code path: `PulseAgentApp.runDynamic()` allocated `new HeartbeatClient(...)` inside the follower branch loop.

Secondary historical issue:

- `pulse-agent.err` contained `NoClassDefFoundError: com/bytedance/pulse/TaskOutputCodec` from `AgentTaskRunner.handleFilePut`.
- Current jar contains `TaskOutputCodec.class`; restarting the agent on the new jar clears this version skew.

## Fix

- Reuse follower leader `HeartbeatClient` while `leader_url` is unchanged.
- Recreate follower leader `HeartbeatClient` only when `leader_url` changes.
- Cache tide worker PID discovery and refresh it every `PULSE_TIDE_DISCOVERY_INTERVAL_MS`, default `60000`.
- Sample only cached tide worker PIDs on each heartbeat.
- Cache tide worker static environment fields during discovery.
- Read `/proc/meminfo` once per agent heartbeat factory.
- Add low-resource JVM defaults for agent deployment:
  `-XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2`.

## Validation

- `mvn package`: success.
- Tests: `43` run, `0` failures, `0` errors.
- New jar SHA256: `6282d68dd607ddfe45efed1555dbd9c0dcbfc6d6855b4c02a461c3f4e2ebca8b`.

Target rollout:

- Scope: `fdbd:dc05:11:634::45` only.
- Mode: agent-only, replaced `/data24/otf/pulse/bin/pulse.jar`, restarted `pulse-agent.service`.
- Result: `summary: total=1 ok=1 failed=0 elapsed=5s`.

Post-fix:

- `pulse-agent.service`: active.
- ExecStart: `/data24/otf/pulse/jre/bin/java -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -cp /data24/otf/pulse/bin/pulse.jar com.bytedance.pulse.PulseAgentApp`.
- Coordinator API: `alive`, `last_observed_age_ms=668`, `seq=18`, `group_mode=follower`.
- Process after 91s uptime: `NLWP=20`, `GC_THREADS=0`, `HTTPCLIENT_THREADS=4`.
- Heartbeat log progressed continuously from `seq=8` to `seq=19`.

## Residual Work

- Roll the fix out to the broader `cdn_new` agent fleet after canary soak if desired.
- Consider replacing Java `HttpClient` with a small fixed-executor client or lower-level HTTP implementation if thread count must be reduced further.
- Consider making tide worker PID discovery explicit from deployment metadata instead of any `/proc` scan.

## Full cdn_new Agent Rollout

Time: 2026-06-07 00:16 CST.

Scope:

- Fleet-ops tag: `cdn_new`.
- Dry-run selected 50 hosts.
- Mode: agent-only.
- Action: replace `/data24/otf/pulse/bin/pulse.jar`, ensure low-resource agent env/unit, restart `pulse-agent.service`.
- Coordinator services were not restarted.

Artifact:

- Jar SHA256: `6282d68dd607ddfe45efed1555dbd9c0dcbfc6d6855b4c02a461c3f4e2ebca8b`.

Preflight:

- `demand.sh`: `summary: total=50 ok=50 failed=0 elapsed=4s`.
- Note: `demand.sh` emitted one krb5 ccache panic stack for `fdbd:dc05:11:636::16`, but the final preflight summary was successful and root access was acquired.

Rollout:

- `summary: total=50 ok=50 failed=0 elapsed=44s`.

Host-level verification:

- `summary: total=50 ok=50 failed=0 elapsed=2s`.
- `ACTIVE=50`.
- `SHA_OK=50`.
- `OPTS_OK=50`.
- `ENV_OK=50`.
- Agent thread count range: `19..22`.
- `HttpClient` thread count range: `3..6`.
- `GC_THREADS`: sum `0`, max `0`.

Coordinator-level verification:

- Source: coordinator `fdbd:dc05:11:634::45`, local `/api/hosts`.
- `matched=50 alive=50 expired=0 missing=0 max_age_ms=4727`.

## Additional Low-Resource Agent Risks

These are not blockers for the current rollout, but they still move the implementation away from the low-resource-agent target:

- `AgentTaskRunner` stores full command output in a `StringBuilder` while also streaming chunks; large output still duplicates memory.
- `AgentTaskRunner.pendingReplies` and task result chunk replay are in-memory queues with replay count, so coordinator/network stalls can accumulate heap.
- Task output uses one extra daemon reader thread per running command; acceptable at concurrency 1, but should be revisited before raising concurrency.
- `HeartbeatClient` still uses Java `HttpClient` default internals; thread count is now bounded by reuse/JVM flags, but a fixed executor or lower-level client would be more deterministic.
- Heartbeat loop still serializes sampling, network send, reply drain, and task message handling; any slow stage can delay the next heartbeat.
- Agent logs one line per heartbeat; at large fleet scale this is steady disk IO and should be rate-limited or made event-driven.

## Iteration 2: Lower Heartbeat and Agent Resource Cost

Time: 2026-06-07 00:04 CST.

Goal:

- Continue moving the agent toward extremely low heartbeat operations and bounded resource usage.

Additional changes:

- `AgentTaskRunner` final task output now uses a bounded in-memory buffer.
- `PULSE_TASK_OUTPUT_MAX_CHARS` default is `262144`.
- `AgentTaskRunner.pendingReplies` now has a bounded queue admission limit.
- `PULSE_AGENT_PENDING_REPLY_MAX` default is `512`.
- Successful heartbeat logs are sampled instead of emitted on every heartbeat.
- `PULSE_HEARTBEAT_SUCCESS_LOG_EVERY` default is `12`.
- `GroupHeartbeatReceiver` now uses a fixed single daemon executor instead of relying on `HttpServer` defaults.

Local validation:

- `mvn package`: success.
- Tests: `45` run, `0` failures, `0` errors, `0` skipped.
- Jar SHA256: `1d98af4c0fe133adf9e93f4a5505a2505e4e4f3743b8bb536b14b221bc25ae96`.

Canary:

- Host: `fdbd:dc05:11:634::45`.
- Rollout: `summary: total=1 ok=1 failed=0 elapsed=5s`.
- Service: `pulse-agent.service=active`.
- Threads after canary: `NLWP=25`.
- Coordinator API: `alive`, `last_observed_age_ms=2683`, `seq=8`, `group_mode=follower`.
- New successful heartbeat log lines were sampled (`seq=1`, `seq=2`) rather than emitted every heartbeat.

Full rollout:

- Scope: `cdn_new`, 50 hosts.
- Mode: agent-only.
- Rollout: `summary: total=50 ok=50 failed=0 elapsed=33s`.

Host-level verification:

- `summary: total=50 ok=50 failed=0 elapsed=1s`.
- `ACTIVE=50`.
- `SHA_OK=50`.
- `OPTS_OK=50`.
- `ENV_OK=50`.
- Agent thread count range: `21..25`.
- `HttpClient` thread count range: `4..8`.
- `GC_THREADS`: sum `0`, max `0`.

Coordinator-level verification:

- Source: coordinator `fdbd:dc05:11:634::45`, local `/api/hosts`.
- `matched=50 alive=50 expired=0 missing=0 max_age_ms=4228`.

Remaining first-principles target:

- The next major step should remove periodic `/proc` discovery entirely by using deployment/runtime metadata to provide tide worker PIDs or a stable local registry.
- The task runner should move large output to file/spool-backed streaming if operators need full historical output without heap pressure.
- Java `HttpClient` can be replaced or configured further if the target moves from “~20 threads” to “single-digit threads”.
