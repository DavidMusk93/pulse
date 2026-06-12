# Debug Session: file-distribution-efficiency

Status: [OPEN]
Started: 2026-06-12

## Question

Evaluate whether file distribution efficiency matches the expected group-leader design:

- File distribution should go through group leaders.
- Coordinator pressure should be low.
- Evidence must come from runtime trace/logs and current implementation, not guesswork.

## Constraints

- Do not change business logic before runtime evidence is collected.
- Generate an evidence-based report document.
- Commit and push each change promptly.

## Hypotheses

- H1: File content is delivered via group leader to followers, so coordinator only communicates with leaders for follower delivery.
- H2: Even when using group leaders, coordinator still serializes one file payload per target agent in heartbeat batch responses, so bytes remain O(N * file_size).
- H3: Some agents use direct mode during plan convergence or restart, increasing coordinator pressure beyond the expected group path.
- H4: File receive acks are urgent, but file delivery itself lacks group-level content deduplication or one-to-many fanout.
- H5: Existing traces prove per-agent delivery/ack but lack first-class group distribution byte metrics.

## Evidence Log

- Static chain:
  - Follower heartbeat goes to leader `/group/heartbeat`.
  - Leader sends batch heartbeat to coordinator.
  - Coordinator responds with `AgentHeartbeatResponse` per agent.
  - Leader stores `planMessages[agentId]`; follower pulls its own messages from leader.
- Static payload:
  - `RemoteTaskService.ControlCommand.toFilePutMessage` embeds full `content` base64 in every `cmd.file_put`.
  - `CoordinatorService.responseMessages(agentId)` calls `taskService.nextCommand(agentId)` per agent.
  - No code path found for `application/vnd.pulse.binary` or group-level shared file payload.
- Runtime host snapshot:
  - cdn count: 50
  - group modes: 7 leaders, 43 followers
  - groups: 7 groups with sizes 1, 11, 7, 8, 7, 8, 8
  - coordinator owners: 24, 25, 1
- Runtime metrics:
  - `group.submitted_agent_count`: 7 series, latest sum 50, max 11
  - `group.accepted_agent_count`: 7 series, latest sum 50, max 11
  - `group.direct_fallback_count`: 7 series, latest sum 0
  - `group.leader_collect_ms`: latest max 1ms
  - `group.group_latency_ms`: latest max 2.5ms
- Controlled probe:
  - file: `efficiency-probe-1781238290360.bin`
  - raw bytes: 131072
  - base64 chars: 174764
  - submit status: 50/50 HTTP 200
  - submit wall time: 69ms, per-submit p50 4ms, max 19ms
  - polling: 0.11s => 36 queued/14 delivering; 5.16s => 2 received/48 delivering; 10.19s => 19 received/31 delivering; 15.23s => 50 received
  - file delivery latency: min 10457ms, p50 14783ms, p95 15081ms, max 15083ms
  - leader and follower latency p50 both around 14783ms
- Byte estimate:
  - current coordinator downstream payload copies: 174764 base64 chars * 50 = 8,738,200 chars before JSON overhead.
  - ideal group-level shared content copies: 174764 * 7 = 1,223,348 chars before JSON overhead.
  - current file content bytes are about 7.14x the group-shared lower bound for 50 nodes / 7 groups.

## Report

- `docs/report/file-distribution-efficiency-2026-06-12.md`

## P0 Implementation

- Added group-level file distribution metrics:
  - `group.response_bytes`
  - `group.file_payload_bytes`
  - `group.file_payload_base64_bytes`
  - `group.file_command_copy_count`
  - `group.file_unique_content_count`
  - `group.file_shared_lower_bound_bytes`
- Added SQLite schema columns and backward-compatible `ALTER TABLE ... ADD COLUMN` migration.
- Coordinator now computes metrics from actual batch heartbeat responses after per-agent messages are generated.
- Verification:
  - `mvn -Dtest=LocalMetricStorageTest test`
  - `mvn -DskipTests package`
