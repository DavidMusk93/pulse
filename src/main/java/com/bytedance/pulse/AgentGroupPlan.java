package com.bytedance.pulse;

import java.util.List;

public record AgentGroupPlan(
        String agentId,
        String groupId,
        String groupMode,
        String leaderAgentId,
        String leaderUrl,
        List<String> members,
        String cluster,
        String area,
        int sizeLimit) {
    public AgentGroupPlan {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public static AgentGroupPlan direct(String agentId) {
        String safeAgentId = agentId == null || agentId.isBlank() ? "unknown" : agentId;
        return new AgentGroupPlan(
                safeAgentId,
                "direct",
                "direct",
                safeAgentId,
                "",
                List.of(safeAgentId),
                "unknown",
                "unknown",
                1);
    }
}
