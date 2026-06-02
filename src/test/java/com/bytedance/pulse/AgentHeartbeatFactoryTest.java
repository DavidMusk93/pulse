package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentHeartbeatFactoryTest {
    @Test
    void createsIncrementalStateHeartbeat() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a", "worker", 100, 15_000, clock);

        HeartbeatRequest first = factory.nextHeartbeat();
        HeartbeatRequest second = factory.nextHeartbeat();

        assertEquals("agent-1", first.agentId());
        assertEquals(100, first.epoch());
        assertEquals(1, first.seq());
        assertEquals(2, second.seq());
        assertEquals("state.heartbeat", first.messages().get(0).type());
        assertEquals("host-1", first.messages().get(0).payload().get("host"));
        assertEquals("fd00::1", first.messages().get(0).payload().get("ip"));
        assertEquals("cdn_new", first.messages().get(0).payload().get("cluster"));
        assertEquals("area-a", first.messages().get(0).payload().get("area"));
    }

    @Test
    void includesRunningAsyncTasksInStateHeartbeat() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a", "worker", 100, 15_000, clock);

        HeartbeatRequest heartbeat = factory.nextHeartbeat(
                List.of(),
                List.of(Map.of(
                        "task_id", "task-1",
                        "trace_id", "trace-1",
                        "task_type", "analyze_block_layout_dry_run",
                        "status", "running")));

        assertEquals(List.of(Map.of(
                        "task_id", "task-1",
                        "trace_id", "trace-1",
                        "task_type", "analyze_block_layout_dry_run",
                        "status", "running")),
                heartbeat.messages().get(0).payload().get("async_tasks"));
    }

    @Test
    void noOutputRunningTaskStillReportsAsyncState() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a", "worker", 100, 15_000, clock);

        Map<String, Object> task = Map.of(
                "task_id", "task-1",
                "task_type", "analyze_block_layout_dry_run",
                "status", "running",
                "runtime_ms", 30_000,
                "stream_bytes", 0,
                "stream_chunks", 0,
                "stream_lines", 0);
        HeartbeatRequest heartbeat = factory.nextHeartbeat(List.of(), List.of(task));

        assertEquals(List.of(task), heartbeat.messages().get(0).payload().get("async_tasks"));
        assertEquals(1, heartbeat.messages().size());
    }
}
