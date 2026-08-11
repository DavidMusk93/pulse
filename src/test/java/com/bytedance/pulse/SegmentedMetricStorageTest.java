package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentedMetricStorageTest {
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    @TempDir
    Path tempDir;

    @Test
    void writesDailyShardsAndQueriesLegacyAndShardsAsOneStore() throws Exception {
        long day0 = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        Path legacy = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(legacy)) {
            storage.writeHeartbeat(sample("agent-1", day0 + 1_000, 1, 10));
        }
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(day0 + 2 * DAY_MS + 60_000));
        Path shards = tempDir.resolve("metrics-v2");

        try (SegmentedMetricStorage storage = SegmentedMetricStorage.open(
                shards,
                legacy,
                32,
                8,
                Duration.ofMillis(10),
                7,
                Duration.ofHours(1),
                1024L * 1024 * 1024,
                clock)) {
            storage.writeHeartbeat(sample("agent-1", day0 + DAY_MS + 1_000, 2, 20));
            storage.writeHeartbeat(sample("agent-1", day0 + 2 * DAY_MS + 1_000, 3, 30));
            assertTrue(storage.awaitIdle(Duration.ofSeconds(2)));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    day0,
                    day0 + 3 * DAY_MS,
                    1_000,
                    100));

            assertEquals(List.of(10.0, 20.0, 30.0),
                    result.series().get(0).points().stream().map(MetricPoint::value).toList());
            assertTrue(Files.isRegularFile(shards.resolve("metrics-raw-2026-08-02.db")));
            assertTrue(Files.isRegularFile(shards.resolve("metrics-raw-2026-08-03.db")));
            assertTrue(hasIndex(shards.resolve("metrics-raw-2026-08-03.db"), "idx_tide_worker_observed_at"));
            assertFalse(hasIndex(legacy, "idx_tide_worker_observed_at"));
            assertEquals(3, storage.health().shardCount());
            assertTrue(storage.health().legacyBytes() > 0);
        }
    }

    @Test
    void retentionDeletesWholeExpiredShard() throws Exception {
        long day0 = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(day0));
        Path shards = tempDir.resolve("metrics-v2");

        try (SegmentedMetricStorage storage = SegmentedMetricStorage.open(
                shards,
                null,
                32,
                8,
                Duration.ofMillis(10),
                2,
                Duration.ofMillis(20),
                1024L * 1024 * 1024,
                clock)) {
            storage.writeHeartbeat(sample("agent-1", day0 + 1_000, 1, 10));
            storage.writeHeartbeat(sample("agent-1", day0 + DAY_MS + 1_000, 2, 20));
            storage.writeHeartbeat(sample("agent-1", day0 + 2 * DAY_MS + 1_000, 3, 30));
            assertTrue(storage.awaitIdle(Duration.ofSeconds(2)));
            clock.advance(Duration.ofDays(2).plusMinutes(1));
            assertTrue(awaitCondition(() -> storage.health().maintenanceCommands() > 0, Duration.ofSeconds(2)));

            assertFalse(Files.exists(shards.resolve("metrics-raw-2026-08-01.db")));
            assertTrue(Files.exists(shards.resolve("metrics-raw-2026-08-02.db")));
            assertTrue(Files.exists(shards.resolve("metrics-raw-2026-08-03.db")));
            assertEquals(1, storage.health().deletedShards());

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    day0,
                    day0 + 3 * DAY_MS,
                    1_000,
                    100));
            assertEquals(List.of(10.0, 20.0, 30.0),
                    result.series().get(0).points().stream().map(MetricPoint::value).toList());
        }
    }

    @Test
    void activeShardOverCapacityDropsNewSamplesAndReportsDegraded() throws Exception {
        long now = Instant.parse("2026-08-03T00:01:00Z").toEpochMilli();
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(now));

        try (SegmentedMetricStorage storage = SegmentedMetricStorage.open(
                tempDir.resolve("metrics-v2"),
                null,
                32,
                8,
                Duration.ofMillis(10),
                7,
                Duration.ofMillis(20),
                1,
                clock)) {
            clock.advance(Duration.ofMinutes(1));
            assertTrue(awaitCondition(() -> storage.health().maintenanceCommands() > 0, Duration.ofSeconds(2)));

            storage.writeHeartbeat(sample("agent-1", now, 1, 10));

            assertEquals("degraded", storage.health().status());
            assertEquals(1, storage.health().droppedCommands());
            assertEquals(1, storage.health().capacityDroppedCommands());
            assertEquals(0, storage.health().writtenCommands());
        }
    }

    @Test
    void historicalQueryFallsBackToMinuteRollupAfterRawShardExpires() throws Exception {
        long day0 = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(day0));
        Path shards = tempDir.resolve("metrics-v2");

        try (SegmentedMetricStorage storage = SegmentedMetricStorage.open(
                shards,
                null,
                32,
                8,
                Duration.ofMillis(10),
                1,
                Duration.ofMillis(20),
                1024L * 1024 * 1024,
                30,
                1024L * 1024 * 1024,
                clock)) {
            storage.writeHeartbeat(sample("agent-1", day0 + 1_000, 1, 10));
            storage.writeHeartbeat(sample("agent-1", day0 + 30_000, 2, 20));
            storage.writeHeartbeat(sample("agent-1", day0 + 61_000, 3, 30));
            assertTrue(storage.awaitIdle(Duration.ofSeconds(2)));

            clock.advance(Duration.ofDays(2));
            assertTrue(awaitCondition(() -> storage.health().maintenanceCommands() > 0, Duration.ofSeconds(2)));

            assertFalse(Files.exists(shards.resolve("metrics-raw-2026-08-01.db")));
            assertTrue(Files.exists(shards.resolve("metrics-rollup-v2-2026-08-01.db")));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    day0,
                    day0 + 59_999,
                    10_000,
                    100));

            assertEquals(List.of(15.0),
                    result.series().get(0).points().stream().map(MetricPoint::value).toList());
            assertEquals("1m", result.series().get(0).points().get(0).metadata().get("rollup"));
            assertTrue(result.suggestedStepMs() >= 60_000);
        }
    }

    @Test
    void rollupCoversEveryCatalogMetric() throws Exception {
        long day0 = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(day0));

        try (SegmentedMetricStorage storage = SegmentedMetricStorage.open(
                tempDir.resolve("metrics-v2"),
                null,
                32,
                8,
                Duration.ofMillis(10),
                1,
                Duration.ofMillis(20),
                1024L * 1024 * 1024,
                30,
                1024L * 1024 * 1024,
                clock)) {
            storage.writeHeartbeat(sampleWithWorker(day0 + 1_000, 1));
            storage.writeGroupLeader(groupSample(day0 + 1_000, 1));
            storage.writeHeartbeat(sampleWithWorker(day0 + 61_000, 2));
            storage.writeGroupLeader(groupSample(day0 + 61_000, 2));
            assertTrue(storage.awaitIdle(Duration.ofSeconds(2)));

            clock.advance(Duration.ofDays(2));
            assertTrue(awaitCondition(() -> storage.health().maintenanceCommands() > 0, Duration.ofSeconds(2)));

            for (MetricCatalogItem item : LocalMetricStorage.catalog()) {
                MetricQueryResult result = storage.queryRange(new MetricQuery(
                        item.metric(),
                        List.of(),
                        day0,
                        day0 + 59_999,
                        60_000,
                        50,
                        100,
                        0,
                        "cluster-a"));
                assertFalse(result.series().isEmpty(), item.metric());
            }
        }
    }

    @Test
    void normalizedRollupUsesWithoutRowidAndQueriesAcrossGenericCutover() throws Exception {
        long day0 = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        long day1 = day0 + DAY_MS;
        Path legacyRollup = tempDir.resolve("metrics-rollup-2026-08-01.db");
        createGenericRollup(legacyRollup, day0, 10);
        Files.setLastModifiedTime(legacyRollup, FileTime.fromMillis(day0 + 60_000));
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(day1 + 30_000));

        try (RollupMetricStorage storage =
                new RollupMetricStorage(tempDir, 30, 1024L * 1024 * 1024, clock)) {
            storage.write(List.of(new RollupRecord(
                    day1,
                    "agent.thread_count",
                    "agent_id=agent-1\u0000cluster=cluster-a",
                    Map.of("agent_id", "agent-1", "cluster", "cluster-a"),
                    20,
                    1,
                    Map.of())));

            MetricQueryResult result = storage.query(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    day0,
                    day1,
                    60_000,
                    100));

            assertEquals(List.of(10.0, 20.0),
                    result.series().get(0).points().stream().map(MetricPoint::value).toList());
        }

        Path v2Rollup = tempDir.resolve("metrics-rollup-v2-2026-08-02.db");
        assertTrue(Files.isRegularFile(v2Rollup));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + v2Rollup);
                var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT sql FROM sqlite_master WHERE type='table' AND name='metric_rollup_1m'")) {
            assertTrue(result.next());
            assertTrue(result.getString(1).contains("WITHOUT ROWID"));
            assertEquals(1, countRows(connection, "metric_catalog"));
            assertEquals(1, countRows(connection, "metric_series"));
            assertEquals(1, countRows(connection, "metric_rollup_1m"));
        }
    }

    @Test
    void reenableAfterLegacyRollbackAdvancesCutover() throws Exception {
        long day0 = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        Path legacy = tempDir.resolve("pulse-metrics.db");
        try (LocalMetricStorage storage = LocalMetricStorage.open(legacy)) {
            storage.writeHeartbeat(sample("agent-1", day0 + 1_000, 1, 10));
        }
        Files.setLastModifiedTime(legacy, FileTime.fromMillis(day0 + 1_000));
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(day0 + 2 * DAY_MS));
        Path shards = tempDir.resolve("metrics-v2");

        try (SegmentedMetricStorage ignored = SegmentedMetricStorage.open(
                shards,
                legacy,
                32,
                8,
                Duration.ofMillis(10),
                7,
                Duration.ofHours(1),
                1024L * 1024 * 1024,
                clock)) {
            // Initial segmented cutover.
        }

        long rollbackSampleAt = day0 + 2 * DAY_MS + Duration.ofMinutes(30).toMillis();
        try (LocalMetricStorage storage = LocalMetricStorage.open(legacy)) {
            storage.writeHeartbeat(sample("agent-1", rollbackSampleAt, 2, 20));
        }
        Files.setLastModifiedTime(legacy, FileTime.fromMillis(rollbackSampleAt));
        clock.advance(Duration.ofDays(1));

        try (SegmentedMetricStorage storage = SegmentedMetricStorage.open(
                shards,
                legacy,
                32,
                8,
                Duration.ofMillis(10),
                7,
                Duration.ofHours(1),
                1024L * 1024 * 1024,
                clock)) {
            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "agent.thread_count",
                    List.of("agent-1"),
                    day0 + 2 * DAY_MS + Duration.ofMinutes(20).toMillis(),
                    day0 + 2 * DAY_MS + Duration.ofMinutes(40).toMillis(),
                    1_000,
                    100));

            assertEquals(List.of(20.0),
                    result.series().get(0).points().stream().map(MetricPoint::value).toList());
        }
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

    private static HeartbeatMetricSample sampleWithWorker(long observedAtMs, long seq) {
        return new HeartbeatMetricSample(
                observedAtMs,
                "agent-1",
                "host-1",
                "cluster-a",
                "area-a",
                "direct",
                "direct",
                1,
                seq,
                30_000,
                1,
                10_000,
                2,
                3,
                4,
                5,
                6,
                Map.of(
                        "tide_workers", List.of(Map.of(
                                "pid", 1234,
                                "component_version", "1.2.3",
                                "role", "leader",
                                "cpu_percent", 7.5,
                                "rss_kb", 8,
                                "threads", 9)),
                        "disks", List.of(Map.of(
                                "device", "nvme0n1",
                                "io_util_pct", 97.5,
                                "saturated_for_ms", 10_000))));
    }

    private static GroupLeaderMetricSample groupSample(long observedAtMs, long generation) {
        return new GroupLeaderMetricSample(
                observedAtMs,
                "cluster-a/area-a/001",
                "agent-1",
                "fd00::1",
                "cluster-a",
                "area-a",
                generation,
                10,
                10,
                10,
                0,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11,
                12,
                "partial",
                Map.of("plan_mismatch", 1, "plan_lag", 2));
    }

    private static boolean hasIndex(Path db, String index) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?")) {
            statement.setString(1, index);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private static void createGenericRollup(Path db, long bucketMs, long value) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE metric_rollup_1m (
                        bucket_ms INTEGER NOT NULL,
                        metric TEXT NOT NULL,
                        series_key TEXT NOT NULL,
                        labels_json TEXT NOT NULL,
                        value_sum REAL NOT NULL,
                        value_count INTEGER NOT NULL,
                        metadata_json TEXT NOT NULL,
                        PRIMARY KEY (metric, series_key, bucket_ms)
                    )
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO metric_rollup_1m (
                        bucket_ms, metric, series_key, labels_json,
                        value_sum, value_count, metadata_json
                    ) VALUES (?, ?, ?, ?, ?, 1, '{}')
                    """)) {
                insert.setLong(1, bucketMs);
                insert.setString(2, "agent.thread_count");
                insert.setString(3, "agent_id=agent-1\u0000cluster=cluster-a");
                insert.setString(4, "{\"agent_id\":\"agent-1\",\"cluster\":\"cluster-a\"}");
                insert.setLong(5, value);
                insert.executeUpdate();
            }
        }
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
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

    private interface Condition {
        boolean check() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
