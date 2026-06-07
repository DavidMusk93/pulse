package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncLocalMetricStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void heartbeatWritesAreQueuedAndFlushedBySingleWriter() throws Exception {
        Path db = tempDir.resolve("metrics.db");
        try (AsyncLocalMetricStorage storage = AsyncLocalMetricStorage.open(db, 16, 4, Duration.ofMillis(20))) {
            storage.writeHeartbeat(sample("agent-1", 1_710_000_000_000L, 40, 19));
            storage.writeHeartbeat(sample("agent-1", 1_710_000_010_000L, 41, 20));

            assertTrue(storage.awaitIdle(Duration.ofSeconds(2)));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));

            assertEquals(List.of(19.0, 20.0), result.series().get(0).points().stream().map(MetricPoint::value).toList());
            assertEquals(2, storage.health().writtenCommands());
            assertEquals(0, storage.health().droppedCommands());
            assertEquals("ok", storage.health().status());
        }
    }

    @Test
    void boundedQueueDropsNewestWithoutBlockingHeartbeatPath() throws Exception {
        Path db = tempDir.resolve("metrics.db");
        try (AsyncLocalMetricStorage storage = AsyncLocalMetricStorage.open(db, 1, 1, Duration.ofSeconds(5))) {
            storage.writeHeartbeat(sample("agent-1", 1_710_000_000_000L, 40, 19));
            storage.writeHeartbeat(sample("agent-1", 1_710_000_010_000L, 41, 20));

            assertTrue(storage.health().droppedCommands() >= 1);
            assertEquals("degraded", storage.health().status());
        }
    }

    @Test
    void writerRunsRetentionCleanupAndWalCheckpointOffHeartbeatPath() throws Exception {
        Path db = tempDir.resolve("metrics.db");
        try (AsyncLocalMetricStorage storage = AsyncLocalMetricStorage.open(
                db,
                16,
                4,
                Duration.ofMillis(10),
                Duration.ofMillis(1),
                Duration.ofMillis(20),
                100)) {
            storage.writeHeartbeat(sample("agent-old", 1_000L, 1, 19));
            assertTrue(storage.awaitIdle(Duration.ofSeconds(2)));

            assertTrue(awaitCondition(() -> storage.health().maintenanceCommands() > 0, Duration.ofSeconds(2)));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-old"),
                    0,
                    System.currentTimeMillis(),
                    1_000,
                    10));
            assertEquals(0, result.series().size());
            assertTrue(storage.health().deletedSamples() > 0);
            assertTrue(storage.health().checkpointCommands() > 0);
            assertEquals(0, storage.health().droppedCommands());
        }
    }

    private static boolean awaitCondition(Condition condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.check()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.check();
    }

    private static HeartbeatMetricSample sample(String agentId, long observedAtMs, long seq, long threads) {
        return new HeartbeatMetricSample(
                observedAtMs,
                agentId,
                "host-1",
                "cluster-a",
                "area-a",
                "direct",
                "direct",
                1,
                seq,
                30_000,
                0,
                10_000,
                1,
                0,
                0,
                threads,
                72_000,
                Map.of());
    }

    private interface Condition {
        boolean check() throws Exception;
    }
}
