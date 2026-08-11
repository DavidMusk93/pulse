package com.bytedance.pulse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PulseMessageEventSourcePlugin implements EventPlugin.Source {
    static final String TYPE = "pulse_message";

    @Override
    public boolean supports(String inputType) {
        return "pulse_message".equals(inputType);
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "source",
                "Pulse heartbeat message",
                "Consumes event.publish envelopes transported by the existing heartbeat message path.",
                List.of());
    }

    @Override
    public List<Event> evaluate(
            String sourceId,
            String eventType,
            String severity,
            Map<String, Object> config,
            Observation observation) {
        Map<String, Object> payload = observation.state();
        String payloadSourceId = text(payload.get("source_id"));
        String payloadEventType = text(payload.get("event_type"));
        if (!sourceId.equals(payloadSourceId) || !eventType.equals(payloadEventType)) {
            return List.of();
        }
        String eventId = required(payload, "event_id");
        String incidentId = required(payload, "incident_id");
        String status = required(payload, "status");
        if (!"firing".equals(status) && !"resolved".equals(status)) {
            throw new IllegalArgumentException("event.publish status must be firing or resolved");
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object value = payload.get("attributes");
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> attributes.put(String.valueOf(key), item));
        }
        return List.of(new Event(
                eventId,
                incidentId,
                eventType,
                sourceId,
                text(payload.getOrDefault("subject", "unknown")),
                text(payload.getOrDefault("agent_id", observation.agentId())),
                text(payload.getOrDefault("severity", "resolved".equals(status) ? "info" : severity)),
                status,
                longValue(payload.get("observed_at_ms"), observation.observedAtMs()),
                text(payload.getOrDefault("summary", eventType + " " + status)),
                attributes));
    }

    private static String required(Map<String, Object> payload, String key) {
        String value = text(payload.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException("event.publish requires " + key);
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
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
}
