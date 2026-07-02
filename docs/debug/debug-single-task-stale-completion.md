# Debug Session: single-task stale completion display

Status: FIXED

## Symptom

- User reported that executing a single-machine task from the coordinator UI at `http://[fdbd:dc05:11:634::45]:6699/` appeared to crash/fail.
- Direct HTTP access to `[fdbd:dc05:11:634::45]:6699` timed out.
- Access through the expected local SOCKS proxy path `socks5h://127.0.0.1:6699` to coordinator `http://[fdbd:dc05:11:634::45]:9966` succeeded.

## Runtime Evidence

- Coordinator API health:
  - `/api/hosts` through SOCKS proxy returned `200`, about `1MB` host payload.
  - `/api/metrics/storage` returned `status=ok`, `queue_depth=0`, `failed_commands=0`, `last_error=""`.
- Reproduction target:
  - agent: `fdbd:dc02:1a:34::13`
  - status: `alive`
  - source group: `cdn2/hl/001`
  - observed load: low at probe time.
- Reproduced a single-agent `prepare_disk_layout_dry_run` submit through:
  - `POST /api/agents/fdbd%3Adc02%3A1a%3A34%3A%3A13/tasks`
  - body: `{"task_type":"prepare_disk_layout_dry_run","args":[]}`
- Submit and snapshot HTTP calls returned `200`; coordinator did not crash.
- After polling, the new task results were completed:
  - `task-f001e78a-bec8-4ee9-b100-cccf67dd4742`: `completed`, `exit_code=0`
  - `task-f08b1fa0-8e10-4f5d-977f-58a00275ec05`: `completed`, `exit_code=0`
- The first completion queue entry was an older failed task:
  - `task-36a5ac4f-b432-467b-9c9c-d17ee528ff46`
  - `status=failed`
  - `exit_code=1`
  - `runner_error=exit_code=1`
  - output showed disk health failure on `/data03`, with recent kernel `Medium Error` entries for block device `sdd`.

## Root Cause

- `RemoteTaskService.complete()` appends new results with `completionQueue.addLast(result)`, so `completion_queue[0]` is the oldest retained result.
- The frontend single-task modal used `completion_queue[0]` as the latest result in:
  - `applyTaskSnapshot`
  - `TaskModal`
- Therefore a stale failed completion at the FIFO queue head was displayed as the current single-machine task result, even after later tasks completed successfully.
- The visible failure was not a coordinator process crash. It was stale result selection plus a real historical agent-side disk health failure.

## Fix

- Added `newestCompletion(snapshot)` in `src/main/frontend/src/main.tsx`.
- Single-task output display now uses the newest completion, i.e. the last item in `completion_queue`.
- Existing FIFO pop behavior is unchanged; the `弹出结果` action still pops the backend queue head by existing protocol.

## Verification

- `npm run build` passed.
- `mvn test -Dskip.frontend.build=true` passed:
  - `79` tests
  - `0` failures
  - `0` errors

## Temporary Evidence

Runtime evidence was captured under `.tmp/` and is intentionally not committed:

- `.tmp/data/proxy-9966-hosts.json`
- `.tmp/data/proxy-9966-metrics-storage.json`
- `.tmp/data/single-task-submit-response.json`
- `.tmp/data/single-task-snapshot-*.json`
- `.tmp/scripts/repro-single-task.sh`
