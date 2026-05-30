package com.bytedance.pulse;

import java.util.List;

public record HeartbeatRequest(
        String groupId,
        String agentId,
        Long epoch,
        Long seq,
        Long ttlMs,
        List<PulseMessage> messages,
        List<AgentHeartbeat> agents) {
    public HeartbeatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    public boolean isBatch() {
        return !agents.isEmpty();
    }

    public AgentHeartbeat toSingleAgentHeartbeat() {
        if (agentId == null || epoch == null || seq == null || ttlMs == null) {
            throw new IllegalArgumentException("single heartbeat requires agent_id, epoch, seq and ttl_ms");
        }
        return new AgentHeartbeat(agentId, epoch, seq, ttlMs, messages);
    }
}
