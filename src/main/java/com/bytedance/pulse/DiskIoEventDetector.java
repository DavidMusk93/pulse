package com.bytedance.pulse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DiskIoEventDetector {
    static final String EVENT_TYPE = "disk.io_saturation";

    private final double thresholdPct;
    private final long sustainMs;
    private final Map<String, ActiveDiskEvent> active = new ConcurrentHashMap<>();

    DiskIoEventDetector(double thresholdPct, long sustainMs) {
        this.thresholdPct = thresholdPct > 0 ? thresholdPct : 95;
        this.sustainMs = Math.max(1, sustainMs);
    }

    synchronized List<HostEvent> evaluate(
            String agentId,
            long observedAtMs,
            Map<String, Object> state) {
        Object disksValue = state.get("disks");
        if (!(disksValue instanceof List<?> disks)) {
            return List.of();
        }
        List<HostEvent> generated = new ArrayList<>();
        for (Object diskValue : disks) {
            if (!(diskValue instanceof Map<?, ?> disk)) {
                continue;
            }
            String device = stringValue(disk.get("device"));
            if (device.isBlank()) {
                continue;
            }
            double utilizationPct = doubleValue(disk.get("io_util_pct"));
            long saturatedForMs = longValue(disk.get("saturated_for_ms"));
            String key = agentId + "\n" + device;
            ActiveDiskEvent current = active.get(key);
            if (utilizationPct > thresholdPct && saturatedForMs >= sustainMs) {
                long startedAtMs = Math.max(0, observedAtMs - saturatedForMs);
                String incidentId = incidentId(agentId, device, startedAtMs);
                HostEvent firing = new HostEvent(
                        incidentId + ":firing",
                        observedAtMs,
                        agentId,
                        "error",
                        EVENT_TYPE,
                        "Disk " + device + " IO utilization remained above "
                                + formatPercent(thresholdPct) + "% for " + saturatedForMs + "ms",
                        details(state, device, utilizationPct, saturatedForMs, startedAtMs, "firing", incidentId));
                if (current == null || !current.incidentId().equals(incidentId)) {
                    generated.add(firing);
                }
                active.put(key, new ActiveDiskEvent(incidentId, firing));
            } else if (current != null && utilizationPct <= thresholdPct) {
                HostEvent resolved = new HostEvent(
                        current.incidentId() + ":resolved",
                        observedAtMs,
                        agentId,
                        "info",
                        EVENT_TYPE,
                        "Disk " + device + " IO utilization recovered to " + formatPercent(utilizationPct) + "%",
                        details(state, device, utilizationPct, 0, current.startedAtMs(), "resolved", current.incidentId()));
                active.remove(key);
                generated.add(resolved);
            }
        }
        return List.copyOf(generated);
    }

    List<HostEvent> activeEvents() {
        return active.values().stream()
                .map(ActiveDiskEvent::event)
                .sorted((left, right) -> left.eventId().compareTo(right.eventId()))
                .toList();
    }

    private static Map<String, Object> details(
            Map<String, Object> state,
            String device,
            double utilizationPct,
            long saturatedForMs,
            long startedAtMs,
            String status,
            String incidentId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incident_id", incidentId);
        details.put("status", status);
        details.put("device", device);
        details.put("io_util_pct", utilizationPct);
        details.put("saturated_for_ms", saturatedForMs);
        details.put("started_at_ms", startedAtMs);
        details.put("host", stringValue(state.get("host")));
        details.put("ip", stringValue(state.get("ip")));
        details.put("cluster", stringValue(state.get("cluster")));
        details.put("area", stringValue(state.get("area")));
        return Map.copyOf(details);
    }

    private static String incidentId(String agentId, String device, long startedAtMs) {
        return "disk-io-" + TaskOutputCodec.sha256(agentId + "\n" + device + "\n" + startedAtMs).substring(0, 20);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private record ActiveDiskEvent(String incidentId, HostEvent event) {
        long startedAtMs() {
            return longValue(event.details().get("started_at_ms"));
        }
    }
}
