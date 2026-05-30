package com.bytedance.pulse;

import java.util.List;

public record AgentHeartbeatResponse(
        String agentId,
        long acceptedSeq,
        List<PulseMessage> messages) {
    public AgentHeartbeatResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
