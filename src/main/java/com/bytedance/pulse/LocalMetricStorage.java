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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LocalMetricStorage implements AutoCloseable {
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
                new MetricCatalogItem("agent.rss_kb", "Agent RSS", "KiB"));
    }

    void writeHeartbeat(HeartbeatMetricSample sample) throws Exception {
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
    }

    MetricQueryResult queryRange(MetricQuery query) throws Exception {
        MetricColumn metric = MetricColumn.fromName(query.metric());
        int pointLimit = Math.max(1, query.pointLimit());
        StringBuilder sql = new StringBuilder("""
                SELECT observed_at_ms, agent_id, heartbeat_path, group_mode, epoch, seq, %s AS metric_value, state_json
                FROM heartbeat_sample
                WHERE observed_at_ms >= ? AND observed_at_ms <= ?
                """.formatted(metric.column()));
        if (!query.agentIds().isEmpty()) {
            sql.append(" AND agent_id IN (");
            sql.append("?,".repeat(query.agentIds().size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
        }
        sql.append(" ORDER BY agent_id ASC, observed_at_ms ASC LIMIT ?");

        Map<String, List<MetricPoint>> pointsByAgent = new LinkedHashMap<>();
        boolean truncated;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, query.startMs());
            statement.setLong(index++, query.endMs());
            for (String agentId : query.agentIds()) {
                statement.setString(index++, agentId);
            }
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
        return new MetricQueryResult(
                query.metric(),
                "avg",
                truncated,
                Math.max(1, query.stepMs()),
                query.agentIds().size(),
                pointLimit,
                series);
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
        }
    }

    private static Map<String, Object> readState(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(json, MAP_TYPE);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private enum MetricColumn {
        ARRIVAL_GAP("heartbeat.arrival_gap_ms", "arrival_gap_ms"),
        SEQ_GAP("heartbeat.seq_gap", "seq_gap"),
        AGENT_COLLECT("heartbeat.agent_collect_ms", "agent_collect_ms"),
        AGENT_ENCODE("heartbeat.agent_encode_ms", "agent_encode_ms"),
        AGENT_SEND("heartbeat.agent_send_ms", "agent_send_ms"),
        AGENT_THREADS("agent.thread_count", "agent_thread_count"),
        AGENT_RSS("agent.rss_kb", "agent_rss_kb");

        private final String name;
        private final String column;

        MetricColumn(String name, String column) {
            this.name = name;
            this.column = column;
        }

        private String column() {
            return column;
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

record MetricQuery(
        String metric,
        List<String> agentIds,
        long startMs,
        long endMs,
        long stepMs,
        int pointLimit) {
    MetricQuery {
        agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
    }
}

record MetricQueryResult(
        String metric,
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
