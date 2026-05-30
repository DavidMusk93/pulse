package com.bytedance.pulse;

import java.time.Clock;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CoordinatorService {
    private final String coordinatorId;
    private final Clock clock;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private final Map<String, AgentGroupPlan> groupPlans = new ConcurrentHashMap<>();
    private final Map<String, GroupView> groupViews = new ConcurrentHashMap<>();
    private final int groupSizeLimit;
    private final int groupLeaderPort;

    public CoordinatorService(String coordinatorId, Clock clock) {
        this.coordinatorId = coordinatorId;
        this.clock = clock;
        this.groupSizeLimit = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_SIZE_LIMIT", "7")));
        this.groupLeaderPort = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
    }

    public String coordinatorId() {
        return coordinatorId;
    }

    public HeartbeatResponse handleHeartbeat(HeartbeatRequest request) {
        long now = clock.millis();
        if (request.isBatch()) {
            List<AgentHeartbeatResponse> agentResponses = new ArrayList<>();
            String source = request.groupId() == null || request.groupId().isBlank() ? "group" : request.groupId();
            for (AgentHeartbeat agent : request.agents()) {
                long acceptedSeq = merge(agent, source, now, agent.messages());
                agentResponses.add(new AgentHeartbeatResponse(agent.agentId(), acceptedSeq, List.of()));
            }
            recomputeGroups(now);
            agentResponses = agentResponses.stream()
                    .map(response -> new AgentHeartbeatResponse(
                            response.agentId(),
                            response.acceptedSeq(),
                            List.of(groupPlanMessage(response.agentId()))))
                    .toList();
            return HeartbeatResponse.batch(coordinatorId, agentResponses);
        }

        AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
        long acceptedSeq = merge(heartbeat, "direct", now, heartbeat.messages());
        recomputeGroups(now);
        return HeartbeatResponse.single(coordinatorId, acceptedSeq, List.of(groupPlanMessage(heartbeat.agentId())));
    }

    public HeartbeatForwardResponse handleForward(HeartbeatForwardRequest request) {
        int accepted = 0;
        int merged = 0;
        String source = request.sourceCoordinatorId() == null ? "peer" : request.sourceCoordinatorId();
        for (ForwardState state : request.states()) {
            List<PulseMessage> stateMessages = state.messages().stream()
                    .filter(PulseMessage::isStateMessage)
                    .toList();
            if (stateMessages.isEmpty()) {
                continue;
            }
            accepted++;
            boolean changed = mergeForwardState(state, source, stateMessages);
            if (changed) {
                merged++;
            }
        }
        recomputeGroups(clock.millis());
        return new HeartbeatForwardResponse(true, coordinatorId, accepted, merged);
    }

    public List<HostView> hosts() {
        long now = clock.millis();
        return states.values().stream()
                .map(state -> state.toHostView(now))
                .sorted(Comparator.comparing(HostView::cluster)
                        .thenComparing(HostView::status)
                        .thenComparing(HostView::agentId))
                .toList();
    }

    public List<GroupView> groups() {
        return groupViews.values().stream()
                .sorted(Comparator.comparing(GroupView::groupId))
                .toList();
    }

    private AgentGroupPlan agentPlan(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agent_id is required");
        }
        return groupPlans.getOrDefault(agentId, AgentGroupPlan.direct(agentId, groupSizeLimit));
    }

    private PulseMessage groupPlanMessage(String agentId) {
        AgentGroupPlan plan = agentPlan(agentId);
        return new PulseMessage(
                "group-plan-" + agentId + "-" + clock.millis(),
                "cmd.group_plan",
                1,
                null,
                clock.millis() + 30_000,
                Map.of(
                        "agent_id", plan.agentId(),
                        "group_id", plan.groupId(),
                        "group_mode", plan.groupMode(),
                        "leader_agent_id", plan.leaderAgentId(),
                        "leader_url", plan.leaderUrl(),
                        "members", plan.members(),
                        "cluster", plan.cluster(),
                        "area", plan.area(),
                        "size_limit", plan.sizeLimit()));
    }

    private long merge(AgentHeartbeat heartbeat, String source, long observedAtMs, List<PulseMessage> messages) {
        NodeState incoming = NodeState.fromHeartbeat(heartbeat, source, observedAtMs, messages);
        states.merge(heartbeat.agentId(), incoming, NodeState::newer);
        return states.get(heartbeat.agentId()).seq;
    }

    private boolean mergeForwardState(ForwardState state, String source, List<PulseMessage> messages) {
        NodeState incoming = NodeState.fromForwardState(state, source, messages);
        NodeState existing = states.get(state.agentId());
        NodeState selected = existing == null ? incoming : NodeState.newer(existing, incoming);
        states.put(state.agentId(), selected);
        return selected == incoming;
    }

    private void recomputeGroups(long now) {
        Map<String, List<HostView>> buckets = new HashMap<>();
        for (HostView host : hosts()) {
            if (!"alive".equals(host.status())) {
                continue;
            }
            String cluster = blankToUnknown(host.cluster());
            String area = blankToUnknown(host.area());
            buckets.computeIfAbsent(cluster + "\n" + area, ignored -> new ArrayList<>()).add(host);
        }

        Map<String, AgentGroupPlan> nextPlans = new HashMap<>();
        Map<String, GroupView> nextGroups = new HashMap<>();
        for (Map.Entry<String, List<HostView>> entry : buckets.entrySet()) {
            String[] key = entry.getKey().split("\n", 2);
            String cluster = key[0];
            String area = key.length > 1 ? key[1] : "unknown";
            List<HostView> members = entry.getValue().stream()
                    .sorted(Comparator.comparing(CoordinatorService::ipSortKey).thenComparing(HostView::agentId))
                    .toList();
            for (int start = 0; start < members.size(); start += groupSizeLimit) {
                int end = Math.min(start + groupSizeLimit, members.size());
                List<HostView> groupMembers = members.subList(start, end);
                int shard = start / groupSizeLimit;
                String groupId = "%s/%s/%03d".formatted(cluster, area, shard);
                HostView leader = groupMembers.get(0);
                String leaderUrl = leaderUrl(leader);
                List<String> memberIds = groupMembers.stream().map(HostView::agentId).toList();
                nextGroups.put(groupId, new GroupView(
                        groupId,
                        cluster,
                        area,
                        leader.agentId(),
                        leaderUrl,
                        memberIds,
                        memberIds.size(),
                        groupSizeLimit));
                for (HostView member : groupMembers) {
                    String mode = member.agentId().equals(leader.agentId()) ? "leader" : "follower";
                    nextPlans.put(member.agentId(), new AgentGroupPlan(
                            member.agentId(),
                            groupId,
                            mode,
                            leader.agentId(),
                            leaderUrl,
                            memberIds,
                            cluster,
                            area,
                            groupSizeLimit));
                }
            }
        }

        groupPlans.clear();
        groupPlans.putAll(nextPlans);
        groupViews.clear();
        groupViews.putAll(nextGroups);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() || "-".equals(value) ? "unknown" : value;
    }

    private String leaderUrl(HostView leader) {
        String ip = leader.ip();
        if (ip == null || ip.isBlank() || "-".equals(ip)) {
            return "";
        }
        if (ip.contains(":")) {
            return "http://[%s]:%d".formatted(ip, groupLeaderPort);
        }
        return "http://%s:%d".formatted(ip, groupLeaderPort);
    }

    private static String ipSortKey(HostView host) {
        String ip = host.ip();
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append("%02x".formatted(value & 0xff));
            }
            return "0/" + builder;
        } catch (Exception ignored) {
            return "1/" + host.agentId();
        }
    }

    private static final class NodeState {
        private final String agentId;
        private final long epoch;
        private final long seq;
        private final long ttlMs;
        private final long observedAtMs;
        private final long expireAtMs;
        private final String source;
        private final Map<String, Object> state;

        private NodeState(
                String agentId,
                long epoch,
                long seq,
                long ttlMs,
                long observedAtMs,
                String source,
                Map<String, Object> state) {
            this.agentId = agentId;
            this.epoch = epoch;
            this.seq = seq;
            this.ttlMs = ttlMs;
            this.observedAtMs = observedAtMs;
            this.expireAtMs = observedAtMs + ttlMs;
            this.source = source;
            this.state = Map.copyOf(state);
        }

        private static NodeState fromHeartbeat(
                AgentHeartbeat heartbeat,
                String source,
                long observedAtMs,
                List<PulseMessage> messages) {
            return new NodeState(
                    heartbeat.agentId(),
                    heartbeat.epoch(),
                    heartbeat.seq(),
                    heartbeat.ttlMs(),
                    observedAtMs,
                    source,
                    extractState(messages));
        }

        private static NodeState fromForwardState(
                ForwardState state,
                String source,
                List<PulseMessage> messages) {
            return new NodeState(
                    state.agentId(),
                    state.epoch(),
                    state.seq(),
                    state.ttlMs(),
                    state.observedAtMs(),
                    source,
                    extractState(messages));
        }

        private static NodeState newer(NodeState left, NodeState right) {
            if (right.epoch > left.epoch) {
                return right;
            }
            if (right.epoch == left.epoch && right.seq > left.seq) {
                return right;
            }
            return left;
        }

        private HostView toHostView(long now) {
            String status = now <= expireAtMs ? "alive" : "expired";
            return new HostView(
                    agentId,
                    epoch,
                    seq,
                    ttlMs,
                    observedAtMs,
                    expireAtMs,
                    status,
                    source,
                    value("host", agentId),
                    value("ip", "-"),
                    value("cluster", "unknown"),
                    value("area", "unknown"),
                    value("zone", "-"),
                    value("role", "-"),
                    value("load", "-"),
                    state);
        }

        private String value(String key, String fallback) {
            Object value = state.get(key);
            if (value == null || value.toString().isBlank()) {
                return fallback;
            }
            return value.toString();
        }

        private static Map<String, Object> extractState(List<PulseMessage> messages) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (PulseMessage message : messages) {
                if (message.isStateMessage() && message.payload() != null) {
                    merged.putAll(message.payload());
                }
            }
            return merged;
        }
    }
}
