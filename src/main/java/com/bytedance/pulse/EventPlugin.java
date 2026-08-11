package com.bytedance.pulse;

import java.util.List;
import java.util.Map;

/**
 * Public SPI for EventBus extensions loaded through {@link java.util.ServiceLoader}.
 */
public interface EventPlugin {
    PluginDescriptor descriptor();

    interface Source extends EventPlugin {
        default boolean supports(String inputType) {
            return "heartbeat".equals(inputType);
        }

        List<Event> evaluate(
                String sourceId,
                String eventType,
                String severity,
                Map<String, Object> config,
                Observation observation);

        default void reset(String sourceId) {
        }
    }

    interface Gate extends EventPlugin {
        GateDecision evaluate(
                Map<String, Object> config,
                GateState state,
                List<Event> activeEvents,
                long nowMs);
    }

    interface Sink extends EventPlugin {
        DeliveryReceipt deliver(Map<String, Object> config, Delivery delivery) throws Exception;
    }

    record PluginDescriptor(
            String type,
            String kind,
            String name,
            String description,
            List<ConfigField> configFields) {
        public PluginDescriptor {
            configFields = configFields == null ? List.of() : List.copyOf(configFields);
        }
    }

    record ConfigField(
            String key,
            String label,
            String type,
            boolean required,
            boolean secret,
            Object defaultValue,
            List<String> options,
            String description) {
        public ConfigField {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record Observation(
            String agentId,
            long observedAtMs,
            Map<String, Object> state) {
        public Observation {
            state = state == null ? Map.of() : Map.copyOf(state);
        }
    }

    record Event(
            String eventId,
            String incidentId,
            String eventType,
            String sourceId,
            String subject,
            String agentId,
            String severity,
            String status,
            long observedAtMs,
            String summary,
            Map<String, Object> attributes) {
        public Event {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    record GateState(
            long lastAttemptAtMs,
            long lastSuccessAtMs,
            int lastActiveCount,
            boolean recoveryPending) {
    }

    record GateDecision(boolean due, String reason) {
        public static GateDecision skip(String reason) {
            return new GateDecision(false, reason);
        }

        public static GateDecision dispatch(String reason) {
            return new GateDecision(true, reason);
        }
    }

    record Delivery(
            String routeId,
            String sinkId,
            String idempotencyKey,
            long createdAtMs,
            boolean recovery,
            List<Event> events) {
        public Delivery {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    record DeliveryReceipt(
            String upstreamId,
            String format,
            int deliveredEvents,
            Map<String, Object> metadata) {
        public DeliveryReceipt {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
