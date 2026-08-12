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
    static final double DEFAULT_THRESHOLD_PCT = 95;
    static final long DEFAULT_SUSTAIN_MS = 10_000;
    static final long DEFAULT_SUSTAIN_SECONDS = DEFAULT_SUSTAIN_MS / 1_000;

    private volatile boolean enabled = true;
    private volatile double thresholdPct;
    private volatile long sustainMs;
    private final Map<String, ActiveIncident> active = new ConcurrentHashMap<>();

    AgentDiskIoEventEmitter(double thresholdPct, long sustainMs) {
        this.thresholdPct = validThreshold(thresholdPct, DEFAULT_THRESHOLD_PCT);
        this.sustainMs = validSustain(sustainMs, DEFAULT_SUSTAIN_MS);
    }

    @Override
    public Descriptor descriptor() {
        return new Descriptor(
                SOURCE_ID,
                EVENT_TYPE,
                "Disk IO saturation",
                "Publishes firing/resolved events after sustained physical disk IO saturation.",
                configFields());
    }

    static List<EventPlugin.ConfigField> configFields() {
        return List.of(
                        field(
                                "threshold_pct",
                                "IO 利用率门槛 (%)",
                                DEFAULT_THRESHOLD_PCT,
                                "仅当 io_util_pct 严格大于此值时累计持续时间"),
                        field(
                                "sustain_seconds",
                                "持续时间 (秒)",
                                DEFAULT_SUSTAIN_SECONDS,
                                "连续超过门槛达到此秒数后生成事件"));
    }

    @Override
    public synchronized void configure(boolean nextEnabled, Map<String, Object> config) {
        double nextThreshold = validThreshold(
                number(config.get("threshold_pct")),
                DEFAULT_THRESHOLD_PCT);
        long sustainSeconds = (long) number(config.get("sustain_seconds"));
        long nextSustain = validSustain(
                secondsToMillis(sustainSeconds),
                DEFAULT_SUSTAIN_MS);
        if (enabled != nextEnabled
                || Double.compare(thresholdPct, nextThreshold) != 0
                || sustainMs != nextSustain) {
            active.clear();
        }
        enabled = nextEnabled;
        thresholdPct = nextThreshold;
        sustainMs = nextSustain;
    }

    @Override
    public synchronized List<PulseMessage> evaluate(Context context) {
        if (!enabled) {
            return List.of();
        }
        double currentThresholdPct = thresholdPct;
        long currentSustainMs = sustainMs;
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
            if (utilizationPct > currentThresholdPct && saturatedForMs >= currentSustainMs) {
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
                                    + format(currentThresholdPct) + "% for " + saturatedForMs + "ms",
                            hostState,
                            utilizationPct,
                            saturatedForMs,
                            startedAtMs));
                }
                active.put(device, new ActiveIncident(incidentId, startedAtMs));
            } else if (current != null && utilizationPct <= currentThresholdPct) {
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

    boolean enabled() {
        return enabled;
    }

    double thresholdPct() {
        return thresholdPct;
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

    private static EventPlugin.ConfigField field(
            String key,
            String label,
            Object defaultValue,
            String description) {
        return new EventPlugin.ConfigField(
                key,
                label,
                "number",
                true,
                false,
                defaultValue,
                List.of(),
                description);
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

    private static double validThreshold(double value, double fallback) {
        return value > 0 && value <= 100 ? value : fallback;
    }

    private static long validSustain(long value, long fallback) {
        return value >= 1_000 ? value : fallback;
    }

    private static long secondsToMillis(long seconds) {
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1_000) {
            return 0;
        }
        return seconds * 1_000;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record ActiveIncident(String incidentId, long startedAtMs) {
    }
}
