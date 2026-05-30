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
import java.util.concurrent.Executors;

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
        this.server.setExecutor(Executors.newCachedThreadPool());
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
