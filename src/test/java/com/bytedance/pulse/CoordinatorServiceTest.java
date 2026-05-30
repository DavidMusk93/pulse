package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoordinatorServiceTest {
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);

    @Test
    void heartbeatStoresHostState() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);

        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 40, "host-a", "10.0.0.1"));
        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 41, "host-a", "10.0.0.1"));
        HeartbeatResponse response = service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-a", "10.0.0.1"));

        assertTrue(response.ok());
        assertEquals(42, response.acceptedSeq());
        HostView host = service.hosts().get(0);
        assertEquals("agent-1", host.agentId());
        assertEquals("host-a", host.host());
        assertEquals("10.0.0.1", host.ip());
        assertEquals("cluster-a", host.cluster());
        assertEquals("area-a", host.area());
        assertEquals("alive", host.status());
        assertEquals(3, host.heartbeatConfirmations());
        assertEquals("cmd.group_plan", response.messages().get(0).type());
    }

    @Test
    void heartbeatRequiresThreeConfirmationsWithinWindowToBeAlive() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);

        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-a", "10.0.0.1"));

        HostView host = service.hosts().get(0);
        assertEquals("warming", host.status());
        assertEquals(1, host.heartbeatConfirmations());
    }

    @Test
    void olderSequenceDoesNotOverwriteNewerState() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-new", "10.0.0.42"));

        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 41, "host-old", "10.0.0.41"));

        HostView host = service.hosts().get(0);
        assertEquals(42, host.seq());
        assertEquals("host-new", host.host());
    }

    @Test
    void higherEpochOverwritesLowerEpoch() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-old", "10.0.0.42"));

        service.handleHeartbeat(singleHeartbeat("agent-1", 2, 1, "host-new-epoch", "10.0.0.2"));

        HostView host = service.hosts().get(0);
        assertEquals(2, host.epoch());
        assertEquals(1, host.seq());
        assertEquals("host-new-epoch", host.host());
    }

    @Test
    void batchHeartbeatReturnsPerAgentAcceptedSequence() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        HeartbeatRequest request = new HeartbeatRequest(
                "group-a",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(
                        agent("agent-1", 1, 10, "host-1", "10.0.0.1"),
                        agent("agent-2", 1, 11, "host-2", "10.0.0.2")));

        HeartbeatResponse response = service.handleHeartbeat(request);

        assertTrue(response.ok());
        assertEquals(2, response.agents().size());
        assertEquals(10, response.agents().get(0).acceptedSeq());
        assertEquals(11, response.agents().get(1).acceptedSeq());
        assertEquals(2, service.hosts().size());
    }

    @Test
    void coordinatorMaintainsDynamicGroupPlansWithLimitSeven() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        for (int i = 1; i <= 8; i++) {
            confirmAlive(service, "agent-" + i, "host-" + i, "10.0.0." + i);
        }

        List<GroupView> groups = service.groups();
        HeartbeatResponse followerResponse = service.handleHeartbeat(singleHeartbeat("agent-2", 1, 20, "host-2", "10.0.0.2"));
        Map<String, Object> payload = followerResponse.messages().get(0).payload();

        assertEquals(2, groups.size());
        assertEquals(7, groups.get(0).size());
        assertEquals(1, groups.get(1).size());
        assertEquals("follower", payload.get("group_mode"));
        assertEquals(7, payload.get("size_limit"));
        assertEquals("agent-1", payload.get("leader_agent_id"));
        assertEquals("http://10.0.0.1:9977", payload.get("leader_url"));
    }

    @Test
    void batchHeartbeatReturnsPerAgentGroupPlanMessages() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        HeartbeatRequest request = new HeartbeatRequest(
                "group-a",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(
                        agent("agent-1", 1, 10, "host-1", "10.0.0.1"),
                        agent("agent-2", 1, 11, "host-2", "10.0.0.2")));

        HeartbeatResponse response = service.handleHeartbeat(request);

        assertEquals("cmd.group_plan", response.agents().get(0).messages().get(0).type());
        assertEquals("cmd.group_plan", response.agents().get(1).messages().get(0).type());
    }

    @Test
    void forwardOnlyMergesStateMessages() {
        CoordinatorService service = new CoordinatorService("coordinator-b", clock);
        ForwardState state = new ForwardState(
                "agent-1",
                1,
                10,
                15_000,
                clock.millis(),
                "cdn2/yg/000",
                List.of(
                        new PulseMessage("state-1", "state.heartbeat", 1, null, null, Map.of("host", "host-1")),
                        new PulseMessage("cmd-1", "cmd.update_config", 1, null, null, Map.of("ignored", true))));

        HeartbeatForwardResponse response = service.handleForward(
                new HeartbeatForwardRequest("coordinator-a", List.of(state)));

        assertTrue(response.ok());
        assertEquals(1, response.accepted());
        assertEquals(1, response.merged());
        HostView host = service.hosts().get(0);
        assertEquals("host-1", host.host());
        assertEquals("cdn2/yg/000", host.source());
        assertEquals(1, host.state().size());
    }

    private static HeartbeatRequest singleHeartbeat(String agentId, long epoch, long seq, String host, String ip) {
        AgentHeartbeat heartbeat = agent(agentId, epoch, seq, host, ip);
        return new HeartbeatRequest(
                null,
                heartbeat.agentId(),
                heartbeat.epoch(),
                heartbeat.seq(),
                heartbeat.ttlMs(),
                heartbeat.messages(),
                List.of());
    }

    private static void confirmAlive(CoordinatorService service, String agentId, String host, String ip) {
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 1, host, ip));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 2, host, ip));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 3, host, ip));
    }

    private static AgentHeartbeat agent(String agentId, long epoch, long seq, String host, String ip) {
        return new AgentHeartbeat(
                agentId,
                epoch,
                seq,
                15_000,
                List.of(new PulseMessage(
                        "msg-" + agentId + "-" + seq,
                        "state.heartbeat",
                        1,
                        null,
                        null,
                        Map.of(
                                "host", host,
                                "ip", ip,
                                "cluster", "cluster-a",
                                "area", "area-a",
                                "zone", "az-a",
                                "role", "worker",
                                "load", "0.42"))));
    }
}
