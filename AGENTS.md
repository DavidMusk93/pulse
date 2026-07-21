# Agent Operating Rules

## Differential Deployment

- Compare local and remote SHA-256 before every agent update.
- Upload only files whose content changed; do not resend the JAR or unrelated task scripts.
- A task-script-only update must not restart `pulse-agent.service`; verify the destination SHA after replacement.
- If the remote file is missing, unreadable, or has an unknown checksum, classify it as changed and upload it.
- Preserve per-host `unchanged`, `updated`, and `failed` results in `.tmp/auto-ops/` evidence.
- A deployment summary is not completion evidence. Final completion requires raw per-host SHA and service verification.
- Any change under `docs/task/` requires a task-only differential sync in the same delivery stage; a local task edit or JAR deployment alone is not complete.
- For every changed task file, run the matching task diff callee with the exact inventory/tag and `--max-hosts`, then preserve raw per-host `unchanged`, `updated`, and `failed` evidence plus a post-sync SHA verification.
- A task-only sync must not upload the JAR or restart `pulse-agent.service`; completion requires proving both invariants from raw command output.
- When multiple task files change, sync and verify each changed path explicitly; never assume a previous bundle or JAR rollout synchronized them.

## Coordinator Access

- Online coordinator pages (e.g., `http://[fdbd:dc05:11:634::45]:9966/`) require SSH v23 proxy; direct browser access is not possible.
- For UI verification, use remote `curl` scripts via SSH instead of `browser_navigate`.

## TLB Operations

- Use auto-ops central runtime with an explicit host scope and `--max-hosts`.
- Refresh `orthrus-cli` temporary permissions immediately before SSH verification.
- Keep IPv4/IPv6 failures classified separately; do not treat an Orthrus summary as proof of SSH access.

## Patch Delivery

- Deliver each feature, test, or fix as a cohesive patch commit as soon as its validation is complete.
- Keep implementation changes and their focused tests in the same patch when they belong to one behavior change.
- Run the relevant tests and `git diff --check` before committing.
- Push every validated patch to the current upstream branch promptly; do not leave completed work only in the local worktree.
- Keep unrelated generated files and user changes out of the patch.

## Memory

- Use nmem (Nowledge Mem) as the only project memory module and source of durable memory.
- Record every reusable experience, design decision, reasoning, guideline, procedure, and handoff in nmem.
- Before related work, search nmem for prior decisions and procedures; after the work, update nmem with the resulting evidence and next action.
- Write nmem entries as human-readable knowledge cards with: `Context / Evidence / Flow / Source Binding / Lessons / Next Action`.
