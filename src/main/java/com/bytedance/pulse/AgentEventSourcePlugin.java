package com.bytedance.pulse;

import java.util.List;
import java.util.Map;

/**
 * Agent-side event source SPI. Implementations publish Pulse messages through the normal heartbeat path.
 */
public interface AgentEventSourcePlugin {
    Descriptor descriptor();

    List<PulseMessage> evaluate(Context context);

    record Descriptor(
            String sourceId,
            String eventType,
            String name,
            String description) {
    }

    record Context(
            String agentId,
            long observedAtMs,
            Map<String, Object> heartbeatState) {
        public Context {
            heartbeatState = heartbeatState == null ? Map.of() : Map.copyOf(heartbeatState);
        }
    }
}
