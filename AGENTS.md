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
- For UI visual verification, create an SSH local tunnel to the Coordinator and use the built-in browser against `127.0.0.1`; do not treat remote `curl`, static asset markers, or HTTP 200 as visual acceptance evidence.

## UI Development Gate

- UI development must optimize for visible user experience: aesthetics, clarity, spatial economy, and user confidence are first-class product requirements.
- The goal is "what the user sees is what the user gets"; implementation is not complete until the actual rendered interface has been inspected at a production-like viewport.
- Excellent user experience is the product's core competitiveness; technically impressive internals do not compensate for an ugly, confusing, or wasteful UI.
- Before shipping any user-visible UI change, perform built-in-browser validation after the change is built and, for production Coordinator work, after canary deployment.
- Capture concrete visual evidence from the browser: screenshot when available; otherwise DOM geometry, computed styles, wrap/overflow checks, and viewport dimensions.
- Reject layouts with hidden text, accidental wrapping, overlap, orphaned controls, excessive blank space, weak visual hierarchy, or status information detached from the element it explains.
- If direct browser access to a Coordinator URL fails, use SSH local forwarding and open the forwarded `127.0.0.1` URL in the built-in browser.
- Operation logs for UI production changes must include the browser validation method and the visual acceptance evidence or the exact limitation that prevented screenshot capture.

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

## Operations Logs

- Record each production operation in a new self-contained file under `docs/ops/` named `YYYY-MM-DD-<scope>-<action>.md`.
- Never append a new operation to an existing aggregate changelog; append-oriented logs are not accepted because they degrade ownership, reviewability, and long-term maintenance.
- Treat a completed operations log as immutable except for factual corrections to that operation.
- Bind every operations log to its inventory scope, artifact SHA, raw evidence, verification result, rollback point, and delivery commit.

## Memory

- Use nmem (Nowledge Mem) as the only project memory module and source of durable memory.
- Record every reusable experience, design decision, reasoning, guideline, procedure, and handoff in nmem.
- Before related work, search nmem for prior decisions and procedures; after the work, update nmem with the resulting evidence and next action.
- Write nmem entries as human-readable knowledge cards with: `Context / Evidence / Flow / Source Binding / Lessons / Next Action`.
