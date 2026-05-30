# Debug Session: heartbeat-group-expiry

Status: CLOSED

## Hypotheses

- H1: agent 进程崩溃或没有重启到新版本，导致 host 过期。
- H2: follower 无法访问 leader 本地 `/group/heartbeat` 端口，导致 follower 不再进入 coordinator。
- H3: leader batch heartbeat 只轮转发送到单个 coordinator，而 coordinator 间没有复制，导致每个 coordinator 更新周期接近 TTL 边界。
- H4: coordinator 动态分组重算产生 leader/follower 震荡，导致部分 group 无法稳定上报。

## Evidence

- `systemctl is-active pulse-agent` on `fdbd:dc05:11:636::17`: active, rejects H1.
- coordinator `/api/hosts` after deployment:
  - coordinator 1: total=63, alive=32, expired=31, direct=16
  - coordinator 2: total=48, alive=32, expired=16, direct=6
  - coordinator 3: total=44, alive=32, expired=12, direct=4
- all coordinators only partially fresh while agents are active, supporting H3.

## Planned Fix

- Keep group assignment inside coordinator heartbeat processing.
- Do not add new API.
- Continue returning `cmd.group_plan` through heartbeat responses.
- Broadcast leader batch heartbeat to all coordinators.
- Increase deployed agent TTL from 15s to 30s to avoid coordinator fanout timing edge.

## Resolution

- H3 was confirmed as a partial cause: leader batch heartbeat must broadcast to all coordinators because coordinators do not replicate state.
- A second issue was found: every dynamic agent listened on `/group/heartbeat`, so non-leader nodes could accept follower heartbeats and return stale cached plans.
- Fixes:
  - leader batch heartbeat broadcasts to all coordinators.
  - agents prefer the primary coordinator response as the group plan decision source.
  - non-leader agents reject follower `/group/heartbeat` with `not_group_leader`.
  - deployed TTL is 30s.
- Final verification:
  - all three coordinators report `total=63 alive=63 expired=0 direct=0`.
  - max group source count is `7`.
  - deployment verify passes for `cdn_new=50/50`, `doubao=8/8`, `tlbmirror=5/5`.
