package com.bytedance.pulse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MetricThresholdSourcePlugin implements EventPlugin.Source {
    static final String TYPE = "metric_threshold";

    private final Map<String, ActiveIncident> active = new ConcurrentHashMap<>();

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "source",
                "Metric threshold",
                "Emits firing and resolved events from numeric heartbeat samples.",
                List.of(
                        field("collection_path", "集合字段", "text", true, "disks", "Heartbeat state 中的对象数组"),
                        field("subject_field", "主体字段", "text", true, "device", "数组元素中的主体标识"),
                        field("value_field", "指标字段", "text", true, "io_util_pct", "用于比较的数值字段"),
                        field("operator", "比较符", "select", true, "gt", "阈值比较方式", "gt", "gte", "lt", "lte"),
                        field("threshold", "阈值", "number", true, 95, "触发阈值"),
                        field("duration_field", "持续时长字段", "text", false, "saturated_for_ms", "采集端累计持续时长"),
                        field("duration_ms", "持续门槛 (ms)", "number", true, 10_000, "达到该时长后生成事件")));
    }

    @Override
    public synchronized List<Event> evaluate(
            String sourceId,
            String eventType,
            String severity,
            Map<String, Object> config,
            Observation observation) {
        Object collectionValue = observation.state().get(text(config, "collection_path", ""));
        if (!(collectionValue instanceof List<?> collection)) {
            return List.of();
        }
        String subjectField = text(config, "subject_field", "subject");
        String valueField = text(config, "value_field", "value");
        String durationField = text(config, "duration_field", "");
        String operator = text(config, "operator", "gt");
        double threshold = number(config.get("threshold"), 0);
        long durationMs = Math.max(0, (long) number(config.get("duration_ms"), 0));
        List<Event> emitted = new ArrayList<>();
        for (Object itemValue : collection) {
            if (!(itemValue instanceof Map<?, ?> item)) {
                continue;
            }
            String subject = string(item.get(subjectField));
            if (subject.isBlank()) {
                continue;
            }
            double value = number(item.get(valueField), Double.NaN);
            if (!Double.isFinite(value)) {
                continue;
            }
            long sustainedForMs = durationField.isBlank()
                    ? durationMs
                    : Math.max(0, (long) number(item.get(durationField), 0));
            String key = sourceId + "\n" + observation.agentId() + "\n" + subject;
            ActiveIncident current = active.get(key);
            if (matches(value, threshold, operator) && sustainedForMs >= durationMs) {
                long startedAtMs = Math.max(0, observation.observedAtMs() - sustainedForMs);
                String incidentId = "event-" + TaskOutputCodec.sha256(
                        sourceId + "\n" + observation.agentId() + "\n" + subject + "\n" + startedAtMs)
                        .substring(0, 24);
                Event firing = event(
                        incidentId + ":firing",
                        incidentId,
                        eventType,
                        sourceId,
                        subject,
                        observation,
                        severity,
                        "firing",
                        valueField + " " + format(value) + " " + operator + " " + format(threshold)
                                + " for " + sustainedForMs + "ms",
                        valueField,
                        value,
                        threshold,
                        sustainedForMs,
                        startedAtMs);
                if (current == null || !current.incidentId().equals(incidentId)) {
                    emitted.add(firing);
                }
                active.put(key, new ActiveIncident(incidentId, firing));
            } else if (current != null && !matches(value, threshold, operator)) {
                emitted.add(event(
                        current.incidentId() + ":resolved",
                        current.incidentId(),
                        eventType,
                        sourceId,
                        subject,
                        observation,
                        "info",
                        "resolved",
                        valueField + " recovered to " + format(value),
                        valueField,
                        value,
                        threshold,
                        0,
                        current.startedAtMs()));
                active.remove(key);
            }
        }
        return List.copyOf(emitted);
    }

    @Override
    public synchronized void reset(String sourceId) {
        active.keySet().removeIf(key -> key.startsWith(sourceId + "\n"));
    }

    private static Event event(
            String eventId,
            String incidentId,
            String eventType,
            String sourceId,
            String subject,
            Observation observation,
            String severity,
            String status,
            String summary,
            String valueField,
            double value,
            double threshold,
            long sustainedForMs,
            long startedAtMs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("incident_id", incidentId);
        attributes.put("status", status);
        attributes.put("subject", subject);
        attributes.put("value_field", valueField);
        attributes.put("value", value);
        attributes.put("threshold", threshold);
        attributes.put("sustained_for_ms", sustainedForMs);
        attributes.put("started_at_ms", startedAtMs);
        copy(attributes, observation.state(), "host", "ip", "cluster", "area", "zone");
        return new Event(
                eventId,
                incidentId,
                eventType,
                sourceId,
                subject,
                observation.agentId(),
                severity,
                status,
                observation.observedAtMs(),
                summary,
                attributes);
    }

    private static void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !value.toString().isBlank()) {
                target.put(key, value);
            }
        }
    }

    private static boolean matches(double value, double threshold, String operator) {
        return switch (operator) {
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            default -> value > threshold;
        };
    }

    private static ConfigField field(
            String key,
            String label,
            String type,
            boolean required,
            Object defaultValue,
            String description,
            String... options) {
        return new ConfigField(
                key, label, type, required, false, defaultValue, List.of(options), description);
    }

    private static String text(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record ActiveIncident(String incidentId, Event event) {
        long startedAtMs() {
            Object value = event.attributes().get("started_at_ms");
            return value instanceof Number number ? number.longValue() : 0;
        }
    }
}
