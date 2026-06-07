package com.bytedance.pulse;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

public final class PulseCoordinatorApp {
    private PulseCoordinatorApp() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_PORT", "8080"));
        String bindHost = System.getenv().getOrDefault("PULSE_BIND_HOST", "127.0.0.1");
        String coordinatorId = System.getenv().getOrDefault("PULSE_COORDINATOR_ID", "coordinator-local");

        LocalMetricStorage metricStorage = metricStorageFromEnv(System.getenv());
        CoordinatorService service = new CoordinatorService(coordinatorId, Clock.systemUTC(), metricStorage);
        CoordinatorHttpServer server = new CoordinatorHttpServer(service, bindHost, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(metricStorage), "pulse-coordinator-shutdown"));
        server.start();

        System.out.printf("Pulse coordinator %s listening on %s:%d%n", coordinatorId, bindHost, server.port());
    }

    static LocalMetricStorage metricStorageFromEnv(Map<String, String> env) throws Exception {
        String dbPath = env.getOrDefault("PULSE_METRICS_DB", "");
        if (dbPath.isBlank()) {
            return null;
        }
        return LocalMetricStorage.open(Path.of(dbPath));
    }

    private static void closeQuietly(LocalMetricStorage metricStorage) {
        if (metricStorage == null) {
            return;
        }
        try {
            metricStorage.close();
        } catch (Exception ignored) {
        }
    }
}
