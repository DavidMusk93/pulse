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
    private static final long ALIVE_CONFIRMATION_WINDOW_MS = 20_000;
    private static final int ALIVE_CONFIRMATION_THRESHOLD = 3;
    private static final long DEFAULT_HOST_SNAPSHOT_TTL_MS = 1_000;
    private static final long DEFAULT_GROUP_RECOMPUTE_INTERVAL_MS = 1_000;

    private final String coordinatorId;
    private final Clock clock;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private final Map<String, AgentGroupPlan> groupPlans = new ConcurrentHashMap<>();
    private final Map<String, GroupView> groupViews = new ConcurrentHashMap<>();
    private final int groupSizeLimit;
    private final int groupLeaderPort;
    private final RemoteTaskService taskService;
    private final long hostSnapshotTtlMs;
    private final long groupRecomputeIntervalMs;
    private final Object hostSnapshotLock = new Object();
    private final Object groupRecomputeLock = new Object();
    private volatile List<HostView> hostSnapshot = List.of();
    private volatile long hostSnapshotAtMs = Long.MIN_VALUE;
    private volatile boolean groupRecomputeDirty = true;
    private volatile long lastGroupRecomputeAtMs = Long.MIN_VALUE;

    public CoordinatorService(String coordinatorId, Clock clock) {
        this.coordinatorId = coordinatorId;
        this.clock = clock;
        this.groupSizeLimit = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_SIZE_LIMIT", "7")));
        this.groupLeaderPort = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
        this.taskService = new RemoteTaskService(clock);
        this.hostSnapshotTtlMs = positiveLong("PULSE_HOST_SNAPSHOT_TTL_MS", DEFAULT_HOST_SNAPSHOT_TTL_MS);
        this.groupRecomputeIntervalMs = positiveLong("PULSE_GROUP_RECOMPUTE_INTERVAL_MS", DEFAULT_GROUP_RECOMPUTE_INTERVAL_MS);
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
                taskService.handleReplies(agent.agentId(), agent.messages());
                agentResponses.add(new AgentHeartbeatResponse(agent.agentId(), acceptedSeq, List.of()));
            }
            maybeRecomputeGroups(now, false);
            agentResponses = agentResponses.stream()
                    .map(response -> new AgentHeartbeatResponse(
                            response.agentId(),
                            response.acceptedSeq(),
                            responseMessages(response.agentId())))
                    .toList();
            return HeartbeatResponse.batch(coordinatorId, agentResponses);
        }

        AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
        long acceptedSeq = merge(heartbeat, "direct", now, heartbeat.messages());
        taskService.handleReplies(heartbeat.agentId(), heartbeat.messages());
        maybeRecomputeGroups(now, false);
        return HeartbeatResponse.single(coordinatorId, acceptedSeq, responseMessages(heartbeat.agentId()));
    }

    public HeartbeatForwardResponse handleForward(HeartbeatForwardRequest request) {
        int accepted = 0;
        int merged = 0;
        String fallbackSource = request.sourceCoordinatorId() == null ? "peer" : request.sourceCoordinatorId();
        for (ForwardState state : request.states()) {
            List<PulseMessage> stateMessages = state.messages().stream()
                    .filter(PulseMessage::isStateMessage)
                    .toList();
            if (stateMessages.isEmpty()) {
                continue;
            }
            accepted++;
            String source = state.source() == null || state.source().isBlank() ? fallbackSource : state.source();
            boolean changed = mergeForwardState(state, source, stateMessages);
            if (changed) {
                merged++;
            }
        }
        maybeRecomputeGroups(clock.millis(), false);
        return new HeartbeatForwardResponse(true, coordinatorId, accepted, merged);
    }

    public List<HostView> hosts() {
        return hostSnapshot(clock.millis());
    }

    public List<GroupView> groups() {
        maybeRecomputeGroups(clock.millis(), true);
        return groupViews.values().stream()
                .sorted(Comparator.comparing(GroupView::groupId))
                .toList();
    }

    public TaskSnapshot taskSnapshot(String agentId) {
        return taskService.snapshot(agentId);
    }

    public TaskSnapshot enqueueTask(String agentId, String taskType) {
        return taskService.enqueue(agentId, taskType);
    }

    public TaskSnapshot keepCompletion(String agentId, String taskId) {
        return taskService.keepCompletion(agentId, taskId);
    }

    public TaskSnapshot popCompletion(String agentId, String taskId) {
        return taskService.popCompletion(agentId, taskId);
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

    private List<PulseMessage> responseMessages(String agentId) {
        if (groupRecomputeDirty && !groupPlans.containsKey(agentId)) {
            maybeRecomputeGroups(clock.millis(), true);
        }
        List<PulseMessage> messages = new ArrayList<>();
        messages.add(groupPlanMessage(agentId));
        taskService.nextCommand(agentId).ifPresent(messages::add);
        return messages;
    }

    private long merge(AgentHeartbeat heartbeat, String source, long observedAtMs, List<PulseMessage> messages) {
        NodeState incoming = NodeState.fromHeartbeat(heartbeat, source, observedAtMs, messages);
        states.merge(heartbeat.agentId(), incoming, NodeState::newer);
        markStateChanged();
        return states.get(heartbeat.agentId()).seq;
    }

    private boolean mergeForwardState(ForwardState state, String source, List<PulseMessage> messages) {
        NodeState incoming = NodeState.fromForwardState(state, source, messages);
        NodeState existing = states.get(state.agentId());
        NodeState selected = existing == null ? incoming : NodeState.newer(existing, incoming);
        states.put(state.agentId(), selected);
        markStateChanged();
        return selected == incoming;
    }

    private void markStateChanged() {
        groupRecomputeDirty = true;
        hostSnapshotAtMs = Long.MIN_VALUE;
    }

    private List<HostView> hostSnapshot(long now) {
        List<HostView> snapshot = hostSnapshot;
        if (!isStale(now, hostSnapshotAtMs, hostSnapshotTtlMs)) {
            return snapshot;
        }
        synchronized (hostSnapshotLock) {
            if (!isStale(now, hostSnapshotAtMs, hostSnapshotTtlMs)) {
                return hostSnapshot;
            }
            List<HostView> rebuilt = buildHosts(now, true);
            hostSnapshot = rebuilt;
            hostSnapshotAtMs = now;
            return rebuilt;
        }
    }

    private List<HostView> buildHosts(long now, boolean sorted) {
        List<HostView> hosts = new ArrayList<>(states.size());
        for (NodeState state : states.values()) {
            hosts.add(state.toHostView(now, groupPlans.getOrDefault(state.agentId, AgentGroupPlan.direct(state.agentId, groupSizeLimit))));
        }
        if (sorted) {
            hosts.sort(Comparator.comparing(HostView::cluster)
                    .thenComparing(HostView::status)
                    .thenComparing(HostView::agentId));
        }
        return List.copyOf(hosts);
    }

    private void maybeRecomputeGroups(long now, boolean force) {
        if (!force && (!groupRecomputeDirty || !isStale(now, lastGroupRecomputeAtMs, groupRecomputeIntervalMs))) {
            return;
        }
        synchronized (groupRecomputeLock) {
            if (!force && (!groupRecomputeDirty || !isStale(now, lastGroupRecomputeAtMs, groupRecomputeIntervalMs))) {
                return;
            }
            recomputeGroups(now);
            lastGroupRecomputeAtMs = now;
            groupRecomputeDirty = false;
        }
    }

    private void recomputeGroups(long now) {
        Map<String, List<HostView>> buckets = new HashMap<>();
        Map<String, AgentGroupPlan> previousPlans = Map.copyOf(groupPlans);
        for (HostView host : buildHosts(now, false)) {
            AgentGroupPlan previousPlan = previousPlans.get(host.agentId());
            if (!eligibleForGroup(host, previousPlan)) {
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
                HostView leader = groupMembers.stream()
                        .filter(member -> "alive".equals(member.status()))
                        .findFirst()
                        .orElse(groupMembers.get(0));
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
        hostSnapshotAtMs = Long.MIN_VALUE;
    }

    private static boolean eligibleForGroup(HostView host, AgentGroupPlan previousPlan) {
        if ("expired".equals(host.status())) {
            return false;
        }
        if ("alive".equals(host.status())) {
            return true;
        }
        return previousPlan != null && !"direct".equals(previousPlan.groupMode()) && !"direct".equals(previousPlan.groupId());
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

    private static long positiveLong(String key, long fallback) {
        try {
            long value = Long.parseLong(System.getenv().getOrDefault(key, String.valueOf(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean isStale(long now, long lastBuiltAtMs, long ttlMs) {
        return lastBuiltAtMs == Long.MIN_VALUE || now < lastBuiltAtMs || now - lastBuiltAtMs >= ttlMs;
    }

    private static final class NodeState {
        private final String agentId;
        private final long epoch;
        private final long seq;
        private final long ttlMs;
        private final long observedAtMs;
        private final long expireAtMs;
        private final List<HeartbeatConfirmation> confirmations;
        private final String source;
        private final Map<String, Object> state;

        private NodeState(
                String agentId,
                long epoch,
                long seq,
                long ttlMs,
                long observedAtMs,
                List<HeartbeatConfirmation> confirmations,
                String source,
                Map<String, Object> state) {
            this.agentId = agentId;
            this.epoch = epoch;
            this.seq = seq;
            this.ttlMs = ttlMs;
            this.observedAtMs = observedAtMs;
            this.expireAtMs = observedAtMs + ttlMs;
            this.confirmations = List.copyOf(confirmations);
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
                    List.of(new HeartbeatConfirmation(heartbeat.epoch(), heartbeat.seq(), observedAtMs)),
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
                    List.of(new HeartbeatConfirmation(state.epoch(), state.seq(), state.observedAtMs())),
                    source,
                    extractState(messages));
        }

        private static NodeState newer(NodeState left, NodeState right) {
            NodeState selected = left;
            if (right.epoch > left.epoch) {
                selected = right;
            } else if (right.epoch == left.epoch && right.seq > left.seq) {
                selected = right;
            }
            return selected.withConfirmations(mergeConfirmations(left.confirmations, right.confirmations, selected.observedAtMs));
        }

        private NodeState withConfirmations(List<HeartbeatConfirmation> nextConfirmations) {
            return new NodeState(agentId, epoch, seq, ttlMs, observedAtMs, nextConfirmations, source, state);
        }

        private HostView toHostView(long now, AgentGroupPlan plan) {
            int confirmations = recentConfirmations(now);
            String status = now > expireAtMs ? "expired" : confirmations >= ALIVE_CONFIRMATION_THRESHOLD ? "alive" : "warming";
            AgentGroupPlan debugPlan = plan == null ? AgentGroupPlan.direct(agentId, 1) : plan;
            return new HostView(
                    agentId,
                    epoch,
                    seq,
                    ttlMs,
                    observedAtMs,
                    expireAtMs,
                    Math.max(0, now - observedAtMs),
                    confirmations,
                    status,
                    source,
                    debugPlan.groupId(),
                    debugPlan.groupMode(),
                    debugPlan.leaderAgentId(),
                    debugPlan.leaderUrl(),
                    debugPlan.members().size(),
                    debugPlan.sizeLimit(),
                    value("host", agentId),
                    value("ip", "-"),
                    value("cluster", "unknown"),
                    value("area", "unknown"),
                    value("zone", "-"),
                    value("role", "-"),
                    value("load", "-"),
                    state);
        }

        private int recentConfirmations(long now) {
            long cutoff = now - ALIVE_CONFIRMATION_WINDOW_MS;
            return (int) confirmations.stream()
                    .filter(confirmation -> confirmation.observedAtMs() >= cutoff && confirmation.observedAtMs() <= now)
                    .map(HeartbeatConfirmation::key)
                    .distinct()
                    .count();
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

        private static List<HeartbeatConfirmation> mergeConfirmations(
                List<HeartbeatConfirmation> left,
                List<HeartbeatConfirmation> right,
                long referenceMs) {
            long cutoff = referenceMs - ALIVE_CONFIRMATION_WINDOW_MS;
            Map<String, HeartbeatConfirmation> merged = new LinkedHashMap<>();
            for (HeartbeatConfirmation confirmation : left) {
                if (confirmation.observedAtMs() >= cutoff) {
                    merged.put(confirmation.key(), confirmation);
                }
            }
            for (HeartbeatConfirmation confirmation : right) {
                if (confirmation.observedAtMs() >= cutoff) {
                    merged.put(confirmation.key(), confirmation);
                }
            }
            return merged.values().stream()
                    .sorted(Comparator.comparingLong(HeartbeatConfirmation::observedAtMs))
                    .toList();
        }
    }

    private record HeartbeatConfirmation(long epoch, long seq, long observedAtMs) {
        private String key() {
            return epoch + "/" + seq;
        }
    }
}
