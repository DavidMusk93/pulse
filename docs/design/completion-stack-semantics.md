# Completion Stack Semantics

## Context

Single-node Run UI displays task completion results returned by `TaskSnapshot.completion_queue`.
The historical implementation exposed and rendered this list as a FIFO queue, so stale older
results could appear before the latest execution result.

## Decision

User-facing completion results use stack semantics:

- `TaskSnapshot.completion_queue` is returned newest-first.
- The first item is the stack top and represents the latest completed result.
- `pop` removes only the current stack top.
- `pop` is disabled in the UI and no-op in the backend while the agent has queued, control-queued,
  in-flight, or running tasks.
- The button label includes the current stack size.

## Rationale

The Run UI is used to inspect the latest execution. Showing newest results first prevents stale
historical failures from being mistaken for the current run. Disabling pop during execution avoids
removing historical results while a new task is still being delivered or running.

## Implementation Notes

- `RemoteTaskService` may keep internal completion storage as FIFO, but `snapshot()` reverses it
  for UI/API consumers.
- `popCompletion()` checks the top of the internal completion deque and only removes it when the
  requested task id matches the stack top.
- `TaskModal` renders a `结果栈` card with the newest result first.
