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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PulseAgentApp {
    private PulseAgentApp() {}

    public static void main(String[] args) throws Exception {
        List<String> coordinatorUrls = coordinatorUrls();
        long intervalMs = Long.parseLong(System.getenv().getOrDefault("PULSE_HEARTBEAT_INTERVAL_MS", "5000"));
        Duration timeout = Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("PULSE_AGENT_TIMEOUT_MS", "2000")));
        AgentHeartbeatFactory heartbeatFactory = AgentHeartbeatFactory.fromEnvironment(Clock.systemUTC());
        AgentTaskRunner taskRunner = new AgentTaskRunner(
                System.getenv().getOrDefault("PULSE_AGENT_ID", System.getenv().getOrDefault("PULSE_AGENT_HOST", "unknown")),
                Clock.systemUTC());
        HeartbeatClient client = new HeartbeatClient(coordinatorUrls, timeout);
        String groupMode = System.getenv().getOrDefault("PULSE_GROUP_MODE", "dynamic");

        System.out.printf(
                "Pulse agent started coordinators=%s interval_ms=%d group_mode=%s%n",
                coordinatorUrls,
                intervalMs,
                groupMode);
        if ("leader".equalsIgnoreCase(groupMode)) {
            runLeader(intervalMs, heartbeatFactory, taskRunner, client);
            return;
        }
        if ("follower".equalsIgnoreCase(groupMode)) {
            runFollower(intervalMs, timeout, heartbeatFactory, taskRunner);
            return;
        }
        if ("dynamic".equalsIgnoreCase(groupMode)) {
            runDynamic(intervalMs, heartbeatFactory, taskRunner, client);
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat(taskRunner.drainReplies(), taskRunner.runningTasks());
            HeartbeatResponse response = client.sendForResponse("/heartbeat", heartbeat);
            if (response != null) {
                taskRunner.handleMessages(response.messages());
            }
            Thread.sleep(intervalMs);
        }
    }

    private static void runLeader(long intervalMs, AgentHeartbeatFactory heartbeatFactory, AgentTaskRunner taskRunner, HeartbeatClient client)
            throws Exception {
        String groupId = System.getenv().getOrDefault("PULSE_GROUP_ID", "unknown/unknown/000");
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
        String bindHost = System.getenv().getOrDefault("PULSE_GROUP_BIND_HOST", "::");
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        GroupHeartbeatReceiver receiver = new GroupHeartbeatReceiver(bindHost, port, collector);
        receiver.setAcceptingFollowers(true, Set.of());
        receiver.start();
        System.out.printf("Pulse group leader started group_id=%s port=%d%n", groupId, port);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest leaderHeartbeat = heartbeatFactory.nextHeartbeat(taskRunner.drainReplies(), taskRunner.runningTasks());
            HeartbeatRequest batch = timedBatch(collector, groupId, leaderHeartbeat, Integer.MAX_VALUE);
            HeartbeatResponse response = client.sendForResponse("/heartbeat", batch);
            if (response != null) {
                receiver.updatePlans(response);
                response.agents().stream()
                        .filter(agent -> leaderHeartbeat.agentId().equals(agent.agentId()))
                        .findFirst()
                        .ifPresent(agent -> taskRunner.handleMessages(agent.messages()));
            }
            Thread.sleep(intervalMs);
        }
    }

    private static void runFollower(long intervalMs, Duration timeout, AgentHeartbeatFactory heartbeatFactory, AgentTaskRunner taskRunner)
            throws Exception {
        String leaderUrl = System.getenv().getOrDefault("PULSE_GROUP_LEADER_URL", "");
        if (leaderUrl.isBlank()) {
            throw new IllegalArgumentException("PULSE_GROUP_LEADER_URL must be set in follower mode");
        }
        HeartbeatClient leaderClient = new HeartbeatClient(List.of(leaderUrl), timeout);
        System.out.printf("Pulse group follower started leader_url=%s%n", leaderUrl);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat(taskRunner.drainReplies(), taskRunner.runningTasks());
            HeartbeatResponse response = leaderClient.sendForResponse("/group/heartbeat", heartbeat);
            if (response != null) {
                taskRunner.handleMessages(response.messages());
            }
            Thread.sleep(intervalMs);
        }
    }

    private static void runDynamic(long intervalMs, AgentHeartbeatFactory heartbeatFactory, AgentTaskRunner taskRunner, HeartbeatClient client)
            throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
        String bindHost = System.getenv().getOrDefault("PULSE_GROUP_BIND_HOST", "::");
        GroupHeartbeatCollector collector = new GroupHeartbeatCollector();
        GroupHeartbeatReceiver receiver = new GroupHeartbeatReceiver(bindHost, port, collector);
        receiver.start();
        AgentGroupPlan currentPlan = AgentGroupPlan.direct("unknown");
        FollowerLeaderClientCache followerLeaderClients = new FollowerLeaderClientCache(client.timeout);
        System.out.printf("Pulse dynamic group receiver started port=%d%n", port);
        while (!Thread.currentThread().isInterrupted()) {
            HeartbeatRequest heartbeat = heartbeatFactory.nextHeartbeat(taskRunner.drainReplies(), taskRunner.runningTasks());
            if ("unknown".equals(currentPlan.agentId())) {
                currentPlan = AgentGroupPlan.direct(heartbeat.agentId());
            }
            stampStatePayloads(heartbeat, heartbeat.agentId(), Map.of(
                    "agent_plan_generation", currentPlan.generation()));
            String mode = currentPlan.groupMode();
            receiver.setAcceptingFollowers("leader".equalsIgnoreCase(mode), Set.copyOf(currentPlan.members()));
            if ("leader".equalsIgnoreCase(mode)) {
                HeartbeatRequest batch = timedBatch(collector, currentPlan.groupId(), heartbeat, currentPlan.sizeLimit());
                HeartbeatResponse response = client.sendForResponse("/heartbeat", batch);
                if (response != null) {
                    receiver.updatePlans(response);
                    List<PulseMessage> selfMessages = response.agents().stream()
                                    .filter(agent -> heartbeat.agentId().equals(agent.agentId()))
                                    .findFirst()
                                    .map(AgentHeartbeatResponse::messages)
                                    .orElse(List.of());
                    taskRunner.handleMessages(selfMessages);
                    currentPlan = planFromMessages(heartbeat.agentId(), selfMessages).orElse(currentPlan);
                }
            } else if ("follower".equalsIgnoreCase(mode) && !currentPlan.leaderUrl().isBlank()) {
                HeartbeatResponse response = followerLeaderClients
                        .clientFor(currentPlan.leaderUrl())
                        .sendForResponse("/group/heartbeat", heartbeat);
                if (response == null) {
                    response = client.sendForResponse("/heartbeat", heartbeat);
                }
                if (response != null) {
                    taskRunner.handleMessages(response.messages());
                    currentPlan = planFromMessages(heartbeat.agentId(), response.messages()).orElse(currentPlan);
                }
            } else {
                HeartbeatResponse response = client.sendForResponse("/heartbeat", heartbeat);
                if (response != null) {
                    taskRunner.handleMessages(response.messages());
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

    private static HeartbeatRequest timedBatch(
            GroupHeartbeatCollector collector, String groupId, HeartbeatRequest leaderHeartbeat, int sizeLimit) {
        long nowMs = Clock.systemUTC().millis();
        long startedNs = System.nanoTime();
        HeartbeatRequest batch = collector.batch(groupId, leaderHeartbeat, nowMs, sizeLimit);
        long collectMs = elapsedMsSince(startedNs);
        stampStatePayloads(batch, leaderHeartbeat.agentId(), Map.of(
                "leader_collect_ms", collectMs,
                "group_sent_at_ms", Clock.systemUTC().millis(),
                "agent_plan_generation", leaderHeartbeatStateLong(leaderHeartbeat, "agent_plan_generation")));
        return batch;
    }

    private static long leaderHeartbeatStateLong(HeartbeatRequest heartbeat, String key) {
        return heartbeat.messages().stream()
                .filter(PulseMessage::isStateMessage)
                .map(PulseMessage::payload)
                .filter(payload -> payload != null)
                .map(payload -> payload.get(key))
                .filter(value -> value instanceof Number)
                .map(value -> ((Number) value).longValue())
                .findFirst()
                .orElse(0L);
    }

    private static void stampStatePayloads(HeartbeatRequest request, String agentId, Map<String, Object> values) {
        if (request.isBatch()) {
            request.agents().stream()
                    .filter(agent -> agentId == null || agent.agentId().equals(agentId))
                    .flatMap(agent -> agent.messages().stream())
                    .forEach(message -> stampStatePayload(message, values));
            return;
        }
        if (agentId == null || agentId.equals(request.agentId())) {
            request.messages().forEach(message -> stampStatePayload(message, values));
        }
    }

    private static void stampStatePayload(PulseMessage message, Map<String, Object> values) {
        if (!message.isStateMessage() || message.payload() == null) {
            return;
        }
        try {
            message.payload().putAll(values);
        } catch (UnsupportedOperationException ignored) {
            // Test fixtures may use immutable maps; production heartbeat payloads are mutable.
        }
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
                intValue(payload, "size_limit", memberCount(payload)),
                longValue(payload, "plan_generation", 0));
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

    private static long longValue(Map<String, Object> payload, String key, long fallback) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int memberCount(Map<String, Object> payload) {
        return Math.max(1, payload.get("members") instanceof List<?> members ? members.size() : 1);
    }

    private static List<String> coordinatorUrls() {
        String raw = System.getenv().getOrDefault("PULSE_COORDINATOR_URLS", "http://[::1]:9966");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    static final class FollowerLeaderClientCache {
        private final Duration timeout;
        private String leaderUrl = "";
        private HeartbeatClient leaderClient;

        FollowerLeaderClientCache(Duration timeout) {
            this.timeout = timeout;
        }

        HeartbeatClient clientFor(String nextLeaderUrl) {
            if (leaderClient == null || !nextLeaderUrl.equals(leaderUrl)) {
                leaderUrl = nextLeaderUrl;
                leaderClient = new HeartbeatClient(List.of(leaderUrl), timeout);
            }
            return leaderClient;
        }
    }

    static final class HeartbeatClient {
        private final List<String> coordinatorUrls;
        private final Duration timeout;
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper mapper = JsonSupport.objectMapper();
        private final int successLogEvery;
        private int nextCoordinatorIndex;
        private long successCount;
        private long lastEncodeMs;
        private long lastSendMs;

        HeartbeatClient(List<String> coordinatorUrls, Duration timeout) {
            if (coordinatorUrls.isEmpty()) {
                throw new IllegalArgumentException("PULSE_COORDINATOR_URLS must not be empty");
            }
            this.coordinatorUrls = List.copyOf(coordinatorUrls);
            this.timeout = timeout;
            this.successLogEvery = Math.max(1, intEnv("PULSE_HEARTBEAT_SUCCESS_LOG_EVERY", 12));
        }

        boolean send(String path, HeartbeatRequest heartbeat) {
            return sendForResponse(path, heartbeat) != null;
        }

        HeartbeatResponse sendForResponse(String path, HeartbeatRequest heartbeat) {
            for (int attempt = 0; attempt < coordinatorUrls.size(); attempt++) {
                String baseUrl = coordinatorUrls.get((nextCoordinatorIndex + attempt) % coordinatorUrls.size());
                try {
                    String outboundAgentId = heartbeat.isBatch() && !heartbeat.agents().isEmpty()
                            ? heartbeat.agents().get(0).agentId()
                            : null;
                    stampStatePayloads(heartbeat, outboundAgentId, Map.of(
                            "agent_encode_ms", lastEncodeMs,
                            "agent_send_ms", lastSendMs));
                    long encodeStartedNs = System.nanoTime();
                    String body = mapper.writeValueAsString(heartbeat);
                    long encodeMs = elapsedMsSince(encodeStartedNs);
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    long sendStartedNs = System.nanoTime();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    long sendMs = elapsedMsSince(sendStartedNs);
                    lastEncodeMs = encodeMs;
                    lastSendMs = sendMs;
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        nextCoordinatorIndex = (nextCoordinatorIndex + attempt + 1) % coordinatorUrls.size();
                        successCount++;
                        if (successCount == 1 || successCount % successLogEvery == 0) {
                            if (heartbeat.isBatch()) {
                                System.out.printf(
                                        "heartbeat status=ok target=%s group=%s agents=%d sampled_count=%d%n",
                                        baseUrl,
                                        heartbeat.groupId(),
                                        heartbeat.agents().size(),
                                        successCount);
                            } else {
                                System.out.printf(
                                        "heartbeat status=ok target=%s seq=%d sampled_count=%d%n",
                                        baseUrl,
                                        heartbeat.seq(),
                                        successCount);
                            }
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

    }

    private static long elapsedMsSince(long startedNs) {
        long elapsedNs = Math.max(0L, System.nanoTime() - startedNs);
        return elapsedNs == 0 ? 0 : Math.max(1L, (elapsedNs + 999_999L) / 1_000_000L);
    }

    private static int intEnv(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static final class GroupHeartbeatReceiver {
        private final HttpServer server;
        private final GroupHeartbeatCollector collector;
        private final ObjectMapper mapper = JsonSupport.objectMapper();
        private final ExecutorService executor;
        private final Map<String, List<PulseMessage>> planMessages = new ConcurrentHashMap<>();
        private volatile boolean acceptingFollowers;
        private volatile Set<String> acceptedMembers = Set.of();

        GroupHeartbeatReceiver(String bindHost, int port, GroupHeartbeatCollector collector) throws IOException {
            this.collector = collector;
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pulse-group-receiver");
                thread.setDaemon(true);
                return thread;
            });
            this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            this.server.setExecutor(executor);
            this.server.createContext("/group/heartbeat", this::handleHeartbeat);
        }

        void start() {
            server.start();
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void setAcceptingFollowers(boolean acceptingFollowers, Set<String> acceptedMembers) {
            this.acceptingFollowers = acceptingFollowers;
            this.acceptedMembers = acceptedMembers == null ? Set.of() : Set.copyOf(acceptedMembers);
        }

        private void handleHeartbeat(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "method not allowed");
                return;
            }
            if (!acceptingFollowers) {
                send(exchange, 503, "{\"ok\":false,\"error\":\"not_group_leader\"}");
                return;
            }
            try {
                HeartbeatRequest request = mapper.readValue(exchange.getRequestBody(), HeartbeatRequest.class);
                if (request.isBatch()) {
                    send(exchange, 400, "group receiver accepts single agent heartbeat only");
                    return;
                }
                if (!acceptedMembers.isEmpty() && !acceptedMembers.contains(request.agentId())) {
                    send(exchange, 409, "{\"ok\":false,\"error\":\"not_group_member\"}");
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
