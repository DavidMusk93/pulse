# Debug Session: coordinator-failed-nodes

Status: OPEN

User request:
- Debug coordinator because there are still failed nodes in cluster task execution.
- Improve task display: do not truncate task information because the UI has enough space.

Constraints:
- Collect runtime evidence before changing coordinator behavior.
- UI display-only changes are allowed after locating the rendering path.
- Keep debug artifacts until user confirms cleanup.

Hypotheses:
1. The two submit failures are browser/coordinator request failures, not agent execution failures.
2. Some agents are alive but have not polled the coordinator yet, so they remain pending/running under high load.
3. The failed nodes have task results in completion queues with non-zero exit code.
4. The coordinator holds stale batch state after restart or after UI refresh, causing mismatch between displayed submit failures and server-side queues.
5. The UI truncates task identifiers because of CSS constraints rather than missing backend data.

Evidence Log:
- Pending.

## Evidence Update 1

Coordinator snapshot from `fdbd:dc05:11:634::45`:

- `cdn_new` hosts: 50/50 alive.
- One explicit failed execution result:
  - agent: `dc05-p11-t636-n016.byted.org`
  - ip: `fdbd:dc05:11:636::16`
  - task: `task-72bb85a9-b100-4b3c-8197-36abeb50f090`
  - status: `rejected`
  - runner_error: `script file is not staged`
- Trace ordering for the failed task:
  - `file.enqueued` at `1780648354659`
  - file transfer status stayed `delivering`; no `file.received_by_agent`
  - `shell.dequeued_for_delivery` at `1780648383584`
  - `task.result_received` status `rejected` at `1780648398921`

Conclusion:
- Coordinator sent `cmd.shell_execute` before the target agent acknowledged the staged shell script file.
- This is a coordinator-side sequencing bug, not a script failure.

## Fix Implemented Locally

- `RemoteTaskService#nextCommand` now peeks the control queue first.
- `cmd.file_put` is still delivered immediately.
- `cmd.shell_execute` is not dequeued or delivered until the related file transfer status is `received`.
- Added regression: shell execute waits before file ack and is delivered immediately after `reply.file_received`.
- UI task id display no longer truncates with ellipsis; task ids can wrap and show fully.

Validation:
- `npm run build` passed.
- `mvn test` passed: 39 tests, 0 failures.

## Final Verification

Commit deployed to coordinators: `eff18bd`.

New JAR SHA:
- `0ee8909888002d6174504c70f567d0e371bdfacf6616687fd4f6da4e6ce7a764`

Deployment:
- coordinators: 3/3 deployed successfully
- strong verify: 3/3 SHA/service/API ok

Post-fix batch verification:
- waited until `cdn_new` alive hosts reached 50/50
- submitted `state-machine-verify.sh` to 50 agents
- submit failures: 0
- completed: 50
- failed: 0
- pending: 0

Important observations:
- Before the full state-machine fix, one host could stay in `delivering` because `cmd.file_put` was removed from the queue before `reply.file_received` arrived.
- The final state-machine implementation keeps `FilePut` queued until ack, so a lost heartbeat response can be retried.
- `ShellExecute` remains blocked until `FilePut.received`.

Status: FIXED, DEBUG ARTIFACTS RETAINED

## UI Submit Failure Reconciliation

Runtime snapshot from coordinator `fdbd:dc05:11:634::45` showed:
- `cdn_new`: 50 hosts
- alive: 50
- latest completions: 50 completed, 0 non-ok

The UI screenshot displayed `提交失败 2` while execution rows showed all 50 completed. This is a UI reconciliation issue: transient browser/coordinator fetch failures were kept in the static submit summary even after per-host task snapshots later proved those hosts completed successfully.

Fix:
- Batch summary now records failed agent IDs.
- Cluster summary subtracts submit failures once the same agent has a non-pending execution snapshot/completion.
- Error banner is hidden when all submit failures have been reconciled by execution results.

Real script verification:
- Submitted local `docs/script/find-tide-worker-log.sh` through the same Shell API to `cdn_new`.
- Targets: 50
- Submitted: 50
- Completed: 50
- Failed: 0
- Pending: 0

## UI Fix Deployment

Commit: `cd5c2d6 Reconcile cluster submit failures in UI`

JAR SHA:
- `f6786d6f4d3214a5b7ed46171c51907e1bcf5a785ba93765d1e3ff5b68a601ef`

Coordinator deployment:
- 3/3 deployed successfully.
- Strong verification: 3/3 SHA, agent service, coordinator service, API ok.
- Static resource verification:
  - `output-title-status-stack` present in CSS.
  - cluster execution output background is white.
  - `failedAgents` reconciliation logic present in JS.

Current interpretation:
- The two immediate failures shown in the UI were not current agent failures. The coordinator snapshot showed 50 latest completions all `completed exit 0`.
- UI now reconciles submit failures by failed agent id and clears submit-failed/error display after the same agent has a non-pending execution result.
