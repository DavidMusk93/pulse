# Debug Session: agent-exec-failure

Status: OPEN

Target: fdbd:dc05:4:248::46

User request: analyze agent execution failure using docs/debug/arthas.md.

Constraints:
- Do not change business logic during evidence collection.
- Use runtime evidence first.
- Keep artifacts until user confirms completion or abort.

Hypotheses:
1. The submitted task command/script failed on the host, and the UI is only surfacing the final failure.
2. The agent task runner accepted the task but failed during file staging, working directory setup, or permission checks.
3. The agent process is alive but its task execution thread/queue is blocked or overloaded.
4. The coordinator delivered stale or repeated control messages that caused an unexpected task state.
5. The JVM has relevant runtime exceptions or thread states visible via Arthas.

Evidence Log:
- Pending.

Next Steps:
- Read docs/debug/arthas.md.
- Attach/probe the target host according to the runbook.
- Collect service status, recent logs, thread/stack evidence, and relevant task state.

## Evidence Update 1

- Target service is reachable via auto-ops.
- `pulse-agent.service` showed `active/running` with MainPID `2927739` in initial baseline.
- `/data24/otf/pulse/tools/arthas/arthas-boot.jar` was not present on target, so Arthas must be deployed before attach.
- Initial baseline command had local-shell expansion artifacts; repeat collection with stricter quoting is required.

## Evidence Update 2

- Arthas boot deployed to target and reports version `4.2.0`.
- Attach attempt to agent PID `2927739` failed before JVM inspection:
  - `Can not read arthas version from: https://arthas.aliyun.com/api/latest_version`
  - `Can not find Arthas under local: /root/.arthas/lib and remote repo mirror: aliyun`
  - `Unable to download arthas from remote server`
- Conclusion: target lacks the full Arthas package and cannot download it directly; need upload a full Arthas distribution or use non-Arthas JVM evidence as fallback.

## Evidence Update 3

- Full Arthas package uploaded to `/data24/otf/pulse/tools/arthas`.
- Arthas attach to PID `2927739` still failed because the target runtime lacks JDK attach classes:
  - `NoClassDefFoundError: com/sun/tools/attach/AgentLoadException`
  - client then failed to connect to telnet because Arthas core did not start.
- Interpretation: the agent runs with `/data24/otf/pulse/jre/bin/java`; Arthas needs JDK attach support. Need run Arthas with a JDK on target, or use fallback JVM/OS evidence.

## Evidence Update 4

Reproduction via coordinator API on target agent `dc05-p4-t248-n046.byted.org`:

- Submitted diagnostic shell task `task-171413fc-a66b-4148-9b7a-cb1d6e8665e3`.
- File staging succeeded:
  - `file.received_by_agent`
  - local path `/data24/otf/pulse/agent/workspace/scripts/task-171413fc-a66b-4148-9b7a-cb1d6e8665e3/script.sh`
- Agent accepted task:
  - `task.accepted_by_agent`
- Execution result:
  - status `completed`
  - exit_code `0`
  - duration_ms `8`
  - runner_error empty
- Host output showed load average: `173.34, 197.27, 209.10`.
- Observed latency:
  - submit/file enqueue at `1780647659564`
  - file received/dequeued at `1780647675277` (~15.7s later)
  - finished at `1780647679443`
  - result observed at `1780647690340` (~10.9s after finish)

Conclusion so far:
- Agent execution pipeline is functional now.
- The target host is under extreme load; delays are plausible.
- Original failure details are not retained in current coordinator task snapshot; completion queue/traces were empty before reproduction.

## Fix Implemented Locally

Root cause confirmed:
- Generic Shell execution was incorrectly sharing the dry-run task default argument behavior.
- Evidence from target reproduction:
  - Submitted shell with `args: []`.
  - Coordinator trace showed `args: ["--dry-run"]`.
  - Agent executed with one arg and script failed with `exit_code=42`.
  - Output: `ARG_COUNT=1`, `ARGS=<--dry-run>`, `unexpected args`.

Code changes:
- `RemoteTaskService#enqueueShellScript` now uses empty default args for shell scripts.
- `AgentTaskRunner#handleShellExecute` now uses empty default args for shell scripts.
- `normalizeArgs` preserves allowlist task defaults, but returns empty args when fallback is empty.

Validation:
- Added coordinator API regression for omitted shell args => `args=[]`.
- Added agent runner regression for empty shell args => `$#=0`.
- `mvn test` passed: 38 tests, 0 failures.

## Post-Fix Verification

Deployed commit `183f1f1` to:
- fdbd:dc05:11:634::45
- fdbd:dc05:13:10c::40
- fdbd:dc07:0:810::44
- fdbd:dc05:4:248::46

New JAR SHA: `0403685bb960a270702bc1eaaa194cd6b3f4c6c038dd14549938df1e45b465ff`.

Post-fix target reproduction:
- Submitted shell with `args: []`.
- Coordinator trace showed `args: []`.
- Agent completed task `task-699f1192-d8d2-43b0-8dde-877e27f8f323`.
- Result:
  - status `completed`
  - exit_code `0`
  - output `ARG_COUNT=0`, `ARGS=<>`, `NO_ARGS_OK`
  - runner_error empty

Hypothesis Results:
1. Task command/script failed: confirmed for scripts that reject unexpected args; failure was caused by injected `--dry-run`.
2. File staging/path/permission issue: rejected; file.received_by_agent succeeded and local_path was valid.
3. Runner blocked/overloaded: rejected for correctness; execution completed in milliseconds, though high host load adds delivery/result latency.
4. Stale/repeated coordinator messages: rejected for reproduced failure; trace shows a fresh task with deterministic arg injection.
5. JVM internal exception: not supported by evidence; Arthas attach was blocked by JRE lacking JDK attach classes, but service was healthy and post-fix execution succeeded.

Status: AWAITING USER CONFIRMATION FOR CLEANUP

## Additional Analysis

Host load evidence after fix:
- `uptime`: load average `175.62, 177.75, 195.93`.
- Pulse agent process:
  - PID `3013143`
  - CPU around `2.4%`
  - threads `115`
  - memory current ~`4.25GB`
- Main load contributors are non-Pulse processes:
  - `tide_worker` PID `1937699`: ~`1229%` CPU, `4715` threads, nice `-20`
  - `tide_worker` PID `1937695`: ~`1157%` CPU, `4714` threads, nice `-20`
  - hdfs_writer processes also consume significant CPU.

Interpretation:
- Pulse agent itself is healthy and not the source of host load.
- High host load explains 15-20s command delivery/result visibility latency, but not the deterministic shell failure.
- Deterministic failure root cause remains `--dry-run` arg injection, now fixed and verified.

Arthas runtime evidence:
- Full Arthas package was uploaded successfully.
- Attach failed because Pulse runs with bundled JRE, not JDK:
  - no `jcmd`, `jstack`, `javac`
  - no `jdk.attach`/`libattach` files under `/data24/otf/pulse/jre`
  - Arthas core failed with `NoClassDefFoundError: com/sun/tools/attach/AgentLoadException`
- To use Arthas on these hosts, deploy a JDK-capable runtime for diagnostics or change the runbook/deploy artifact to include attach-capable JDK tools.

Current Status:
- Functional bug fixed and verified on target.
- Residual operational issue: target host is extremely loaded by Tide/HDFS processes, unrelated to Pulse correctness but impacts latency.
- Debug artifacts intentionally retained because user selected continue analysis.
