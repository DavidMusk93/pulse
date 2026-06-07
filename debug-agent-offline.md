# Debug Session: agent-offline

Status: [OPEN]

Target:

- Host: `fdbd:dc05:11:634::45`
- Symptom: agent frequently appears offline / drops from coordinator view.

Constraints:

- No business logic modification before runtime evidence is collected.
- Prefer existing logs and service/runtime state before adding instrumentation.

Hypotheses:

- H1: The agent process or `pulse-agent.service` is restarting/crashing, causing heartbeat gaps.
- H2: Coordinator was restarted during recent UI deployments and in-memory heartbeat state temporarily dropped this host.
- H3: Network or IPv6 connectivity from agent to the active coordinator endpoint is intermittently failing.
- H4: Host resource pressure, clock skew, or JVM/process stalls delay heartbeat emission beyond the offline threshold.
- H5: Agent identity or metadata is unstable, so the same machine reports under changing host keys and appears to drop.

Evidence Plan:

- Check coordinator `/api/hosts` state for the target host.
- Check remote `pulse-agent.service` and recent journal logs.
- Check agent install/log directory and heartbeat-related logs.
- Compare target host with coordinator logs and recent deployment timestamps.

Evidence Collected:

- 2026-06-06 23:30 CST: `pulse-agent.service` on `fdbd:dc05:11:634::45` is `active`; `NRestarts=0`; PID `527048`; service entered active at `23:12:25`.
- 2026-06-06 23:30 CST: agent journal shows one manual stop/start at `23:12:24-23:12:25`, no repeated systemd restart loop afterwards.
- 2026-06-06 23:31 CST: coordinator `/api/hosts` reports exactly one row for `fdbd:dc05:11:634::45`, status `expired`, `last_observed_age_ms=554433`, `expire_at_ms` already passed.
- 2026-06-06 23:31 CST: `/data24/otf/pulse/logs/pulse-agent.log` and `.err` mtimes are both `23:22:29`, while process is still active, indicating heartbeat loop stopped without process exit.
- 2026-06-06 23:31 CST: remote `/data24/otf/pulse/bin/pulse.jar` contains `com/bytedance/pulse/TaskOutputCodec.class`.
- 2026-06-06 23:31 CST: `pulse-agent.err` contains a prior `NoClassDefFoundError: com/bytedance/pulse/TaskOutputCodec` from `AgentTaskRunner.handleFilePut`, but the current jar now contains that class.
- 2026-06-06 23:32 CST: process `527048` has `NLWP=117`; `ps -L` shows many accumulated `HttpClient-xx-SelectorManager` threads (`HttpClient-72` through `HttpClient-117`).
- Code evidence: `PulseAgentApp.runDynamic()` creates `new HeartbeatClient(List.of(currentPlan.leaderUrl()), client.timeout)` inside the follower branch loop, and `HeartbeatClient` creates a new `HttpClient` per instance.

Hypothesis Status:

- H1 partially confirmed: agent previously crashed on `NoClassDefFoundError`, but current issue is not frequent systemd restarts.
- H2 rejected as primary current cause: coordinator restarts explain temporary drops during deployment, but current expired state persists while coordinator is active.
- H3 partially confirmed: logs contain heartbeat failures to coordinators and group leaders, but not sufficient as the primary mechanism for the current stuck process.
- H4 confirmed: process is alive but heartbeat loop/logging stopped; thread evidence points to resource leak/stall.
- H5 not supported: coordinator has one stable record for the target host/agent id.

Current Conclusion:

- Most likely root cause is a thread/resource leak in dynamic follower mode: every follower heartbeat iteration allocates a fresh `HeartbeatClient` and therefore a fresh Java `HttpClient`, accumulating selector threads until the agent loop stalls or becomes unhealthy.
- Secondary contributing issue: previous deployment/version skew produced `NoClassDefFoundError` for `TaskOutputCodec` when handling `cmd.file_put`; current jar contains the class, so restarting the agent on the new jar should clear that specific classpath mismatch.

Fix Applied:

