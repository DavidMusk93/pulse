package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class EventBusService implements AutoCloseable {
    static final String SECRET_MASK = "********";

    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();

    private final Path statePath;
    private final Clock clock;
    private final EventRecorder recorder;
    private final Map<String, EventPlugin.Source> sourcePlugins = new LinkedHashMap<>();
    private final Map<String, EventPlugin.Gate> gatePlugins = new LinkedHashMap<>();
    private final Map<String, EventPlugin.Sink> sinkPlugins = new LinkedHashMap<>();
    private final Map<String, EventPlugin.Event> activeEvents = new LinkedHashMap<>();
    private final Map<String, Set<String>> deliveryAcks = new LinkedHashMap<>();
    private final Map<String, EventRouteStatus> routeStatus = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private EventBusConfig config;
    private long revision;

    EventBusService(
            Path statePath,
            Clock clock,
            EventRecorder recorder,
            boolean startScheduler) throws Exception {
        this(statePath, clock, recorder, startScheduler, List.of());
    }

    EventBusService(
            Path statePath,
            Clock clock,
            EventRecorder recorder,
            boolean startScheduler,
            List<EventPlugin> additionalPlugins) throws Exception {
        this.statePath = statePath.toAbsolutePath();
        this.clock = clock;
        this.recorder = recorder;
        registerBuiltIns();
        loadExtensions();
        additionalPlugins.forEach(this::register);
        load();
        validate(config);
        if (!Files.isRegularFile(this.statePath)) {
            persist();
        }
        if (startScheduler) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "pulse-eventbus-dispatch");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::dispatchQuietly, 30, 5, TimeUnit.SECONDS);
        } else {
            scheduler = null;
        }
    }

    void ingestMessages(
            String agentId,
            long observedAtMs,
            List<PulseMessage> messages) {
        List<EventPlugin.Event> emitted = new ArrayList<>();
        synchronized (this) {
            Map<String, EventTypeDefinition> eventTypes = eventTypes(config);
            Map<String, EventSourceDefinition> sources = sources(config);
            for (PulseMessage message : messages) {
                if (!message.isEventMessage() || message.payload() == null) {
                    continue;
                }
                String sourceId = String.valueOf(message.payload().getOrDefault("source_id", ""));
                EventSourceDefinition source = sources.get(sourceId);
                if (source == null) {
                    continue;
                }
                EventTypeDefinition eventType = eventTypes.get(source.eventType());
                if (!source.enabled() || eventType == null || !eventType.enabled()) {
                    continue;
                }
                EventPlugin.Source plugin = sourcePlugins.get(source.pluginType());
                if (plugin == null || !plugin.supports("pulse_message")) {
                    continue;
                }
                emitted.addAll(plugin.evaluate(
                        source.id(),
                        eventType.id(),
                        eventType.severity(),
                        source.config(),
                        new EventPlugin.Observation(agentId, observedAtMs, message.payload())));
            }
        }
        accept(emitted);
    }

    List<EventPlugin.Event> publish(
            String sourceId,
            String token,
            Map<String, Object> payload) {
        List<EventPlugin.Event> emitted;
        synchronized (this) {
            EventSourceDefinition source = sources(config).get(sourceId);
            if (source == null || !source.enabled()) {
                throw new IllegalArgumentException("unknown or disabled source: " + sourceId);
            }
            EventTypeDefinition eventType = eventTypes(config).get(source.eventType());
            if (eventType == null || !eventType.enabled()) {
                throw new IllegalArgumentException("source event type is disabled: " + source.eventType());
            }
            EventPlugin.Source plugin = sourcePlugins.get(source.pluginType());
            if (plugin == null || !plugin.supports("webhook")) {
                throw new IllegalArgumentException("source does not accept webhook events: " + sourceId);
            }
            String expectedToken = String.valueOf(source.config().getOrDefault("ingest_token", ""));
            if (expectedToken.isBlank() || !MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    (token == null ? "" : token).getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("invalid event source token");
            }
            long observedAtMs = longValue(payload.get("observed_at_ms"), clock.millis());
            String agentId = String.valueOf(payload.getOrDefault("agent_id", "external"));
            emitted = plugin.evaluate(
                    source.id(),
                    eventType.id(),
                    eventType.severity(),
                    source.config(),
                    new EventPlugin.Observation(agentId, observedAtMs, payload));
        }
        accept(emitted);
        return List.copyOf(emitted);
    }

    private void accept(List<EventPlugin.Event> emitted) {
        if (emitted.isEmpty()) {
            return;
        }
        synchronized (this) {
            for (EventPlugin.Event event : emitted) {
                String eventKey = activeKey(event);
                if ("resolved".equals(event.status())) {
                    activeEvents.remove(eventKey);
                    deliveryAcks.remove(eventKey);
                } else if ("firing".equals(event.status())) {
                    activeEvents.put(eventKey, event);
                    deliveryAcks.remove(eventKey);
                }
            }
            try {
                persist();
            } catch (Exception exception) {
                System.err.printf(
                        "eventbus_state_write status=failed error=%s%n",
                        errorMessage(exception));
            }
        }
        for (EventPlugin.Event event : emitted) {
            try {
                recorder.record(toHostEvent(event));
            } catch (Exception exception) {
                System.err.printf(
                        "eventbus_record status=failed event_id=%s source_id=%s error=%s%n",
                        event.eventId(),
                        event.sourceId(),
                        errorMessage(exception));
            }
        }
    }

    synchronized EventBusView view() {
        return new EventBusView(
                redact(config),
                pluginDescriptors(),
                Map.copyOf(routeStatus),
                activeEvents.values().stream()
                        .sorted(Comparator.comparing(EventPlugin.Event::eventId))
                        .toList(),
                config.routes().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        EventRouteDefinition::id,
                        this::pendingCount)));
    }

    private int pendingCount(EventRouteDefinition route) {
        if (!route.enabled()) {
            return 0;
        }
        Map<String, EventSinkDefinition> configuredSinks = sinks(config);
        Set<String> targets = route.sinkIds().stream()
                .filter(sinkId -> {
                    EventSinkDefinition sink = configuredSinks.get(sinkId);
                    return sink != null && sink.enabled();
                })
                .map(sinkId -> statusKey(route.id(), sinkId))
                .collect(java.util.stream.Collectors.toSet());
        if (targets.isEmpty()) {
            return 0;
        }
        return (int) activeEvents.entrySet().stream()
                .filter(entry -> routeMatches(route, entry.getValue()))
                .filter(entry -> !deliveryAcks
                        .getOrDefault(entry.getKey(), Set.of())
                        .containsAll(targets))
                .count();
    }

    synchronized long revision() {
        return revision;
    }

    synchronized long awaitRevision(long observedRevision, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        while (revision <= observedRevision) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            wait(remaining);
        }
        return revision;
    }

    synchronized EventBusView update(EventBusConfig requested) throws Exception {
        EventBusConfig merged = withPluginDefaults(mergeSecrets(requested));
        validate(merged);
        Set<String> changedSources = changedSources(config, merged);
        for (String sourceId : changedSources) {
            sourcePlugins.values().forEach(plugin -> plugin.reset(sourceId));
            Set<String> removed = activeEvents.entrySet().stream()
                    .filter(entry -> sourceId.equals(entry.getValue().sourceId()))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            activeEvents.keySet().removeAll(removed);
            deliveryAcks.keySet().removeAll(removed);
        }
        config = merged;
        Set<String> validStatusKeys = statusKeys(config);
        routeStatus.keySet().removeIf(key -> !validStatusKeys.contains(key));
        deliveryAcks.values().forEach(acks -> acks.removeIf(key -> !validStatusKeys.contains(key)));
        pruneDeliveredEvents();
        persist();
        return view();
    }

    synchronized PulseMessage agentSourceConfigMessage() {
        List<Map<String, Object>> agentSources = config.sources().stream()
                .filter(source -> AgentDiskIoEventSourcePlugin.TYPE.equals(source.pluginType()))
                .sorted(Comparator.comparing(EventSourceDefinition::id))
                .map(source -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("source_id", source.id());
                    value.put("event_type", source.eventType());
                    value.put("enabled", source.enabled());
                    value.put("config", new java.util.TreeMap<>(source.config()));
                    return Map.copyOf(value);
                })
                .toList();
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(agentSources);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize agent source config", exception);
        }
        String generation = TaskOutputCodec.sha256(serialized).substring(0, 20);
        return new PulseMessage(
                "event-source-config-" + generation,
                "cmd.event_source_config",
                1,
                null,
                null,
                Map.of(
                        "generation", generation,
                        "config_version", config.version(),
                        "sources", agentSources));
    }

    EventPlugin.DeliveryReceipt testSink(String sinkId) throws Exception {
        EventSinkDefinition sink;
        EventPlugin.Sink plugin;
        synchronized (this) {
            sink = sinks(config).get(sinkId);
            if (sink == null) {
                throw new IllegalArgumentException("unknown sink: " + sinkId);
            }
            plugin = sinkPlugins.get(sink.pluginType());
        }
        long now = clock.millis();
        EventPlugin.Event testEvent = new EventPlugin.Event(
                "eventbus-test:" + now,
                "eventbus-test",
                "eventbus.test",
                "eventbus.test",
                "test",
                "coordinator",
                "info",
                "firing",
                now,
                "EventBus sink connectivity test",
                Map.of("value", 1, "threshold", 1, "sustained_for_ms", 0, "host", "Pulse"));
        return plugin.deliver(
                sink.config(),
                new EventPlugin.Delivery(
                        "eventbus.test",
                        sink.id(),
                        "test-" + TaskOutputCodec.sha256(sink.id() + ":" + now).substring(0, 20),
                        now,
                        false,
                        List.of(testEvent)));
    }

    void dispatchDue() throws Exception {
        List<Dispatch> due = new ArrayList<>();
        long now = clock.millis();
        synchronized (this) {
            for (EventRouteDefinition route : config.routes()) {
                if (!route.enabled()) {
                    continue;
                }
                EventPlugin.Gate gate = gatePlugins.get(route.gateType());
                if (gate == null) {
                    continue;
                }
                for (String sinkId : route.sinkIds()) {
                    EventSinkDefinition sink = sinks(config).get(sinkId);
                    if (sink == null || !sink.enabled()) {
                        continue;
                    }
                    String statusKey = statusKey(route.id(), sinkId);
                    List<EventPlugin.Event> selected = selectPending(route, statusKey);
                    EventRouteStatus status = routeStatus.getOrDefault(statusKey, EventRouteStatus.empty());
                    EventPlugin.GateDecision decision = gate.evaluate(
                            route.gateConfig(), status.gateState(), selected, now);
                    if (!decision.due()) {
                        continue;
                    }
                    boolean recovery = false;
                    String deliveryId = deliveryId(route, sinkId, decision.reason(), now);
                    routeStatus.put(statusKey, new EventRouteStatus(
                            now,
                            status.lastSuccessAtMs(),
                            status.lastActiveCount(),
                            status.recoveryPending(),
                            status.lastError(),
                            deliveryId,
                            status.lastDeliveredEvents()));
                    due.add(new Dispatch(route, sink, selected, recovery, deliveryId, statusKey));
                }
            }
            if (!due.isEmpty()) {
                persist();
            }
        }
        for (Dispatch dispatch : due) {
            deliver(dispatch, now);
        }
    }

    private void deliver(Dispatch dispatch, long now) throws Exception {
        EventPlugin.Sink plugin = sinkPlugins.get(dispatch.sink().pluginType());
        EventPlugin.Delivery delivery = new EventPlugin.Delivery(
                dispatch.route().id(),
                dispatch.sink().id(),
                dispatch.deliveryId(),
                now,
                dispatch.recovery(),
                dispatch.events());
        try {
            EventPlugin.DeliveryReceipt receipt = plugin.deliver(dispatch.sink().config(), delivery);
            synchronized (this) {
                for (EventPlugin.Event event : dispatch.events()) {
                    deliveryAcks.computeIfAbsent(activeKey(event), ignored -> new HashSet<>())
                            .add(dispatch.statusKey());
                }
                routeStatus.put(dispatch.statusKey(), new EventRouteStatus(
                        now,
                        now,
                        dispatch.events().size(),
                        false,
                        "",
                        dispatch.deliveryId(),
                        receipt.deliveredEvents()));
                pruneDeliveredEvents();
                persist();
            }
            recordDeliveryEvent(dispatch, now, "succeeded", receipt.format(), "");
            System.out.printf(
                    "eventbus_delivery status=succeeded route_id=%s sink_id=%s delivery_id=%s events=%d format=%s%n",
                    dispatch.route().id(),
                    dispatch.sink().id(),
                    dispatch.deliveryId(),
                    dispatch.events().size(),
                    receipt.format());
        } catch (Exception exception) {
            synchronized (this) {
                EventRouteStatus previous = routeStatus.getOrDefault(
                        dispatch.statusKey(), EventRouteStatus.empty());
                routeStatus.put(dispatch.statusKey(), new EventRouteStatus(
                        now,
                        previous.lastSuccessAtMs(),
                        previous.lastActiveCount(),
                        previous.recoveryPending(),
                        errorMessage(exception),
                        dispatch.deliveryId(),
                        previous.lastDeliveredEvents()));
                persist();
            }
            recordDeliveryEvent(dispatch, now, "failed", "", errorMessage(exception));
            System.err.printf(
                    "eventbus_delivery status=failed route_id=%s sink_id=%s delivery_id=%s error=%s%n",
                    dispatch.route().id(),
                    dispatch.sink().id(),
                    dispatch.deliveryId(),
                    errorMessage(exception));
        }
    }

    private void recordDeliveryEvent(
            Dispatch dispatch,
            long observedAtMs,
            String status,
            String format,
            String error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route_id", dispatch.route().id());
        details.put("sink_id", dispatch.sink().id());
        details.put("delivery_id", dispatch.deliveryId());
        details.put("status", status);
        details.put("event_count", dispatch.events().size());
        details.put("recovery", dispatch.recovery());
        if (!format.isBlank()) {
            details.put("format", format);
        }
        if (!error.isBlank()) {
            details.put("error", error);
        }
        try {
            recorder.record(new HostEvent(
                    dispatch.deliveryId() + ":" + dispatch.sink().id() + ":" + status,
                    observedAtMs,
                    "coordinator",
                    "failed".equals(status) ? "error" : "info",
                    "eventbus.delivery",
                    "EventBus delivery " + status,
                    details));
        } catch (Exception exception) {
            System.err.printf(
                    "eventbus_delivery_record status=failed delivery_id=%s error=%s%n",
                    dispatch.deliveryId(),
                    errorMessage(exception));
        }
    }

    private synchronized List<EventPlugin.Event> selectPending(
            EventRouteDefinition route,
            String statusKey) {
        return activeEvents.values().stream()
                .filter(event -> routeMatches(route, event))
                .filter(event -> !deliveryAcks
                        .getOrDefault(activeKey(event), Set.of())
                        .contains(statusKey))
                .sorted(Comparator.comparing(EventPlugin.Event::eventId))
                .toList();
    }

    private void pruneDeliveredEvents() {
        Set<String> completed = activeEvents.entrySet().stream()
                .filter(entry -> {
                    Set<String> targets = deliveryTargets(entry.getValue());
                    return !targets.isEmpty()
                            && deliveryAcks.getOrDefault(entry.getKey(), Set.of()).containsAll(targets);
                })
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        activeEvents.keySet().removeAll(completed);
        deliveryAcks.keySet().removeAll(completed);
    }

    private Set<String> deliveryTargets(EventPlugin.Event event) {
        Set<String> targets = new HashSet<>();
        Map<String, EventSinkDefinition> configuredSinks = sinks(config);
        for (EventRouteDefinition route : config.routes()) {
            if (!routeMatches(route, event)) {
                continue;
            }
            for (String sinkId : route.sinkIds()) {
                EventSinkDefinition sink = configuredSinks.get(sinkId);
                if (sink != null && sink.enabled()) {
                    targets.add(statusKey(route.id(), sinkId));
                }
            }
        }
        return targets;
    }

    private void dispatchQuietly() {
        try {
            dispatchDue();
        } catch (Exception exception) {
            System.err.printf("eventbus_dispatch status=failed error=%s%n", errorMessage(exception));
        }
    }

    private void registerBuiltIns() {
        register(new AgentDiskIoEventSourcePlugin());
        register(new PulseMessageEventSourcePlugin());
        register(new WebhookEventSourcePlugin());
        register(new PeriodicDigestGatePlugin());
        register(new LarkWebhookSinkPlugin());
    }

    private void loadExtensions() {
        ServiceLoader.load(EventPlugin.Source.class).forEach(this::register);
        ServiceLoader.load(EventPlugin.Gate.class).forEach(this::register);
        ServiceLoader.load(EventPlugin.Sink.class).forEach(this::register);
    }

    private void register(EventPlugin plugin) {
        String type = plugin.descriptor().type();
        if (plugin instanceof EventPlugin.Source source) {
            sourcePlugins.putIfAbsent(type, source);
        } else if (plugin instanceof EventPlugin.Gate gate) {
            gatePlugins.putIfAbsent(type, gate);
        } else if (plugin instanceof EventPlugin.Sink sink) {
            sinkPlugins.putIfAbsent(type, sink);
        }
    }

    private synchronized void load() throws Exception {
        if (!Files.isRegularFile(statePath)) {
            config = defaultConfig();
            return;
        }
        EventBusState state = MAPPER.readValue(Files.readString(statePath), EventBusState.class);
        config = withPluginDefaults(state.config() == null ? defaultConfig() : state.config());
        routeStatus.clear();
        routeStatus.putAll(state.routeStatus());
        activeEvents.clear();
        state.activeEvents().stream()
                .filter(event -> "firing".equals(event.status()))
                .forEach(event -> activeEvents.put(activeKey(event), event));
        deliveryAcks.clear();
        state.deliveryAcks().forEach(
                (eventKey, acks) -> deliveryAcks.put(eventKey, new HashSet<>(acks)));
    }

    private synchronized void persist() throws Exception {
        Path parent = statePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        Files.writeString(temporary, MAPPER.writeValueAsString(new EventBusState(
                config,
                routeStatus,
                List.copyOf(activeEvents.values()),
                deliveryAcks)));
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
        try {
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
        revision++;
        notifyAll();
    }

    private void validate(EventBusConfig candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("eventbus config is required");
        }
        uniqueIds(candidate.eventTypes().stream().map(EventTypeDefinition::id).toList(), "event type");
        uniqueIds(candidate.sources().stream().map(EventSourceDefinition::id).toList(), "source");
        uniqueIds(candidate.sinks().stream().map(EventSinkDefinition::id).toList(), "sink");
        uniqueIds(candidate.routes().stream().map(EventRouteDefinition::id).toList(), "route");
        Map<String, EventTypeDefinition> eventTypes = eventTypes(candidate);
        Map<String, EventSourceDefinition> sources = sources(candidate);
        Map<String, EventSinkDefinition> sinks = sinks(candidate);
        for (EventSourceDefinition source : candidate.sources()) {
            if (!sourcePlugins.containsKey(source.pluginType())) {
                throw new IllegalArgumentException("unknown source plugin: " + source.pluginType());
            }
            if (!eventTypes.containsKey(source.eventType())) {
                throw new IllegalArgumentException("source references unknown event type: " + source.eventType());
            }
            requiredConfig(sourcePlugins.get(source.pluginType()).descriptor(), source.config(), source.id());
            if (AgentDiskIoEventSourcePlugin.TYPE.equals(source.pluginType())) {
                if (!AgentDiskIoEventEmitter.SOURCE_ID.equals(source.id())
                        || !AgentDiskIoEventEmitter.EVENT_TYPE.equals(source.eventType())) {
                    throw new IllegalArgumentException(
                            "agent_disk_io requires source_id=disk-io-saturation"
                                    + " and event_type=disk.io_saturation");
                }
                double thresholdPct = doubleValue(source.config().get("threshold_pct"), -1);
                long sustainSeconds = longValue(source.config().get("sustain_seconds"), -1);
                if (thresholdPct <= 0 || thresholdPct > 100) {
                    throw new IllegalArgumentException(
                            source.id() + " threshold_pct must be in (0, 100]");
                }
                if (sustainSeconds < 1) {
                    throw new IllegalArgumentException(
                            source.id() + " sustain_seconds must be at least 1");
                }
            }
        }
        for (EventSinkDefinition sink : candidate.sinks()) {
            if (!sinkPlugins.containsKey(sink.pluginType())) {
                throw new IllegalArgumentException("unknown sink plugin: " + sink.pluginType());
            }
            requiredConfig(sinkPlugins.get(sink.pluginType()).descriptor(), sink.config(), sink.id());
            if (LarkWebhookSinkPlugin.TYPE.equals(sink.pluginType())) {
                long timeoutSeconds = longValue(sink.config().get("timeout_seconds"), -1);
                if (timeoutSeconds < 1 || timeoutSeconds > 60) {
                    throw new IllegalArgumentException(
                            sink.id() + " timeout_seconds must be in [1, 60]");
                }
            }
        }
        for (EventRouteDefinition route : candidate.routes()) {
            if (!gatePlugins.containsKey(route.gateType())) {
                throw new IllegalArgumentException("unknown gate plugin: " + route.gateType());
            }
            if (!sources.keySet().containsAll(route.sourceIds())) {
                throw new IllegalArgumentException("route references unknown source: " + route.id());
            }
            if (!eventTypes.keySet().containsAll(route.eventTypes())) {
                throw new IllegalArgumentException("route references unknown event type: " + route.id());
            }
            if (route.clusters().stream().anyMatch(cluster -> cluster == null || cluster.isBlank())) {
                throw new IllegalArgumentException("route cluster filters must not be blank: " + route.id());
            }
            if (route.sinkIds().isEmpty() || !sinks.keySet().containsAll(route.sinkIds())) {
                throw new IllegalArgumentException("route must reference existing sinks: " + route.id());
            }
            requiredConfig(gatePlugins.get(route.gateType()).descriptor(), route.gateConfig(), route.id());
            if (PeriodicDigestGatePlugin.TYPE.equals(route.gateType())
                    && longValue(route.gateConfig().get("interval_seconds"), -1)
                            < PeriodicDigestGatePlugin.MIN_INTERVAL_SECONDS) {
                throw new IllegalArgumentException(
                        route.id() + " interval_seconds must be at least "
                                + PeriodicDigestGatePlugin.MIN_INTERVAL_SECONDS);
            }
        }
    }

    private static void requiredConfig(
            EventPlugin.PluginDescriptor descriptor,
            Map<String, Object> config,
            String ownerId) {
        for (EventPlugin.ConfigField field : descriptor.configFields()) {
            Object value = config.get(field.key());
            if (field.required() && (value == null || value.toString().isBlank())) {
                throw new IllegalArgumentException(
                        ownerId + " requires config field: " + field.key());
            }
        }
    }

    private EventBusConfig mergeSecrets(EventBusConfig requested) {
        if (requested == null) {
            return null;
        }
        Map<String, EventSourceDefinition> currentSources = sources(config);
        List<EventSourceDefinition> mergedSources = requested.sources().stream().map(source -> {
            EventSourceDefinition current = currentSources.get(source.id());
            EventPlugin.Source plugin = sourcePlugins.get(source.pluginType());
            if (current == null || plugin == null) {
                return source;
            }
            return source.withConfig(mergeSecretFields(
                    plugin.descriptor(), current.config(), source.config()));
        }).toList();
        Map<String, EventSinkDefinition> currentSinks = sinks(config);
        List<EventSinkDefinition> mergedSinks = requested.sinks().stream().map(sink -> {
            EventSinkDefinition current = currentSinks.get(sink.id());
            EventPlugin.Sink plugin = sinkPlugins.get(sink.pluginType());
            if (current == null || plugin == null) {
                return sink;
            }
            return sink.withConfig(mergeSecretFields(
                    plugin.descriptor(), current.config(), sink.config()));
        }).toList();
        return new EventBusConfig(
                requested.version(),
                requested.eventTypes(),
                mergedSources,
                mergedSinks,
                requested.routes());
    }

    private EventBusConfig withPluginDefaults(EventBusConfig value) {
        if (value == null) {
            return null;
        }
        List<EventSourceDefinition> sourcesWithDefaults = value.sources().stream()
                .map(source -> {
                    EventSourceDefinition migrated = migrateSourceConfig(
                            migrateLegacyAgentSource(source));
                    EventPlugin.Source plugin = sourcePlugins.get(migrated.pluginType());
                    return plugin == null
                            ? migrated
                            : migrated.withConfig(withDefaults(plugin.descriptor(), migrated.config()));
                })
                .toList();
        List<EventRouteDefinition> routesWithDefaults = value.routes().stream()
                .map(route -> {
                    Map<String, Object> migrated = migrateGateConfig(route);
                    EventPlugin.Gate plugin = gatePlugins.get(route.gateType());
                    Map<String, Object> configWithDefaults = plugin == null
                            ? migrated
                            : withDefaults(plugin.descriptor(), migrated);
                    return new EventRouteDefinition(
                            route.id(),
                            route.name(),
                            route.enabled(),
                            route.sourceIds(),
                            route.eventTypes(),
                            route.clusters(),
                            route.sinkIds(),
                            route.gateType(),
                            configWithDefaults);
                })
                .toList();
        List<EventSinkDefinition> sinksWithDefaults = value.sinks().stream()
                .map(sink -> {
                    EventSinkDefinition migrated = migrateSinkConfig(sink);
                    EventPlugin.Sink plugin = sinkPlugins.get(migrated.pluginType());
                    return plugin == null
                            ? migrated
                            : migrated.withConfig(withDefaults(plugin.descriptor(), migrated.config()));
                })
                .toList();
        return new EventBusConfig(
                value.version(),
                value.eventTypes(),
                sourcesWithDefaults,
                sinksWithDefaults,
                routesWithDefaults);
    }

    private static EventSourceDefinition migrateSourceConfig(EventSourceDefinition source) {
        if (!AgentDiskIoEventSourcePlugin.TYPE.equals(source.pluginType())) {
            return source;
        }
        Map<String, Object> config = new LinkedHashMap<>(source.config());
        if (!config.containsKey("sustain_seconds") && config.containsKey("sustain_ms")) {
            config.put(
                    "sustain_seconds",
                    millisecondsToSeconds(longValue(config.get("sustain_ms"), 0)));
        }
        config.remove("sustain_ms");
        return source.withConfig(config);
    }

    private static Map<String, Object> migrateGateConfig(EventRouteDefinition route) {
        Map<String, Object> config = new LinkedHashMap<>(route.gateConfig());
        if (PeriodicDigestGatePlugin.TYPE.equals(route.gateType())) {
            if (!config.containsKey("interval_seconds") && config.containsKey("interval_ms")) {
                config.put(
                        "interval_seconds",
                        millisecondsToSeconds(longValue(config.get("interval_ms"), 0)));
            }
            config.remove("interval_ms");
            config.remove("publish_recovery");
        }
        return config;
    }

    private static EventSinkDefinition migrateSinkConfig(EventSinkDefinition sink) {
        if (!LarkWebhookSinkPlugin.TYPE.equals(sink.pluginType())) {
            return sink;
        }
        Map<String, Object> config = new LinkedHashMap<>(sink.config());
        if (!config.containsKey("timeout_seconds") && config.containsKey("timeout_ms")) {
            config.put(
                    "timeout_seconds",
                    millisecondsToSeconds(longValue(config.get("timeout_ms"), 0)));
        }
        config.remove("timeout_ms");
        return sink.withConfig(config);
    }

    private static EventSourceDefinition migrateLegacyAgentSource(EventSourceDefinition source) {
        if (PulseMessageEventSourcePlugin.TYPE.equals(source.pluginType())
                && AgentDiskIoEventEmitter.SOURCE_ID.equals(source.id())
                && AgentDiskIoEventEmitter.EVENT_TYPE.equals(source.eventType())) {
            return new EventSourceDefinition(
                    source.id(),
                    source.name(),
                    AgentDiskIoEventSourcePlugin.TYPE,
                    source.eventType(),
                    source.enabled(),
                    source.config());
        }
        return source;
    }

    private static Map<String, Object> withDefaults(
            EventPlugin.PluginDescriptor descriptor,
            Map<String, Object> config) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (EventPlugin.ConfigField field : descriptor.configFields()) {
            if (field.defaultValue() != null) {
                merged.put(field.key(), field.defaultValue());
            }
        }
        merged.putAll(config);
        return merged;
    }

    private EventBusConfig redact(EventBusConfig value) {
        List<EventSourceDefinition> redactedSources = value.sources().stream().map(source -> {
            EventPlugin.Source plugin = sourcePlugins.get(source.pluginType());
            return plugin == null
                    ? source
                    : source.withConfig(redactSecretFields(plugin.descriptor(), source.config()));
        }).toList();
        List<EventSinkDefinition> redactedSinks = value.sinks().stream().map(sink -> {
            EventPlugin.Sink plugin = sinkPlugins.get(sink.pluginType());
            return plugin == null
                    ? sink
                    : sink.withConfig(redactSecretFields(plugin.descriptor(), sink.config()));
        }).toList();
        return new EventBusConfig(
                value.version(),
                value.eventTypes(),
                redactedSources,
                redactedSinks,
                value.routes());
    }

    private static Map<String, Object> mergeSecretFields(
            EventPlugin.PluginDescriptor descriptor,
            Map<String, Object> current,
            Map<String, Object> requested) {
        Map<String, Object> merged = new LinkedHashMap<>(requested);
        for (EventPlugin.ConfigField field : descriptor.configFields()) {
            if (field.secret() && SECRET_MASK.equals(merged.get(field.key()))) {
                Object existing = current.get(field.key());
                if (existing != null) {
                    merged.put(field.key(), existing);
                }
            }
        }
        return merged;
    }

    private static Map<String, Object> redactSecretFields(
            EventPlugin.PluginDescriptor descriptor,
            Map<String, Object> config) {
        Map<String, Object> redacted = new LinkedHashMap<>(config);
        for (EventPlugin.ConfigField field : descriptor.configFields()) {
            if (field.secret() && redacted.containsKey(field.key())) {
                redacted.put(field.key(), SECRET_MASK);
            }
        }
        return redacted;
    }

    private List<EventPlugin.PluginDescriptor> pluginDescriptors() {
        List<EventPlugin.PluginDescriptor> descriptors = new ArrayList<>();
        sourcePlugins.values().forEach(plugin -> descriptors.add(plugin.descriptor()));
        gatePlugins.values().forEach(plugin -> descriptors.add(plugin.descriptor()));
        sinkPlugins.values().forEach(plugin -> descriptors.add(plugin.descriptor()));
        return descriptors.stream()
                .sorted(Comparator.comparing(EventPlugin.PluginDescriptor::kind)
                        .thenComparing(EventPlugin.PluginDescriptor::type))
                .toList();
    }

    private static Set<String> changedSources(EventBusConfig current, EventBusConfig next) {
        Map<String, EventSourceDefinition> before = sources(current);
        Map<String, EventSourceDefinition> after = sources(next);
        Set<String> changed = new HashSet<>(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(id -> before.containsKey(id) && before.get(id).equals(after.get(id)));
        return changed;
    }

    private static Set<String> statusKeys(EventBusConfig config) {
        Set<String> keys = new HashSet<>();
        for (EventRouteDefinition route : config.routes()) {
            route.sinkIds().forEach(sinkId -> keys.add(statusKey(route.id(), sinkId)));
        }
        return keys;
    }

    private static String deliveryId(
            EventRouteDefinition route,
            String sinkId,
            String reason,
            long now) {
        long interval = Math.max(
                PeriodicDigestGatePlugin.MIN_INTERVAL_MS,
                longValue(
                        route.gateConfig().get("interval_seconds"),
                        PeriodicDigestGatePlugin.DEFAULT_INTERVAL_SECONDS) * 1_000);
        long bucket = now / interval;
        return "delivery-" + TaskOutputCodec.sha256(
                route.id() + "\n" + sinkId + "\n" + reason + "\n" + bucket).substring(0, 24);
    }

    private static String statusKey(String routeId, String sinkId) {
        return routeId + "::" + sinkId;
    }

    private static String activeKey(EventPlugin.Event event) {
        return event.sourceId() + "::" + event.incidentId();
    }

    private static boolean routeMatches(
            EventRouteDefinition route,
            EventPlugin.Event event) {
        if (!route.enabled()
                || (!route.sourceIds().isEmpty() && !route.sourceIds().contains(event.sourceId()))
                || (!route.eventTypes().isEmpty() && !route.eventTypes().contains(event.eventType()))) {
            return false;
        }
        if (route.clusters().isEmpty()) {
            return true;
        }
        Object cluster = event.attributes().get("cluster");
        return cluster != null && route.clusters().contains(cluster.toString());
    }

    private static EventBusConfig defaultConfig() {
        return new EventBusConfig(
                1,
                List.of(new EventTypeDefinition(
                        "disk.io_saturation",
                        "磁盘 IO 饱和",
                        "磁盘 IO 利用率持续超过门槛",
                        "error",
                        true)),
                List.of(new EventSourceDefinition(
                        "disk-io-saturation",
                        "Agent 磁盘 IO 事件",
                        AgentDiskIoEventSourcePlugin.TYPE,
                        "disk.io_saturation",
                        true,
                        Map.of(
                                "threshold_pct", AgentDiskIoEventEmitter.DEFAULT_THRESHOLD_PCT,
                                "sustain_seconds", AgentDiskIoEventEmitter.DEFAULT_SUSTAIN_SECONDS))),
                List.of(),
                List.of());
    }

    private static HostEvent toHostEvent(EventPlugin.Event event) {
        Map<String, Object> details = new LinkedHashMap<>(event.attributes());
        details.put("incident_id", event.incidentId());
        details.put("source_id", event.sourceId());
        details.put("subject", event.subject());
        details.put("status", event.status());
        return new HostEvent(
                event.eventId(),
                event.observedAtMs(),
                event.agentId(),
                event.severity(),
                event.eventType(),
                event.summary(),
                details);
    }

    private static Map<String, EventTypeDefinition> eventTypes(EventBusConfig config) {
        Map<String, EventTypeDefinition> values = new LinkedHashMap<>();
        config.eventTypes().forEach(value -> values.put(value.id(), value));
        return values;
    }

    private static Map<String, EventSourceDefinition> sources(EventBusConfig config) {
        Map<String, EventSourceDefinition> values = new LinkedHashMap<>();
        config.sources().forEach(value -> values.put(value.id(), value));
        return values;
    }

    private static Map<String, EventSinkDefinition> sinks(EventBusConfig config) {
        Map<String, EventSinkDefinition> values = new LinkedHashMap<>();
        config.sinks().forEach(value -> values.put(value.id(), value));
        return values;
    }

    private static void uniqueIds(List<String> ids, String kind) {
        Set<String> unique = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || !unique.add(id)) {
                throw new IllegalArgumentException(kind + " IDs must be non-empty and unique");
            }
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long millisecondsToSeconds(long milliseconds) {
        return milliseconds <= 0 ? 0 : Math.max(1, (milliseconds + 999) / 1_000);
    }

    private static String errorMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @FunctionalInterface
    interface EventRecorder {
        void record(HostEvent event) throws Exception;
    }

    private record Dispatch(
            EventRouteDefinition route,
            EventSinkDefinition sink,
            List<EventPlugin.Event> events,
            boolean recovery,
            String deliveryId,
            String statusKey) {
    }
}
