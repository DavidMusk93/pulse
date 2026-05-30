package com.bytedance.pulse;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class GroupHeartbeatCollector {
    private final Map<String, AgentHeartbeat> latest = new ConcurrentHashMap<>();

    public void record(HeartbeatRequest request) {
        AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
        latest.merge(heartbeat.agentId(), heartbeat, GroupHeartbeatCollector::newer);
    }

    public HeartbeatRequest batch(String groupId, HeartbeatRequest leaderHeartbeat, long nowMs, int sizeLimit) {
        AgentHeartbeat leader = leaderHeartbeat.toSingleAgentHeartbeat();
        record(leaderHeartbeat);
        List<AgentHeartbeat> agents = new ArrayList<>();
        agents.add(leader);
        latest.values().stream()
                .filter(heartbeat -> nowMs <= heartbeat.messages().stream()
                        .filter(PulseMessage::isStateMessage)
                        .map(PulseMessage::payload)
                        .filter(payload -> payload != null)
                        .map(payload -> payload.get("agent_time_ms"))
                        .findFirst()
                        .map(Object::toString)
                        .flatMap(GroupHeartbeatCollector::parseLong)
                        .map(agentTime -> agentTime + heartbeat.ttlMs())
                        .orElse(nowMs + heartbeat.ttlMs()))
                .filter(heartbeat -> !heartbeat.agentId().equals(leader.agentId()))
                .sorted(Comparator.comparing(AgentHeartbeat::agentId))
                .limit(Math.max(0, sizeLimit - 1L))
                .forEach(agents::add);
        return new HeartbeatRequest(groupId, null, null, null, null, List.of(), agents);
    }

    public int size() {
        return latest.size();
    }

    private static AgentHeartbeat newer(AgentHeartbeat left, AgentHeartbeat right) {
        if (right.epoch() > left.epoch()) {
            return right;
        }
        if (right.epoch() == left.epoch() && right.seq() > left.seq()) {
            return right;
        }
        return left;
    }

    private static java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }
}
