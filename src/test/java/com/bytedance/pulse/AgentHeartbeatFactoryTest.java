package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentHeartbeatFactoryTest {
    @TempDir
    Path tempDir;

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
    void includesLowResourceAgentDiagnosticsInStateHeartbeat() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a", "worker", 100, 15_000, clock);

        Map<String, Object> payload = factory.nextHeartbeat().messages().get(0).payload();

        assertTrue(((Number) payload.get("agent_thread_count")).longValue() > 0);
        assertTrue(((Number) payload.get("agent_rss_kb")).longValue() >= 0);
        assertTrue(((Number) payload.get("agent_collect_ms")).longValue() >= 0);
        assertEquals(0L, payload.get("agent_encode_ms"));
        assertEquals(0L, payload.get("agent_send_ms"));
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

    @Test
    @SuppressWarnings("unchecked")
    void cachesTideWorkerDiscoveryBetweenHeartbeats() throws Exception {
        Path proc = tempDir.resolve("proc");
        Files.createDirectories(proc.resolve("123"));
        Files.writeString(proc.resolve("meminfo"), "MemTotal:       1000000 kB\n");
        Files.writeString(proc.resolve("123").resolve("cmdline"), "bin/tide_worker\u0000--flag");
        Files.writeString(proc.resolve("123").resolve("environ"), "PORT1=6511\u0000TIDELET_COMPONENT_VERSION=1.2.3\u0000");
        Files.writeString(proc.resolve("123").resolve("status"), "VmRSS:\t1000 kB\nThreads:\t8\n");
        Files.writeString(proc.resolve("123").resolve("stat"), "123 (tide_worker) S 0 0 0 0 0 0 0 0 0 0 10 20 0 0\n");
        MutableClock clock = new MutableClock(1_710_000_000_000L);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a", "worker", 100, 15_000, clock, proc, 60_000);

        Map<String, Object> firstPayload = factory.nextHeartbeat().messages().get(0).payload();
        Files.delete(proc.resolve("123").resolve("cmdline"));
        clock.advanceMillis(5_000);
        Map<String, Object> secondPayload = factory.nextHeartbeat().messages().get(0).payload();

        List<Map<String, Object>> firstWorkers = (List<Map<String, Object>>) firstPayload.get("tide_workers");
        List<Map<String, Object>> secondWorkers = (List<Map<String, Object>>) secondPayload.get("tide_workers");
        assertEquals(1, firstWorkers.size());
        assertEquals(1, secondWorkers.size());
        assertEquals("6511", secondWorkers.get(0).get("port1"));
        assertEquals("1.2.3", secondWorkers.get(0).get("component_version"));
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
