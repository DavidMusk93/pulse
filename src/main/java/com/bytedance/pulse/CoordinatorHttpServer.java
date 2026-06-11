package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

public class CoordinatorHttpServer {
    private final CoordinatorService service;
    private final HttpServer server;
    private final ObjectMapper mapper = JsonSupport.objectMapper();
    private final PeerForwarder peerForwarder;
    private final HttpClient routeClient = HttpClient.newHttpClient();
    private final BiFunction<String, URI, URI> taskRouteResolver;
    private final ArrayDeque<SseEvent> metricEventCache = new ArrayDeque<>();
    private final AtomicLong metricEventSequence = new AtomicLong();
    private final int metricEventCacheLimit;

    public CoordinatorHttpServer(CoordinatorService service, int port) throws IOException {
        this(service, "127.0.0.1", port);
    }

    public CoordinatorHttpServer(CoordinatorService service, String bindHost, int port) throws IOException {
        this(service, bindHost, port, PeerForwarder.fromEnvironment(service.coordinatorId()));
    }

    CoordinatorHttpServer(CoordinatorService service, String bindHost, int port, PeerForwarder peerForwarder) throws IOException {
        this(service, bindHost, port, peerForwarder, CoordinatorHttpServer::defaultTaskRouteUri);
    }

    CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            PeerForwarder peerForwarder,
            BiFunction<String, URI, URI> taskRouteResolver) throws IOException {
        this.service = service;
        this.peerForwarder = peerForwarder;
        this.taskRouteResolver = taskRouteResolver;
        this.metricEventCacheLimit = positiveInt("PULSE_METRIC_SSE_CACHE_EVENTS", 256);
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), httpBacklog());
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
            if ("GET".equals(method) && "/api/metrics/catalog".equals(path)) {
                writeJson(exchange, 200, service.metricCatalog());
                return;
            }
            if ("GET".equals(method) && "/api/metrics/query_range".equals(path)) {
                writeJson(exchange, 200, service.queryMetrics(metricQuery(exchange.getRequestURI())));
                return;
            }
            if ("GET".equals(method) && "/api/metrics/events".equals(path)) {
                writeJson(exchange, 200, service.queryMetricEvents(metricEventQuery(exchange.getRequestURI())));
                return;
            }
            if ("GET".equals(method) && "/api/metrics/storage".equals(path)) {
                writeJson(exchange, 200, service.metricStorageHealth());
                return;
            }
            if ("GET".equals(method) && "/api/metrics/stream".equals(path)) {
                try {
                    writeMetricStream(exchange);
                } catch (IOException ignored) {
                    // Client disconnected or proxy closed the SSE stream.
                }
                return;
            }
            if ("GET".equals(method) && path.startsWith("/assets/")) {
                writeStaticAsset(exchange, path);
                return;
            }
            if ("GET".equals(method) && "/api/tasks/stream".equals(path)) {
                List<String> agentIds = queryList(exchange.getRequestURI(), "agents");
                try {
                    writeTaskSnapshotStream(exchange, agentIds);
                } catch (IOException ignored) {
                    // Client disconnected or proxy closed the SSE stream.
                }
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/agents/") && path.endsWith("/tasks/stream")) {
                String agentId = path.substring("/api/agents/".length(), path.length() - "/tasks/stream".length());
                if (proxyTaskRequestIfNeeded(exchange, agentId, null, true)) {
                    return;
                }
                try {
                    writeTaskSnapshotStream(exchange, agentId);
                } catch (IOException ignored) {
                    // Client disconnected or proxy closed the SSE stream.
                }
                return;
            }
            if (path.startsWith("/api/agents/") && path.endsWith("/tasks")) {
                String agentId = path.substring("/api/agents/".length(), path.length() - "/tasks".length());
                if ("GET".equals(method)) {
                    if (proxyTaskRequestIfNeeded(exchange, agentId, null, false)) {
                        return;
                    }
                    writeJson(exchange, 200, service.taskSnapshot(agentId));
                    return;
                }
                if ("POST".equals(method)) {
                    String rawBody = readBody(exchange);
                    if (proxyTaskRequestIfNeeded(exchange, agentId, rawBody, false)) {
                        return;
                    }
                    Map<?, ?> body = mapper.readValue(rawBody, Map.class);
                    String operation = stringBody(body, "operation");
                    if ("file_put".equals(operation)) {
                        writeJson(exchange, 200, service.enqueueFilePut(
                                agentId,
                                stringBody(body, "file_name"),
                                stringBody(body, "content_base64"),
                                stringBody(body, "content_sha256"),
                                longBody(body, "content_bytes"),
                                stringBody(body, "target_dir"),
                                stringBody(body, "file_role")));
                        return;
                    }
                    if ("shell_script".equals(operation)) {
                        writeJson(exchange, 200, service.enqueueShellScript(
                                agentId,
                                stringBody(body, "file_name"),
                                stringBody(body, "content_base64"),
                                stringBody(body, "content_sha256"),
                                longBody(body, "content_bytes"),
                                parseArgs(body.get("args"))));
                        return;
                    }
                    Object taskType = body.get("task_type");
                    if (taskType == null || taskType.toString().isBlank()) {
                        throw new IllegalArgumentException("task_type is required");
                    }
                    writeJson(exchange, 200, service.enqueueTask(agentId, taskType.toString(), parseArgs(body.get("args"))));
                    return;
                }
            }
            Optional<TaskCompletionAction> completionAction = completionAction(path);
            if ("POST".equals(method) && completionAction.isPresent()) {
                TaskCompletionAction action = completionAction.get();
                if (proxyTaskRequestIfNeeded(exchange, action.agentId(), readBody(exchange), false)) {
                    return;
                }
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

    private void writeTaskSnapshotStream(HttpExchange exchange, String agentId) throws IOException {
        writeTaskSnapshotStream(exchange, List.of(agentId));
    }

    private void writeTaskSnapshotStream(HttpExchange exchange, List<String> agentIds) throws IOException {
        if (agentIds.isEmpty()) {
            throw new IllegalArgumentException("agents query is required");
        }
        exchange.getResponseHeaders().set("content-type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("cache-control", "no-cache");
        exchange.getResponseHeaders().set("connection", "keep-alive");
        exchange.getResponseHeaders().set("x-accel-buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        long intervalMs = positiveLong("PULSE_TASK_SSE_INTERVAL_MS", 1_000);
        long maxStreamMs = positiveLong("PULSE_TASK_SSE_MAX_MS", 15 * 60_000);
        long deadline = System.currentTimeMillis() + maxStreamMs;
        int sequence = 0;
        int idleTicks = 0;
        Map<String, String> lastPayloads = new java.util.HashMap<>();
        try (OutputStream output = exchange.getResponseBody()) {
            writeSse(output, "hello", sequence++, mapper.writeValueAsString(Map.of(
                    "agent_ids", agentIds,
                    "agent_count", agentIds.size(),
                    "server_time_ms", System.currentTimeMillis())));
            while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < deadline) {
                boolean wroteSnapshot = false;
                for (String agentId : agentIds) {
                    String payload = mapper.writeValueAsString(service.taskSnapshot(agentId));
                    if (!payload.equals(lastPayloads.get(agentId))) {
                        writeSse(output, "task.snapshot", sequence++, payload);
                        lastPayloads.put(agentId, payload);
                        wroteSnapshot = true;
                    }
                }
                if (wroteSnapshot) {
                    idleTicks = 0;
                } else if (++idleTicks >= 15) {
                    writeSse(output, "ping", sequence++, mapper.writeValueAsString(Map.of(
                            "server_time_ms", System.currentTimeMillis())));
                    idleTicks = 0;
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void writeMetricStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("content-type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("cache-control", "no-cache");
        exchange.getResponseHeaders().set("connection", "keep-alive");
        exchange.getResponseHeaders().set("x-accel-buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        boolean once = "true".equalsIgnoreCase(queryValue(exchange.getRequestURI(), "once"));
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1, longQuery(exchange.getRequestURI(), "interval_ms", positiveLong("PULSE_METRIC_SSE_INTERVAL_MS", 5_000)));
        long maxStreamMs = Math.max(1, longQuery(exchange.getRequestURI(), "max_ms", positiveLong("PULSE_METRIC_SSE_MAX_MS", 15 * 60_000)));
        String lastEventId = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Last-Event-ID"))
                .orElse(queryValue(exchange.getRequestURI(), "last_event_id"));
        boolean resumed = lastEventId != null && !lastEventId.isBlank();
        long compensateFromMs = resumed ? Math.max(lastEventTimestamp(lastEventId), now - 300_000) : now - 30_000;
        List<SseEvent> replayEvents = resumed ? metricEventsAfter(lastEventId) : List.of();
        try (OutputStream output = exchange.getResponseBody()) {
            writeSse(output, new SseEvent(nextMetricEventId(), "hello", mapper.writeValueAsString(Map.of(
                    "coordinator_id", service.coordinatorId(),
                    "server_time_ms", now,
                    "compensate_from_ms", compensateFromMs,
                    "resumed", resumed,
                    "last_event_id", lastEventId == null ? "" : lastEventId,
                    "event_cache_supported", true,
                    "replayed_events", replayEvents.size(),
                    "replay_limit", metricEventCacheLimit,
                    "stream_interval_ms", intervalMs,
                    "stream_max_ms", maxStreamMs))));
            for (SseEvent event : replayEvents) {
                writeSse(output, event);
            }
            writeCachedMetricEvent(output, "storage.health", mapper.writeValueAsString(service.metricStorageHealth()));
            writeCachedMetricEvent(output, "metric.invalidate", mapper.writeValueAsString(Map.of(
                    "from", compensateFromMs,
                    "to", now,
                    "metrics", List.of("heartbeat.arrival_gap_ms", "agent.thread_count", "group.submitted_agent_count"))));
            if (!once) {
                long deadline = System.currentTimeMillis() + maxStreamMs;
                while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < deadline) {
                    sleepQuietly(intervalMs);
                    long tick = System.currentTimeMillis();
                    if (tick >= deadline) {
                        break;
                    }
                    writeCachedMetricEvent(output, "metric.invalidate", mapper.writeValueAsString(Map.of(
                            "from", tick - Math.max(intervalMs, 30_000),
                            "to", tick,
                            "metrics", List.of("heartbeat.arrival_gap_ms", "agent.thread_count", "group.submitted_agent_count"))));
                    writeCachedMetricEvent(output, "ping", mapper.writeValueAsString(Map.of("server_time_ms", tick)));
                }
            }
        }
    }

    private static void sleepQuietly(long intervalMs) {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeCachedMetricEvent(OutputStream output, String event, String data) throws IOException {
        SseEvent sseEvent = new SseEvent(nextMetricEventId(), event, data);
        cacheMetricEvent(sseEvent);
        writeSse(output, sseEvent);
    }

    private String nextMetricEventId() {
        return System.currentTimeMillis() + "-" + metricEventSequence.incrementAndGet();
    }

    private void cacheMetricEvent(SseEvent event) {
        synchronized (metricEventCache) {
            metricEventCache.addLast(event);
            while (metricEventCache.size() > metricEventCacheLimit) {
                metricEventCache.removeFirst();
            }
        }
    }

    private List<SseEvent> metricEventsAfter(String lastEventId) {
        synchronized (metricEventCache) {
            List<SseEvent> events = new ArrayList<>();
            for (SseEvent event : metricEventCache) {
                if (compareEventId(event.id(), lastEventId) > 0) {
                    events.add(event);
                }
            }
            return List.copyOf(events);
        }
    }

    private static long lastEventTimestamp(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        int separator = lastEventId.indexOf('-');
        String timestamp = separator < 0 ? lastEventId : lastEventId.substring(0, separator);
        try {
            return Long.parseLong(timestamp);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int compareEventId(String left, String right) {
        long leftTimestamp = lastEventTimestamp(left);
        long rightTimestamp = lastEventTimestamp(right);
        if (leftTimestamp != rightTimestamp) {
            return Long.compare(leftTimestamp, rightTimestamp);
        }
        return Long.compare(eventSequence(left), eventSequence(right));
    }

    private static long eventSequence(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return 0;
        }
        int separator = eventId.indexOf('-');
        if (separator < 0 || separator == eventId.length() - 1) {
            return 0;
        }
        try {
            return Long.parseLong(eventId.substring(separator + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void writeSse(OutputStream output, String event, int sequence, String data) throws IOException {
        writeSse(output, new SseEvent(System.currentTimeMillis() + "-" + sequence, event, data));
    }

    private static void writeSse(OutputStream output, SseEvent event) throws IOException {
        String payload = "id: " + event.id() + "\n"
                + "retry: 3000\n"
                + "event: " + event.event() + "\n"
                + "data: " + event.data().replace("\n", "\\n") + "\n\n";
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static List<String> queryList(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        String prefix = key + "=";
        for (String part : query.split("&")) {
            if (!part.startsWith(prefix)) {
                continue;
            }
            String decoded = URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
            Arrays.stream(decoded.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private static MetricQuery metricQuery(URI uri) {
        return new MetricQuery(
                requiredQuery(uri, "metric"),
                queryList(uri, "agents"),
                longQuery(uri, "start_ms", longQuery(uri, "from", 0)),
                longQuery(uri, "end_ms", longQuery(uri, "to", Long.MAX_VALUE)),
                longQuery(uri, "step_ms", longQuery(uri, "step", 10_000)),
                (int) Math.min(LocalMetricStorage.MAX_SERIES_LIMIT,
                        longQuery(uri, "series_limit", LocalMetricStorage.DEFAULT_SERIES_LIMIT)),
                (int) Math.min(LocalMetricStorage.MAX_POINT_LIMIT,
                        longQuery(uri, "point_limit", LocalMetricStorage.MAX_POINT_LIMIT)),
                (int) Math.min(LocalMetricStorage.MAX_SERIES_LIMIT,
                        longQuery(uri, "top_n", longQuery(uri, "topN", 0))),
                queryValue(uri, "cluster"));
    }

    private static MetricEventQuery metricEventQuery(URI uri) {
        return new MetricEventQuery(
                longQuery(uri, "start_ms", longQuery(uri, "from", 0)),
                longQuery(uri, "end_ms", longQuery(uri, "to", Long.MAX_VALUE)),
                queryValue(uri, "agent"),
                queryList(uri, "severity"),
                (int) Math.min(Integer.MAX_VALUE, longQuery(uri, "limit", 500)));
    }

    private static String requiredQuery(URI uri, String key) {
        String value = queryValue(uri, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static long longQuery(URI uri, String key, long fallback) {
        String value = queryValue(uri, key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private static String queryValue(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        String prefix = key + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix)) {
                return URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return mapper.readValue(body, type);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean proxyTaskRequestIfNeeded(
            HttpExchange exchange,
            String agentId,
            String body,
            boolean streamResponse) throws IOException, InterruptedException {
        Optional<URI> target = taskRouteUri(exchange, agentId);
        if (target.isEmpty()) {
            return false;
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.get())
                .timeout(Duration.ofMillis(positiveLong("PULSE_TASK_ROUTE_TIMEOUT_MS", 5_000)))
                .header("x-pulse-task-routed", "1")
                .method(exchange.getRequestMethod(), publisher);
        if (body != null) {
            builder.header("content-type", "application/json; charset=utf-8");
        }
        if (streamResponse) {
            HttpResponse<InputStream> response = routeClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.headers().firstValue("content-type")
                    .ifPresent(value -> exchange.getResponseHeaders().set("content-type", value));
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (InputStream input = response.body(); OutputStream output = exchange.getResponseBody()) {
                input.transferTo(output);
            }
            return true;
        }
        HttpResponse<String> response = routeClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("content-type")
                .ifPresent(value -> exchange.getResponseHeaders().set("content-type", value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.statusCode(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        return true;
    }

    private Optional<URI> taskRouteUri(HttpExchange exchange, String agentId) {
        if ("1".equals(exchange.getRequestHeaders().getFirst("x-pulse-task-routed"))) {
            return Optional.empty();
        }
        Optional<String> owner = service.agentCoordinatorId(agentId);
        if (owner.isEmpty() || owner.get().equals(service.coordinatorId())) {
            return Optional.empty();
        }
        return Optional.of(taskRouteResolver.apply(owner.get(), exchange.getRequestURI()));
    }

    private static URI defaultTaskRouteUri(String coordinatorId, URI requestUri) {
        String base = taskRouteBase(coordinatorId);
        String rawPath = requestUri.getRawPath();
        String rawQuery = requestUri.getRawQuery();
        return URI.create(base + rawPath + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery));
    }

    private static String taskRouteBase(String coordinatorId) {
        String template = System.getenv().getOrDefault("PULSE_TASK_ROUTE_TEMPLATE", "");
        if (!template.isBlank()) {
            return String.format(template, coordinatorId);
        }
        long port = positiveLong("PULSE_PORT", 9966);
        return "http://" + coordinatorId + ":" + port;
    }

    private static List<String> parseArgs(Object rawArgs) {
        if (rawArgs == null) {
            return null;
        }
        if (rawArgs instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .toList();
        }
        String value = rawArgs.toString();
        if (value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.trim().split("\\s+"))
                .filter(part -> !part.isBlank())
                .toList();
    }

    private static String stringBody(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString();
    }

    private static long longBody(Map<?, ?> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || value.toString().isBlank() ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a number");
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

    private static int httpBacklog() {
        return positiveInt("PULSE_HTTP_BACKLOG", 512);
    }

    private static int positiveInt(String key, int fallback) {
        try {
            int value = Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long positiveLong(String key, long fallback) {
        try {
            long value = Long.parseLong(System.getenv().getOrDefault(key, String.valueOf(fallback)));
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

    private record SseEvent(String id, String event, String data) {}

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
