package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgentHeartbeatFactoryTest {
    @Test
    void createsIncrementalStateHeartbeat() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "az-a", "worker", 100, 15_000, clock);

        HeartbeatRequest first = factory.nextHeartbeat();
        HeartbeatRequest second = factory.nextHeartbeat();

        assertEquals("agent-1", first.agentId());
        assertEquals(100, first.epoch());
        assertEquals(1, first.seq());
        assertEquals(2, second.seq());
        assertEquals("state.heartbeat", first.messages().get(0).type());
        assertEquals("host-1", first.messages().get(0).payload().get("host"));
        assertEquals("fd00::1", first.messages().get(0).payload().get("ip"));
    }
}
