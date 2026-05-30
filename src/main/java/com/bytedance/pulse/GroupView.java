package com.bytedance.pulse;

import java.util.List;

public record GroupView(
        String groupId,
        String cluster,
        String area,
        String leaderAgentId,
        String leaderUrl,
        List<String> members,
        int size,
        int sizeLimit) {
    public GroupView {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
