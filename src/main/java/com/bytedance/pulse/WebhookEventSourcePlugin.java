package com.bytedance.pulse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WebhookEventSourcePlugin implements EventPlugin.Source {
    static final String TYPE = "webhook_event";

    @Override
    public boolean supports(String inputType) {
        return "webhook".equals(inputType);
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "source",
                "Webhook event ingress",
                "Accepts structured firing/resolved events through the EventBus source API.",
                List.of(
                        field("ingest_token", "接入 Token", "password", true, true, "", "请求头 x-pulse-event-token"),
                        field("subject_field", "主体字段", "text", true, false, "subject", "标识发生事件的资源"),
                        field("summary_field", "摘要字段", "text", true, false, "summary", "用于消息展示的摘要"),
                        field("status_field", "状态字段", "text", true, false, "status", "firing 或 resolved"),
                        field("incident_id_field", "Incident ID 字段", "text", false, false, "incident_id", "关联触发与恢复"),
                        field("severity_field", "级别字段", "text", false, false, "severity", "可覆盖事件类型默认级别")));
    }

    @Override
    public List<Event> evaluate(
            String sourceId,
            String eventType,
            String severity,
            Map<String, Object> config,
            Observation observation) {
        Map<String, Object> payload = observation.state();
        String subject = value(payload, text(config, "subject_field", "subject"), observation.agentId());
        String status = value(payload, text(config, "status_field", "status"), "firing").toLowerCase(
                java.util.Locale.ROOT);
        if (!"firing".equals(status) && !"resolved".equals(status)) {
            throw new IllegalArgumentException("webhook event status must be firing or resolved");
        }
        String incidentIdField = text(config, "incident_id_field", "incident_id");
        String incidentId = value(payload, incidentIdField, "");
        if (incidentId.isBlank()) {
            incidentId = "incident-" + TaskOutputCodec.sha256(
                    sourceId + "\n" + observation.agentId() + "\n" + subject).substring(0, 24);
        }
        String eventId = value(payload, "event_id", "");
        if (eventId.isBlank()) {
            eventId = incidentId + ":" + status + ":" + observation.observedAtMs();
        }
        String summary = value(
                payload,
                text(config, "summary_field", "summary"),
                eventType + " " + status + " for " + subject);
        String eventSeverity = value(
                payload,
                text(config, "severity_field", "severity"),
                "resolved".equals(status) ? "info" : severity);
        Map<String, Object> attributes = new LinkedHashMap<>(payload);
        attributes.remove("event_id");
        return List.of(new Event(
                eventId,
                incidentId,
                eventType,
                sourceId,
                subject,
                observation.agentId(),
                eventSeverity,
                status,
                observation.observedAtMs(),
                summary,
                attributes));
    }

    private static ConfigField field(
            String key,
            String label,
            String type,
            boolean required,
            boolean secret,
            Object defaultValue,
            String description) {
        return new ConfigField(
                key, label, type, required, secret, defaultValue, List.of(), description);
    }

    private static String text(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static String value(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }
}
