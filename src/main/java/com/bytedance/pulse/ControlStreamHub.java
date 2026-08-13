package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class ControlStreamHub implements AutoCloseable {
    private static final long METRIC_INVALIDATE_INTERVAL_MS = 5_000;
    private static final long KEEPALIVE_INTERVAL_MS = 15_000;
    private static final long TASK_SNAPSHOT_CACHE_MS = 1_000;
    private static final int MAX_AGENT_SUBSCRIPTIONS = 512;

    private final CoordinatorService service;
    private final EventBusService eventBusService;
    private final ObjectMapper mapper;
    private final Function<String, Object> taskSnapshotProvider;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, TaskSnapshotValue> taskSnapshots = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TaskSnapshotValue>> taskLoads =
            new ConcurrentHashMap<>();
    private final Map<String, FailureBackoff> ownerFailures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService dispatcher;
    private final ThreadPoolExecutor taskSnapshotExecutor;
    private final AtomicLong cursor = new AtomicLong();
    private final Map<List<String>, HostStreamV3Codec.Session> hostTemplates =
            new HashMap<>();
    private volatile boolean started;
    private volatile Captured latestCaptured;
    private long lastMetricInvalidateAt;
    private long lastKeepaliveAt;
    private long hostTemplateRevision = -1;

    ControlStreamHub(
            CoordinatorService service,
            EventBusService eventBusService,
            ObjectMapper mapper,
            Function<String, Object> taskSnapshotProvider) {
        this.service = service;
        this.eventBusService = eventBusService;
        this.mapper = mapper;
        this.taskSnapshotProvider = taskSnapshotProvider;
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pulse-control-stream-dispatch");
            thread.setDaemon(true);
            return thread;
        });
        this.taskSnapshotExecutor = new ThreadPoolExecutor(
                2,
                4,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "pulse-control-task-snapshot");
                    thread.setDaemon(true);
                    return thread;
                });
        this.taskSnapshotExecutor.allowCoreThreadTimeOut(true);
    }

    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        dispatcher.scheduleWithFixedDelay(this::dispatchQuietly, 0, 1, TimeUnit.SECONDS);
    }

    void subscribe(
            ChannelHandlerContext context,
            List<String> clusters,
            List<String> agentIds,
            boolean once) {
        List<String> boundedAgentIds = agentIds.stream()
                .filter(agentId -> agentId != null && !agentId.isBlank())
                .distinct()
                .limit(MAX_AGENT_SUBSCRIPTIONS)
                .toList();
        Client client = new Client(
                context, List.copyOf(clusters), boundedAgentIds, once);
        clients.add(client);
        context.channel().closeFuture().addListener(ignored -> clients.remove(client));
        client.taskInitialReady = client.agentIds.isEmpty();
        executeOnDispatcher(() -> publish(client, currentCapture(), true));
        if (client.taskInitialReady) {
            return;
        }
        CompletableFuture<?>[] initialLoads = client.agentIds.stream()
                .map(this::loadTaskSnapshot)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(initialLoads).whenComplete(
                (ignored, failure) -> executeOnDispatcher(
                        () -> {
                            client.taskInitialReady = true;
                            publish(client, currentCapture(), false);
                        }));
    }

    int clientCount() {
        return clients.size();
    }

    private void dispatchQuietly() {
        try {
            if (clients.isEmpty()) {
                return;
            }
            Set<String> activeAgentIds = ConcurrentHashMap.newKeySet();
            clients.stream()
                    .flatMap(client -> client.agentIds.stream())
                    .distinct()
                    .forEach(agentId -> {
                        activeAgentIds.add(agentId);
                        loadTaskSnapshot(agentId);
                    });
            taskSnapshots.keySet().removeIf(agentId -> !activeAgentIds.contains(agentId));
            Captured captured = capture();
            latestCaptured = captured;
            for (Client client : clients) {
                publish(client, captured, false);
            }
        } catch (Exception exception) {
            System.err.printf("control_stream status=dispatch_failed error=%s%n",
                    exception.getMessage());
        }
    }

    private Captured currentCapture() {
        Captured captured = latestCaptured;
        if (captured != null
                && System.currentTimeMillis() - captured.nowMs <= 1_000) {
            return captured;
        }
        captured = capture();
        latestCaptured = captured;
        return captured;
    }

    private Captured capture() {
        CoordinatorService.HostSnapshot hosts = service.hostSnapshotWithRevision();
        long eventBusRevision = eventBusService == null ? 0 : eventBusService.revision();
        EventBusView eventBus = eventBusService == null ? null : eventBusService.view();
        long now = System.currentTimeMillis();
        boolean invalidateMetrics = now - lastMetricInvalidateAt >= METRIC_INVALIDATE_INTERVAL_MS;
        if (invalidateMetrics) {
            lastMetricInvalidateAt = now;
        }
        boolean keepalive = now - lastKeepaliveAt >= KEEPALIVE_INTERVAL_MS;
        if (keepalive) {
            lastKeepaliveAt = now;
        }
        return new Captured(
                hosts,
                eventBusRevision,
                eventBus,
                service.metricStorageHealth(),
                service.metricCatalog().stream().map(MetricCatalogItem::metric).toList(),
                now,
                invalidateMetrics,
                keepalive);
    }

    private void publish(Client client, Captured captured, boolean initial) {
        boolean sendInitial = initial;
        if (!client.context.channel().isActive()) {
            clients.remove(client);
            return;
        }
        if (client.once && client.initialPublished && !client.taskInitialReady) {
            return;
        }
        if (!client.context.channel().isWritable()) {
            client.resyncRequired = true;
            return;
        }
        if (client.resyncRequired) {
            if (!write(client, "control.resync_required", Map.of(
                    "reason", "slow_client",
                    "server_time_ms", captured.nowMs))) {
                return;
            }
            client.reset();
            client.resyncRequired = false;
            sendInitial = true;
        }
        if (sendInitial || client.hostSession == null) {
            HostStreamV3Codec.Session session = initialHostSession(
                    captured, client.clusters);
            if (!write(client, "hosts.snapshot", session.snapshot())) {
                return;
            }
            client.hostSession = session;
            client.hostRevision = captured.hosts.revision();
        } else if (captured.hosts.revision() > client.hostRevision) {
            if (!client.context.channel().isWritable()) {
                client.resyncRequired = true;
                return;
            }
            Object delta = client.hostSession.delta(
                    captured.hosts.revision(), captured.hosts.hosts());
            if (!write(client, "hosts.delta", delta)) {
                return;
            }
            client.hostRevision = captured.hosts.revision();
        }
        if (captured.eventBus != null
                && (sendInitial || captured.eventBusRevision > client.eventBusRevision)) {
            if (!write(client, "eventbus.snapshot", captured.eventBus)) {
                return;
            }
            client.eventBusRevision = captured.eventBusRevision;
        }
        if (sendInitial && !write(client, "storage.health", captured.storageHealth)) {
            return;
        }
        for (String agentId : client.agentIds) {
            TaskSnapshotValue taskSnapshot = taskSnapshots.get(agentId);
            if (taskSnapshot != null
                    && (sendInitial
                            || !taskSnapshot.version.equals(
                                    client.taskVersions.get(agentId)))) {
                if (!write(client, "task.snapshot", taskSnapshot.snapshot)) {
                    return;
                }
                client.taskVersions.put(agentId, taskSnapshot.version);
            }
        }
        if ((sendInitial || captured.invalidateMetrics)
                && !write(client, "metric.invalidate", Map.of(
                        "from", Math.max(0, captured.nowMs - 60_000),
                        "to", captured.nowMs,
                        "metrics", captured.metrics))) {
            return;
        }
        if (captured.keepalive
                && !write(client, "ping", Map.of("server_time_ms", captured.nowMs))) {
            return;
        }
        client.initialPublished = true;
        if (client.once && client.taskInitialReady) {
            clients.remove(client);
            CoordinatorHttpServer.closeSse(client.context);
        }
    }

    private HostStreamV3Codec.Session initialHostSession(
            Captured captured,
            List<String> clusters) {
        if (captured.hosts.revision() != hostTemplateRevision) {
            hostTemplates.clear();
            hostTemplateRevision = captured.hosts.revision();
        }
        List<String> scope = clusters.stream()
                .filter(cluster -> cluster != null && !cluster.isBlank())
                .distinct()
                .sorted()
                .toList();
        return hostTemplates.computeIfAbsent(
                scope,
                ignored -> HostStreamV3Codec.session(
                        mapper,
                        captured.hosts.revision(),
                        scope,
                        captured.hosts.hosts()))
                .fork();
    }

    private CompletableFuture<TaskSnapshotValue> loadTaskSnapshot(String agentId) {
        TaskSnapshotValue cached = taskSnapshots.get(agentId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMs < TASK_SNAPSHOT_CACHE_MS) {
            return CompletableFuture.completedFuture(cached);
        }
        String owner = service.agentCoordinatorId(agentId)
                .orElse(service.coordinatorId());
        FailureBackoff failure = ownerFailures.get(owner);
        if (failure != null && now < failure.retryAtMs) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<TaskSnapshotValue> existing = taskLoads.get(agentId);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<TaskSnapshotValue> created = new CompletableFuture<>();
        existing = taskLoads.putIfAbsent(agentId, created);
        if (existing != null) {
            return existing;
        }
        try {
            taskSnapshotExecutor.execute(() -> {
                try {
                    FailureBackoff currentFailure = ownerFailures.get(owner);
                    if (currentFailure != null
                            && System.currentTimeMillis() < currentFailure.retryAtMs) {
                        created.complete(cached);
                        return;
                    }
                    Object snapshot = taskSnapshotProvider.apply(agentId);
                    TaskSnapshotValue loaded = new TaskSnapshotValue(
                            snapshot,
                            mapper.valueToTree(snapshot).toString(),
                            System.currentTimeMillis());
                    taskSnapshots.put(agentId, loaded);
                    ownerFailures.remove(owner);
                    created.complete(loaded);
                } catch (RuntimeException exception) {
                    ownerFailures.compute(owner, (ignored, previous) ->
                            FailureBackoff.next(previous, System.currentTimeMillis()));
                    created.completeExceptionally(exception);
                } finally {
                    taskLoads.remove(agentId, created);
                }
            });
        } catch (RejectedExecutionException exception) {
            taskLoads.remove(agentId, created);
            created.complete(cached);
        }
        return created;
    }

    private void executeOnDispatcher(Runnable runnable) {
        try {
            dispatcher.execute(runnable);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private boolean write(Client client, String event, Object payload) {
        if (!client.context.channel().isWritable()) {
            client.resyncRequired = true;
            return false;
        }
        CoordinatorHttpServer.writeSse(
                client.context,
                String.valueOf(cursor.incrementAndGet()),
                event,
                mapper.valueToTree(payload).toString());
        return true;
    }

    @Override
    public synchronized void close() {
        started = false;
        for (Client client : new ArrayList<>(clients)) {
            client.context.close();
        }
        clients.clear();
        dispatcher.shutdownNow();
        taskSnapshotExecutor.shutdownNow();
        taskLoads.clear();
        taskSnapshots.clear();
        ownerFailures.clear();
        hostTemplates.clear();
        hostTemplateRevision = -1;
        latestCaptured = null;
    }

    private record Captured(
            CoordinatorService.HostSnapshot hosts,
            long eventBusRevision,
            EventBusView eventBus,
            Object storageHealth,
            List<String> metrics,
            long nowMs,
            boolean invalidateMetrics,
            boolean keepalive) {}

    private record TaskSnapshotValue(Object snapshot, String version, long loadedAtMs) {}

    private record FailureBackoff(int failures, long retryAtMs) {
        private static FailureBackoff next(FailureBackoff previous, long nowMs) {
            int failures = previous == null ? 1 : Math.min(6, previous.failures + 1);
            long delayMs = Math.min(60_000, 5_000L << (failures - 1));
            return new FailureBackoff(failures, nowMs + delayMs);
        }
    }

    private static final class Client {
        private final ChannelHandlerContext context;
        private final List<String> clusters;
        private final List<String> agentIds;
        private final boolean once;
        private final Map<String, String> taskVersions = new java.util.HashMap<>();
        private HostStreamV3Codec.Session hostSession;
        private long hostRevision;
        private long eventBusRevision;
        private boolean resyncRequired;
        private boolean initialPublished;
        private boolean taskInitialReady;

        private Client(
                ChannelHandlerContext context,
                List<String> clusters,
                List<String> agentIds,
                boolean once) {
            this.context = context;
            this.clusters = clusters;
            this.agentIds = agentIds;
            this.once = once;
        }

        private void reset() {
            hostSession = null;
            hostRevision = 0;
            eventBusRevision = 0;
            taskVersions.clear();
        }
    }
}
