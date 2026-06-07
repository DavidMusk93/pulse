package com.bytedance.pulse;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

public final class PulseCoordinatorApp {
    private PulseCoordinatorApp() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_PORT", "8080"));
        String bindHost = System.getenv().getOrDefault("PULSE_BIND_HOST", "127.0.0.1");
        String coordinatorId = System.getenv().getOrDefault("PULSE_COORDINATOR_ID", "coordinator-local");

        MetricStorage metricStorage = metricStorageFromEnv(System.getenv());
        CoordinatorService service = new CoordinatorService(coordinatorId, Clock.systemUTC(), metricStorage);
        CoordinatorHttpServer server = new CoordinatorHttpServer(service, bindHost, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(metricStorage), "pulse-coordinator-shutdown"));
        server.start();

        System.out.printf("Pulse coordinator %s listening on %s:%d%n", coordinatorId, bindHost, server.port());
    }

    static MetricStorage metricStorageFromEnv(Map<String, String> env) throws Exception {
        String enabled = env.getOrDefault("PULSE_LOCAL_STORAGE_ENABLED", "true");
        if ("false".equalsIgnoreCase(enabled)) {
            return null;
        }
        String dbPath = env.getOrDefault("PULSE_LOCAL_STORAGE_PATH", env.getOrDefault("PULSE_METRICS_DB", ""));
        if (dbPath.isBlank()) {
            return null;
        }
        return AsyncLocalMetricStorage.open(
                Path.of(dbPath),
                positiveInt(env, "PULSE_LOCAL_STORAGE_QUEUE_SIZE", 20_000),
                positiveInt(env, "PULSE_LOCAL_STORAGE_BATCH_SIZE", 500),
                Duration.ofMillis(positiveLong(env, "PULSE_LOCAL_STORAGE_FLUSH_MS", 1_000)));
    }

    private static void closeQuietly(MetricStorage metricStorage) {
        if (metricStorage == null) {
            return;
        }
        try {
            metricStorage.close();
        } catch (Exception ignored) {
        }
    }

    private static int positiveInt(Map<String, String> env, String key, int fallback) {
        return (int) Math.min(Integer.MAX_VALUE, positiveLong(env, key, fallback));
    }

    private static long positiveLong(Map<String, String> env, String key, long fallback) {
        String value = env.getOrDefault(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
