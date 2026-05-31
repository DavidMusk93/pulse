# Lossless Remote Task Deployment Verification

## Summary

- Commit: `24c6636 Implement lossless task result transport`
- Jar: `target/pulse-0.1.0-SNAPSHOT.jar`
- SHA256: `20820db08c74a00d55a88de2733ae175f1fcb674f5e737727d0e410ddc2b7950`
- Date: 2026-05-31

## Implementation

- Removed lossy task result fields from the active protocol: `stdout_tail`, `stderr_tail`, `output_truncated`.
- Agent now captures task stdout as complete `output`, computes `output_sha256` and `output_bytes`, then chooses `identity` or `gzip+base64`.
- Large encoded outputs are split into `reply.task_result_chunk` messages and replayed across heartbeats.
- Coordinator reassembles chunks by `task_id + trace_id`, validates chunk hash and full output hash, then inserts completion only after lossless reconstruction.
- UI reads only `output`, `output_sha256`, `output_bytes`, `output_encoding`, and `output_type`.
- TLB SSH triage runbook added at `docs/debug/tlb-ssh-node-triage.md`.

## Local Verification

- `mvn test`: 22 tests, 0 failures, 0 errors.
- `mvn package`: success.
- Added test coverage for out-of-order chunk reassembly and SHA256 validation.

## Deployment

Coordinator URLs:

- `fdbd:dc05:11:634::45`
- `fdbd:dc05:13:10c::40`
- `fdbd:dc07:0:810::44`

Deploy results:

| Cluster | Scope | Result | Elapsed |
| --- | ---: | --- | ---: |
| `cdn_new` | 50 | 50 ok, 0 failed | 107s |
| `doubao` | 8 | 8 ok, 0 failed | 53s |
| `tlbmirror` | 5 | 5 ok, 0 failed | 54s |
| `tlb` | 330 | 330 ok, 0 failed | 395s |

Coordinator jar SHA verification:

| Coordinator | SHA256 |
| --- | --- |
| `fdbd:dc05:11:634::45` | `20820db08c74a00d55a88de2733ae175f1fcb674f5e737727d0e410ddc2b7950` |
| `fdbd:dc05:13:10c::40` | `20820db08c74a00d55a88de2733ae175f1fcb674f5e737727d0e410ddc2b7950` |
| `fdbd:dc07:0:810::44` | `20820db08c74a00d55a88de2733ae175f1fcb674f5e737727d0e410ddc2b7950` |

## UI/API Verification

Checked `/hosts` through coordinator-local curl:

- Contains `output_sha256`.
- Does not contain `stdout_tail`.
- Does not contain `stderr_tail`.
- Does not contain `output_truncated`.

Heartbeat view after rollout:

| Coordinator | Total | Alive |
| --- | ---: | ---: |
| `fdbd:dc05:11:634::45` | 378 | 372 |
| `fdbd:dc05:13:10c::40` | 378 | 371 |
| `fdbd:dc07:0:810::44` | 378 | 372 |

The remaining non-alive hosts are concentrated in `tlb` and match the known network/SSH instability pattern.

## E2E Verification

### Lossless Failure Result

- Agent: `dc02-p1a-t34-n013.byted.org`
- Task: `task-8f40320e-a4a8-4559-b016-1428083b4c5f`
- Trace: `trace-7ff6b2ec-ec68-45f3-9ed0-b99c2db8fe0c`
- Task type: `prepare_disk_layout_dry_run`
- Status: `failed`
- Exit code: `1`
- Output bytes: `6652`
- Encoding: `gzip+base64`
- SHA256: `554db5fd61ecc1e3f90856bc96fbcf7eb5bf5690ab631171c0fdf1b64c470ee7`
- Result visible on all 3 coordinators.
- Local recomputed SHA256 matched `output_sha256`.
- No legacy lossy fields were present.

### Lossless Successful Result

- Agent: `dc05-p11-t636-n032.byted.org`
- Task: `task-252c205c-47d2-4c13-b6af-beaacbaad53e`
- Trace: `trace-5450a5d4-2c3b-4bd0-ae11-7d5f63604620`
- Task type: `analyze_block_layout_dry_run`
- Status: `completed`
- Exit code: `0`
- Output bytes: `91037`
- Encoding: `gzip+base64`
- SHA256: `9698f8dc30b313c1d09bdc2d30421837078738bc1ee65d81be098c53c7636405`
- Result visible on all 3 coordinators.
- Local recomputed SHA256 matched `output_sha256`.
- No legacy lossy fields were present.

## TLB SSH Findings

During `tlb` deployment, these IPv4 nodes showed repeated SSH/SCP timeout symptoms before deployment completed:

- `10.162.231.34`
- `10.162.231.36`
- `10.162.231.37`
- `10.162.231.40`
- `10.162.231.41`

Post-run local checks:

| Host | Ping | SSH |
| --- | --- | --- |
| `10.162.231.34` | failed | failed after ~12s |
| `10.162.231.36` | failed | failed after ~12s |
| `10.162.231.37` | failed | failed after ~12s |
| `10.162.231.40` | failed | failed after ~12s |
| `10.162.231.41` | failed | failed after ~12s |

Classification:

- From local control host: `node_or_network_unreachable`.
- From batch deployment timeline: intermittent path, because the overall `tlb` deployment eventually returned `330 ok`.
- Follow-up path is documented in `docs/debug/tlb-ssh-node-triage.md`: try `tiger` login, get `eth0` IPv4, run `orthrus-cil demand <ipv4>`, then retry deployment/verification.
