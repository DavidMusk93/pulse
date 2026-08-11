package com.bytedance.pulse;

import java.util.List;
import java.util.Map;

final class PeriodicDigestGatePlugin implements EventPlugin.Gate {
    static final String TYPE = "periodic_digest";
    static final long MIN_INTERVAL_MS = 5 * 60_000L;
    static final long DEFAULT_INTERVAL_MS = 15 * 60_000L;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "gate",
                "Periodic digest",
                "Publishes active incidents at a bounded interval and one recovery digest.",
                List.of(
                        new ConfigField(
                                "interval_ms",
                                "发布周期 (ms)",
                                "number",
                                true,
                                false,
                                DEFAULT_INTERVAL_MS,
                                List.of(),
                                "最短 5 分钟，持续事件按该周期重新发布"),
                        new ConfigField(
                                "publish_recovery",
                                "发布恢复摘要",
                                "boolean",
                                false,
                                false,
                                true,
                                List.of(),
                                "最后一个活动事件恢复后发布一次恢复摘要")));
    }

    @Override
    public GateDecision evaluate(
            Map<String, Object> config,
            GateState state,
            List<Event> activeEvents,
            long nowMs) {
        long intervalMs = Math.max(MIN_INTERVAL_MS, longValue(config.get("interval_ms"), DEFAULT_INTERVAL_MS));
        if (state.lastAttemptAtMs() > 0 && nowMs - state.lastAttemptAtMs() < intervalMs) {
            return GateDecision.skip("interval");
        }
        if (!activeEvents.isEmpty()) {
            return GateDecision.dispatch(state.lastSuccessAtMs() == 0 ? "initial" : "reminder");
        }
        boolean publishRecovery = booleanValue(config.get("publish_recovery"), true);
        if (publishRecovery && (state.recoveryPending() || state.lastActiveCount() > 0)) {
            return GateDecision.dispatch("recovery");
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

    private static boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }
}
