package com.bytedance.pulse;

import java.util.List;

public record AgentHeartbeat(
        String agentId,
        long epoch,
        long seq,
        long ttlMs,
        List<PulseMessage> messages) {
    public AgentHeartbeat {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
