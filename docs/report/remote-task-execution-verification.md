# Remote Task Execution Verification

## Summary

- Final commit: `9ec55d4 Harden remote task trace replay`
- Previous implementation commit: `c7cce17 Implement remote task execution`
- Multi-coordinator result replay commit: `9524922 Replicate task results across coordinators`
- Jar: `target/pulse-0.1.0-SNAPSHOT.jar`
- Final jar SHA256: `9b7cbcc0ffcbf863067f2c6366f6398c5ee629a223b7a8a574a7bcc17002ce0f`
- Remote install root: `/data24/otf/pulse`
- Remote task directory: `/data24/otf/pulse/tasks`

## Repository Task Scripts

The two allowlisted dry-run task scripts are part of the Pulse repository and are deployed with Pulse:

| Task ID | Repository Path | Remote Path | Args |
| --- | --- | --- | --- |
| `prepare_disk_layout_dry_run` | `docs/task/prepare-disk-layout.sh` | `/data24/otf/pulse/tasks/prepare-disk-layout.sh` | `--dry-run` |
| `analyze_block_layout_dry_run` | `docs/task/analyze-block-layout.py` | `/data24/otf/pulse/tasks/analyze-block-layout.py` | `--dry-run` |

The deployment script writes `PULSE_TASK_DIR=/data24/otf/pulse/tasks` into both agent and coordinator env files.

## Local Verification

Commands:

```bash
mvn test
mvn package
shasum -a 256 target/pulse-0.1.0-SNAPSHOT.jar
```

Results:

- `mvn test`: `20 tests, 0 failures, 0 errors`
- `mvn package`: success
- SHA256: `9b7cbcc0ffcbf863067f2c6366f6398c5ee629a223b7a8a574a7bcc17002ce0f`
- VS Code diagnostics: no diagnostics for `AgentTaskRunner.java` and `RemoteTaskService.java`

## Final Fixes

The first online E2E exposed two final hardening issues:

- `analyze_block_layout_dry_run` exceeded the original `120s` timeout on a large host and returned `timed_out`.
- `reply.task_accepted` could land on a non-origin coordinator because agents rotate coordinator URLs, so the origin coordinator trace could miss `task.accepted_by_agent`.

Final fixes:

- Task timeout increased to `600000ms`.
- Coordinator sends `timeout_ms=600000` in `cmd.task_execute`.
- Agent uses the `timeout_ms` from the command payload, with `600000ms` fallback.
- Agent replays both `reply.task_accepted` and `reply.task_result` for 3 heartbeat sends.
- Coordinator still deduplicates completion queue by `task_id`.

## Deployment

Dry-run scope confirmation:

| Cluster | Expected | Dry-run |
| --- | ---: | --- |
| `cdn_new` | 50 | `total=50` |
| `doubao` | 8 | `total=8` |
| `tlbmirror` | 5 | `total=5` |

Final deployment results:

| Cluster | Scope | Result |
| --- | ---: | --- |
| `cdn_new` | 50 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 5 | `summary: total=5 ok=5 failed=0` |

## Install Verification

Read-only install verification checks:

- `prepare-disk-layout.sh` exists and is executable.
- `analyze-block-layout.py` exists and is executable.
- `pulse-agent.env` contains `PULSE_TASK_DIR=/data24/otf/pulse/tasks`.
- `pulse-agent.service` is active.

Results:

| Cluster | Scope | Result |
| --- | ---: | --- |
| `cdn_new` | 50 | `summary: total=50 ok=50 failed=0` |
| `doubao` | 8 | `summary: total=8 ok=8 failed=0` |
| `tlbmirror` | 5 | `summary: total=5 ok=5 failed=0` |

## Online E2E

Verification script: `.tmp/verify_remote_task_e2e.py`

Target coordinator set:

- `fdbd:dc05:11:634::45`
- `fdbd:dc05:13:10c::40`
- `fdbd:dc07:0:810::44`

Host API results after final deployment:

| Coordinator | Total | Alive | Warming | Expired |
| --- | ---: | ---: | ---: | ---: |
| `fdbd:dc05:11:634::45` | 63 | 63 | 0 | 0 |
| `fdbd:dc05:13:10c::40` | 63 | 63 | 0 | 0 |
| `fdbd:dc07:0:810::44` | 63 | 63 | 0 | 0 |

UI checks:

- `/hosts` contains `data-action="run-task"`.
- `/hosts` contains `task-modal`.
- `/hosts` contains both allowlisted task IDs.
- `/hosts` does not contain arbitrary `shell command` input.

Task execution:

| Field | Value |
| --- | --- |
| Agent | `dc02-p1a-t34-n013.byted.org` |
| Task ID | `task-0238811e-8066-408f-9b88-443d7f92bc33` |
| Trace ID | `trace-d710ee10-5fc2-4619-b90b-cc47fc9f2c35` |
| Task Type | `analyze_block_layout_dry_run` |
| Args | `["--dry-run"]` |
| Status | `completed` |
| Exit Code | `0` |
| Duration | `47896ms` |
| Stdout Tail | `65536` bytes |
| Stderr Tail | `0` bytes |

Completion replay results:

| Coordinator | Completion Visible | Status | Exit Code | Trace ID |
| --- | --- | --- | ---: | --- |
| `fdbd:dc05:11:634::45` | yes | `completed` | 0 | `trace-d710ee10-5fc2-4619-b90b-cc47fc9f2c35` |
| `fdbd:dc05:13:10c::40` | yes | `completed` | 0 | `trace-d710ee10-5fc2-4619-b90b-cc47fc9f2c35` |
| `fdbd:dc07:0:810::44` | yes | `completed` | 0 | `trace-d710ee10-5fc2-4619-b90b-cc47fc9f2c35` |

Trace events on origin coordinator:

- `task.enqueued`
- `task.dequeued_for_delivery`
- `task.accepted_by_agent`
- `task.result_received`

Completion queue actions:

- `keep`: result remains visible.
- `pop`: result is removed from the origin coordinator completion queue.

## Notes

- `.tmp/verify_remote_task_e2e.py` and `.tmp/verify_remote_task_install.sh` are temporary verification helpers under ignored `.tmp/`.
- The online E2E intentionally used `analyze_block_layout_dry_run` to verify the longer timeout and output tail behavior.
- Completion replay across all three coordinators was verified for the same `task_id`.
