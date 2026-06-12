package com.bytedance.pulse;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class GroupHeartbeatCollector {
    private final Map<String, AgentHeartbeat> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> firstBufferedAtMs = new ConcurrentHashMap<>();
    private volatile long lastLeaderFlushAtMs = Long.MIN_VALUE;

    public void record(HeartbeatRequest request) {
        record(request, System.currentTimeMillis());
    }

    public void record(HeartbeatRequest request, long observedAtMs) {
        AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
        latest.merge(heartbeat.agentId(), heartbeat, GroupHeartbeatCollector::newer);
        firstBufferedAtMs.putIfAbsent(heartbeat.agentId(), observedAtMs);
    }

    public HeartbeatRequest batch(String groupId, HeartbeatRequest leaderHeartbeat, long nowMs, int sizeLimit) {
        AgentHeartbeat leader = leaderHeartbeat.toSingleAgentHeartbeat();
        record(leaderHeartbeat, nowMs);
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
        agents.forEach(agent -> firstBufferedAtMs.remove(agent.agentId()));
        lastLeaderFlushAtMs = nowMs;
        return new HeartbeatRequest(groupId, null, null, null, null, List.of(), agents);
    }

    public GroupFlushDecision flushDecision(
            HeartbeatRequest leaderHeartbeat,
            long nowMs,
            long heartbeatIntervalMs,
            long firstAgentGraceMs,
            int sizeLimit,
            int maxMessages,
            int maxBytes) {
        AgentHeartbeat leader = leaderHeartbeat.toSingleAgentHeartbeat();
        boolean urgent = latest.values().stream().anyMatch(GroupHeartbeatCollector::hasUrgentMessage);
        if (urgent) {
            return new GroupFlushDecision(true, GroupFlushTrigger.URGENT_MESSAGE);
        }
        long firstAgentDueMs = heartbeatIntervalMs + firstAgentGraceMs;
        boolean firstAgentDue = firstBufferedAtMs.entrySet().stream()
                .filter(entry -> !leader.agentId().equals(entry.getKey()))
                .anyMatch(entry -> nowMs - entry.getValue() >= firstAgentDueMs);
        if (firstAgentDue) {
            return new GroupFlushDecision(true, GroupFlushTrigger.FIRST_AGENT_DUE);
        }
        if (lastLeaderFlushAtMs == Long.MIN_VALUE || nowMs - lastLeaderFlushAtMs >= heartbeatIntervalMs) {
            return new GroupFlushDecision(true, GroupFlushTrigger.SELF_DUE);
        }
        if (latest.size() >= sizeLimit || messageCount() >= maxMessages || estimatedBytes() >= maxBytes) {
            return new GroupFlushDecision(true, GroupFlushTrigger.BATCH_FULL);
        }
        return new GroupFlushDecision(false, GroupFlushTrigger.NONE);
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

    private static boolean hasUrgentMessage(AgentHeartbeat heartbeat) {
        return heartbeat.messages().stream().anyMatch(message -> switch (message.type()) {
            case "reply.file_received", "reply.task_accepted", "reply.task_result", "reply.task_result_chunk" -> true;
            case "reply.task_output_append" -> message.payload() != null && Boolean.TRUE.equals(message.payload().get("urgent"));
            default -> message.payload() != null && "true".equals(String.valueOf(message.payload().get("urgent")));
        });
    }

    private int messageCount() {
        return latest.values().stream().mapToInt(heartbeat -> heartbeat.messages().size()).sum();
    }

    private int estimatedBytes() {
        return latest.values().stream()
                .flatMap(heartbeat -> heartbeat.messages().stream())
                .map(PulseMessage::payload)
                .filter(payload -> payload != null)
                .mapToInt(payload -> payload.toString().length())
                .sum();
    }

    private static java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }
}

enum GroupFlushTrigger {
    NONE,
    SELF_DUE,
    FIRST_AGENT_DUE,
    URGENT_MESSAGE,
    BATCH_FULL,
    PLAN_CHANGE,
    SHUTDOWN
}

record GroupFlushDecision(boolean shouldFlush, GroupFlushTrigger trigger) {}
