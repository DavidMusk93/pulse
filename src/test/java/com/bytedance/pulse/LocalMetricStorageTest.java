package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMetricStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void storesHeartbeatSamplesAndQueriesRangeByMetricAndAgent() throws Exception {
        Path db = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(db)) {
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_000_000L,
                    "agent-1",
                    "host-1",
                    "cluster-a",
                    "area-a",
                    "direct",
                    "direct",
                    1,
                    40,
                    30_000,
                    0,
                    12,
                    3,
                    4,
                    5,
                    19,
                    72_000,
                    Map.of("load", "0.42")));
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_010_000L,
                    "agent-1",
                    "host-1",
                    "cluster-a",
                    "area-a",
                    "group",
                    "follower",
                    1,
                    43,
                    30_000,
                    2,
                    15,
                    4,
                    4,
                    7,
                    21,
                    73_000,
                    Map.of("load", "0.43")));
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_020_000L,
                    "agent-2",
                    "host-2",
                    "cluster-a",
                    "area-a",
                    "direct",
                    "direct",
                    1,
                    1,
                    30_000,
                    0,
                    9,
                    2,
                    3,
                    4,
                    17,
                    62_000,
                    Map.of("load", "0.11")));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "heartbeat.arrival_gap_ms",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_011_000L,
                    1_000,
                    100));

            assertEquals("avg", result.samplePolicy());
            assertFalse(result.truncated());
            assertEquals(1_000, result.suggestedStepMs());
            assertEquals(1, result.series().size());
            MetricSeries series = result.series().get(0);
            assertEquals("agent-1", series.labels().get("agent_id"));
            assertEquals(List.of(12.0, 15.0), series.points().stream().map(MetricPoint::value).toList());
            assertEquals(List.of("direct", "group"), series.points().stream().map(point -> point.metadata().get("heartbeat_path")).toList());
        }

        assertTrue(db.toFile().length() > 0);
    }

    @Test
    void createsDesignTablesAndExtractsTideWorkerSamples() throws Exception {
        Path db = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(db)) {
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_000_000L,
                    "agent-1",
                    "host-1",
                    "cluster-a",
                    "area-a",
                    "direct",
                    "leader",
                    1,
                    40,
                    30_000,
                    0,
                    12,
                    3,
                    4,
                    5,
                    19,
                    72_000,
                    Map.of("ip", "fd00::1", "tide_workers", List.of(Map.of(
                            "pid", 1234,
                            "component_version", "1.2.3",
                            "cpu_percent", "1.50",
                            "rss_kb", 64000,
                            "threads", 8,
                            "port1", "6511",
                            "role", "leader")))));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "tide_worker.rss_kb",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_000_000L,
                    1_000,
                    10));

            assertEquals(1, result.series().size());
            assertEquals("1234", result.series().get(0).labels().get("pid"));
            assertEquals(64_000.0, result.series().get(0).points().get(0).value());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                var statement = connection.createStatement()) {
            assertEquals(1, count(statement, "host_dimension"));
            assertEquals(1, count(statement, "tide_worker_sample"));
            assertEquals(0, count(statement, "group_leader_sample"));
            assertEquals(0, count(statement, "host_event"));
        }
    }

    @Test
    void storesAndQueriesGroupLeaderSamples() throws Exception {
        Path db = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(db)) {
            storage.writeGroupLeader(new GroupLeaderMetricSample(
                    1_710_000_000_000L,
                    "cluster-a/area-a/001",
                    "agent-leader",
                    "fd00::1",
                    "cluster-a",
                    "area-a",
                    7,
                    11,
                    10,
                    10,
                    0,
                    1,
                    0,
                    0,
                    3,
                    2,
                    12,
                    "partial",
                    Map.of("leader_url", "http://[fd00::1]:9977")));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "group.submitted_agent_count",
                    List.of(),
                    1_710_000_000_000L,
                    1_710_000_000_000L,
                    1_000,
                    10));

            assertEquals(1, result.series().size());
            assertEquals("cluster-a/area-a/001", result.series().get(0).labels().get("group_id"));
            assertEquals(10.0, result.series().get(0).points().get(0).value());
        }
    }

    @Test
    void queryRangeAggregatesHeartbeatPointsByRequestedStep() throws Exception {
        Path db = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(db)) {
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_000_000L, "agent-1", "host-1", "cluster-a", "area-a", "direct", "direct",
                    1, 40, 30_000, 0, 10, 0, 0, 0, 10, 1_000, Map.of()));
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_005_000L, "agent-1", "host-1", "cluster-a", "area-a", "direct", "direct",
                    1, 41, 30_000, 0, 20, 0, 0, 0, 10, 1_000, Map.of()));
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_010_000L, "agent-1", "host-1", "cluster-a", "area-a", "direct", "direct",
                    1, 42, 30_000, 0, 40, 0, 0, 0, 10, 1_000, Map.of()));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "heartbeat.arrival_gap_ms",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_010_000L,
                    10_000,
                    10));

            assertEquals(List.of(15.0, 40.0), result.series().get(0).points().stream().map(MetricPoint::value).toList());
            assertEquals(List.of(1_710_000_000_000L, 1_710_000_010_000L),
                    result.series().get(0).points().stream().map(MetricPoint::timestampMs).toList());
        }
    }

    @Test
    void storesAndQueriesHostEventsForChartAnnotations() throws Exception {
        Path db = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(db)) {
            storage.writeHostEvent(new HostEvent(
                    "event-1",
                    1_710_000_000_000L,
                    "agent-1",
                    "warn",
                    "heartbeat.arrival_gap_spike",
                    "arrival gap exceeded budget",
                    Map.of("gap_ms", 45_000)));

            List<HostEvent> events = storage.queryEvents(new MetricEventQuery(
                    1_710_000_000_000L,
                    1_710_000_001_000L,
                    "agent-1",
                    List.of("warn", "error"),
                    10));

            assertEquals(1, events.size());
            assertEquals("heartbeat.arrival_gap_spike", events.get(0).eventType());
            assertEquals(45_000, events.get(0).details().get("gap_ms"));
        }
    }

    private static int count(java.sql.Statement statement, String table) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
}