- `PulseAgentApp.runDynamic()` now reuses a cached follower leader `HeartbeatClient` and only recreates it when `leader_url` changes.
- `AgentHeartbeatFactory.tideWorkers()` now uses low-frequency `/proc` discovery (`PULSE_TIDE_DISCOVERY_INTERVAL_MS`, default `60000`) plus per-heartbeat sampling of cached tide worker PIDs.
- Tide worker static fields (`PORT1`, `TIDELET_COMPONENT_VERSION`) are read during discovery, not on every heartbeat.
- `/proc/meminfo` is read once per factory instance, not once per heartbeat.
- `docs/script/pulse-cdn-new-deploy.sh` now sets low-resource agent JVM defaults:
  `-XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2`.

Validation:

- `mvn package`: success.
- Tests: `43` run, `0` failures, `0` errors.
- New jar SHA256: `6282d68dd607ddfe45efed1555dbd9c0dcbfc6d6855b4c02a461c3f4e2ebca8b`.
- Deployed target only: `fdbd:dc05:11:634::45`, agent-only, `pulse-agent.service` restart.
- Post-fix 1: coordinator API reported `status=alive`, `last_observed_age_ms=10`, `seq=7`, `group_mode=follower`.
- Post-fix 2: after 91s uptime, process `NLWP=20`, `GC_THREADS=0`, `HTTPCLIENT_THREADS=4`, and heartbeat log progressed continuously from `seq=8` to `seq=19`.

Status:

- Root cause fixed and verified on the target host.
- Debug session remains `[OPEN]` until cleanup is explicitly requested.

Full cdn_new Rollout:

- 2026-06-07 00:16 CST: dry-run selected 50 `cdn_new` hosts.
- Preflight: `summary: total=50 ok=50 failed=0 elapsed=4s`.
- Rollout: agent-only full update, `summary: total=50 ok=50 failed=0 elapsed=44s`.
- Host-level verification: `ACTIVE=50`, `SHA_OK=50`, `OPTS_OK=50`, `ENV_OK=50`.
- Thread verification: agent threads `19..22`, `HttpClient` threads `3..6`, `GC_THREADS=0`.
- Coordinator verification from `fdbd:dc05:11:634::45`: `matched=50 alive=50 expired=0 missing=0 max_age_ms=4727`.

Additional Low-Resource Risks:

- `AgentTaskRunner` duplicates full command output in memory while also streaming chunks.
- `pendingReplies` is an in-memory queue and can grow under coordinator/network stalls.
- One output reader thread is created per running task.
- Java `HttpClient` default internals are still not fully deterministic; fixed executor or lighter client remains preferable.
- Heartbeat loop is still serial and can be delayed by slow sampling, network send, reply drain, or task handling.
- Per-heartbeat stdout logging creates steady disk IO at fleet scale.

Iteration 2:

- Applied bounded final task output buffer (`PULSE_TASK_OUTPUT_MAX_CHARS=262144`).
- Applied bounded pending reply admission (`PULSE_AGENT_PENDING_REPLY_MAX=512`).
- Sampled successful heartbeat logs (`PULSE_HEARTBEAT_SUCCESS_LOG_EVERY=12`).
- Added fixed single daemon executor for `GroupHeartbeatReceiver`.
- Local `mvn package`: `45` tests, `0` failures, `0` errors.
- New jar SHA256: `1d98af4c0fe133adf9e93f4a5505a2505e4e4f3743b8bb536b14b221bc25ae96`.
- Canary `fdbd:dc05:11:634::45`: active, `NLWP=25`, coordinator API `alive`, sampled heartbeat success logs observed.
- Full `cdn_new` rollout: `summary: total=50 ok=50 failed=0 elapsed=33s`.
- Host verification: `ACTIVE=50`, `SHA_OK=50`, `OPTS_OK=50`, `ENV_OK=50`, threads `21..25`, `HttpClient` threads `4..8`, `GC_THREADS=0`.
- Coordinator verification: `matched=50 alive=50 expired=0 missing=0 max_age_ms=4228`.
