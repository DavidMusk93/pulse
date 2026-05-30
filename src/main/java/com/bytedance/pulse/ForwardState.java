package com.bytedance.pulse;

import java.util.List;

public record ForwardState(
        String agentId,
        long epoch,
        long seq,
        long ttlMs,
        long observedAtMs,
        List<PulseMessage> messages) {
    public ForwardState {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
