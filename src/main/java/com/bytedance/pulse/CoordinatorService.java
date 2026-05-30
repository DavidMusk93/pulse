package com.bytedance.pulse;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CoordinatorService {
    private final String coordinatorId;
    private final Clock clock;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();

    public CoordinatorService(String coordinatorId, Clock clock) {
        this.coordinatorId = coordinatorId;
        this.clock = clock;
    }

    public String coordinatorId() {
        return coordinatorId;
    }

    public HeartbeatResponse handleHeartbeat(HeartbeatRequest request) {
        if (request.isBatch()) {
            List<AgentHeartbeatResponse> agentResponses = new ArrayList<>();
            String source = request.groupId() == null || request.groupId().isBlank() ? "group" : request.groupId();
            for (AgentHeartbeat agent : request.agents()) {
                long acceptedSeq = merge(agent, source, clock.millis(), agent.messages());
                agentResponses.add(new AgentHeartbeatResponse(agent.agentId(), acceptedSeq, List.of()));
            }
            return HeartbeatResponse.batch(coordinatorId, agentResponses);
        }

        AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
        long acceptedSeq = merge(heartbeat, "direct", clock.millis(), heartbeat.messages());
        return HeartbeatResponse.single(coordinatorId, acceptedSeq, List.of());
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
        return new HeartbeatForwardResponse(true, coordinatorId, accepted, merged);
    }

    public List<HostView> hosts() {
        long now = clock.millis();
        return states.values().stream()
                .map(state -> state.toHostView(now))
                .sorted(Comparator.comparing(HostView::status).thenComparing(HostView::agentId))
                .toList();
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
