# Debug Session: cdn-new-immediate-failures

Status: OPEN

Target:
- UI: http://[fdbd:dc05:11:634::45]:9966/
- Cluster: cdn_new

User request:
- Three cdn_new nodes still fail immediately during Shell submit.
- Use docs/debug/arthas.md to inspect coordinator Java runtime and logs.
- Locate root cause and fix.

Constraints:
- Do not modify business logic before collecting runtime evidence.
- Use runtime evidence from coordinator logs/JVM/API first.
- Keep debug artifacts until user confirms cleanup.

Hypotheses:
1. Browser submits 50 POST requests concurrently and some requests fail before coordinator records a task.
2. Coordinator Java HTTP server has an executor/backlog/connection handling bottleneck under burst POSTs.
3. Coordinator logs contain request handler exceptions that close client connections and surface as `Failed to fetch`.
4. Coordinator restart/leader peer transition causes transient UI-side submission failures.
5. UI submit strategy is too bursty; even if backend is correct, it should throttle/retry per-agent POSTs.

Evidence Log:
- Pending.

## Evidence Step 1 Started

Collected baseline target:
- coordinator host `fdbd:dc05:11:634::45`
- service/runtime/logs/network status
- local-to-coordinator 50-concurrent POST reproduction using `docs/script/find-tide-worker-log.sh`


## Evidence Update 1

Baseline from target coordinator `fdbd:dc05:11:634::45`:

- `pulse-coordinator.service` active, PID `1400636`.
- Java process running for ~41 minutes, no recent journal exceptions.
- Listen socket on `*:9966` shows backlog/send-q `50`.
- Many established agent/peer connections exist on port 9966.
- Target host currently lacks JDK attach tools; Arthas boot is present but attach support needs a JDK/full Arthas runtime.

Reproduction:
- From coordinator localhost (`127.0.0.1:9966`), 50 concurrent POST `/api/agents/{id}/tasks` all succeeded: `ok=50 failed=0`, slowest 5ms.
- From local machine to `http://[fdbd:dc05:11:634::45]:9966/api/hosts`, 5/5 GET requests timed out after 12s.

Interpretation:
- Coordinator handler and synchronized service path are not the immediate bottleneck for 50 submissions.
- Failures seen in browser are consistent with external/browser-to-coordinator HTTP connection instability or burst sensitivity, not agent task execution.

## Fix Implemented Locally

Runtime/JVM evidence summary:
- Coordinator Java process is healthy: ~95 threads, ~102 FDs, no recent journal exceptions.
- Localhost 50-concurrent POST to coordinator succeeded completely: `ok=50 failed=0`, slowest ~5ms.
- External local-machine GET to `http://[fdbd:dc05:11:634::45]:9966/api/hosts` timed out repeatedly.
- Listen socket showed backlog/send-q `50` before fix.

Root cause:
- The immediate UI failures are browser/external HTTP connection failures during burst submission, not coordinator task creation or agent execution failures.
- The UI submitted all targets at once without retry, so transient network/accept failures became permanent `提交失败` rows.

Fix:
- Backend: coordinator HTTP backlog now defaults to `512` via `PULSE_HTTP_BACKLOG` instead of JDK/OS default backlog around `50`.
- Frontend: cluster submit path now uses max 6 concurrent POSTs and retries retryable network errors twice.
- Affects predefined task, file upload, and shell script submit paths.

Validation:
- `npm run build` passed.
- `mvn test` passed: 39 tests, 0 failures.

## Deployment and Post-Fix Verification

Commit:
- `127b443 Throttle and retry cluster task submissions`

JAR SHA:
- `580f7b65ef4aa5b8cc30854099a09a3bab4692661a34d7f3e2fee2c68d419030`

Deployment:
- coordinators deployed: 3/3
- deployment script verification: 3/3 ok
- independent strong verification:
  - SHA: 3/3 ok
  - `pulse-agent.service`: 3/3 active
  - `pulse-coordinator.service`: 3/3 active
  - coordinator API: 3/3 ok
  - listen backlog: 3/3 shows `512`

Post-fix cdn_new verification:
- Script: local `docs/script/find-tide-worker-log.sh`
- Submit behavior: throttled in batches of 6 with retry, matching the UI fix.
- targets: 50
- submitted: 50
- submit failed: 0
- completed: 50
- failed: 0
- pending: 0

Arthas note:
- Followed `docs/debug/arthas.md` and deployed `arthas-boot.jar` to the coordinator host.
- The deployed runtime remains bundled JRE without JDK attach tools (`jcmd`, `jstack`, `jdk.attach`, `libattach` absent), so full Arthas attach is not available in the current production runtime.
- Runtime evidence was collected from service logs, `/proc`, thread/process state, socket/backlog state, and live API/POST reproduction.

Final root cause:
- Not agent execution failure and not coordinator task creation failure.
- The UI was issuing a burst of 50 browser-to-coordinator POSTs. The external HTTP path to `fdbd:dc05:11:634::45:9966` is unstable under direct browser/local access, while coordinator-local 50 concurrent POSTs succeed in milliseconds.
- Original UI had no throttling or retry, so transient `Failed to fetch` became immediate permanent submit failures.

Status: FIXED, DEBUG ARTIFACTS RETAINED
