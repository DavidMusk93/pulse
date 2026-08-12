package com.bytedance.pulse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

record EventBusConfig(
        int version,
        List<EventTypeDefinition> eventTypes,
        List<EventSourceDefinition> sources,
        List<EventSinkDefinition> sinks,
        List<EventRouteDefinition> routes) {
    EventBusConfig {
        version = version <= 0 ? 1 : version;
        eventTypes = immutableList(eventTypes);
        sources = immutableList(sources);
        sinks = immutableList(sinks);
        routes = immutableList(routes);
    }

    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}

record EventTypeDefinition(
        String id,
        String name,
        String description,
        String severity,
        boolean enabled) {
}

record EventSourceDefinition(
        String id,
        String name,
        String pluginType,
        String eventType,
        boolean enabled,
        Map<String, Object> config) {
    EventSourceDefinition {
        config = immutableMap(config);
    }

    EventSourceDefinition withConfig(Map<String, Object> nextConfig) {
        return new EventSourceDefinition(id, name, pluginType, eventType, enabled, nextConfig);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
    }
}

record EventSinkDefinition(
        String id,
        String name,
        String pluginType,
        boolean enabled,
        Map<String, Object> config) {
    EventSinkDefinition {
        config = immutableMap(config);
    }

    EventSinkDefinition withConfig(Map<String, Object> nextConfig) {
        return new EventSinkDefinition(id, name, pluginType, enabled, nextConfig);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
    }
}

record EventRouteDefinition(
        String id,
        String name,
        boolean enabled,
        List<String> sourceIds,
        List<String> eventTypes,
        List<String> clusters,
        List<String> sinkIds,
        String gateType,
        Map<String, Object> gateConfig) {
    EventRouteDefinition {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        sinkIds = sinkIds == null ? List.of() : List.copyOf(sinkIds);
        gateConfig = gateConfig == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(gateConfig));
    }
}

record EventBusState(
        EventBusConfig config,
        Map<String, EventRouteStatus> routeStatus,
        List<EventPlugin.Event> activeEvents,
        Map<String, Set<String>> deliveryAcks) {
    EventBusState {
        routeStatus = routeStatus == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(routeStatus));
        activeEvents = activeEvents == null ? List.of() : List.copyOf(activeEvents);
        deliveryAcks = deliveryAcks == null
                ? Map.of()
                : deliveryAcks.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    EventBusState(
            EventBusConfig config,
            Map<String, EventRouteStatus> routeStatus,
            List<EventPlugin.Event> activeEvents) {
        this(config, routeStatus, activeEvents, Map.of());
    }
}

record EventRouteStatus(
        long lastAttemptAtMs,
        long lastSuccessAtMs,
        int lastActiveCount,
        boolean recoveryPending,
        String lastError,
        String lastDeliveryId,
        int lastDeliveredEvents) {
    EventPlugin.GateState gateState() {
        return new EventPlugin.GateState(
                lastAttemptAtMs,
                lastSuccessAtMs,
                lastActiveCount,
                recoveryPending);
    }

    static EventRouteStatus empty() {
        return new EventRouteStatus(0, 0, 0, false, "", "", 0);
    }
}

record EventBusView(
        EventBusConfig config,
        List<EventPlugin.PluginDescriptor> plugins,
        Map<String, EventRouteStatus> routeStatus,
        List<EventPlugin.Event> activeEvents,
        Map<String, Integer> pendingByRoute) {
}
