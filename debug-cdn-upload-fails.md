# Debug Session: cdn-upload-fails

Status: [OPEN]
Started: 2026-06-12

## Symptom

- `cdn_new` cluster file upload fails immediately in online debug UI.
- Page: `http://[fdbd:dc05:11:634::45]:9966/`
- Visible aggregate error: `请求被 coordinator 拒绝：400 Bad Request`
- Observed UI state: target 50, submit success 16, submit failed 34, executed 0.

## Constraints

- Do not change business logic before runtime evidence is collected.
- First existing-code change must be instrumentation only.
- Commit and push each code/debugging change promptly.

## Hypotheses

- H1: The frontend/batch submit payload for `cdn_new` omits or misformats a coordinator-required upload parameter.
- H2: The `cdn_new` target host list or batch split contains invalid targets, and coordinator rejects affected submissions before execution.
- H3: Upload destination `$agent_work_dir/files` fails coordinator-side parsing or allowlist validation for this cluster.
- H4: File upload multipart field names, metadata, or body shape no longer match the coordinator API contract.
- H5: The IPv6 online debug access path or proxy modifies request context enough to trigger coordinator validation failure.

## Evidence Log

- Pending: inspect request path and code structure.
- Pending: reproduce via browser/network and collect exact failing request payload/response.

## Decisions

- Pending.
