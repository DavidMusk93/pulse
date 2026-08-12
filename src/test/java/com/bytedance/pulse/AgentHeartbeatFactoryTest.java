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

    @Test
    @SuppressWarnings("unchecked")
    void reportsDiskIoUtilizationAndContinuousSaturation() throws Exception {
        Path proc = tempDir.resolve("proc");
        Path sys = tempDir.resolve("sys");
        Files.createDirectories(proc.resolve("self"));
        Files.createDirectories(sys.resolve("class/block/sda"));
        Files.createDirectories(sys.resolve("class/block/sda1"));
        Files.writeString(sys.resolve("class/block/sda1/partition"), "1\n");
        Files.writeString(proc.resolve("meminfo"), "MemTotal:       1000000 kB\n");
        Files.writeString(proc.resolve("diskstats"), """
                8 0 sda 0 0 0 0 0 0 0 0 0 1000 0
                8 1 sda1 0 0 0 0 0 0 0 0 0 1000 0
                7 0 loop0 0 0 0 0 0 0 0 0 0 1000 0
                """);
        MutableClock clock = new MutableClock(1_710_000_000_000L);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a", "worker", 100, 15_000, clock, proc, 60_000);

        HeartbeatRequest baselineHeartbeat = factory.nextHeartbeat();
        List<Map<String, Object>> baseline =
                (List<Map<String, Object>>) baselineHeartbeat.messages().get(0).payload().get("disks");
        assertTrue(baseline.isEmpty());
        assertEquals(1, baselineHeartbeat.messages().size());

        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 5800 0\n");
        HeartbeatRequest firstHeartbeat = factory.nextHeartbeat();
        List<Map<String, Object>> first =
                (List<Map<String, Object>>) firstHeartbeat.messages().get(0).payload().get("disks");
        assertEquals(1, first.size());
        assertEquals("sda", first.get(0).get("device"));
        assertEquals(96.0, first.get(0).get("io_util_pct"));
        assertEquals(5_000L, first.get(0).get("saturated_for_ms"));
        assertEquals(1, firstHeartbeat.messages().size());

        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 10600 0\n");
        HeartbeatRequest sustainedHeartbeat = factory.nextHeartbeat();
        List<Map<String, Object>> sustained =
                (List<Map<String, Object>>) sustainedHeartbeat.messages().get(0).payload().get("disks");
        assertEquals(10_000L, sustained.get(0).get("saturated_for_ms"));
        assertEquals(2, sustainedHeartbeat.messages().size());
        PulseMessage firing = sustainedHeartbeat.messages().get(1);
        assertEquals(AgentDiskIoEventEmitter.MESSAGE_TYPE, firing.type());
        assertEquals("firing", firing.payload().get("status"));
        assertEquals("disk-io-saturation", firing.payload().get("source_id"));
        assertEquals(true, firing.payload().get("urgent"));

        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 11100 0\n");
        HeartbeatRequest recoveredHeartbeat = factory.nextHeartbeat();
        List<Map<String, Object>> recovered =
                (List<Map<String, Object>>) recoveredHeartbeat.messages().get(0).payload().get("disks");
        assertEquals(10.0, recovered.get(0).get("io_util_pct"));
        assertEquals(0L, recovered.get(0).get("saturated_for_ms"));
        assertEquals(2, recoveredHeartbeat.messages().size());
        PulseMessage resolved = recoveredHeartbeat.messages().get(1);
        assertEquals("resolved", resolved.payload().get("status"));
        assertEquals(firing.payload().get("incident_id"), resolved.payload().get("incident_id"));
    }

    @Test
    void heartbeatSourceConfigControlsThresholdDurationAndEnableState() throws Exception {
        Path proc = tempDir.resolve("dynamic-proc");
        Path sys = tempDir.resolve("sys");
        Files.createDirectories(proc.resolve("self"));
        Files.createDirectories(sys.resolve("class/block/sda"));
        Files.writeString(proc.resolve("meminfo"), "MemTotal:       1000000 kB\n");
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 1000 0\n");
        MutableClock clock = new MutableClock(1_710_000_000_000L);
        AgentHeartbeatFactory factory = new AgentHeartbeatFactory(
                "agent-1", "host-1", "fd00::1", "cdn_new", "area-a", "az-a",
                "worker", 100, 15_000, clock, proc, 60_000);

        assertTrue(factory.applyEventSourceConfig(List.of(sourceConfig(
                "generation-a", true, 95, 10))));
        factory.nextHeartbeat();

        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 5750 0\n");
        assertEquals(1, factory.nextHeartbeat().messages().size());

        assertTrue(!factory.applyEventSourceConfig(List.of(sourceConfig(
                "generation-a", true, 95, 10))));
        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 10550 0\n");
        assertEquals(1, factory.nextHeartbeat().messages().size());

        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 15350 0\n");
        HeartbeatRequest firing = factory.nextHeartbeat();
        assertEquals(2, firing.messages().size());
        assertEquals("firing", firing.messages().get(1).payload().get("status"));

        assertTrue(factory.applyEventSourceConfig(List.of(sourceConfig(
                "generation-b", false, 95, 10))));
        clock.advanceMillis(5_000);
        Files.writeString(proc.resolve("diskstats"), "8 0 sda 0 0 0 0 0 0 0 0 0 20150 0\n");
        assertEquals(1, factory.nextHeartbeat().messages().size());
    }

    private static PulseMessage sourceConfig(
            String generation,
            boolean enabled,
            double thresholdPct,
            long sustainSeconds) {
        return new PulseMessage(
                "source-config-" + generation,
                "cmd.event_source_config",
                1,
                null,
                null,
                Map.of(
                        "generation", generation,
                        "config_version", 1,
                        "sources", List.of(Map.of(
                                "source_id", AgentDiskIoEventEmitter.SOURCE_ID,
                                "enabled", enabled,
                                "config", Map.of(
                                        "threshold_pct", thresholdPct,
                                        "sustain_seconds", sustainSeconds)))));
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
