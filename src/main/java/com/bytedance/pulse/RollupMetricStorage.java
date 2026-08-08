package com.bytedance.pulse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class RollupMetricStorage implements AutoCloseable {
    private static final String SHARD_PREFIX = "metrics-rollup-";
    private static final String SHARD_SUFFIX = ".db";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();
    private static final TypeReference<Map<String, String>> LABEL_TYPE = new TypeReference<>() {};
    private final Path directory;
    private final int retentionDays;
    private final long maxBytes;
    private Connection activeConnection;
    private LocalDate activeDate;
    private final AtomicLong deletedShards = new AtomicLong();

    RollupMetricStorage(Path directory, int retentionDays, long maxBytes) throws Exception {
        this.directory = directory.toAbsolutePath();
        this.retentionDays = Math.max(1, retentionDays);
        this.maxBytes = Math.max(1, maxBytes);
        Files.createDirectories(this.directory);
    }

    void write(List<RollupRecord> records) throws Exception {
        Map<LocalDate, List<RollupRecord>> byDate = new LinkedHashMap<>();
        for (RollupRecord record : records) {
            byDate.computeIfAbsent(dateAt(record.bucketMs()), ignored -> new ArrayList<>()).add(record);
        }
        for (Map.Entry<LocalDate, List<RollupRecord>> entry : byDate.entrySet()) {
            Connection connection = connectionFor(entry.getKey());
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO metric_rollup_1m (
                        bucket_ms, metric, series_key, labels_json, value_sum, value_count, metadata_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(metric, series_key, bucket_ms) DO UPDATE SET
                        labels_json=excluded.labels_json,
                        value_sum=metric_rollup_1m.value_sum + excluded.value_sum,
                        value_count=metric_rollup_1m.value_count + excluded.value_count,
                        metadata_json=excluded.metadata_json
                    """)) {
                for (RollupRecord record : entry.getValue()) {
                    statement.setLong(1, record.bucketMs());
                    statement.setString(2, record.metric());
                    statement.setString(3, record.seriesKey());
                    statement.setString(4, MAPPER.writeValueAsString(record.labels()));
                    statement.setDouble(5, record.valueSum());
                    statement.setLong(6, record.valueCount());
                    statement.setString(7, MAPPER.writeValueAsString(record.metadata()));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    MetricQueryResult query(MetricQuery query) throws Exception {
        int pointLimit = LocalMetricStorage.effectivePointLimit(query);
        int seriesLimit = LocalMetricStorage.effectiveSeriesLimit(query);
        long stepMs = Math.max(60_000L, LocalMetricStorage.effectiveStepMs(query));
        Map<String, Map<String, String>> labelsBySeries = new LinkedHashMap<>();
        Map<String, List<MetricPoint>> pointsBySeries = new LinkedHashMap<>();
        int rows = 0;
        boolean truncated = false;
        for (Path shard : queryShards(query.startMs(), query.endMs())) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + shard + "?mode=ro");
                    PreparedStatement statement = queryStatement(connection, query, stepMs, pointLimit)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows++;
                        if (rows > pointLimit) {
                            truncated = true;
                            break;
                        }
                        String seriesKey = result.getString("series_key");
                        labelsBySeries.putIfAbsent(
                                seriesKey, MAPPER.readValue(result.getString("labels_json"), LABEL_TYPE));
                        pointsBySeries.computeIfAbsent(seriesKey, ignored -> new ArrayList<>())
                                .add(new MetricPoint(
                                        result.getLong("point_ms"),
                                        result.getDouble("metric_value"),
                                        Map.of("rollup", "1m")));
                    }
                }
            }
            if (truncated) {
                break;
            }
        }
        List<MetricSeries> series = pointsBySeries.entrySet().stream()
                .map(entry -> new MetricSeries(
                        labelsBySeries.get(entry.getKey()),
                        entry.getValue().stream().sorted(Comparator.comparingLong(MetricPoint::timestampMs)).toList()))
                .toList();
        SeriesBudgetResult budgeted = LocalMetricStorage.applySeriesBudget(series, query, seriesLimit);
        boolean seriesTruncated = budgeted.truncated();
        return new MetricQueryResult(
                LocalMetricStorage.queryId(query),
                query.metric(),
                query.startMs(),
                query.endMs(),
                LocalMetricStorage.metricUnit(query.metric()),
                "avg",
                truncated || seriesTruncated,
                LocalMetricStorage.suggestedStepMs(
                        query, pointLimit, seriesLimit, truncated || seriesTruncated, stepMs),
                seriesLimit,
                pointLimit,
                budgeted.series());
    }

    private PreparedStatement queryStatement(
            Connection connection, MetricQuery query, long stepMs, int pointLimit) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    (bucket_ms / ?) * ? AS point_ms,
                    series_key,
                    MIN(labels_json) AS labels_json,
                    SUM(value_sum) / SUM(value_count) AS metric_value
                FROM metric_rollup_1m
                WHERE metric = ? AND bucket_ms >= ? AND bucket_ms <= ?
                """);
        if (!query.agentIds().isEmpty()) {
            sql.append(" AND json_extract(labels_json, '$.agent_id') IN (");
            sql.append("?,".repeat(query.agentIds().size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
        }
        if (!query.cluster().isBlank()) {
            sql.append(" AND json_extract(labels_json, '$.cluster') = ?");
        }
        sql.append(" GROUP BY series_key, (bucket_ms / ?) ORDER BY series_key, point_ms LIMIT ?");
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        int index = 1;
        statement.setLong(index++, stepMs);
        statement.setLong(index++, stepMs);
        statement.setString(index++, query.metric());
        statement.setLong(index++, query.startMs());
        statement.setLong(index++, query.endMs());
        for (String agentId : query.agentIds()) {
            statement.setString(index++, agentId);
        }
        if (!query.cluster().isBlank()) {
            statement.setString(index++, query.cluster());
        }
        statement.setLong(index++, stepMs);
        statement.setInt(index, pointLimit + 1);
        return statement;
    }

    void maintain(LocalDate currentDate) throws Exception {
        closeIfDateChanged(currentDate);
        LocalDate oldestRetained = currentDate.minusDays(retentionDays - 1L);
        for (Path shard : shards()) {
            LocalDate date = shardDate(shard);
            if (date.isBefore(oldestRetained) && !date.equals(activeDate)) {
                deleteShard(shard);
            }
        }
        long bytes = bytes();
        for (Path shard : shards()) {
            if (bytes <= maxBytes) {
                break;
            }
            LocalDate date = shardDate(shard);
            if (date.equals(currentDate) || date.equals(activeDate)) {
                continue;
            }
            deleteShard(shard);
            bytes = bytes();
        }
    }

    long bytes() {
        return shards().stream().mapToLong(RollupMetricStorage::databaseBytes).sum();
    }

    int shardCount() {
        return shards().size();
    }

    long maxBytes() {
        return maxBytes;
    }

    boolean overCapacity() {
        return bytes() > maxBytes;
    }

    long deletedShards() {
        return deletedShards.get();
    }

    private Connection connectionFor(LocalDate date) throws Exception {
        if (activeConnection != null && date.equals(activeDate)) {
            return activeConnection;
        }
        closeActive();
        Path path = shardPath(date);
        activeConnection = DriverManager.getConnection("jdbc:sqlite:" + path);
        activeDate = date;
        try (Statement statement = activeConnection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metric_rollup_1m (
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
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_metric_rollup_time ON metric_rollup_1m(metric, bucket_ms)");
        }
        return activeConnection;
    }

    private void closeIfDateChanged(LocalDate currentDate) throws Exception {
        if (activeConnection != null && !currentDate.equals(activeDate)) {
            closeActive();
        }
    }

    private List<Path> queryShards(long startMs, long endMs) {
        LocalDate start = dateAt(startMs);
        LocalDate end = dateAt(Math.max(startMs, endMs));
        return shards().stream()
                .filter(path -> {
                    LocalDate date = shardDate(path);
                    return !date.isBefore(start) && !date.isAfter(end);
                })
                .toList();
    }

    private List<Path> shards() {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, SHARD_PREFIX + "*" + SHARD_SUFFIX)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    paths.add(path);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        paths.sort(Comparator.comparing(this::shardDate));
        return List.copyOf(paths);
    }

    private Path shardPath(LocalDate date) {
        return directory.resolve(SHARD_PREFIX + DATE_FORMAT.format(date) + SHARD_SUFFIX);
    }

    private LocalDate shardDate(Path shard) {
        String name = shard.getFileName().toString();
        return LocalDate.parse(
                name.substring(SHARD_PREFIX.length(), name.length() - SHARD_SUFFIX.length()), DATE_FORMAT);
    }

    private static LocalDate dateAt(long timestampMs) {
        return Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private void deleteShard(Path shard) throws Exception {
        Files.deleteIfExists(Path.of(shard + "-wal"));
        Files.deleteIfExists(Path.of(shard + "-shm"));
        if (Files.deleteIfExists(shard)) {
            deletedShards.incrementAndGet();
        }
    }

    private static long databaseBytes(Path path) {
        return fileSize(path) + fileSize(Path.of(path + "-wal")) + fileSize(Path.of(path + "-shm"));
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void closeActive() throws Exception {
        if (activeConnection == null) {
            return;
        }
        Connection connection = activeConnection;
        activeConnection = null;
        activeDate = null;
        Exception failure = null;
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            connection.close();
        } catch (Exception exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        closeActive();
    }
}

record RollupRecord(
        long bucketMs,
        String metric,
        String seriesKey,
        Map<String, String> labels,
        double valueSum,
        long valueCount,
        Map<String, Object> metadata) {
    RollupRecord {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
