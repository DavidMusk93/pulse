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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private final List<String> metricPeerUrls;
    private final ArrayDeque<SseEvent> metricEventCache = new ArrayDeque<>();
    private final AtomicLong metricEventSequence = new AtomicLong();
    private final int metricEventCacheLimit;
    private final int taskOutputPreviewChars;

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
        this(service, bindHost, port, peerForwarder, taskRouteResolver, peerUrlsFromEnvironment());
    }

    CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            PeerForwarder peerForwarder,
            BiFunction<String, URI, URI> taskRouteResolver,
            List<String> metricPeerUrls) throws IOException {
        this.service = service;
        this.peerForwarder = peerForwarder;
        this.taskRouteResolver = taskRouteResolver;
        this.metricPeerUrls = metricPeerUrls == null ? List.of() : List.copyOf(metricPeerUrls);
        this.metricEventCacheLimit = positiveInt("PULSE_METRIC_SSE_CACHE_EVENTS", 256);
        this.taskOutputPreviewChars = positiveInt("PULSE_TASK_OUTPUT_PREVIEW_CHARS", 8 * 1024);
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
                if (BinaryHeartbeatCodec.writeIfBinary(exchange, response, mapper)) {
                    return;
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
                writeJson(exchange, 200, queryMetrics(exchange));
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
            if ("POST".equals(method) && "/api/files/batch_put".equals(path)) {
                writeJson(exchange, 200, handleBatchFilePut(exchange, readBody(exchange)));
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
                    writeJson(exchange, 200, taskSnapshotView(service.taskSnapshot(agentId)));
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
                        writeJson(exchange, 200, taskSnapshotView(service.enqueueFilePut(
                                agentId,
                                stringBody(body, "file_name"),
                                stringBody(body, "content_base64"),
                                stringBody(body, "content_sha256"),
                                longBody(body, "content_bytes"),
                                stringBody(body, "target_dir"),
                                stringBody(body, "file_role"))));
                        return;
                    }
                    if ("shell_script".equals(operation)) {
                        writeJson(exchange, 200, taskSnapshotView(service.enqueueShellScript(
                                agentId,
                                stringBody(body, "file_name"),
                                stringBody(body, "content_base64"),
                                stringBody(body, "content_sha256"),
                                longBody(body, "content_bytes"),
                                parseArgs(body.get("args")))));
                        return;
                    }
                    Object taskType = body.get("task_type");
                    if (taskType == null || taskType.toString().isBlank()) {
                        throw new IllegalArgumentException("task_type is required");
                    }
                    writeJson(exchange, 200, taskSnapshotView(service.enqueueTask(agentId, taskType.toString(), parseArgs(body.get("args")))));
                    return;
                }
            }
            Optional<TaskCompletionAction> completionAction = completionAction(path);
            // All completion actions (output, output_stream, keep, pop) operate on the
            // coordinator-local completion queue populated by agent heartbeats.
            // Proxying them to another coordinator is meaningless — the target
            // coordinator does not hold this agent's completion data.
            if ("GET".equals(method) && completionAction.isPresent() && "output_stream".equals(completionAction.get().action())) {
                TaskCompletionAction action = completionAction.get();
                writeTaskCompletionOutputStream(exchange, action.agentId(), action.taskId());
                return;
            }
            if ("GET".equals(method) && completionAction.isPresent() && "output".equals(completionAction.get().action())) {
                TaskCompletionAction action = completionAction.get();
                writeTaskCompletionOutputJson(exchange, action.agentId(), action.taskId());
                return;
            }
            if ("POST".equals(method) && completionAction.isPresent()) {
                TaskCompletionAction action = completionAction.get();
                if ("keep".equals(action.action())) {
                    writeJson(exchange, 200, taskSnapshotView(service.keepCompletion(action.agentId(), action.taskId())));
                    return;
                }
                if ("pop".equals(action.action())) {
                    writeJson(exchange, 200, taskSnapshotView(service.popCompletion(action.agentId(), action.taskId())));
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

    private Map<String, Object> handleBatchFilePut(HttpExchange exchange, String rawBody) throws Exception {
        Map<?, ?> body = mapper.readValue(rawBody, Map.class);
        List<String> agentIds = parseStringList(body.get("agent_ids"));
        if (agentIds.isEmpty()) {
            throw new IllegalArgumentException("agent_ids is required");
        }
        Map<String, Object> snapshots = new LinkedHashMap<>();
        List<String> failedAgents = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, List<String>> remoteAgents = new LinkedHashMap<>();
        List<String> localAgents = new ArrayList<>();
        boolean routed = "1".equals(exchange.getRequestHeaders().getFirst("x-pulse-task-routed"));
        for (String agentId : agentIds) {
            Optional<String> owner = service.agentCoordinatorId(agentId);
            if (!routed && owner.isPresent() && !owner.get().equals(service.coordinatorId())) {
                remoteAgents.computeIfAbsent(owner.get(), ignored -> new ArrayList<>()).add(agentId);
            } else {
                localAgents.add(agentId);
            }
        }
        if (!localAgents.isEmpty()) {
            service.enqueueFilePutBatch(
                    localAgents,
                    stringBody(body, "file_name"),
                    stringBody(body, "content_base64"),
                    stringBody(body, "content_sha256"),
                    longBody(body, "content_bytes"),
                    stringBody(body, "target_dir"),
                    stringBody(body, "file_role"))
                    .forEach((agentId, snapshot) -> snapshots.put(agentId, taskSnapshotView(snapshot)));
        }
        for (Map.Entry<String, List<String>> entry : remoteAgents.entrySet()) {
            Map<String, Object> remoteResult = routeBatchFilePut(entry.getKey(), entry.getValue(), body);
            Object remoteSnapshots = remoteResult.get("snapshots");
            if (remoteSnapshots instanceof Map<?, ?> snapshotMap) {
                snapshotMap.forEach((agentId, snapshot) -> snapshots.put(String.valueOf(agentId), snapshot));
            }
            failedAgents.addAll(parseStringList(remoteResult.get("failed_agents")));
            Object remoteErrors = remoteResult.get("errors");
            if (remoteErrors instanceof Map<?, ?> errorMap) {
                errorMap.forEach((agentId, error) -> errors.put(String.valueOf(agentId), String.valueOf(error)));
            }
        }
        for (String agentId : agentIds) {
            if (!snapshots.containsKey(agentId) && !failedAgents.contains(agentId)) {
                failedAgents.add(agentId);
                errors.put(agentId, "missing snapshot");
            }
        }
        return Map.of(
                "ok", failedAgents.isEmpty(),
                "total", agentIds.size(),
                "succeeded", agentIds.size() - failedAgents.size(),
                "failed", failedAgents.size(),
                "failed_agents", failedAgents,
                "errors", errors,
                "snapshots", snapshots);
    }

    private Map<String, Object> routeBatchFilePut(String coordinatorId, List<String> agentIds, Map<?, ?> originalBody)
            throws IOException, InterruptedException {
        URI uri = taskRouteResolver.apply(coordinatorId, URI.create("/api/files/batch_put"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_ids", agentIds);
        body.put("file_name", stringBody(originalBody, "file_name"));
        body.put("content_base64", stringBody(originalBody, "content_base64"));
        body.put("content_sha256", stringBody(originalBody, "content_sha256"));
        body.put("content_bytes", longBody(originalBody, "content_bytes"));
        body.put("target_dir", stringBody(originalBody, "target_dir"));
        body.put("file_role", stringBody(originalBody, "file_role"));
        String requestBody = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(positiveLong("PULSE_TASK_ROUTE_TIMEOUT_MS", 5_000)))
                .header("x-pulse-task-routed", "1")
                .header("content-type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> response = routeClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Map<String, String> errors = new LinkedHashMap<>();
            agentIds.forEach(agentId -> errors.put(agentId, response.body()));
            return Map.of(
                    "ok", false,
                    "total", agentIds.size(),
                    "succeeded", 0,
                    "failed", agentIds.size(),
                    "failed_agents", agentIds,
                    "errors", errors,
                    "snapshots", Map.of());
        }
        return mapper.readValue(response.body(), Map.class);
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
                    String payload = mapper.writeValueAsString(taskSnapshotForHttp(exchange, agentId));
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

    private MetricQueryResult queryMetrics(HttpExchange exchange) throws Exception {
        MetricQuery query = metricQuery(exchange.getRequestURI());
        MetricQueryResult local = service.queryMetrics(query);
        if ("1".equals(exchange.getRequestHeaders().getFirst("x-pulse-metric-routed")) || metricPeerUrls.isEmpty()) {
            return local;
        }
        List<MetricQueryResult> results = new ArrayList<>();
        results.add(local);
        String rawPath = exchange.getRequestURI().getRawPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        for (String peerUrl : metricPeerUrls) {
            try {
                URI target = URI.create(peerUrl + rawPath + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery));
                HttpRequest request = HttpRequest.newBuilder(target)
                        .timeout(Duration.ofMillis(positiveLong("PULSE_METRIC_ROUTE_TIMEOUT_MS", 2_000)))
                        .header("x-pulse-metric-routed", "1")
                        .GET()
                        .build();
                HttpResponse<String> response = routeClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    results.add(mapper.readValue(response.body(), MetricQueryResult.class));
                } else {
                    System.err.printf("metric_route status=bad_response peer=%s code=%d body=%s%n",
                            peerUrl, response.statusCode(), response.body());
                }
            } catch (Exception exception) {
                System.err.printf("metric_route status=failed peer=%s error=%s%n", peerUrl, exception.getMessage());
            }
        }
        return mergeMetricResults(query, results);
    }

    private static MetricQueryResult mergeMetricResults(MetricQuery query, List<MetricQueryResult> results) {
        MetricQueryResult first = results.get(0);
        Map<Map<String, String>, List<MetricPoint>> byLabels = new java.util.LinkedHashMap<>();
        boolean truncated = false;
        long suggestedStepMs = first.suggestedStepMs();
        for (MetricQueryResult result : results) {
            truncated = truncated || result.truncated();
            suggestedStepMs = Math.max(suggestedStepMs, result.suggestedStepMs());
            for (MetricSeries series : result.series()) {
                byLabels.computeIfAbsent(series.labels(), ignored -> new ArrayList<>()).addAll(series.points());
            }
        }
        int limit = Math.min(first.seriesLimit(), query.topN() > 0 ? query.topN() : first.seriesLimit());
        List<MetricSeries> series = byLabels.entrySet().stream()
                .map(entry -> new MetricSeries(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(java.util.Comparator.comparingLong(MetricPoint::timestampMs))
                                .toList()))
                .sorted((left, right) -> stableMetricLabelKey(left).compareTo(stableMetricLabelKey(right)))
                .toList();
        if (series.size() > limit) {
            truncated = true;
            series = List.copyOf(series.subList(0, limit));
        }
        return new MetricQueryResult(
                "q-merged-" + query.startMs() + "-" + query.endMs() + "-" + Math.abs((query.metric() + query.agentIds() + query.cluster()).hashCode()),
                query.metric(),
                query.startMs(),
                query.endMs(),
                first.unit(),
                first.samplePolicy(),
                truncated,
                suggestedStepMs,
                first.seriesLimit(),
                first.pointLimit(),
                series);
    }

    private static String stableMetricLabelKey(MetricSeries series) {
        return series.labels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left + "|" + right);
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

    private void writeTaskCompletionOutputJson(HttpExchange exchange, String agentId, String taskId) throws IOException {
        Optional<TaskResult> result = service.taskCompletion(agentId, taskId);
        if (result.isEmpty()) {
            writeJson(exchange, 404, Map.of("ok", false, "error", "not_found"));
            return;
        }
        writeJson(exchange, 200, taskResultView(result.get(), true));
    }

    private void writeTaskCompletionOutputStream(HttpExchange exchange, String agentId, String taskId) throws IOException {
        Optional<TaskResult> result = service.taskCompletion(agentId, taskId);
        if (result.isEmpty()) {
            writeJson(exchange, 404, Map.of("ok", false, "error", "not_found"));
            return;
        }
        TaskResult taskResult = result.get();
        String output = taskResult.output() == null ? "" : taskResult.output();
        int chunkChars = positiveInt("PULSE_TASK_OUTPUT_SSE_CHARS", 32 * 1024);
        int offset = completionOutputOffset(exchange, output.length());
        exchange.getResponseHeaders().set("content-type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("cache-control", "no-cache");
        exchange.getResponseHeaders().set("connection", "keep-alive");
        exchange.getResponseHeaders().set("x-accel-buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream response = exchange.getResponseBody()) {
            writeSse(response, new SseEvent(String.valueOf(offset), "completion.output_start", mapper.writeValueAsString(Map.of(
                    "task_id", taskResult.taskId(),
                    "agent_id", taskResult.agentId(),
                    "output_bytes", taskResult.outputBytes(),
                    "output_chars", output.length(),
                    "output_sha256", taskResult.outputSha256(),
                    "offset", offset))));
            while (offset < output.length()) {
                int nextOffset = Math.min(output.length(), offset + chunkChars);
                writeSse(response, new SseEvent(String.valueOf(nextOffset), "completion.output_chunk", mapper.writeValueAsString(Map.of(
                        "task_id", taskResult.taskId(),
                        "agent_id", taskResult.agentId(),
                        "offset", offset,
                        "next_offset", nextOffset,
                        "chunk", output.substring(offset, nextOffset),
                        "done", nextOffset >= output.length()))));
                offset = nextOffset;
            }
            writeSse(response, new SseEvent(String.valueOf(output.length()), "completion.output_end", mapper.writeValueAsString(Map.of(
                    "task_id", taskResult.taskId(),
                    "agent_id", taskResult.agentId(),
                    "output_bytes", taskResult.outputBytes(),
                    "output_chars", output.length(),
                    "output_sha256", taskResult.outputSha256(),
                    "done", true))));
        }
    }

    private int completionOutputOffset(HttpExchange exchange, int outputLength) {
        String raw = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Last-Event-ID"))
                .orElse(queryValue(exchange.getRequestURI(), "offset"));
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(raw.trim());
            return Math.max(0, Math.min(offset, outputLength));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private TaskSnapshotView taskSnapshotView(TaskSnapshot snapshot) {
        return new TaskSnapshotView(
                snapshot.agentId(),
                snapshot.executionQueue(),
                snapshot.completionQueue().stream()
                        .map(result -> taskResultView(result, false))
                        .toList(),
                snapshot.traces(),
                snapshot.taskTypes(),
                snapshot.fileTransfers(),
                snapshot.outputStreams());
    }

    private TaskResultView taskResultView(TaskResult result, boolean fullOutput) {
        String output = result.output() == null ? "" : result.output();
        boolean inline = fullOutput || output.length() <= taskOutputPreviewChars;
        String visibleOutput = inline ? output : output.substring(0, Math.min(taskOutputPreviewChars, output.length()));
        return new TaskResultView(
                result.taskId(),
                result.traceId(),
                result.agentId(),
                result.taskType(),
                result.status(),
                result.exitCode(),
                result.startedAtMs(),
                result.finishedAtMs(),
                result.durationMs(),
                visibleOutput,
                result.outputType(),
                result.outputEncoding(),
                result.outputSha256(),
                result.outputBytes(),
                result.outputLines(),
                result.runnerError(),
                inline,
                !inline,
                output.length(),
                taskOutputStreamUrl(result.agentId(), result.taskId()));
    }

    private static String taskOutputStreamUrl(String agentId, String taskId) {
        String encodedAgentId = URLEncoder.encode(agentId, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedTaskId = URLEncoder.encode(taskId, StandardCharsets.UTF_8).replace("+", "%20");
        return "/api/agents/" + encodedAgentId + "/tasks/completions/" + encodedTaskId + "/output_stream";
    }

    private Object taskSnapshotForHttp(HttpExchange exchange, String agentId) throws IOException {
        Optional<URI> target = taskSnapshotRouteUri(exchange, agentId);
        if (target.isEmpty()) {
            return taskSnapshotView(service.taskSnapshot(agentId));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(target.get())
                    .timeout(Duration.ofMillis(positiveLong("PULSE_TASK_ROUTE_TIMEOUT_MS", 5_000)))
                    .header("x-pulse-task-routed", "1")
                    .GET()
                    .build();
            HttpResponse<String> response = routeClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return mapper.readValue(response.body(), Map.class);
            }
            System.err.printf("task_snapshot_route status=bad_response agent_id=%s target=%s code=%d body=%s%n",
                    agentId, target.get(), response.statusCode(), response.body());
            return taskSnapshotView(service.taskSnapshot(agentId));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("task snapshot route interrupted", exception);
        } catch (Exception exception) {
            System.err.printf("task_snapshot_route status=failed agent_id=%s target=%s error=%s%n",
                    agentId, target.get(), exception.getMessage());
            return taskSnapshotView(service.taskSnapshot(agentId));
        }
    }

    private Optional<URI> taskSnapshotRouteUri(HttpExchange exchange, String agentId) {
        if ("1".equals(exchange.getRequestHeaders().getFirst("x-pulse-task-routed"))) {
            return Optional.empty();
        }
        Optional<String> owner = service.agentCoordinatorId(agentId);
        if (owner.isEmpty() || owner.get().equals(service.coordinatorId())) {
            return Optional.empty();
        }
        String encodedAgentId = URLEncoder.encode(agentId, StandardCharsets.UTF_8).replace("+", "%20");
        URI snapshotUri = URI.create("/api/agents/" + encodedAgentId + "/tasks");
        return Optional.of(taskRouteResolver.apply(owner.get(), snapshotUri));
    }

    private boolean proxyTaskRequestIfNeeded(
            HttpExchange exchange,
            String agentId,
            String body,
            boolean streamResponse) throws IOException, InterruptedException {
        return proxyTaskRequestIfNeeded(
                exchange,
                agentId,
                body,
                streamResponse,
                positiveLong("PULSE_TASK_ROUTE_TIMEOUT_MS", 5_000));
    }

    private boolean proxyTaskRequestIfNeeded(
            HttpExchange exchange,
            String agentId,
            String body,
            boolean streamResponse,
            long routeTimeoutMs) throws IOException, InterruptedException {
        Optional<URI> target = taskRouteUri(exchange, agentId);
        if (target.isEmpty()) {
            return false;
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target.get())
                .timeout(Duration.ofMillis(routeTimeoutMs))
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
        return "http://" + routeHost(coordinatorId) + ":" + port;
    }

    static String routeHost(String coordinatorId) {
        String value = coordinatorId == null ? "" : coordinatorId.trim();
        if (value.contains(":") && !(value.startsWith("[") && value.endsWith("]"))) {
            return "[" + value + "]";
        }
        return value;
    }

    private static List<String> peerUrlsFromEnvironment() {
        String rawPeers = System.getenv().getOrDefault("PULSE_COORDINATOR_PEERS", "");
        return Arrays.stream(rawPeers.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
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

    private static List<String> parseStringList(Object rawValues) {
        if (rawValues == null) {
            return List.of();
        }
        if (rawValues instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }
        String value = rawValues.toString();
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .distinct()
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

    private record TaskSnapshotView(
            String agentId,
            List<RemoteTask> executionQueue,
            List<TaskResultView> completionQueue,
            List<TaskTraceLogEntry> traces,
            List<String> taskTypes,
            List<FileTransferStatus> fileTransfers,
            List<TaskStreamSnapshot> outputStreams) {}

    private record TaskResultView(
            String taskId,
            String traceId,
            String agentId,
            String taskType,
            String status,
            Integer exitCode,
            long startedAtMs,
            long finishedAtMs,
            long durationMs,
            String output,
            String outputType,
            String outputEncoding,
            String outputSha256,
            long outputBytes,
            long outputLines,
            String runnerError,
            boolean outputInline,
            boolean outputPreview,
            int outputChars,
            String outputStreamUrl) {}

    private record TaskCompletionAction(String agentId, String taskId, String action) {}

    private record SseEvent(String id, String event, String data) {}

    interface PeerForwarder {
        void forward(HeartbeatRequest request);

        static PeerForwarder noop() {
            return request -> {};
        }

        static PeerForwarder fromEnvironment(String coordinatorId) {
            List<String> peers = peerUrlsFromEnvironment();
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
