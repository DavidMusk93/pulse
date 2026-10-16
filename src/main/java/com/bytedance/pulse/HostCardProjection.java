package com.bytedance.pulse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact runtime projection consumed by Coordinator host cards.
 *
 * <p>The host SSE stream deliberately omits nested {@code state}. Cards still need a
 * small, bounded runtime summary (agent footprint, disk IO hotspot, Tide workers and
 * active async tasks). Shipping the full state (dozens of disks per host) would cost
 * about 5x more bytes, so disks are reduced to count / max / hottest / saturated.
 */
final class HostCardProjection {
    static final int MAX_WORKERS = 8;
    static final int MAX_TASKS = 8;
    private static final List<String> WORKER_FIELDS = List.of(
            "pid", "cpu_percent", "user_cpu_percent", "sys_cpu_percent",
            "rss_kb", "mem_percent", "threads", "port1", "component_version");
    private static final List<String> TASK_FIELDS = List.of(
            "task_id", "task_type", "status");

    private HostCardProjection() {}

    static List<Map<String, Object>> project(List<HostView> hosts) {
        List<Map<String, Object>> cards = new ArrayList<>(hosts.size());
        for (HostView host : hosts) {
            cards.add(project(host));
        }
        return cards;
    }

    static Map<String, Object> project(HostView host) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("agent_id", host.agentId());
        Map<String, Object> state = host.state();
        if (state == null) {
            return card;
        }
        copy(state, card, "agent_rss_kb");
        copy(state, card, "agent_thread_count");
        copy(state, card, "agent_collect_ms");
        copy(state, card, "agent_send_ms");
        copy(state, card, "leader_collect_ms");
        Object disks = state.get("disks");
        if (disks instanceof List<?> list) {
            card.put("disks", diskSummary(list));
        }
        card.put("tide_workers", pickList(state.get("tide_workers"), WORKER_FIELDS, MAX_WORKERS));
        card.put("async_tasks", pickList(state.get("async_tasks"), TASK_FIELDS, MAX_TASKS));
        return card;
    }

    static Map<String, Object> diskSummary(List<?> disks) {
        int count = 0;
        int saturated = 0;
        double maxIo = -1;
        String hottest = null;
        for (Object item : disks) {
            if (!(item instanceof Map<?, ?> disk)) {
                continue;
            }
            count++;
            double io = number(disk.get("io_util_pct"));
            if (io > maxIo) {
                maxIo = io;
                hottest = disk.get("device") == null ? null : String.valueOf(disk.get("device"));
            }
            if (number(disk.get("saturated_for_ms")) > 0 || Boolean.TRUE.equals(disk.get("saturated"))) {
                saturated++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", count);
        summary.put("max_io_util_pct", maxIo < 0 ? 0 : Math.round(maxIo * 100.0) / 100.0);
        summary.put("hottest_device", hottest);
        summary.put("saturated", saturated);
        return summary;
    }

    private static List<Map<String, Object>> pickList(Object value, List<String> fields, int limit) {
        List<Map<String, Object>> picked = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return picked;
        }
        for (Object item : list) {
            if (picked.size() >= limit) {
                break;
            }
            if (!(item instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String field : fields) {
                Object fieldValue = source.get(field);
                if (fieldValue != null) {
                    entry.put(field, fieldValue);
                }
            }
            picked.add(entry);
        }
        return picked;
    }

    private static void copy(Map<String, Object> state, Map<String, Object> card, String key) {
        Object value = state.get(key);
        if (value != null) {
            card.put(key, value);
        }
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
