package com.bytedance.pulse;

import java.util.List;

public record HeartbeatResponse(
        boolean ok,
        String coordinatorId,
        Long acceptedSeq,
        List<PulseMessage> messages,
        List<AgentHeartbeatResponse> agents) {
    public HeartbeatResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    public static HeartbeatResponse single(String coordinatorId, long acceptedSeq, List<PulseMessage> messages) {
        return new HeartbeatResponse(true, coordinatorId, acceptedSeq, messages, List.of());
    }

    public static HeartbeatResponse batch(String coordinatorId, List<AgentHeartbeatResponse> agents) {
        return new HeartbeatResponse(true, coordinatorId, null, List.of(), agents);
    }
}
