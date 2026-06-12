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

- Pending.

## Report

- Pending.
