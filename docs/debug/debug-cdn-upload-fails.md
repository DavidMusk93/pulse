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

- Static chain: frontend uploads file content as JSON/base64 via `POST /api/agents/{agentId}/tasks`; coordinator handles `operation=file_put`.
- Static 400 point: `CoordinatorHttpServer.handle` converts `IllegalArgumentException` into `400 {"ok":false,"error":...}`.
- External browser/curl path to `http://[fdbd:dc05:11:634::45]:9966/` currently times out or returns an empty reply, while SSH-local coordinator API is responsive.
- Runtime env on `fdbd:dc05:11:634::45`: `PULSE_COORDINATOR_ID=fdbd:dc05:11:634::45`, peers are bracketed URLs, but `PULSE_TASK_ROUTE_TEMPLATE` is absent.
- Runtime owner split from local `/api/hosts` for `cdn2/cdn_new`: `fdbd:dc05:11:634::45=16`, `fdbd:dc05:13:10c::40=22`, `fdbd:dc07:0:810::44=12`.
- Runtime reproduction on local coordinator:
  - local-owner file upload to `fdbd:dc05:11:636::32` returned `200`.
  - remote-owner file upload to `fdbd:dc02:1a:34::13` returned `400 {"error":"unsupported URI http://fdbd:dc07:0:810::44:9966/api/agents/fdbd%3Adc02%3A1a%3A34%3A%3A13/tasks","ok":false}`.

## Analysis

- H1 rejected: required upload fields are accepted for local-owner agents with the same payload.
- H2 partially confirmed: failures correlate with remote-owner agents, not invalid target hosts.
- H3 rejected: `target_dir=files` is accepted for local-owner upload.
- H4 rejected: JSON/base64 payload shape is accepted for local-owner upload.
- H5 refined: IPv6 identity breaks the coordinator-to-coordinator task route URL when the default route base is constructed from raw `coordinatorId`.
- Root cause: `CoordinatorHttpServer.taskRouteBase` builds `http://` + raw IPv6 coordinator id + `:9966`, producing invalid URI like `http://fdbd:dc07:0:810::44:9966/...`. This throws `IllegalArgumentException`, which the HTTP handler returns as `400`.

## Decisions

- Fix should make the default task route base IPv6-safe by bracketing raw IPv6 host literals.
- Deployment env already brackets `PULSE_COORDINATOR_PEERS`; the missing path is only the default `PULSE_TASK_ROUTE_TEMPLATE` fallback.

## Fix Log

- Code fix: `CoordinatorHttpServer.taskRouteBase` now uses `routeHost(coordinatorId)`.
- `routeHost` wraps raw IPv6 coordinator IDs in `[]` and leaves already-bracketed IPv6 or hostname IDs unchanged.
- Regression test added: `CoordinatorHttpServerTest#defaultTaskRouteHostBracketsIpv6CoordinatorIds`.
- Diagnostics: no VS Code diagnostics in modified Java files.
- Focused verification passed: `mvn -Dtest=CoordinatorHttpServerTest#defaultTaskRouteHostBracketsIpv6CoordinatorIds test`.
- Broader `mvn -Dtest=CoordinatorHttpServerTest test` currently has unrelated existing failures in heartbeat/metrics tests, not in the route-host regression path.

## Deployment

- Commit deployed: `1bcb6e6 Fix IPv6 coordinator task routing`.
- Built artifact: `target/pulse-0.1.0-SNAPSHOT.jar`.
- JAR SHA: `13555e7a6c74f3dc5e20504ec3223091e086488b67c70268ac0e05e2ca344666`.
- Coordinators deployed and restarted successfully:
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`

## Post-Fix Evidence

- Same local-owner probe on `fdbd:dc05:11:634::45` returned `200 OK`.
- Same remote-owner probe that previously returned `400 unsupported URI http://fdbd:dc07:0:810::44:9966/...` now returned `200 OK` with `file.enqueued`.
- Full `cdn2/cdn_new` small-file submit probe from `fdbd:dc05:11:634::45`:
  - total: `50`
  - ok: `50`
  - fail: `0`

## Current Status

- Root cause fixed and deployed.
- Upload submission no longer fails immediately for remote-owner agents due to IPv6 route URI construction.
