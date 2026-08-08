package com.bytedance.pulse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
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
    private static final String LEGACY_PREFIX = "metrics-rollup-";
    private static final String V2_PREFIX = "metrics-rollup-v2-";
    private static final String SHARD_SUFFIX = ".db";
    private static final String CUTOVER_FILE = "rollup-v2-cutover-ms";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();
    private static final TypeReference<Map<String, String>> LABEL_TYPE = new TypeReference<>() {};

    private final Path directory;
    private final int retentionDays;
    private final long maxBytes;
    private final Clock clock;
    private final long cutoverMs;
    private final Map<String, Integer> metricIdCache = new LinkedHashMap<>();
    private final Map<String, Long> seriesIdCache = new LinkedHashMap<>();
    private final AtomicLong deletedShards = new AtomicLong();
    private Connection activeConnection;
    private LocalDate activeDate;

    RollupMetricStorage(Path directory, int retentionDays, long maxBytes, Clock clock) throws Exception {
        this.directory = directory.toAbsolutePath();
        this.retentionDays = Math.max(1, retentionDays);
        this.maxBytes = Math.max(1, maxBytes);
        this.clock = clock;
        Files.createDirectories(this.directory);
        this.cutoverMs = loadOrCreateCutover();
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
                        metric_id, bucket_ms, series_id, value_sum, value_count
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(metric_id, bucket_ms, series_id) DO UPDATE SET
                        value_sum=metric_rollup_1m.value_sum + excluded.value_sum,
                        value_count=metric_rollup_1m.value_count + excluded.value_count
                    """)) {
                for (RollupRecord record : entry.getValue()) {
                    statement.setInt(1, metricId(connection, record.metric()));
                    statement.setLong(2, record.bucketMs());
                    statement.setLong(3, seriesId(connection, record));
                    statement.setDouble(4, record.valueSum());
                    statement.setLong(5, record.valueCount());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                metricIdCache.clear();
                seriesIdCache.clear();
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
        QueryCollector collector = new QueryCollector(pointLimit);

        long legacyEndMs = Math.min(query.endMs(), cutoverMs - 1);
        if (query.startMs() <= legacyEndMs) {
            collectLegacy(query, query.startMs(), legacyEndMs, stepMs, collector);
        }
        long v2StartMs = Math.max(query.startMs(), cutoverMs);
        if (!collector.truncated && v2StartMs <= query.endMs()) {
            collectV2(query, v2StartMs, query.endMs(), stepMs, collector);
        }

        List<MetricSeries> series = collector.pointsBySeries.entrySet().stream()
                .map(entry -> new MetricSeries(
                        collector.labelsBySeries.get(entry.getKey()),
                        entry.getValue().stream().sorted(Comparator.comparingLong(MetricPoint::timestampMs)).toList()))
                .toList();
        SeriesBudgetResult budgeted = LocalMetricStorage.applySeriesBudget(series, query, seriesLimit);
        boolean truncated = collector.truncated || budgeted.truncated();
        return new MetricQueryResult(
                LocalMetricStorage.queryId(query),
                query.metric(),
                query.startMs(),
                query.endMs(),
                LocalMetricStorage.metricUnit(query.metric()),
                "avg",
                truncated,
                LocalMetricStorage.suggestedStepMs(query, pointLimit, seriesLimit, truncated, stepMs),
                seriesLimit,
                pointLimit,
                budgeted.series());
    }

    private void collectLegacy(
            MetricQuery query, long startMs, long endMs, long stepMs, QueryCollector collector) throws Exception {
        for (Path shard : queryShards(LEGACY_PREFIX, startMs, endMs)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + shard + "?mode=ro");
                    PreparedStatement statement =
                            legacyQueryStatement(connection, query, startMs, endMs, stepMs, collector.remaining())) {
                collect(statement, collector);
            }
            if (collector.truncated) {
                return;
            }
        }
    }

    private void collectV2(
            MetricQuery query, long startMs, long endMs, long stepMs, QueryCollector collector) throws Exception {
        for (Path shard : queryShards(V2_PREFIX, startMs, endMs)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + shard + "?mode=ro");
                    PreparedStatement statement =
                            v2QueryStatement(connection, query, startMs, endMs, stepMs, collector.remaining())) {
                collect(statement, collector);
            }
            if (collector.truncated) {
                return;
            }
        }
    }

    private static void collect(PreparedStatement statement, QueryCollector collector) throws Exception {
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (!collector.add(
                        result.getString("series_key"),
                        result.getString("labels_json"),
                        result.getLong("point_ms"),
                        result.getDouble("metric_value"))) {
                    return;
                }
            }
        }
    }

    private PreparedStatement legacyQueryStatement(
            Connection connection,
            MetricQuery query,
            long startMs,
            long endMs,
            long stepMs,
            int pointLimit) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    (bucket_ms / ?) * ? AS point_ms,
                    series_key,
                    MIN(labels_json) AS labels_json,
                    SUM(value_sum) / SUM(value_count) AS metric_value
                FROM metric_rollup_1m
                WHERE metric = ? AND bucket_ms >= ? AND bucket_ms <= ?
                """);
        appendLabelFilters(sql, query);
        sql.append(" GROUP BY series_key, (bucket_ms / ?) ORDER BY series_key, point_ms LIMIT ?");
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        int index = bindCommon(statement, query, stepMs, startMs, endMs);
        statement.setLong(index++, stepMs);
        statement.setInt(index, pointLimit + 1);
        return statement;
    }

    private PreparedStatement v2QueryStatement(
            Connection connection,
            MetricQuery query,
            long startMs,
            long endMs,
            long stepMs,
            int pointLimit) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    (r.bucket_ms / ?) * ? AS point_ms,
                    s.series_key,
                    MIN(s.labels_json) AS labels_json,
                    SUM(r.value_sum) / SUM(r.value_count) AS metric_value
                FROM metric_rollup_1m r
                JOIN metric_catalog m ON m.metric_id = r.metric_id
                JOIN metric_series s ON s.series_id = r.series_id
                WHERE m.metric = ? AND r.bucket_ms >= ? AND r.bucket_ms <= ?
                """);
        appendLabelFilters(sql, query);
        sql.append(" GROUP BY s.series_key, (r.bucket_ms / ?) ORDER BY s.series_key, point_ms LIMIT ?");
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        int index = bindCommon(statement, query, stepMs, startMs, endMs);
        statement.setLong(index++, stepMs);
        statement.setInt(index, pointLimit + 1);
        return statement;
    }

    private static void appendLabelFilters(StringBuilder sql, MetricQuery query) {
        if (!query.agentIds().isEmpty()) {
            sql.append(" AND json_extract(labels_json, '$.agent_id') IN (");
            sql.append("?,".repeat(query.agentIds().size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
        }
        if (!query.cluster().isBlank()) {
            sql.append(" AND json_extract(labels_json, '$.cluster') = ?");
        }
    }

    private int bindCommon(
            PreparedStatement statement,
            MetricQuery query,
            long stepMs,
            long startMs,
            long endMs) throws Exception {
        int index = 1;
        statement.setLong(index++, stepMs);
        statement.setLong(index++, stepMs);
        statement.setString(index++, query.metric());
        statement.setLong(index++, startMs);
        statement.setLong(index++, endMs);
        for (String agentId : query.agentIds()) {
            statement.setString(index++, agentId);
        }
        if (!query.cluster().isBlank()) {
            statement.setString(index++, query.cluster());
        }
        return index;
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
        activeConnection = DriverManager.getConnection("jdbc:sqlite:" + shardPath(date));
        activeDate = date;
        metricIdCache.clear();
        seriesIdCache.clear();
        try (Statement statement = activeConnection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metric_catalog (
                        metric_id INTEGER PRIMARY KEY,
                        metric TEXT NOT NULL UNIQUE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metric_series (
                        series_id INTEGER PRIMARY KEY,
                        series_key TEXT NOT NULL UNIQUE,
                        labels_json TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metric_rollup_1m (
                        metric_id INTEGER NOT NULL,
                        bucket_ms INTEGER NOT NULL,
                        series_id INTEGER NOT NULL,
                        value_sum REAL NOT NULL,
                        value_count INTEGER NOT NULL,
                        PRIMARY KEY (metric_id, bucket_ms, series_id)
                    ) WITHOUT ROWID
                    """);
        }
        return activeConnection;
    }

    private int metricId(Connection connection, String metric) throws Exception {
        Integer cached = metricIdCache.get(metric);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT OR IGNORE INTO metric_catalog (metric) VALUES (?)")) {
            insert.setString(1, metric);
            insert.executeUpdate();
        }
        try (PreparedStatement select =
                connection.prepareStatement("SELECT metric_id FROM metric_catalog WHERE metric = ?")) {
            select.setString(1, metric);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("missing metric catalog entry: " + metric);
                }
                int metricId = result.getInt(1);
                metricIdCache.put(metric, metricId);
                return metricId;
            }
        }
    }

    private long seriesId(Connection connection, RollupRecord record) throws Exception {
        Long cached = seriesIdCache.get(record.seriesKey());
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO metric_series (series_key, labels_json) VALUES (?, ?)")) {
            insert.setString(1, record.seriesKey());
            insert.setString(2, MAPPER.writeValueAsString(record.labels()));
            insert.executeUpdate();
        }
        try (PreparedStatement select =
                        connection.prepareStatement("SELECT series_id FROM metric_series WHERE series_key = ?")) {
            select.setString(1, record.seriesKey());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("missing metric series: " + record.seriesKey());
                }
                long seriesId = result.getLong(1);
                seriesIdCache.put(record.seriesKey(), seriesId);
                return seriesId;
            }
        }
    }

    private void closeIfDateChanged(LocalDate currentDate) throws Exception {
        if (activeConnection != null && !currentDate.equals(activeDate)) {
            closeActive();
        }
    }

    private List<Path> queryShards(String prefix, long startMs, long endMs) {
        LocalDate start = dateAt(startMs);
        LocalDate end = dateAt(Math.max(startMs, endMs));
        return shards(prefix).stream()
                .filter(path -> {
                    LocalDate date = shardDate(path);
                    return !date.isBefore(start) && !date.isAfter(end);
                })
                .toList();
    }

    private List<Path> shards() {
        List<Path> paths = new ArrayList<>(shards(LEGACY_PREFIX));
        paths.addAll(shards(V2_PREFIX));
        paths.sort(Comparator.comparing(this::shardDate));
        return List.copyOf(paths);
    }

    private List<Path> shards(String prefix) {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, prefix + "*" + SHARD_SUFFIX)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path)
                        && (V2_PREFIX.equals(prefix) || !name.startsWith(V2_PREFIX))) {
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
        return directory.resolve(V2_PREFIX + DATE_FORMAT.format(date) + SHARD_SUFFIX);
    }

    private LocalDate shardDate(Path shard) {
        String name = shard.getFileName().toString();
        String prefix = name.startsWith(V2_PREFIX) ? V2_PREFIX : LEGACY_PREFIX;
        return LocalDate.parse(name.substring(prefix.length(), name.length() - SHARD_SUFFIX.length()), DATE_FORMAT);
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

    private long loadOrCreateCutover() throws Exception {
        Path marker = directory.resolve(CUTOVER_FILE);
        if (Files.isRegularFile(marker)) {
            long cutover = Long.parseLong(Files.readString(marker).trim());
            if (legacyLatestMtime() <= cutover) {
                return cutover;
            }
            long nextCutover = minuteBucket(clock.millis());
            writeCutover(marker, nextCutover);
            return nextCutover;
        }
        long cutover = minuteBucket(clock.millis());
        writeCutover(marker, cutover);
        return cutover;
    }

    private static long minuteBucket(long timestampMs) {
        return timestampMs - Math.floorMod(timestampMs, 60_000L);
    }

    private long legacyLatestMtime() {
        long latest = 0;
        for (Path shard : shards(LEGACY_PREFIX)) {
            latest = Math.max(latest, databaseLatestMtime(shard));
        }
        return latest;
    }

    private static void writeCutover(Path marker, long cutover) throws Exception {
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                String.valueOf(cutover),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long databaseLatestMtime(Path path) {
        return Math.max(
                fileMtime(path),
                Math.max(fileMtime(Path.of(path + "-wal")), fileMtime(Path.of(path + "-shm"))));
    }

    private static long fileMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0;
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
        metricIdCache.clear();
        seriesIdCache.clear();
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

    private static final class QueryCollector {
        private final int pointLimit;
        private final Map<String, Map<String, String>> labelsBySeries = new LinkedHashMap<>();
        private final Map<String, List<MetricPoint>> pointsBySeries = new LinkedHashMap<>();
        private int rows;
        private boolean truncated;

        private QueryCollector(int pointLimit) {
            this.pointLimit = pointLimit;
        }

        private int remaining() {
            return Math.max(1, pointLimit - rows);
        }

        private boolean add(String seriesKey, String labelsJson, long timestampMs, double value) throws Exception {
            rows++;
            if (rows > pointLimit) {
                truncated = true;
                return false;
            }
            labelsBySeries.putIfAbsent(seriesKey, MAPPER.readValue(labelsJson, LABEL_TYPE));
            pointsBySeries.computeIfAbsent(seriesKey, ignored -> new ArrayList<>())
                    .add(new MetricPoint(timestampMs, value, Map.of("rollup", "1m")));
            return true;
        }
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
