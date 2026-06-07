package com.bytedance.pulse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

interface MetricStorage extends AutoCloseable {
    void writeHeartbeat(HeartbeatMetricSample sample) throws Exception;

    void writeGroupLeader(GroupLeaderMetricSample sample) throws Exception;

    MetricQueryResult queryRange(MetricQuery query) throws Exception;

    List<HostEvent> queryEvents(MetricEventQuery query) throws Exception;

    MetricStorageHealth health();

    @Override
    void close() throws Exception;
}

final class LocalMetricStorage implements MetricStorage {
    static final int DEFAULT_SERIES_LIMIT = 50;
    static final int MAX_SERIES_LIMIT = 200;
    static final int MAX_POINT_LIMIT = 20_000;

    private static final String HEARTBEAT_TABLE = "heartbeat_sample";
    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Connection connection;

    private LocalMetricStorage(Connection connection) throws SQLException {
        this.connection = connection;
        initialize();
    }

    static LocalMetricStorage open(Path dbPath) throws Exception {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return new LocalMetricStorage(connection);
    }

    static List<MetricCatalogItem> catalog() {
        return List.of(
                new MetricCatalogItem("heartbeat.arrival_gap_ms", "Heartbeat arrival gap", "ms"),
                new MetricCatalogItem("heartbeat.seq_gap", "Heartbeat sequence gap", "count"),
                new MetricCatalogItem("heartbeat.agent_collect_ms", "Agent collection time", "ms"),
                new MetricCatalogItem("heartbeat.agent_encode_ms", "Agent encode time", "ms"),
                new MetricCatalogItem("heartbeat.agent_send_ms", "Agent send time", "ms"),
                new MetricCatalogItem("agent.thread_count", "Agent thread count", "threads"),
                new MetricCatalogItem("agent.rss_kb", "Agent RSS", "KiB"),
                new MetricCatalogItem("tide_worker.cpu_pct", "Tide worker CPU", "%"),
                new MetricCatalogItem("tide_worker.rss_kb", "Tide worker RSS", "KiB"),
                new MetricCatalogItem("tide_worker.thread_count", "Tide worker threads", "threads"),
                new MetricCatalogItem("group.member_count", "Group member count", "count"),
                new MetricCatalogItem("group.submitted_agent_count", "Group submitted agents", "count"),
                new MetricCatalogItem("group.accepted_agent_count", "Group accepted agents", "count"),
                new MetricCatalogItem("group.missing_member_count", "Group missing members", "count"),
                new MetricCatalogItem("group.stale_member_count", "Group stale members", "count"),
                new MetricCatalogItem("group.direct_fallback_count", "Group direct fallback", "count"),
                new MetricCatalogItem("group.status_unhealthy", "Group unhealthy status", "ratio"),
                new MetricCatalogItem("group.leader_collect_ms", "Group leader collection time", "ms"),
                new MetricCatalogItem("group.group_latency_ms", "Group latency", "ms"));
    }

