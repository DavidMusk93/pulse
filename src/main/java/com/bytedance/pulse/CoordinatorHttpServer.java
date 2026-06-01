package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CoordinatorHttpServer {
    private final CoordinatorService service;
    private final HttpServer server;
    private final ObjectMapper mapper = JsonSupport.objectMapper();
    private final PeerForwarder peerForwarder;

    public CoordinatorHttpServer(CoordinatorService service, int port) throws IOException {
        this(service, "127.0.0.1", port);
    }

    public CoordinatorHttpServer(CoordinatorService service, String bindHost, int port) throws IOException {
        this(service, bindHost, port, PeerForwarder.fromEnvironment(service.coordinatorId()));
    }

    CoordinatorHttpServer(CoordinatorService service, String bindHost, int port, PeerForwarder peerForwarder) throws IOException {
        this.service = service;
        this.peerForwarder = peerForwarder;
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(httpExecutor());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/heartbeat".equals(path)) {
                HeartbeatRequest request = readJson(exchange, HeartbeatRequest.class);
                HeartbeatResponse response = service.handleHeartbeat(request);
                try {
                    peerForwarder.forward(request);
                } catch (Exception exception) {
                    System.err.printf("heartbeat_fwd status=unexpected_error error=%s%n", exception.getMessage());
                }
                writeJson(exchange, 200, response);
                return;
            }
            if ("POST".equals(method) && "/heartbeat_fwd".equals(path)) {
                HeartbeatForwardRequest request = readJson(exchange, HeartbeatForwardRequest.class);
                writeJson(exchange, 200, service.handleForward(request));
                return;
            }
            if ("GET".equals(method) && "/api/hosts".equals(path)) {
                writeJson(exchange, 200, service.hosts());
                return;
            }
            if ("GET".equals(method) && path.startsWith("/assets/")) {
                writeStaticAsset(exchange, path);
                return;
            }
            if (path.startsWith("/api/agents/") && path.endsWith("/tasks")) {
                String agentId = path.substring("/api/agents/".length(), path.length() - "/tasks".length());
                if ("GET".equals(method)) {
                    writeJson(exchange, 200, service.taskSnapshot(agentId));
                    return;
                }
                if ("POST".equals(method)) {
                    Map<?, ?> body = readJson(exchange, Map.class);
                    Object taskType = body.get("task_type");
                    if (taskType == null || taskType.toString().isBlank()) {
                        throw new IllegalArgumentException("task_type is required");
                    }
                    writeJson(exchange, 200, service.enqueueTask(agentId, taskType.toString()));
                    return;
                }
            }
            Optional<TaskCompletionAction> completionAction = completionAction(path);
            if ("POST".equals(method) && completionAction.isPresent()) {
                TaskCompletionAction action = completionAction.get();
                if ("keep".equals(action.action())) {
                    writeJson(exchange, 200, service.keepCompletion(action.agentId(), action.taskId()));
                    return;
                }
                if ("pop".equals(action.action())) {
                    writeJson(exchange, 200, service.popCompletion(action.agentId(), action.taskId()));
                    return;
                }
            }
            if ("GET".equals(method) && ("/".equals(path) || "/hosts".equals(path))) {
                writeHtml(exchange, 200, HostTilesPage.render(service.coordinatorId(), service.hosts()));
                return;
            }
            writeJson(exchange, 404, Map.of("ok", false, "error", "not_found"));
        } catch (IllegalArgumentException exception) {
            writeJson(exchange, 400, Map.of("ok", false, "error", exception.getMessage()));
        } catch (Exception exception) {
            writeJson(exchange, 500, Map.of("ok", false, "error", "internal_error"));
        } finally {
            exchange.close();
        }
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return mapper.readValue(body, type);
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void writeHtml(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void writeStaticAsset(HttpExchange exchange, String path) throws IOException {
        String fileName = path.substring("/assets/".length());
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("..")) {
            writeJson(exchange, 404, Map.of("ok", false, "error", "not_found"));
            return;
        }
        String resourcePath = "static/" + fileName;
        try (InputStream resource = CoordinatorHttpServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (resource == null) {
                writeJson(exchange, 404, Map.of("ok", false, "error", "not_found"));
                return;
            }
            byte[] bytes = resource.readAllBytes();
            exchange.getResponseHeaders().set("content-type", contentType(fileName));
            exchange.getResponseHeaders().set("cache-control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private static String contentType(String fileName) {
        if (fileName.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (fileName.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static ThreadPoolExecutor httpExecutor() {
        int maxThreads = positiveInt("PULSE_HTTP_MAX_THREADS", Math.max(32, Runtime.getRuntime().availableProcessors() * 4));
        int coreThreads = Math.min(8, maxThreads);
        int queueSize = positiveInt("PULSE_HTTP_QUEUE_SIZE", 2_048);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static int positiveInt(String key, int fallback) {
        try {
            int value = Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Optional<TaskCompletionAction> completionAction(String path) {
        String prefix = "/api/agents/";
        String marker = "/tasks/completions/";
        if (!path.startsWith(prefix) || !path.contains(marker)) {
            return Optional.empty();
        }
        int markerIndex = path.indexOf(marker);
        String agentId = path.substring(prefix.length(), markerIndex);
        String rest = path.substring(markerIndex + marker.length());
        int separator = rest.lastIndexOf('/');
        if (agentId.isBlank() || separator <= 0 || separator >= rest.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new TaskCompletionAction(agentId, rest.substring(0, separator), rest.substring(separator + 1)));
    }

    private record TaskCompletionAction(String agentId, String taskId, String action) {}

    interface PeerForwarder {
        void forward(HeartbeatRequest request);

        static PeerForwarder noop() {
            return request -> {};
        }

        static PeerForwarder fromEnvironment(String coordinatorId) {
            String rawPeers = System.getenv().getOrDefault("PULSE_COORDINATOR_PEERS", "");
            List<String> peers = Arrays.stream(rawPeers.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
            if (peers.isEmpty()) {
                return noop();
            }
            Duration timeout = Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("PULSE_PEER_TIMEOUT_MS", "1000")));
            return new HttpPeerForwarder(coordinatorId, peers, timeout);
        }
    }

    static final class HttpPeerForwarder implements PeerForwarder {
        private final String coordinatorId;
        private final List<String> peerUrls;
        private final Duration timeout;
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final ObjectMapper mapper = JsonSupport.objectMapper();

        HttpPeerForwarder(String coordinatorId, List<String> peerUrls, Duration timeout) {
            this.coordinatorId = coordinatorId;
            this.peerUrls = List.copyOf(peerUrls);
            this.timeout = timeout;
        }

        @Override
        public void forward(HeartbeatRequest request) {
            HeartbeatForwardRequest forwardRequest = toForwardRequest(request);
            if (forwardRequest.states().isEmpty()) {
                return;
            }
            for (String peerUrl : peerUrls) {
                HttpRequest httpRequest;
                try {
                    httpRequest = HttpRequest.newBuilder(URI.create(peerUrl + "/heartbeat_fwd"))
                            .timeout(timeout)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(forwardRequest)))
                            .build();
                } catch (Exception exception) {
                    System.err.printf("heartbeat_fwd status=build_failed peer=%s error=%s%n", peerUrl, exception.getMessage());
                    continue;
                }
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                System.err.printf(
                                        "heartbeat_fwd status=bad_response peer=%s code=%d body=%s%n",
                                        peerUrl,
                                        response.statusCode(),
                                        response.body());
                            }
                        })
                        .exceptionally(exception -> {
                            System.err.printf(
                                    "heartbeat_fwd status=failed peer=%s error=%s%n",
                                    peerUrl,
                                    exception.getMessage());
                            return null;
                        });
            }
        }

        HeartbeatForwardRequest toForwardRequest(HeartbeatRequest request) {
            if (request.isBatch()) {
                String source = request.groupId() == null || request.groupId().isBlank() ? "group" : request.groupId();
                return new HeartbeatForwardRequest(
                        coordinatorId,
                        request.agents().stream()
                                .map(agent -> toForwardState(agent, source))
                                .filter(state -> !state.messages().isEmpty())
                                .toList());
            }
            AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
            ForwardState state = toForwardState(heartbeat, "direct");
            return new HeartbeatForwardRequest(
                    coordinatorId,
                    state.messages().isEmpty() ? List.of() : List.of(state));
        }

        private ForwardState toForwardState(AgentHeartbeat heartbeat, String source) {
            return new ForwardState(
                    heartbeat.agentId(),
                    heartbeat.epoch(),
                    heartbeat.seq(),
                    heartbeat.ttlMs(),
                    System.currentTimeMillis(),
                    source,
                    heartbeat.messages().stream()
                            .filter(PulseMessage::isStateMessage)
                            .toList());
        }
    }
}
