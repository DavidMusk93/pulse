package com.bytedance.pulse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AgentDiskIoEventEmitter implements AgentEventSourcePlugin {
    static final String MESSAGE_TYPE = "event.publish";
    static final String SOURCE_ID = "disk-io-saturation";
    static final String EVENT_TYPE = "disk.io_saturation";

    private final double thresholdPct;
    private final long sustainMs;
    private final Map<String, ActiveIncident> active = new ConcurrentHashMap<>();

    AgentDiskIoEventEmitter(double thresholdPct, long sustainMs) {
        this.thresholdPct = thresholdPct > 0 ? thresholdPct : 95;
        this.sustainMs = Math.max(1, sustainMs);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                SOURCE_ID,
                EVENT_TYPE,
                "Disk IO saturation",
                "Publishes firing/resolved events after sustained physical disk IO saturation.");
    }

    @Override
    public synchronized List<PulseMessage> evaluate(Context context) {
        String agentId = context.agentId();
        long observedAtMs = context.observedAtMs();
        Map<String, Object> hostState = context.heartbeatState();
        List<Map<String, Object>> disks = diskValues(hostState.get("disks"));
        List<PulseMessage> messages = new ArrayList<>();
        for (Map<String, Object> disk : disks) {
            String device = text(disk.get("device"));
            if (device.isBlank()) {
                continue;
            }
            double utilizationPct = number(disk.get("io_util_pct"));
            long saturatedForMs = (long) number(disk.get("saturated_for_ms"));
            ActiveIncident current = active.get(device);
            if (utilizationPct > thresholdPct && saturatedForMs >= sustainMs) {
                long startedAtMs = Math.max(0, observedAtMs - saturatedForMs);
                String incidentId = "disk-io-" + TaskOutputCodec.sha256(
                        agentId + "\n" + device + "\n" + startedAtMs).substring(0, 20);
                if (current == null || !current.incidentId().equals(incidentId)) {
                    messages.add(message(
                            incidentId + ":firing",
                            incidentId,
                            agentId,
                            device,
                            "error",
                            "firing",
                            observedAtMs,
                            "Disk " + device + " IO utilization remained above "
                                    + format(thresholdPct) + "% for " + saturatedForMs + "ms",
                            hostState,
                            utilizationPct,
                            saturatedForMs,
                            startedAtMs));
                }
                active.put(device, new ActiveIncident(incidentId, startedAtMs));
            } else if (current != null && utilizationPct <= thresholdPct) {
                messages.add(message(
                        current.incidentId() + ":resolved",
                        current.incidentId(),
                        agentId,
                        device,
                        "info",
                        "resolved",
                        observedAtMs,
                        "Disk " + device + " IO utilization recovered to " + format(utilizationPct) + "%",
                        hostState,
                        utilizationPct,
                        0,
                        current.startedAtMs()));
                active.remove(device);
            }
        }
        return List.copyOf(messages);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> diskValues(Object value) {
        return value instanceof List<?> list
                ? list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .toList()
                : List.of();
    }

    private PulseMessage message(
            String eventId,
            String incidentId,
            String agentId,
            String subject,
            String severity,
            String status,
            long observedAtMs,
            String summary,
            Map<String, Object> hostState,
            double utilizationPct,
            long saturatedForMs,
            long startedAtMs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("device", subject);
        attributes.put("io_util_pct", utilizationPct);
        attributes.put("threshold", thresholdPct);
        attributes.put("saturated_for_ms", saturatedForMs);
        attributes.put("started_at_ms", startedAtMs);
        copy(attributes, hostState, "host", "ip", "cluster", "area", "zone");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", eventId);
        payload.put("incident_id", incidentId);
        payload.put("event_type", EVENT_TYPE);
        payload.put("source_id", SOURCE_ID);
        payload.put("subject", subject);
        payload.put("agent_id", agentId);
        payload.put("severity", severity);
        payload.put("status", status);
        payload.put("observed_at_ms", observedAtMs);
        payload.put("summary", summary);
        payload.put("attributes", attributes);
        payload.put("urgent", true);
        return new PulseMessage(eventId, MESSAGE_TYPE, 1, null, null, payload);
    }

    private static void copy(
            Map<String, Object> target,
            Map<String, Object> source,
            String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !value.toString().isBlank()) {
                target.put(key, value);
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record ActiveIncident(String incidentId, long startedAtMs) {
    }
}