    @Override
    public void writeHeartbeat(HeartbeatMetricSample sample) throws Exception {
        String sql = """
                INSERT INTO heartbeat_sample (
                    observed_at_ms, agent_id, host, cluster, area, heartbeat_path, group_mode,
                    epoch, seq, ttl_ms, seq_gap, arrival_gap_ms, agent_collect_ms,
                    agent_encode_ms, agent_send_ms, agent_thread_count, agent_rss_kb, state_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sample.observedAtMs());
            statement.setString(2, sample.agentId());
            statement.setString(3, sample.host());
            statement.setString(4, sample.cluster());
            statement.setString(5, sample.area());
            statement.setString(6, sample.heartbeatPath());
            statement.setString(7, sample.groupMode());
            statement.setLong(8, sample.epoch());
            statement.setLong(9, sample.seq());
            statement.setLong(10, sample.ttlMs());
            statement.setLong(11, sample.seqGap());
            statement.setLong(12, sample.arrivalGapMs());
            statement.setLong(13, sample.agentCollectMs());
            statement.setLong(14, sample.agentEncodeMs());
            statement.setLong(15, sample.agentSendMs());
            statement.setLong(16, sample.agentThreadCount());
            statement.setLong(17, sample.agentRssKb());
            statement.setString(18, MAPPER.writeValueAsString(sample.state()));
            statement.executeUpdate();
        }
        upsertHostDimension(sample);
        writeTideWorkers(sample);
    }

    @Override
    public void writeGroupLeader(GroupLeaderMetricSample sample) throws Exception {
        String sql = """
                INSERT OR REPLACE INTO group_leader_sample (
                    bucket_ms, observed_at_ms, group_id, leader_agent_id, leader_ip, cluster, area,
                    group_generation, member_count, submitted_agent_count, accepted_agent_count,
                    rejected_agent_count, stale_member_count, missing_member_count, direct_fallback_count,
                    leader_collect_ms, group_latency_ms, arrival_gap_ms, status, debug_json, stored_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, bucketMs(sample.observedAtMs()));
            statement.setLong(index++, sample.observedAtMs());
            statement.setString(index++, sample.groupId());
            statement.setString(index++, sample.leaderAgentId());
            statement.setString(index++, sample.leaderIp());
            statement.setString(index++, sample.cluster());
            statement.setString(index++, sample.area());
            statement.setLong(index++, sample.groupGeneration());
            statement.setLong(index++, sample.memberCount());
            statement.setLong(index++, sample.submittedAgentCount());
            statement.setLong(index++, sample.acceptedAgentCount());
            statement.setLong(index++, sample.rejectedAgentCount());
            statement.setLong(index++, sample.staleMemberCount());
            statement.setLong(index++, sample.missingMemberCount());
            statement.setLong(index++, sample.directFallbackCount());
            statement.setLong(index++, sample.leaderCollectMs());
            statement.setLong(index++, sample.groupLatencyMs());
            statement.setLong(index++, sample.arrivalGapMs());
            statement.setString(index++, sample.status());
            statement.setString(index++, MAPPER.writeValueAsString(sample.debug()));
            statement.setLong(index, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public void writeHostEvent(HostEvent event) throws Exception {
        String sql = """
                INSERT OR REPLACE INTO host_event (
                    event_id, observed_at_ms, agent_id, severity, event_type, message, details_json, stored_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.eventId());
            statement.setLong(2, event.observedAtMs());
            statement.setString(3, event.agentId());
            statement.setString(4, event.severity());
            statement.setString(5, event.eventType());
            statement.setString(6, event.message());
            statement.setString(7, MAPPER.writeValueAsString(event.details()));
            statement.setLong(8, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public int deleteExpiredSamples(long cutoffMs, int limit) throws SQLException {
        int boundedLimit = Math.max(1, limit);
        return deleteExpired("heartbeat_sample", cutoffMs, boundedLimit)
                + deleteExpired("tide_worker_sample", cutoffMs, boundedLimit)
                + deleteExpired("group_leader_sample", cutoffMs, boundedLimit)
                + deleteExpired("host_event", cutoffMs, boundedLimit);
    }

    public void checkpointWal() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(PASSIVE)");
        }
    }

    private int deleteExpired(String table, long cutoffMs, int limit) throws SQLException {
        String sql = """
                DELETE FROM %s
                WHERE rowid IN (
                    SELECT rowid FROM %s WHERE observed_at_ms < ? ORDER BY observed_at_ms ASC LIMIT ?
                )
                """.formatted(table, table);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cutoffMs);
            statement.setInt(2, limit);
            return statement.executeUpdate();
        }
    }

    public void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
    }

    public void commitTransaction() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    public void rollbackTransaction() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public MetricQueryResult queryRange(MetricQuery query) throws Exception {
        MetricColumn metric = MetricColumn.fromName(query.metric());
        if (metric.source == MetricSource.TIDE_WORKER) {
            return queryTideWorkerRange(query, metric);
        }
        if (metric.source == MetricSource.GROUP_LEADER) {
            return queryGroupLeaderRange(query, metric);
        }
        int pointLimit = effectivePointLimit(query);
        int seriesLimit = effectiveSeriesLimit(query);
        long stepMs = effectiveStepMs(query);
        List<String> agentIds = limitedAgentIds(query, seriesLimit);
        StringBuilder sql = new StringBuilder("""
                SELECT
                    (observed_at_ms / ?) * ? AS observed_at_ms,
                    agent_id,
                    MIN(heartbeat_path) AS heartbeat_path,
                    MIN(group_mode) AS group_mode,
                    MAX(epoch) AS epoch,
                    MAX(seq) AS seq,
                    AVG(%s) AS metric_value,
                    MIN(state_json) AS state_json
                FROM heartbeat_sample
                WHERE observed_at_ms >= ? AND observed_at_ms <= ?
                """.formatted(metric.column()));
        if (!agentIds.isEmpty()) {
            sql.append(" AND agent_id IN (");
            sql.append("?,".repeat(agentIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
        }
        sql.append(" GROUP BY agent_id, (observed_at_ms / ?) ORDER BY agent_id ASC, observed_at_ms ASC LIMIT ?");

        Map<String, List<MetricPoint>> pointsByAgent = new LinkedHashMap<>();
        boolean truncated;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, stepMs);
            statement.setLong(index++, stepMs);
            statement.setLong(index++, query.startMs());
            statement.setLong(index++, query.endMs());
            for (String agentId : agentIds) {
                statement.setString(index++, agentId);
            }
            statement.setLong(index++, stepMs);
            statement.setInt(index, pointLimit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                int rows = 0;
                while (resultSet.next()) {
                    rows++;
                    if (rows > pointLimit) {
                        break;
                    }
                    String agentId = resultSet.getString("agent_id");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("heartbeat_path", resultSet.getString("heartbeat_path"));
                    metadata.put("group_mode", resultSet.getString("group_mode"));
                    metadata.put("epoch", resultSet.getLong("epoch"));
                    metadata.put("seq", resultSet.getLong("seq"));
                    metadata.putAll(readState(resultSet.getString("state_json")));
                    pointsByAgent
                            .computeIfAbsent(agentId, ignored -> new ArrayList<>())
                            .add(new MetricPoint(
                                    resultSet.getLong("observed_at_ms"),
                                    resultSet.getDouble("metric_value"),
                                    metadata));
                }
                truncated = rows > pointLimit;
            }
        }

        List<MetricSeries> series = pointsByAgent.entrySet().stream()
                .map(entry -> new MetricSeries(Map.of("agent_id", entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
        SeriesBudgetResult budgeted = applySeriesBudget(series, query, seriesLimit);
        series = budgeted.series();
        boolean seriesTruncated = query.agentIds().size() > seriesLimit || budgeted.truncated();
        return new MetricQueryResult(
                queryId(query),
                query.metric(),
                query.startMs(),
                query.endMs(),
                metric.unit(),
                "avg",
                truncated || seriesTruncated,
                suggestedStepMs(query, pointLimit, seriesLimit, truncated || seriesTruncated, stepMs),
                seriesLimit,
                pointLimit,
                series);
    }

    @Override
    public MetricStorageHealth health() {
        return new MetricStorageHealth("ok", 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
    }

    private MetricQueryResult queryTideWorkerRange(MetricQuery query, MetricColumn metric) throws Exception {
        int pointLimit = effectivePointLimit(query);
        int seriesLimit = effectiveSeriesLimit(query);
        long stepMs = effectiveStepMs(query);
        List<String> agentIds = limitedAgentIds(query, seriesLimit);
        StringBuilder sql = new StringBuilder("""
                SELECT
                    (observed_at_ms / ?) * ? AS observed_at_ms,
                    agent_id,
                    pid,
                    MIN(version) AS version,
                    MIN(role) AS role,
                    AVG(%s) AS metric_value
                FROM tide_worker_sample
                WHERE observed_at_ms >= ? AND observed_at_ms <= ?
                """.formatted(metric.column()));
        if (!agentIds.isEmpty()) {
            sql.append(" AND agent_id IN (");
            sql.append("?,".repeat(agentIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
        }
        sql.append("""
                 GROUP BY agent_id, pid, (observed_at_ms / ?)
                 ORDER BY agent_id ASC, pid ASC, observed_at_ms ASC LIMIT ?
                """);

        Map<String, List<MetricPoint>> pointsBySeries = new LinkedHashMap<>();
        Map<String, Map<String, String>> labelsBySeries = new LinkedHashMap<>();
        boolean truncated;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, stepMs);
            statement.setLong(index++, stepMs);
            statement.setLong(index++, query.startMs());
            statement.setLong(index++, query.endMs());
            for (String agentId : agentIds) {
                statement.setString(index++, agentId);
            }
            statement.setLong(index++, stepMs);
            statement.setInt(index, pointLimit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                int rows = 0;
                while (resultSet.next()) {
                    rows++;
                    if (rows > pointLimit) {
                        break;
                    }
                    String pid = String.valueOf(resultSet.getLong("pid"));
                    String seriesId = resultSet.getString("agent_id") + ":" + pid;
                    labelsBySeries.putIfAbsent(seriesId, Map.of(
                            "agent_id", resultSet.getString("agent_id"),
                            "pid", pid,
                            "version", nullToEmpty(resultSet.getString("version")),
                            "role", nullToEmpty(resultSet.getString("role"))));
                    pointsBySeries.computeIfAbsent(seriesId, ignored -> new ArrayList<>())
                            .add(new MetricPoint(resultSet.getLong("observed_at_ms"), resultSet.getDouble("metric_value"), Map.of()));
                }
                truncated = rows > pointLimit;
            }
        }
        List<MetricSeries> series = pointsBySeries.entrySet().stream()
                .map(entry -> new MetricSeries(labelsBySeries.get(entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
        SeriesBudgetResult budgeted = applySeriesBudget(series, query, seriesLimit);
        series = budgeted.series();
        boolean seriesTruncated = query.agentIds().size() > seriesLimit || budgeted.truncated();
        return new MetricQueryResult(
                queryId(query),
                query.metric(),
                query.startMs(),
                query.endMs(),
                metric.unit(),
                "avg",
                truncated || seriesTruncated,
                suggestedStepMs(query, pointLimit, seriesLimit, truncated || seriesTruncated, stepMs),
                seriesLimit,
                pointLimit,
                series);
    }

    private MetricQueryResult queryGroupLeaderRange(MetricQuery query, MetricColumn metric) throws Exception {
        int pointLimit = effectivePointLimit(query);
        int seriesLimit = effectiveSeriesLimit(query);
        long stepMs = effectiveStepMs(query);
        String sql = """
                SELECT
                    (observed_at_ms / ?) * ? AS observed_at_ms,
                    group_id,
                    MIN(leader_agent_id) AS leader_agent_id,
                    MIN(cluster) AS cluster,
                    MIN(area) AS area,
                    MIN(status) AS status,
                    AVG(%s) AS metric_value
                FROM group_leader_sample
                WHERE observed_at_ms >= ? AND observed_at_ms <= ?
                GROUP BY group_id, (observed_at_ms / ?)
                ORDER BY group_id ASC, observed_at_ms ASC LIMIT ?
                """.formatted(metric.column());
        Map<String, List<MetricPoint>> pointsByGroup = new LinkedHashMap<>();
        Map<String, Map<String, String>> labelsByGroup = new LinkedHashMap<>();
        boolean truncated;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, stepMs);
            statement.setLong(2, stepMs);
            statement.setLong(3, query.startMs());
            statement.setLong(4, query.endMs());
            statement.setLong(5, stepMs);
            statement.setInt(6, pointLimit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                int rows = 0;
                while (resultSet.next()) {
                    rows++;
                    if (rows > pointLimit) {
                        break;
                    }
                    String groupId = resultSet.getString("group_id");
                    labelsByGroup.putIfAbsent(groupId, Map.of(
                            "group_id", groupId,
                            "leader_agent_id", resultSet.getString("leader_agent_id"),
                            "cluster", resultSet.getString("cluster"),
                            "area", resultSet.getString("area"),
                            "status", resultSet.getString("status")));
                    pointsByGroup.computeIfAbsent(groupId, ignored -> new ArrayList<>())
                            .add(new MetricPoint(resultSet.getLong("observed_at_ms"), resultSet.getDouble("metric_value"), Map.of()));
                }
                truncated = rows > pointLimit;
            }
        }
        List<MetricSeries> series = pointsByGroup.entrySet().stream()
                .map(entry -> new MetricSeries(labelsByGroup.get(entry.getKey()), List.copyOf(entry.getValue())))
                .toList();
        SeriesBudgetResult budgeted = applySeriesBudget(series, query, seriesLimit);
        series = budgeted.series();
        boolean seriesTruncated = budgeted.truncated();
        return new MetricQueryResult(
                queryId(query),
                query.metric(),
                query.startMs(),
                query.endMs(),
                metric.unit(),
                "avg",
                truncated || seriesTruncated,
                suggestedStepMs(query, pointLimit, seriesLimit, truncated || seriesTruncated, stepMs),
                seriesLimit,
                pointLimit,
                series);
    }

    @Override
    public List<HostEvent> queryEvents(MetricEventQuery query) throws Exception {
        int limit = Math.max(1, query.limit());
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, observed_at_ms, agent_id, severity, event_type, message, details_json
                FROM host_event
                WHERE observed_at_ms >= ? AND observed_at_ms <= ?
                """);
        if (query.agentId() != null && !query.agentId().isBlank()) {
            sql.append(" AND agent_id = ?");
        }
        if (!query.severities().isEmpty()) {
            sql.append(" AND severity IN (");
            sql.append("?,".repeat(query.severities().size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
        }
        sql.append(" ORDER BY observed_at_ms ASC LIMIT ?");
        List<HostEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, query.startMs());
            statement.setLong(index++, query.endMs());
            if (query.agentId() != null && !query.agentId().isBlank()) {
                statement.setString(index++, query.agentId());
            }
            for (String severity : query.severities()) {
                statement.setString(index++, severity);
            }
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new HostEvent(
                            resultSet.getString("event_id"),
                            resultSet.getLong("observed_at_ms"),
                            resultSet.getString("agent_id"),
                            resultSet.getString("severity"),
                            resultSet.getString("event_type"),
                            resultSet.getString("message"),
                            readState(resultSet.getString("details_json"))));
                }
            }
        }
        return events;
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS heartbeat_sample (
                        observed_at_ms INTEGER NOT NULL,
                        agent_id TEXT NOT NULL,
                        host TEXT NOT NULL,
                        cluster TEXT NOT NULL,
                        area TEXT NOT NULL,
                        heartbeat_path TEXT NOT NULL,
                        group_mode TEXT NOT NULL,
                        epoch INTEGER NOT NULL,
                        seq INTEGER NOT NULL,
                        ttl_ms INTEGER NOT NULL,
                        seq_gap INTEGER NOT NULL,
                        arrival_gap_ms INTEGER NOT NULL,
                        agent_collect_ms INTEGER NOT NULL,
                        agent_encode_ms INTEGER NOT NULL,
                        agent_send_ms INTEGER NOT NULL,
                        agent_thread_count INTEGER NOT NULL,
                        agent_rss_kb INTEGER NOT NULL,
                        state_json TEXT NOT NULL,
                        PRIMARY KEY (observed_at_ms, agent_id, epoch, seq)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_heartbeat_agent_time ON heartbeat_sample(agent_id, observed_at_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_heartbeat_cluster_time ON heartbeat_sample(cluster, observed_at_ms)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS host_dimension (
                        agent_id TEXT PRIMARY KEY,
                        ip TEXT NOT NULL,
                        normalized_ip TEXT NOT NULL,
                        cluster TEXT NOT NULL DEFAULT 'unknown',
                        area TEXT NOT NULL DEFAULT 'unknown',
                        host_group TEXT NOT NULL DEFAULT 'unknown',
                        mode TEXT NOT NULL DEFAULT 'unknown',
                        coordinator_id TEXT NOT NULL DEFAULT 'local',
                        first_seen_ms INTEGER NOT NULL,
                        last_seen_ms INTEGER NOT NULL,
                        last_status TEXT NOT NULL,
                        last_heartbeat_seq INTEGER,
                        metadata_json TEXT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_host_dimension_cluster ON host_dimension(cluster, area, host_group)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_host_dimension_ip ON host_dimension(normalized_ip)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tide_worker_sample (
                        bucket_ms INTEGER NOT NULL,
                        observed_at_ms INTEGER NOT NULL,
                        agent_id TEXT NOT NULL,
                        pid INTEGER NOT NULL,
                        version TEXT,
                        role TEXT,
                        leader TEXT,
                        area TEXT,
                        group_name TEXT,
                        cpu_pct REAL,
                        usr_pct REAL,
                        sys_pct REAL,
                        mem_pct REAL,
                        rss_kb INTEGER,
                        thread_count INTEGER,
                        port INTEGER,
                        age_seconds INTEGER,
                        mode TEXT,
                        size_current INTEGER,
                        size_total INTEGER,
                        debug_json TEXT,
                        stored_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (agent_id, observed_at_ms, pid)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tide_worker_time ON tide_worker_sample(bucket_ms, observed_at_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tide_worker_agent_time ON tide_worker_sample(agent_id, observed_at_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tide_worker_role_time ON tide_worker_sample(role, observed_at_ms)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS group_leader_sample (
                        bucket_ms INTEGER NOT NULL,
                        observed_at_ms INTEGER NOT NULL,
                        group_id TEXT NOT NULL,
                        leader_agent_id TEXT NOT NULL,
                        leader_ip TEXT,
                        cluster TEXT NOT NULL DEFAULT 'unknown',
                        area TEXT NOT NULL DEFAULT 'unknown',
                        group_generation INTEGER,
                        member_count INTEGER NOT NULL,
                        submitted_agent_count INTEGER NOT NULL,
                        accepted_agent_count INTEGER NOT NULL,
                        rejected_agent_count INTEGER NOT NULL DEFAULT 0,
                        stale_member_count INTEGER NOT NULL DEFAULT 0,
                        missing_member_count INTEGER NOT NULL DEFAULT 0,
                        direct_fallback_count INTEGER NOT NULL DEFAULT 0,
                        leader_collect_ms INTEGER,
                        group_latency_ms INTEGER,
                        arrival_gap_ms INTEGER,
                        status TEXT NOT NULL,
                        debug_json TEXT,
                        stored_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (group_id, observed_at_ms)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_group_leader_time ON group_leader_sample(bucket_ms, observed_at_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_group_leader_cluster_time ON group_leader_sample(cluster, area, bucket_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_group_leader_agent_time ON group_leader_sample(leader_agent_id, observed_at_ms)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS host_event (
                        event_id TEXT PRIMARY KEY,
                        observed_at_ms INTEGER NOT NULL,
                        agent_id TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        message TEXT NOT NULL,
                        details_json TEXT,
                        stored_at_ms INTEGER NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_host_event_agent_time ON host_event(agent_id, observed_at_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_host_event_type_time ON host_event(event_type, observed_at_ms)");
        }
    }

    private void upsertHostDimension(HeartbeatMetricSample sample) throws Exception {
        String sql = """
                INSERT INTO host_dimension (
                    agent_id, ip, normalized_ip, cluster, area, host_group, mode, coordinator_id,
                    first_seen_ms, last_seen_ms, last_status, last_heartbeat_seq, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'local', ?, ?, 'alive', ?, ?)
                ON CONFLICT(agent_id) DO UPDATE SET
                    ip=excluded.ip,
                    normalized_ip=excluded.normalized_ip,
                    cluster=excluded.cluster,
                    area=excluded.area,
                    host_group=excluded.host_group,
                    mode=excluded.mode,
                    last_seen_ms=excluded.last_seen_ms,
                    last_status=excluded.last_status,
                    last_heartbeat_seq=excluded.last_heartbeat_seq,
                    metadata_json=excluded.metadata_json
                """;
        String ip = stringValue(sample.state().get("ip"), sample.agentId());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sample.agentId());
            statement.setString(2, ip);
            statement.setString(3, ip);
            statement.setString(4, sample.cluster());
            statement.setString(5, sample.area());
            statement.setString(6, stringValue(sample.state().get("group"), "unknown"));
            statement.setString(7, sample.groupMode());
            statement.setLong(8, sample.observedAtMs());
            statement.setLong(9, sample.observedAtMs());
            statement.setLong(10, sample.seq());
            statement.setString(11, MAPPER.writeValueAsString(sample.state()));
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private void writeTideWorkers(HeartbeatMetricSample sample) throws Exception {
        Object workersValue = sample.state().get("tide_workers");
        if (!(workersValue instanceof List<?> workers) || workers.isEmpty()) {
            return;
        }
        String sql = """
                INSERT OR REPLACE INTO tide_worker_sample (
                    bucket_ms, observed_at_ms, agent_id, pid, version, role, leader, area, group_name,
                    cpu_pct, usr_pct, sys_pct, mem_pct, rss_kb, thread_count, port,
                    age_seconds, mode, size_current, size_total, debug_json, stored_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object workerValue : workers) {
                if (!(workerValue instanceof Map<?, ?> rawWorker)) {
                    continue;
                }
                Map<String, Object> worker = (Map<String, Object>) rawWorker;
                int index = 1;
                statement.setLong(index++, bucketMs(sample.observedAtMs()));
                statement.setLong(index++, sample.observedAtMs());
                statement.setString(index++, sample.agentId());
                statement.setLong(index++, longValue(worker.get("pid")));
                statement.setString(index++, stringValue(worker.get("component_version"), ""));
                statement.setString(index++, stringValue(worker.get("role"), ""));
                statement.setString(index++, stringValue(worker.get("leader"), ""));
                statement.setString(index++, stringValue(worker.get("area"), sample.area()));
                statement.setString(index++, stringValue(worker.get("group"), ""));
                statement.setDouble(index++, doubleValue(worker.get("cpu_percent")));
                statement.setDouble(index++, doubleValue(worker.get("user_cpu_percent")));
                statement.setDouble(index++, doubleValue(worker.get("sys_cpu_percent")));
                statement.setDouble(index++, doubleValue(worker.get("mem_percent")));
                statement.setLong(index++, longValue(worker.get("rss_kb")));
                statement.setLong(index++, longValue(worker.get("threads")));
                statement.setLong(index++, longValue(worker.get("port1")));
                statement.setLong(index++, longValue(worker.get("age_seconds")));
                statement.setString(index++, stringValue(worker.get("mode"), ""));
                statement.setLong(index++, longValue(worker.get("size_current")));
                statement.setLong(index++, longValue(worker.get("size_total")));
                statement.setString(index++, MAPPER.writeValueAsString(worker));
                statement.setLong(index, System.currentTimeMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Map<String, Object> readState(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(json, MAP_TYPE);
    }

    private static long bucketMs(long observedAtMs) {
        return observedAtMs - Math.floorMod(observedAtMs, 10_000L);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int effectivePointLimit(MetricQuery query) {
        return Math.min(MAX_POINT_LIMIT, Math.max(1, query.pointLimit()));
    }

    private static int effectiveSeriesLimit(MetricQuery query) {
        return Math.min(MAX_SERIES_LIMIT, Math.max(1, query.seriesLimit()));
    }

    private static long effectiveStepMs(MetricQuery query) {
        return Math.max(1, query.stepMs());
    }

    private static long suggestedStepMs(
            MetricQuery query, int pointLimit, int seriesLimit, boolean truncated, long actualStepMs) {
        if (!truncated) {
            return actualStepMs;
        }
        if (query.endMs() <= query.startMs()) {
            return actualStepMs;
        }
        long spanMs = Math.max(1, query.endMs() - query.startMs() + 1);
        int estimatedSeries = query.agentIds().isEmpty()
                ? seriesLimit
                : Math.min(seriesLimit, Math.max(1, query.agentIds().size()));
        long budgetedStepMs = (long) Math.ceil((double) spanMs * estimatedSeries / Math.max(1, pointLimit));
        return Math.max(actualStepMs, Math.max(1, budgetedStepMs));
    }

    private static List<String> limitedAgentIds(MetricQuery query, int seriesLimit) {
        if (query.agentIds().size() <= seriesLimit) {
            return query.agentIds();
        }
        return List.copyOf(query.agentIds().subList(0, seriesLimit));
    }

    private static SeriesBudgetResult applySeriesBudget(List<MetricSeries> series, MetricQuery query, int seriesLimit) {
        int limit = Math.min(seriesLimit, query.topN() > 0 ? query.topN() : seriesLimit);
        boolean truncated = series.size() > limit;
        List<MetricSeries> ordered = query.topN() > 0
                ? series.stream()
                        .sorted(Comparator
                                .comparingDouble(LocalMetricStorage::maxPointValue)
                                .reversed()
                                .thenComparing(LocalMetricStorage::stableLabelKey))
                        .toList()
                : series;
        if (ordered.size() > limit) {
            ordered = List.copyOf(ordered.subList(0, limit));
        }
        if (truncated) {
            List<MetricSeries> withAggregate = new ArrayList<>(ordered);
            withAggregate.add(aggregateSeries(series));
            ordered = List.copyOf(withAggregate);
        }
        return new SeriesBudgetResult(ordered, truncated);
    }

    private static MetricSeries aggregateSeries(List<MetricSeries> series) {
        Map<Long, AggregateBucket> buckets = new LinkedHashMap<>();
        series.stream()
                .flatMap(item -> item.points().stream())
                .sorted(Comparator.comparingLong(MetricPoint::timestampMs))
                .forEach(point -> buckets
                        .computeIfAbsent(point.timestampMs(), ignored -> new AggregateBucket())
                        .add(point.value()));
        List<MetricPoint> points = buckets.entrySet().stream()
                .map(entry -> new MetricPoint(
                        entry.getKey(),
                        entry.getValue().average(),
                        Map.of("series_count", entry.getValue().count())))
                .toList();
        return new MetricSeries(Map.of("series_role", "aggregate", "aggregate", "avg"), points);
    }

    private static double maxPointValue(MetricSeries series) {
        return series.points().stream().mapToDouble(MetricPoint::value).max().orElse(Double.NEGATIVE_INFINITY);
    }

    private static String stableLabelKey(MetricSeries series) {
        return series.labels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left + "|" + right);
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        return value.toString();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || value.toString().isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static String queryId(MetricQuery query) {
        int hash = Math.abs((query.metric() + query.agentIds()).hashCode());
        return "q-" + query.startMs() + "-" + query.endMs() + "-" + hash;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private enum MetricColumn {
        ARRIVAL_GAP("heartbeat.arrival_gap_ms", "arrival_gap_ms", "ms", MetricSource.HEARTBEAT),
        SEQ_GAP("heartbeat.seq_gap", "seq_gap", "count", MetricSource.HEARTBEAT),
        AGENT_COLLECT("heartbeat.agent_collect_ms", "agent_collect_ms", "ms", MetricSource.HEARTBEAT),
        AGENT_ENCODE("heartbeat.agent_encode_ms", "agent_encode_ms", "ms", MetricSource.HEARTBEAT),
        AGENT_SEND("heartbeat.agent_send_ms", "agent_send_ms", "ms", MetricSource.HEARTBEAT),
        AGENT_THREADS("agent.thread_count", "agent_thread_count", "threads", MetricSource.HEARTBEAT),
        AGENT_RSS("agent.rss_kb", "agent_rss_kb", "KiB", MetricSource.HEARTBEAT),
        TIDE_CPU("tide_worker.cpu_pct", "cpu_pct", "%", MetricSource.TIDE_WORKER),
        TIDE_RSS("tide_worker.rss_kb", "rss_kb", "KiB", MetricSource.TIDE_WORKER),
        TIDE_THREADS("tide_worker.thread_count", "thread_count", "threads", MetricSource.TIDE_WORKER),
        GROUP_MEMBER("group.member_count", "member_count", "count", MetricSource.GROUP_LEADER),
        GROUP_SUBMITTED("group.submitted_agent_count", "submitted_agent_count", "count", MetricSource.GROUP_LEADER),
        GROUP_ACCEPTED("group.accepted_agent_count", "accepted_agent_count", "count", MetricSource.GROUP_LEADER),
        GROUP_MISSING("group.missing_member_count", "missing_member_count", "count", MetricSource.GROUP_LEADER),
        GROUP_STALE("group.stale_member_count", "stale_member_count", "count", MetricSource.GROUP_LEADER),
        GROUP_DIRECT_FALLBACK("group.direct_fallback_count", "direct_fallback_count", "count", MetricSource.GROUP_LEADER),
        GROUP_UNHEALTHY("group.status_unhealthy", "CASE WHEN status = 'ok' THEN 0 ELSE 1 END", "ratio", MetricSource.GROUP_LEADER),
        GROUP_COLLECT("group.leader_collect_ms", "leader_collect_ms", "ms", MetricSource.GROUP_LEADER),
        GROUP_LATENCY("group.group_latency_ms", "group_latency_ms", "ms", MetricSource.GROUP_LEADER);

        private final String name;
        private final String column;
        private final String unit;
        private final MetricSource source;

        MetricColumn(String name, String column, String unit, MetricSource source) {
            this.name = name;
            this.column = column;
            this.unit = unit;
            this.source = source;
        }

        private String column() {
            return column;
        }

        private String unit() {
            return unit;
        }

        private static MetricColumn fromName(String name) {
            for (MetricColumn metric : values()) {
                if (metric.name.equals(name)) {
                    return metric;
                }
            }
            throw new IllegalArgumentException("unsupported metric: " + name);
        }
    }

    private enum MetricSource {
        HEARTBEAT,
        TIDE_WORKER,
        GROUP_LEADER
    }
}

record SeriesBudgetResult(List<MetricSeries> series, boolean truncated) {}

final class AggregateBucket {
    private double sum;
    private int count;

    void add(double value) {
        sum += value;
        count++;
    }

    double average() {
        return count == 0 ? 0.0 : sum / count;
    }

    int count() {
        return count;
    }
}

record HeartbeatMetricSample(
        long observedAtMs,
        String agentId,
        String host,
        String cluster,
        String area,
        String heartbeatPath,
        String groupMode,
        long epoch,
        long seq,
        long ttlMs,
        long seqGap,
        long arrivalGapMs,
        long agentCollectMs,
        long agentEncodeMs,
        long agentSendMs,
        long agentThreadCount,
        long agentRssKb,
        Map<String, Object> state) {
    HeartbeatMetricSample {
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}

record GroupLeaderMetricSample(
        long observedAtMs,
        String groupId,
        String leaderAgentId,
        String leaderIp,
        String cluster,
        String area,
        long groupGeneration,
        long memberCount,
        long submittedAgentCount,
        long acceptedAgentCount,
        long rejectedAgentCount,
        long staleMemberCount,
        long missingMemberCount,
        long directFallbackCount,
        long leaderCollectMs,
        long groupLatencyMs,
        long arrivalGapMs,
        String status,
        Map<String, Object> debug) {
    GroupLeaderMetricSample {
        debug = debug == null ? Map.of() : Map.copyOf(debug);
    }
}

record HostEvent(
        String eventId,
        long observedAtMs,
        String agentId,
        String severity,
        String eventType,
        String message,
        Map<String, Object> details) {
    HostEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}

record MetricEventQuery(
        long startMs,
        long endMs,
        String agentId,
        List<String> severities,
        int limit) {
    MetricEventQuery {
        severities = severities == null ? List.of() : List.copyOf(severities);
    }
}

record MetricQuery(
        String metric,
        List<String> agentIds,
        long startMs,
        long endMs,
        long stepMs,
        int seriesLimit,
        int pointLimit,
        int topN) {
    MetricQuery(String metric, List<String> agentIds, long startMs, long endMs, long stepMs, int seriesLimit, int pointLimit) {
        this(metric, agentIds, startMs, endMs, stepMs, seriesLimit, pointLimit, 0);
    }

    MetricQuery(String metric, List<String> agentIds, long startMs, long endMs, long stepMs, int pointLimit) {
        this(metric, agentIds, startMs, endMs, stepMs, LocalMetricStorage.DEFAULT_SERIES_LIMIT, pointLimit, 0);
    }

    MetricQuery {
        agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
        topN = Math.max(0, topN);
    }
}

record MetricQueryResult(
        String queryId,
        String metric,
        long from,
        long to,
        String unit,
        String samplePolicy,
        boolean truncated,
        long suggestedStepMs,
        int seriesLimit,
        int pointLimit,
        List<MetricSeries> series) {
    MetricQueryResult {
        series = series == null ? List.of() : List.copyOf(series);
    }
}

record MetricSeries(Map<String, String> labels, List<MetricPoint> points) {
    MetricSeries {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        points = points == null ? List.of() : List.copyOf(points);
    }
}

record MetricPoint(long timestampMs, double value, Map<String, Object> metadata) {
    MetricPoint {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

record MetricCatalogItem(String metric, String title, String unit) {}
