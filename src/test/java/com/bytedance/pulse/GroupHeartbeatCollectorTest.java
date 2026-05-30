package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupHeartbeatCollectorTest {
    @Test
    void batchesLeaderAndFollowersWithSizeLimitSeven() {
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        for (int i = 1; i <= 8; i++) {
            collector.record(heartbeat("agent-" + i, i));
        }

        HeartbeatRequest batch = collector.batch("cdn2/yg/000", heartbeat("leader", 100), 1_710_000_000_000L, 7);

        assertEquals("cdn2/yg/000", batch.groupId());
        assertEquals(7, batch.agents().size());
        assertEquals("leader", batch.agents().get(0).agentId());
        assertEquals("agent-1", batch.agents().get(1).agentId());
        assertEquals("agent-6", batch.agents().get(6).agentId());
    }

    @Test
    void newerSequenceWinsForSameAgent() {
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        collector.record(heartbeat("agent-1", 2));
        collector.record(heartbeat("agent-1", 1));

        HeartbeatRequest batch = collector.batch("group", heartbeat("leader", 1), 1_710_000_000_000L, 7);

        AgentHeartbeat agent = batch.agents().stream()
                .filter(item -> item.agentId().equals("agent-1"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, agent.seq());
    }

    private static HeartbeatRequest heartbeat(String agentId, long seq) {
        return new HeartbeatRequest(
                null,
                agentId,
                1L,
                seq,
                15_000L,
                List.of(new PulseMessage(
                        "msg-" + agentId + "-" + seq,
                        "state.heartbeat",
                        1,
                        null,
                        null,
                        Map.of(
                                "host", agentId,
                                "agent_time_ms", 1_710_000_000_000L))),
                List.of());
    }
}
