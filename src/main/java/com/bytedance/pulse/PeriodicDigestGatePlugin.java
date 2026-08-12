package com.bytedance.pulse;

import java.util.List;
import java.util.Map;

final class PeriodicDigestGatePlugin implements EventPlugin.Gate {
    static final String TYPE = "periodic_digest";
    static final long MIN_INTERVAL_SECONDS = 5 * 60L;
    static final long DEFAULT_INTERVAL_SECONDS = 15 * 60L;
    static final long MIN_INTERVAL_MS = MIN_INTERVAL_SECONDS * 1_000;
    static final long DEFAULT_INTERVAL_MS = DEFAULT_INTERVAL_SECONDS * 1_000;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "gate",
                "Periodic digest",
                "Publishes active incidents at a bounded interval and one recovery digest.",
                List.of(
                        new ConfigField(
                                "interval_seconds",
                                "发布周期 (秒)",
                                "number",
                                true,
                                false,
                                DEFAULT_INTERVAL_SECONDS,
                                List.of(),
                                "最短 300 秒；失败批次在下一周期重试")));
    }

    @Override
    public GateDecision evaluate(
            Map<String, Object> config,
            GateState state,
            List<Event> activeEvents,
            long nowMs) {
        long intervalSeconds = Math.max(
                MIN_INTERVAL_SECONDS,
                longValue(config.get("interval_seconds"), DEFAULT_INTERVAL_SECONDS));
        long intervalMs = intervalSeconds * 1_000;
        if (state.lastAttemptAtMs() > 0 && nowMs - state.lastAttemptAtMs() < intervalMs) {
            return GateDecision.skip("interval");
        }
        if (!activeEvents.isEmpty()) {
            return GateDecision.dispatch(state.lastSuccessAtMs() == 0 ? "initial" : "retry");
        }
        return GateDecision.skip("idle");
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
