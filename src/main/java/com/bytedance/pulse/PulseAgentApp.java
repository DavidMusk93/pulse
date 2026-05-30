package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PulseAgentApp {
    private PulseAgentApp() {}

    public static void main(String[] args) throws Exception {
        List<String> coordinatorUrls = coordinatorUrls();
        long intervalMs = Long.parseLong(System.getenv().getOrDefault("PULSE_HEARTBEAT_INTERVAL_MS", "5000"));
        Duration timeout = Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("PULSE_AGENT_TIMEOUT_MS", "2000")));
        AgentHeartbeatFactory heartbeatFactory = AgentHeartbeatFactory.fromEnvironment(Clock.systemUTC());
        HeartbeatClient client = new HeartbeatClient(coordinatorUrls, timeout);
        String groupMode = System.getenv().getOrDefault("PULSE_GROUP_MODE", "dynamic");

        System.out.printf(
                "Pulse agent started coordinators=%s interval_ms=%d group_mode=%s%n",
                coordinatorUrls,
                intervalMs,
                groupMode);
        if ("leader".equalsIgnoreCase(groupMode)) {
            runLeader(intervalMs, heartbeatFactory, client);
            return;
        }
        if ("follower".equalsIgnoreCase(groupMode)) {
            runFollower(intervalMs, timeout, heartbeatFactory);
            return;
        }
        if ("dynamic".equalsIgnoreCase(groupMode)) {
            runDynamic(intervalMs, heartbeatFactory, client);
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat();
            client.send("/heartbeat", heartbeat);
            Thread.sleep(intervalMs);
        }
    }

    private static void runLeader(long intervalMs, AgentHeartbeatFactory heartbeatFactory, HeartbeatClient client)
            throws Exception {
        String groupId = System.getenv().getOrDefault("PULSE_GROUP_ID", "unknown/unknown/000");
        int sizeLimit = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_SIZE_LIMIT", "7"));
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
        String bindHost = System.getenv().getOrDefault("PULSE_GROUP_BIND_HOST", "::");
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        GroupHeartbeatReceiver receiver = new GroupHeartbeatReceiver(bindHost, port, collector);
        receiver.start();
        System.out.printf("Pulse group leader started group_id=%s size_limit=%d port=%d%n", groupId, sizeLimit, port);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest leaderHeartbeat = heartbeatFactory.nextHeartbeat();
            HeartbeatRequest batch = collector.batch(groupId, leaderHeartbeat, Clock.systemUTC().millis(), sizeLimit);
            client.send("/heartbeat", batch);
            Thread.sleep(intervalMs);
        }
    }

    private static void runFollower(long intervalMs, Duration timeout, AgentHeartbeatFactory heartbeatFactory)
            throws Exception {
        String leaderUrl = System.getenv().getOrDefault("PULSE_GROUP_LEADER_URL", "");
        if (leaderUrl.isBlank()) {
            throw new IllegalArgumentException("PULSE_GROUP_LEADER_URL must be set in follower mode");
        }
        HeartbeatClient leaderClient = new HeartbeatClient(List.of(leaderUrl), timeout);
        System.out.printf("Pulse group follower started leader_url=%s%n", leaderUrl);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat();
            leaderClient.send("/group/heartbeat", heartbeat);
            Thread.sleep(intervalMs);
        }
    }

    private static void runDynamic(long intervalMs, AgentHeartbeatFactory heartbeatFactory, HeartbeatClient client)
            throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
        String bindHost = System.getenv().getOrDefault("PULSE_GROUP_BIND_HOST", "::");
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        GroupHeartbeatReceiver receiver = new GroupHeartbeatReceiver(bindHost, port, collector);
        receiver.start();
        AgentGroupPlan currentPlan = AgentGroupPlan.direct("unknown", Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_SIZE_LIMIT", "7")));
        System.out.printf("Pulse dynamic group receiver started port=%d%n", port);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat();
            if ("unknown".equals(currentPlan.agentId())) {
                currentPlan = AgentGroupPlan.direct(heartbeat.agentId(), currentPlan.sizeLimit());
            }
            String mode = currentPlan.groupMode();
            if ("leader".equalsIgnoreCase(mode)) {
                HeartbeatRequest batch = collector.batch(currentPlan.groupId(), heartbeat, Clock.systemUTC().millis(), currentPlan.sizeLimit());
                List<HeartbeatResponse> responses = client.sendToAllForResponses("/heartbeat", batch);
                if (!responses.isEmpty()) {
                    HeartbeatResponse planResponse = responses.get(0);
                    receiver.updatePlans(planResponse);
                    currentPlan = planFromMessages(heartbeat.agentId(), planResponse.agents().stream()
                                    .filter(agent -> heartbeat.agentId().equals(agent.agentId()))
                                    .findFirst()
                                    .map(AgentHeartbeatResponse::messages)
                                    .orElse(List.of()))
                            .orElse(currentPlan);
                }
            } else if ("follower".equalsIgnoreCase(mode) && !currentPlan.leaderUrl().isBlank()) {
                HeartbeatClient leaderClient = new HeartbeatClient(List.of(currentPlan.leaderUrl()), client.timeout);
                HeartbeatResponse response = leaderClient.sendForResponse("/group/heartbeat", heartbeat);
                if (response == null) {
                    response = client.sendToPrimaryForResponse("/heartbeat", heartbeat);
                }
                if (response != null) {
                    currentPlan = planFromMessages(heartbeat.agentId(), response.messages()).orElse(currentPlan);
                }
            } else {
                HeartbeatResponse response = client.sendToPrimaryForResponse("/heartbeat", heartbeat);
                if (response != null) {
                    currentPlan = planFromMessages(heartbeat.agentId(), response.messages()).orElse(currentPlan);
                }
            }
            Thread.sleep(intervalMs);
        }
    }

    private static java.util.Optional<AgentGroupPlan> planFromMessages(String agentId, List<PulseMessage> messages) {
        return messages.stream()
                .filter(message -> "cmd.group_plan".equals(message.type()))
                .map(PulseMessage::payload)
                .filter(payload -> payload != null)
                .findFirst()
                .map(payload -> planFromPayload(agentId, payload));
    }

    @SuppressWarnings("unchecked")
    private static AgentGroupPlan planFromPayload(String fallbackAgentId, Map<String, Object> payload) {
        return new AgentGroupPlan(
                stringValue(payload, "agent_id", fallbackAgentId),
                stringValue(payload, "group_id", "direct"),
                stringValue(payload, "group_mode", "direct"),
                stringValue(payload, "leader_agent_id", fallbackAgentId),
                stringValue(payload, "leader_url", ""),
                payload.get("members") instanceof List<?> members
                        ? members.stream().map(Object::toString).toList()
                        : List.of(fallbackAgentId),
                stringValue(payload, "cluster", "unknown"),
                stringValue(payload, "area", "unknown"),
                intValue(payload, "size_limit", 7));
    }

    private static String stringValue(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static int intValue(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
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

        boolean send(String path, HeartbeatRequest heartbeat) {
            return sendForResponse(path, heartbeat) != null;
        }

        HeartbeatResponse sendForResponse(String path, HeartbeatRequest heartbeat) {
            for (int attempt = 0; attempt < coordinatorUrls.size(); attempt++) {
                String baseUrl = coordinatorUrls.get((nextCoordinatorIndex + attempt) % coordinatorUrls.size());
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(heartbeat)))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        nextCoordinatorIndex = (nextCoordinatorIndex + attempt + 1) % coordinatorUrls.size();
                        if (heartbeat.isBatch()) {
                            System.out.printf(
                                    "heartbeat status=ok target=%s group=%s agents=%d%n",
                                    baseUrl,
                                    heartbeat.groupId(),
                                    heartbeat.agents().size());
                        } else {
                            System.out.printf(
                                    "heartbeat status=ok target=%s seq=%d%n",
                                    baseUrl,
                                    heartbeat.seq());
                        }
                        return mapper.readValue(response.body(), HeartbeatResponse.class);
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
            return null;
        }

        HeartbeatResponse sendToPrimaryForResponse(String path, HeartbeatRequest heartbeat) {
            for (String baseUrl : coordinatorUrls) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(heartbeat)))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        if (heartbeat.isBatch()) {
                            System.out.printf(
                                    "heartbeat status=ok target=%s group=%s agents=%d%n",
                                    baseUrl,
                                    heartbeat.groupId(),
                                    heartbeat.agents().size());
                        } else {
                            System.out.printf(
                                    "heartbeat status=ok target=%s seq=%d%n",
                                    baseUrl,
                                    heartbeat.seq());
                        }
                        return mapper.readValue(response.body(), HeartbeatResponse.class);
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
            return null;
        }

        List<HeartbeatResponse> sendToAllForResponses(String path, HeartbeatRequest heartbeat) {
            List<HeartbeatResponse> responses = new java.util.ArrayList<>();
            for (String baseUrl : coordinatorUrls) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(heartbeat)))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        responses.add(mapper.readValue(response.body(), HeartbeatResponse.class));
                        if (heartbeat.isBatch()) {
                            System.out.printf(
                                    "heartbeat status=ok target=%s group=%s agents=%d%n",
                                    baseUrl,
                                    heartbeat.groupId(),
                                    heartbeat.agents().size());
                        }
                    } else {
                        System.err.printf(
                                "heartbeat status=bad_response coordinator=%s code=%d body=%s%n",
                                baseUrl,
                                response.statusCode(),
                                response.body());
                    }
                } catch (Exception exception) {
                    System.err.printf(
                            "heartbeat status=failed coordinator=%s error=%s%n",
                            baseUrl,
                            exception.getMessage());
                }
            }
            return responses;
        }
    }

    static final class GroupHeartbeatReceiver {
        private final HttpServer server;
        private final GroupHeartbeatCollector collector;
        private final ObjectMapper mapper = JsonSupport.objectMapper();
        private final Map<String, List<PulseMessage>> planMessages = new ConcurrentHashMap<>();

        GroupHeartbeatReceiver(String bindHost, int port, GroupHeartbeatCollector collector) throws IOException {
            this.collector = collector;
            this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            this.server.createContext("/group/heartbeat", this::handleHeartbeat);
        }

        void start() {
            server.start();
        }

        private void handleHeartbeat(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "method not allowed");
                return;
            }
            try {
                HeartbeatRequest request = mapper.readValue(exchange.getRequestBody(), HeartbeatRequest.class);
                if (request.isBatch()) {
                    send(exchange, 400, "group receiver accepts single agent heartbeat only");
                    return;
                }
                collector.record(request);
                HeartbeatResponse response = HeartbeatResponse.single(
                        "group-leader",
                        request.seq() == null ? 0 : request.seq(),
                        planMessages.getOrDefault(request.agentId(), List.of()));
                send(exchange, 200, mapper.writeValueAsString(response));
            } catch (Exception exception) {
                send(exchange, 400, "{\"ok\":false,\"error\":\"" + exception.getMessage() + "\"}");
            }
        }

        void updatePlans(HeartbeatResponse response) {
            for (AgentHeartbeatResponse agent : response.agents()) {
                if (!agent.messages().isEmpty()) {
                    planMessages.put(agent.agentId(), agent.messages());
                }
            }
        }

        private static void send(HttpExchange exchange, int statusCode, String body) throws IOException {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
