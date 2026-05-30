package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public final class PulseAgentApp {
    private PulseAgentApp() {}

    public static void main(String[] args) throws Exception {
        List<String> coordinatorUrls = coordinatorUrls();
        long intervalMs = Long.parseLong(System.getenv().getOrDefault("PULSE_HEARTBEAT_INTERVAL_MS", "5000"));
        Duration timeout = Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("PULSE_AGENT_TIMEOUT_MS", "2000")));
        AgentHeartbeatFactory heartbeatFactory = AgentHeartbeatFactory.fromEnvironment(Clock.systemUTC());
        HeartbeatClient client = new HeartbeatClient(coordinatorUrls, timeout);

        System.out.printf("Pulse agent started coordinators=%s interval_ms=%d%n", coordinatorUrls, intervalMs);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat();
            client.send(heartbeat);
            Thread.sleep(intervalMs);
        }
    }

    private static List<String> coordinatorUrls() {
        String raw = System.getenv().getOrDefault("PULSE_COORDINATOR_URLS", "http://[::1]:9966");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    static final class HeartbeatClient {
        private final List<String> coordinatorUrls;
        private final Duration timeout;
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper mapper = JsonSupport.objectMapper();
        private int nextCoordinatorIndex;

        HeartbeatClient(List<String> coordinatorUrls, Duration timeout) {
            if (coordinatorUrls.isEmpty()) {
                throw new IllegalArgumentException("PULSE_COORDINATOR_URLS must not be empty");
            }
            this.coordinatorUrls = List.copyOf(coordinatorUrls);
            this.timeout = timeout;
        }

        void send(HeartbeatRequest heartbeat) {
            for (int attempt = 0; attempt < coordinatorUrls.size(); attempt++) {
                String baseUrl = coordinatorUrls.get((nextCoordinatorIndex + attempt) % coordinatorUrls.size());
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/heartbeat"))
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(heartbeat)))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        nextCoordinatorIndex = (nextCoordinatorIndex + attempt + 1) % coordinatorUrls.size();
                        System.out.printf(
                                "heartbeat status=ok coordinator=%s seq=%d%n",
                                baseUrl,
                                heartbeat.seq());
                        return;
                    }
                    System.err.printf(
                            "heartbeat status=bad_response coordinator=%s code=%d body=%s%n",
                            baseUrl,
                            response.statusCode(),
                            response.body());
                } catch (Exception exception) {
                    System.err.printf(
                            "heartbeat status=failed coordinator=%s error=%s%n",
                            baseUrl,
                            exception.getMessage());
                }
            }
        }
    }
}
