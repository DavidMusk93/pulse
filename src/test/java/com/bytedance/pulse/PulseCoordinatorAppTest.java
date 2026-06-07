package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PulseCoordinatorAppTest {
    @TempDir
    Path tempDir;

    @Test
    void opensMetricStorageWhenDatabasePathIsConfigured() throws Exception {
        Path db = tempDir.resolve("coordinator/metrics.db");

        try (MetricStorage storage = PulseCoordinatorApp.metricStorageFromEnv(Map.of("PULSE_METRICS_DB", db.toString()))) {
            assertNotNull(storage);
            storage.writeHeartbeat(new HeartbeatMetricSample(
                    1_710_000_000_000L,
                    "agent-1",
                    "host-1",
                    "cluster-a",
                    "area-a",
                    "direct",
                    "direct",
                    1,
                    1,
                    30_000,
                    0,
                    0,
                    0,
                    0,
                    0,
                    9,
                    42_000,
                    Map.of()));
            assertEquals(true, ((AsyncLocalMetricStorage) storage).awaitIdle(Duration.ofSeconds(2)));
            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_000_000L,
                    1_000,
                    10));
            assertEquals(9.0, result.series().get(0).points().get(0).value());
        }
    }

    @Test
    void leavesMetricStorageDisabledWhenDatabasePathIsBlank() throws Exception {
        assertNull(PulseCoordinatorApp.metricStorageFromEnv(Map.of()));
        assertNull(PulseCoordinatorApp.metricStorageFromEnv(Map.of("PULSE_METRICS_DB", " ")));
    }
}
