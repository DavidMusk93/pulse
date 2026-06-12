# Debug: cdn_new offline agents

Status: [OPEN]

Session id: `cdn-new-offline`

## Symptom

`cdn_new` cluster has 3 offline machines. Expected all `cdn_new` agents to be alive and reporting heartbeat.

## Hypotheses

1. Agent process is down on the 3 hosts due to systemd service failure or crash.
2. Agent process is running, but heartbeat cannot reach coordinators due to network or coordinator URL failure.
3. Hosts are reachable but running an old or wrong JAR/config that reports different cluster/agent identity.
4. Coordinator state is stale or split across peers, making live hosts appear offline from one coordinator view.
5. The machines are inaccessible or overloaded, preventing agent startup and heartbeat collection.

## Evidence log

- Coordinator API initially showed no `offline` entries for `cluster in (cdn_new, cdn2)`, but `cdn2` count was `47`, while fleet `cdn_new` dry-run expected `50`.
- Set difference showed the missing 3 machines were the coordinator hosts:
  - `fdbd:dc05:11:634::45`
  - `fdbd:dc05:13:10c::40`
  - `fdbd:dc07:0:810::44`
- Remote checks showed these machines had `pulse-agent.service` active and `PULSE_AGENT_CLUSTER=cdn2`, so the problem was not cluster env drift.
- Runtime logs on coordinator hosts included old agent errors such as `NoClassDefFoundError: TaskOutputCodec`, and local JAR SHA was old before rebuild/redeploy.
- After `git pull --ff-only`, frontend rebuild, `CoordinatorHttpServerTest`, and `mvn -DskipTests package`, target JAR SHA was:
  - `c7fe627342b240f3c0f2f2e4ec26d777b0aadf83fc8150e94f041d17a6b0075d`

## Fix log

- First deploy attempt with `--parallel 16` stopped locally due shell job-control / background SSH stdin behavior. It was terminated and replaced with sequential `--parallel 1`.
- Full `cdn_new` deployment first pass:
  - total `50`
  - ok `16`
  - failed `34`
  - failures were SSH `Permission denied`, not agent failures.
- Ran `scripts/demand.sh` against failed host list:
  - total `34`
  - ok `34`
  - failed `0`
- Retry deployment against failed host list:
  - total `34`
  - ok `34`
  - failed `0`
- Full verify:
  - total `50`
  - ok `50`
  - failed `0`
- Final coordinator API verification:
  - `fdbd:dc05:11:634::45`: `cdn2 total=50 offline=0`
  - `fdbd:dc05:13:10c::40`: `cdn2 total=50 offline=0`
  - `fdbd:dc07:0:810::44`: `cdn2 total=50 offline=0`
  - fleet expected `50`, coordinator seen `50`, missing `0`

## Conclusion

Root cause: the 3 "dropped" `cdn_new` machines were coordinator hosts running stale/broken agent runtime. They disappeared from the `cdn2` view, so the symptom looked like 3 machines dropped. Rebuilding the latest JAR and redeploying all `cdn_new` agents/coordinators restored the coordinator API view to `50/50 alive`.

## IPv6 Identity Follow-up

User pointed out that metrics and the project must not use hostnames; all runtime identity should use IPv6.

Changes:
- `pulse-cdn-new-deploy.sh` now writes IPv6 into:
  - `PULSE_AGENT_ID`
  - `PULSE_AGENT_HOST`
  - `PULSE_AGENT_IP`
  - `PULSE_COORDINATOR_ID`
- `AgentHeartbeatFactory` fallback now prefers first non-loopback IP instead of local host name.
- `pulse-cdn-new-probe.sh` and `pulse-cdn-new-verify.sh` report the target IPv6 instead of host name.
- frontend URL display no longer uses a hostname parser path.
- `CoordinatorService` deduplicates host views by stable IPv6 identity, preferring alive IPv6 records over legacy hostname records.

Deployment:
- Full `cdn_new` agent deployment with IPv6 env:
  - total `50`
  - ok `50`
  - failed `0`
  - agent/coordinator JAR SHA during full deploy: `2d9a10ba46df59cd4a826801e7a98d6bccb939476a40f4ebfb02b00105605d0d`
- Coordinator-only deploy for API IPv6 dedupe:
  - all 3 coordinators active
  - coordinator JAR SHA: `8154ffbe4b121193fe595592e28f28efdf425d2b2e51bf42b0d831ffb9c4604f`

Post-fix evidence:
- Coordinator env:
  - `PULSE_AGENT_ID=fdbd:...`
  - `PULSE_AGENT_HOST=fdbd:...`
  - `PULSE_AGENT_IP=fdbd:...`
  - `PULSE_COORDINATOR_ID=fdbd:...`
- `/api/hosts`:
  - `cdn2_total=50`
  - `bad_identity_fields=0`
- metrics labels:
  - `metric_series=8`
  - `bad_labels=0`
- cluster liveness:
  - all three coordinators report `cdn2 total=50 offline=0`.
