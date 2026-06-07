package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
}
