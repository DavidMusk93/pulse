package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void flushDecisionClassifiesSelfDueFirstAgentDueUrgentAndBatchFull() {
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        HeartbeatRequest leader = heartbeat("leader", 1);

        GroupFlushDecision initial = collector.flushDecision(leader, 1_000, 5_000, 3_000, 7, 100, 1024 * 1024);
        assertTrue(initial.shouldFlush());
        assertEquals(GroupFlushTrigger.SELF_DUE, initial.trigger());
        collector.batch("group", leader, 1_000, 7);

        collector.record(heartbeat("agent-1", 1), 2_000);
        GroupFlushDecision waiting = collector.flushDecision(leader, 9_000, 20_000, 3_000, 7, 100, 1024 * 1024);
        assertFalse(waiting.shouldFlush());

        GroupFlushDecision firstDue = collector.flushDecision(leader, 26_000, 20_000, 3_000, 7, 100, 1024 * 1024);
        assertTrue(firstDue.shouldFlush());
        assertEquals(GroupFlushTrigger.FIRST_AGENT_DUE, firstDue.trigger());

        GroupHeartbeatCollector urgentCollector = new GroupHeartbeatCollector();
        urgentCollector.batch("group", leader, 1_000, 7);
        urgentCollector.record(heartbeatWithMessage("agent-2", 1, new PulseMessage(
                "accepted-agent-2",
                "reply.task_accepted",
                1,
                "cmd-task-1",
                null,
                Map.of("task_id", "task-1"))), 2_000);
        GroupFlushDecision urgent = urgentCollector.flushDecision(leader, 3_000, 5_000, 3_000, 7, 100, 1024 * 1024);
        assertTrue(urgent.shouldFlush());
        assertEquals(GroupFlushTrigger.URGENT_MESSAGE, urgent.trigger());

        GroupHeartbeatCollector fullCollector = new GroupHeartbeatCollector();
        fullCollector.batch("group", leader, 1_000, 7);
        fullCollector.record(heartbeat("agent-3", 1), 2_000);
        GroupFlushDecision full = fullCollector.flushDecision(leader, 3_000, 5_000, 3_000, 1, 100, 1024 * 1024);
        assertTrue(full.shouldFlush());
        assertEquals(GroupFlushTrigger.BATCH_FULL, full.trigger());
    }

    private static HeartbeatRequest heartbeat(String agentId, long seq) {
        return heartbeatWithMessage(agentId, seq, new PulseMessage(
                "msg-" + agentId + "-" + seq,
                "state.heartbeat",
                1,
                null,
                null,
                Map.of(
                        "host", agentId,
                        "agent_time_ms", 1_710_000_000_000L)));
    }

    private static HeartbeatRequest heartbeatWithMessage(String agentId, long seq, PulseMessage message) {
        return new HeartbeatRequest(
                null,
                agentId,
                1L,
                seq,
                15_000L,
                List.of(message),
                List.of());
    }
}
