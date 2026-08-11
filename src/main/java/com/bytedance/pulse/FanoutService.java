package com.bytedance.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class FanoutService implements AutoCloseable {
    static final long MIN_INTERVAL_MS = 5 * 60_000L;
    static final long DEFAULT_INTERVAL_MS = 15 * 60_000L;

    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();

    private final Path configPath;
    private final Clock clock;
    private final Supplier<List<HostEvent>> activeEvents;
    private final LarkClient larkClient;
    private final Map<String, FanoutSource> sources = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;

    FanoutService(
            Path configPath,
            Clock clock,
            Supplier<List<HostEvent>> activeEvents,
            LarkClient larkClient,
            boolean startScheduler) throws Exception {
        this.configPath = configPath.toAbsolutePath();
        this.clock = clock;
        this.activeEvents = activeEvents;
        this.larkClient = larkClient;
        load();
        if (startScheduler) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pulse-event-fanout");
                thread.setDaemon(true);
                return thread;
            });
            // Allow heartbeat state to rebuild before evaluating a persisted non-zero active count.
            scheduler.scheduleWithFixedDelay(this::dispatchQuietly, 30, 5, TimeUnit.SECONDS);
        } else {
            scheduler = null;
        }
    }

    synchronized List<FanoutSource> sources() {
        return List.copyOf(sources.values());
    }

    FanoutSource register(FanoutRegistration registration) throws Exception {
        if (registration == null || !"lark_chat".equals(registration.type())) {
            throw new IllegalArgumentException("fanout source type must be lark_chat");
        }
        String query = registration.targetQuery() == null ? "" : registration.targetQuery().trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("target_query is required");
        }
        long requestedIntervalMs = registration.intervalMs() > 0 ? registration.intervalMs() : DEFAULT_INTERVAL_MS;
        long intervalMs = Math.max(MIN_INTERVAL_MS, requestedIntervalMs);
        LarkTarget target = larkClient.resolveChat(query);
        long now = clock.millis();
        FanoutSource source = new FanoutSource(
                "fanout-" + UUID.randomUUID(),
                "lark_chat",
                target.name(),
                query,
                target.chatId(),
                intervalMs,
                true,
                now,
                0,
                0,
                "",
                0);
        synchronized (this) {
            sources.put(source.sourceId(), source);
            persist();
        }
        return source;
    }

    synchronized boolean remove(String sourceId) throws Exception {
        boolean removed = sources.remove(sourceId) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    synchronized void dispatchDue() throws Exception {
        long now = clock.millis();
        List<HostEvent> active = activeEvents.get();
        boolean changed = false;
        for (FanoutSource source : List.copyOf(sources.values())) {
            if (!source.enabled() || now - source.lastAttemptAtMs() < source.intervalMs()) {
                continue;
            }
            if (active.isEmpty() && source.lastActiveCount() == 0) {
                continue;
            }
            String message = digest(active, now);
            String idempotencyKey = idempotencyKey(source, now);
            FanoutSource attempted = source.withAttempt(now);
            try {
                larkClient.send(source.targetId(), message, idempotencyKey);
                attempted = attempted.withSuccess(now, active.size());
            } catch (Exception exception) {
                attempted = attempted.withFailure(errorMessage(exception));
            }
            sources.put(source.sourceId(), attempted);
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    private void dispatchQuietly() {
        try {
            dispatchDue();
        } catch (Exception exception) {
            System.err.printf("event_fanout status=failed error=%s%n", errorMessage(exception));
        }
    }

    private static String digest(List<HostEvent> active, long now) {
        if (active.isEmpty()) {
            return "[Pulse] 磁盘 IO 告警已全部恢复\n时间: " + now;
        }
        StringBuilder message = new StringBuilder()
                .append("[Pulse] 磁盘 IO 持续高利用率\n")
                .append("活动事件: ").append(active.size()).append("\n")
                .append("阈值: >95%, 持续 >10s\n");
        active.stream().limit(20).forEach(event -> message
                .append("- ")
                .append(event.details().getOrDefault("ip", event.agentId()))
                .append(" / ")
                .append(event.details().getOrDefault("device", "unknown"))
                .append(": ")
                .append(event.details().getOrDefault("io_util_pct", 0))
                .append("%, 持续 ")
                .append(event.details().getOrDefault("saturated_for_ms", 0))
                .append("ms\n"));
        if (active.size() > 20) {
            message.append("- 其余 ").append(active.size() - 20).append(" 个事件已折叠\n");
        }
        message.append("时间: ").append(now);
        return message.toString();
    }

    private static String idempotencyKey(FanoutSource source, long now) {
        long bucket = now / source.intervalMs();
        return "pulse-" + TaskOutputCodec.sha256(source.sourceId() + ":" + bucket).substring(0, 32);
    }

    private synchronized void load() throws Exception {
        if (!Files.isRegularFile(configPath)) {
            return;
        }
        FanoutConfig config = MAPPER.readValue(Files.readString(configPath), FanoutConfig.class);
        if (config.sources() != null) {
            config.sources().forEach(source -> sources.put(source.sourceId(), source));
        }
    }

    private synchronized void persist() throws Exception {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.writeString(temporary, MAPPER.writeValueAsString(new FanoutConfig(List.copyOf(sources.values()))));
        try {
            Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static String errorMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    interface LarkClient {
        LarkTarget resolveChat(String query) throws Exception;

        void send(String chatId, String message, String idempotencyKey) throws Exception;
    }

    static final class BytedCliLarkClient implements LarkClient {
        private final String executable;
        private final String identity;
        private final Duration timeout;

        BytedCliLarkClient(String executable, String identity, Duration timeout) {
            this.executable = executable;
            this.identity = identity;
            this.timeout = timeout;
        }

        @Override
        public LarkTarget resolveChat(String query) throws Exception {
            JsonNode output = run(List.of(
                    executable, "--json", "lark", "im", "chat-search",
                    "--as", identity, "--query", query, "--page-all"));
            List<LarkTarget> targets = new ArrayList<>();
            collectTargets(output, targets);
            List<LarkTarget> exact = targets.stream().filter(target -> query.equals(target.name())).distinct().toList();
            if (exact.size() == 1) {
                return exact.get(0);
            }
            List<LarkTarget> distinct = targets.stream().distinct().toList();
            if (distinct.size() == 1) {
                return distinct.get(0);
            }
            throw new IllegalArgumentException(
                    distinct.isEmpty()
                            ? "bytedcli did not find a Lark chat for: " + query
                            : "Lark chat query is ambiguous: " + query);
        }

        @Override
        public void send(String chatId, String message, String idempotencyKey) throws Exception {
            run(List.of(
                    executable, "--json", "lark", "im", "messages-send",
                    "--as", identity, "--chat-id", chatId,
                    "--text", message, "--idempotency-key", idempotencyKey));
        }

        private JsonNode run(List<String> command) throws Exception {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IllegalStateException("bytedcli timed out");
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("bytedcli failed: " + output.trim());
            }
            return MAPPER.readTree(output);
        }

        private static void collectTargets(JsonNode node, List<LarkTarget> targets) {
            if (node == null) {
                return;
            }
            if (node.isObject()) {
                String chatId = text(node, "chat_id", "chatId");
                String name = text(node, "name", "chat_name", "chatName");
                if (chatId.startsWith("oc_")) {
                    targets.add(new LarkTarget(chatId, name.isBlank() ? chatId : name));
                }
                node.elements().forEachRemaining(child -> collectTargets(child, targets));
            } else if (node.isArray()) {
                node.elements().forEachRemaining(child -> collectTargets(child, targets));
            }
        }

        private static String text(JsonNode node, String... keys) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && value.isValueNode()) {
                    return value.asText("");
                }
            }
            return "";
        }
    }
}

record LarkTarget(String chatId, String name) {}

record FanoutRegistration(String type, String targetQuery, long intervalMs) {}

record FanoutConfig(List<FanoutSource> sources) {}

record FanoutSource(
        String sourceId,
        String type,
        String name,
        String targetQuery,
        String targetId,
        long intervalMs,
        boolean enabled,
        long createdAtMs,
        long lastAttemptAtMs,
        long lastSuccessAtMs,
        String lastError,
        int lastActiveCount) {
    FanoutSource withAttempt(long now) {
        return new FanoutSource(
                sourceId, type, name, targetQuery, targetId, intervalMs, enabled,
                createdAtMs, now, lastSuccessAtMs, lastError, lastActiveCount);
    }

    FanoutSource withSuccess(long now, int activeCount) {
        return new FanoutSource(
                sourceId, type, name, targetQuery, targetId, intervalMs, enabled,
                createdAtMs, lastAttemptAtMs, now, "", activeCount);
    }

    FanoutSource withFailure(String error) {
        return new FanoutSource(
                sourceId, type, name, targetQuery, targetId, intervalMs, enabled,
                createdAtMs, lastAttemptAtMs, lastSuccessAtMs, error, lastActiveCount);
    }
}
