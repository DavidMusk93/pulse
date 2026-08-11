package com.bytedance.pulse;

import java.util.List;
import java.util.Map;

/**
 * Agent-side event source SPI. Implementations publish Pulse messages through the normal heartbeat path.
 */
public interface AgentEventSourcePlugin {
    Descriptor descriptor();

    default void configure(boolean enabled, Map<String, Object> config) {
    }

    List<PulseMessage> evaluate(Context context);

    record Descriptor(
            String sourceId,
            String eventType,
            String name,
            String description,
            List<EventPlugin.ConfigField> configFields) {
        public Descriptor {
            configFields = configFields == null ? List.of() : List.copyOf(configFields);
        }
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
