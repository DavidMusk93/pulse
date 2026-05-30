package com.bytedance.pulse;

import java.util.Map;

public record PulseMessage(
        String messageId,
        String type,
        int version,
        String replyTo,
        Long deadlineMs,
        Map<String, Object> payload) {
    public boolean isStateMessage() {
        return type != null && type.startsWith("state.");
    }
}
