package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class CoordinatorService {
    private static final long ALIVE_CONFIRMATION_WINDOW_MS = 20_000;
    private static final int ALIVE_CONFIRMATION_THRESHOLD = 3;
    private static final int MIN_AGENTS_FOR_GROUP_LEADER = 5;
    private static final long DEFAULT_HOST_SNAPSHOT_TTL_MS = 1_000;
    private static final long DEFAULT_GROUP_RECOMPUTE_INTERVAL_MS = 1_000;
    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();

    private final String coordinatorId;
    private final Clock clock;
    private final Map<String, NodeState> states = new ConcurrentHashMap<>();
    private final Map<String, AgentGroupPlan> groupPlans = new ConcurrentHashMap<>();
    private final Map<String, GroupView> groupViews = new ConcurrentHashMap<>();
    private final Map<String, Long> groupMetricObservedAt = new ConcurrentHashMap<>();
    private final int groupLeaderPort;
    private final RemoteTaskService taskService;
    private final MetricStorage metricStorage;
    private final long hostSnapshotTtlMs;
    private final long groupRecomputeIntervalMs;
    private final Object hostSnapshotLock = new Object();
    private final Object groupRecomputeLock = new Object();
    private volatile List<HostView> hostSnapshot = List.of();
    private volatile long hostSnapshotAtMs = Long.MIN_VALUE;
    private volatile boolean groupRecomputeDirty = true;
    private volatile long lastGroupRecomputeAtMs = Long.MIN_VALUE;

    public CoordinatorService(String coordinatorId, Clock clock) {
        this(coordinatorId, clock, null);
    }

    CoordinatorService(String coordinatorId, Clock clock, MetricStorage metricStorage) {
        this.coordinatorId = coordinatorId;
        this.clock = clock;
        this.groupLeaderPort = Integer.parseInt(System.getenv().getOrDefault("PULSE_GROUP_PORT", "9977"));
        this.taskService = new RemoteTaskService(clock);
        this.metricStorage = metricStorage;
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
            int accepted = 0;
            for (AgentHeartbeat agent : request.agents()) {
                long acceptedSeq = merge(agent, source, now, agent.messages(), coordinatorId);
                taskService.handleReplies(agent.agentId(), agent.messages());
                agentResponses.add(new AgentHeartbeatResponse(agent.agentId(), acceptedSeq, List.of()));
                accepted++;
            }
            maybeRecomputeGroups(now, false);
            agentResponses = agentResponses.stream()
                    .map(response -> new AgentHeartbeatResponse(
                            response.agentId(),
                            response.acceptedSeq(),
                            responseMessages(response.agentId())))
                    .toList();
            writeGroupLeaderMetric(request, source, now, accepted, agentResponses);
            return HeartbeatResponse.batch(coordinatorId, agentResponses);
        }

        AgentHeartbeat heartbeat = request.toSingleAgentHeartbeat();
        long acceptedSeq = merge(heartbeat, "direct", now, heartbeat.messages(), coordinatorId);
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
            accepted++;
            String source = state.source() == null || state.source().isBlank() ? fallbackSource : state.source();
            boolean changed = mergeForwardState(state, source, request.sourceCoordinatorId(), stateMessages);
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

    public List<MetricCatalogItem> metricCatalog() {
        return LocalMetricStorage.catalog();
    }

    public MetricQueryResult queryMetrics(MetricQuery query) throws Exception {
        if (metricStorage == null) {
            throw new IllegalArgumentException("metric storage is disabled");
        }
        return metricStorage.queryRange(query);
    }

    public MetricStorageHealth metricStorageHealth() {
        if (metricStorage == null) {
            return new MetricStorageHealth("disabled", 0, 0, 0, 0, 0, 0, 0, 0, 0, "metric storage is disabled");
        }
        return metricStorage.health();
    }

    public List<HostEvent> queryMetricEvents(MetricEventQuery query) throws Exception {
        if (metricStorage == null) {
            throw new IllegalArgumentException("metric storage is disabled");
        }
        return metricStorage.queryEvents(query);
    }

    public TaskSnapshot taskSnapshot(String agentId) {
        return taskService.snapshot(agentId);
    }

    public Optional<String> agentCoordinatorId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        NodeState state = states.get(agentId);
        return state == null || state.coordinatorId.isBlank() ? Optional.empty() : Optional.of(state.coordinatorId);
    }

    public TaskSnapshot enqueueTask(String agentId, String taskType) {
        return taskService.enqueue(agentId, taskType);
    }

    public TaskSnapshot enqueueTask(String agentId, String taskType, List<String> args) {
        return taskService.enqueue(agentId, taskType, args);
    }

    public TaskSnapshot enqueueFilePut(
            String agentId,
            String fileName,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            String targetDir,
            String fileRole) {
        return taskService.enqueueFilePut(agentId, fileName, contentBase64, contentSha256, contentBytes, targetDir, fileRole);
    }

    public TaskSnapshot enqueueShellScript(
            String agentId,
            String fileName,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            List<String> args) {
        return taskService.enqueueShellScript(agentId, fileName, contentBase64, contentSha256, contentBytes, args);
    }

    public Map<String, TaskSnapshot> enqueueFilePutBatch(
            List<String> agentIds,
            String fileName,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            String targetDir,
            String fileRole) {
        return taskService.enqueueFilePutBatch(agentIds, fileName, contentBase64, contentSha256, contentBytes, targetDir, fileRole);
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
        return groupPlans.getOrDefault(agentId, AgentGroupPlan.direct(agentId));
    }

    private PulseMessage groupPlanMessage(String agentId) {
        AgentGroupPlan plan = agentPlan(agentId);
        return new PulseMessage(
                "group-plan-" + agentId + "-" + clock.millis(),
                "cmd.group_plan",
                1,
                null,
                clock.millis() + 30_000,
                Map.ofEntries(
                        Map.entry("agent_id", plan.agentId()),
                        Map.entry("group_id", plan.groupId()),
                        Map.entry("group_mode", plan.groupMode()),
                        Map.entry("leader_agent_id", plan.leaderAgentId()),
                        Map.entry("leader_url", plan.leaderUrl()),
                        Map.entry("members", plan.members()),
                        Map.entry("cluster", plan.cluster()),
                        Map.entry("area", plan.area()),
                        Map.entry("size_limit", plan.sizeLimit()),
                        Map.entry("plan_generation", plan.generation()),
                        Map.entry("expected_generation", plan.generation())));
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

    private long merge(
            AgentHeartbeat heartbeat,
            String source,
            long observedAtMs,
            List<PulseMessage> messages,
            String ownerCoordinatorId) {
        NodeState previous = states.get(heartbeat.agentId());
        NodeState incoming = NodeState.fromHeartbeat(
                heartbeat,
                source,
                observedAtMs,
                messages,
                ownerCoordinatorId,
                previous == null ? Map.of() : previous.state);
        states.merge(heartbeat.agentId(), incoming, NodeState::newer);
        writeHeartbeatMetric(heartbeat, source, observedAtMs, messages, previous);
        markStateChanged();
        return states.get(heartbeat.agentId()).seq;
    }

    private boolean mergeForwardState(
            ForwardState state,
            String source,
            String ownerCoordinatorId,
            List<PulseMessage> messages) {
        NodeState existing = states.get(state.agentId());
        NodeState incoming = NodeState.fromForwardState(
                state,
                source,
                ownerCoordinatorId,
                messages,
                existing == null ? Map.of() : existing.state);
        NodeState selected = existing == null ? incoming : NodeState.newer(existing, incoming);
        states.put(state.agentId(), selected);
        markStateChanged();
        return selected == incoming;
    }

    private void markStateChanged() {
        groupRecomputeDirty = true;
        hostSnapshotAtMs = Long.MIN_VALUE;
    }

    private void writeHeartbeatMetric(
            AgentHeartbeat heartbeat,
            String source,
            long observedAtMs,
            List<PulseMessage> messages,
            NodeState previous) {
        if (metricStorage == null) {
            return;
        }
        Map<String, Object> state = NodeState.extractState(messages);
        AgentGroupPlan plan = groupPlans.getOrDefault(heartbeat.agentId(), AgentGroupPlan.direct(heartbeat.agentId()));
        Map<String, Object> metricState = metricState(state, source, plan);
        long arrivalGapMs = previous == null ? 0 : Math.max(0, observedAtMs - previous.observedAtMs);
        long seqGap = previous == null || previous.epoch != heartbeat.epoch()
                ? 0
                : Math.max(0, heartbeat.seq() - previous.seq - 1);
        try {
            metricStorage.writeHeartbeat(new HeartbeatMetricSample(
                    observedAtMs,
                    metricIdentity(heartbeat.agentId(), state),
                    metricIdentity(heartbeat.agentId(), state),
                    stringState(state, "cluster", "unknown"),
                    stringState(state, "area", "unknown"),
                    metricHeartbeatPath(source, plan),
                    plan.groupMode(),
                    heartbeat.epoch(),
                    heartbeat.seq(),
                    heartbeat.ttlMs(),
                    seqGap,
                    arrivalGapMs,
                    longState(state, "agent_collect_ms"),
                    longState(state, "agent_encode_ms"),
                    longState(state, "agent_send_ms"),
                    longState(state, "agent_thread_count"),
                    longState(state, "agent_rss_kb"),
                    metricState));
        } catch (Exception exception) {
            System.err.printf("metric_write status=failed agent_id=%s error=%s%n", heartbeat.agentId(), exception.getMessage());
        }
    }

    private static String metricHeartbeatPath(String source, AgentGroupPlan plan) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        if ("direct".equals(source)) {
            return plan == null || "direct".equals(plan.groupMode()) ? "direct" : "fallback_direct";
        }
        return "group_leader_batch";
    }

    private static Map<String, Object> metricState(Map<String, Object> state, String source, AgentGroupPlan plan) {
        Map<String, Object> metricState = new LinkedHashMap<>(state);
        if (source != null && !source.isBlank() && !"direct".equals(source)) {
            metricState.put("source_group_id", source);
        }
        if (plan != null && !"direct".equals(plan.groupMode())) {
            metricState.put("expected_group_id", plan.groupId());
            metricState.put("expected_group_mode", plan.groupMode());
        }
        return metricState;
    }

    private void writeGroupLeaderMetric(
            HeartbeatRequest request,
            String groupId,
            long observedAtMs,
            int accepted,
            List<AgentHeartbeatResponse> responses) {
        if (metricStorage == null) {
            return;
        }
        FileDistributionMetric distributionMetric = fileDistributionMetric(responses);
        GroupView group = groupViews.get(groupId);
        List<String> expectedMembers = group == null ? List.of() : group.members();
        long staleMembers = expectedMembers.isEmpty()
                ? 0
                : request.agents().stream().filter(agent -> !expectedMembers.contains(agent.agentId())).count();
        long missingMembers = expectedMembers.isEmpty()
                ? 0
                : Math.max(0, expectedMembers.size() - request.agents().size());
        String leaderAgentId = group == null ? request.agents().get(0).agentId() : group.leaderAgentId();
        Map<String, Object> leaderState = request.agents().stream()
                .filter(agent -> agent.agentId().equals(leaderAgentId))
                .findFirst()
                .map(agent -> NodeState.extractState(agent.messages()))
                .orElseGet(Map::of);
        Long previousObservedAt = groupMetricObservedAt.put(groupId, observedAtMs);
        long arrivalGapMs = previousObservedAt == null ? 0 : Math.max(0, observedAtMs - previousObservedAt);
        long groupSentAtMs = longState(leaderState, "group_sent_at_ms");
        long groupLatencyMs = groupSentAtMs <= 0 ? 0 : Math.max(0, observedAtMs - groupSentAtMs);
        long expectedGeneration = groupPlans.getOrDefault(leaderAgentId, AgentGroupPlan.direct(leaderAgentId)).generation();
        long agentPlanGeneration = longState(leaderState, "agent_plan_generation");
        long planMismatch = agentPlanGeneration <= 0 ? 0 : expectedGeneration == agentPlanGeneration ? 0 : 1;
        long directFallbackCount = directFallbackCount(expectedMembers, leaderAgentId);
        String status = staleMembers > 0 ? "stale_plan" : missingMembers > 0 ? "partial" : "ok";
        try {
            metricStorage.writeGroupLeader(new GroupLeaderMetricSample(
                    observedAtMs,
                    groupId,
                    leaderAgentId,
                    stringState(leaderState, "ip", ""),
                    group == null ? stringState(leaderState, "cluster", "unknown") : group.cluster(),
                    group == null ? stringState(leaderState, "area", "unknown") : group.area(),
                    expectedGeneration,
                    expectedMembers.isEmpty() ? request.agents().size() : expectedMembers.size(),
                    request.agents().size(),
                    accepted,
                    0,
                    staleMembers,
                    missingMembers,
                    directFallbackCount,
                    longState(leaderState, "leader_collect_ms"),
                    groupLatencyMs,
                    arrivalGapMs,
                    distributionMetric.responseBytes(),
                    distributionMetric.filePayloadBytes(),
                    distributionMetric.filePayloadBase64Bytes(),
                    distributionMetric.fileCommandCopyCount(),
                    distributionMetric.fileUniqueContentCount(),
                    distributionMetric.fileSharedLowerBoundBytes(),
                    status,
                    Map.ofEntries(
                            Map.entry("leader_url", group == null ? "" : group.leaderUrl()),
                            Map.entry("expected_generation", expectedGeneration),
                            Map.entry("agent_plan_generation", agentPlanGeneration),
                            Map.entry("plan_generation_known", agentPlanGeneration > 0),
                            Map.entry("plan_mismatch", planMismatch),
                            Map.entry("plan_lag", planMismatch),
                            Map.entry("file_response_bytes", distributionMetric.responseBytes()),
                            Map.entry("file_payload_bytes", distributionMetric.filePayloadBytes()),
                            Map.entry("file_payload_base64_bytes", distributionMetric.filePayloadBase64Bytes()),
                            Map.entry("file_command_copy_count", distributionMetric.fileCommandCopyCount()),
                            Map.entry("file_unique_content_count", distributionMetric.fileUniqueContentCount()),
                            Map.entry("file_shared_lower_bound_bytes", distributionMetric.fileSharedLowerBoundBytes()))));
        } catch (Exception exception) {
            System.err.printf("metric_group_write status=failed group_id=%s error=%s%n", groupId, exception.getMessage());
        }
    }

    private static FileDistributionMetric fileDistributionMetric(List<AgentHeartbeatResponse> responses) {
        long responseBytes = 0;
        long filePayloadBytes = 0;
        long filePayloadBase64Bytes = 0;
        long fileCommandCopyCount = 0;
        Map<String, Long> uniqueContents = new HashMap<>();
        for (AgentHeartbeatResponse response : responses == null ? List.<AgentHeartbeatResponse>of() : responses) {
            responseBytes += jsonBytes(response);
            for (PulseMessage message : response.messages()) {
                if (!"cmd.file_put".equals(message.type()) || message.payload() == null) {
                    continue;
                }
                fileCommandCopyCount++;
                long rawBytes = longValue(message.payload().get("content_bytes"));
                String content = stringValue(message.payload().get("content"));
                String contentSha256 = stringValue(message.payload().get("content_sha256"));
                long base64Bytes = content.getBytes(StandardCharsets.UTF_8).length;
                filePayloadBytes += rawBytes;
                filePayloadBase64Bytes += base64Bytes;
                String uniqueKey = contentSha256.isBlank() ? stringValue(message.payload().get("file_id")) : contentSha256;
                uniqueContents.merge(uniqueKey, base64Bytes, Math::max);
            }
        }
        long lowerBoundBytes = uniqueContents.values().stream().mapToLong(Long::longValue).sum();
        return new FileDistributionMetric(
                responseBytes,
                filePayloadBytes,
                filePayloadBase64Bytes,
                fileCommandCopyCount,
                uniqueContents.size(),
                lowerBoundBytes);
    }

    private static long jsonBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value).length;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long directFallbackCount(List<String> expectedMembers, String leaderAgentId) {
        if (expectedMembers.isEmpty()) {
            return 0;
        }
        return expectedMembers.stream()
                .filter(agentId -> !agentId.equals(leaderAgentId))
                .map(states::get)
                .filter(state -> state != null && "direct".equals(state.source))
                .count();
    }

    private static String stringState(Map<String, Object> state, String key, String fallback) {
        Object value = state.get(key);
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        return value.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String metricIdentity(String agentId, Map<String, Object> state) {
        String ip = stringState(state, "ip", "");
        if (!ip.isBlank() && !"-".equals(ip)) {
            return ip;
        }
        return agentId;
    }

    private static long longState(Map<String, Object> state, String key) {
        Object value = state.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
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
            List<HostView> rebuilt = buildHosts(now, true).stream()
                    .map(CoordinatorService::canonicalHostView)
                    .toList();
            hostSnapshot = rebuilt;
            hostSnapshotAtMs = now;
            return rebuilt;
        }
    }

    private List<HostView> buildHosts(long now, boolean sorted) {
        Map<String, HostView> byIdentity = new LinkedHashMap<>();
        for (NodeState state : states.values()) {
            HostView host = state.toHostView(now, groupPlans.getOrDefault(state.agentId, AgentGroupPlan.direct(state.agentId)));
            String identity = stableHostIdentity(host);
            byIdentity.merge(identity, host, CoordinatorService::preferredHostView);
        }
        List<HostView> hosts = new ArrayList<>(byIdentity.values());
        if (sorted) {
            hosts.sort(Comparator.comparing(HostView::cluster)
                    .thenComparing(HostView::status)
                    .thenComparing(HostView::agentId));
        }
        return List.copyOf(hosts);
    }

    private static String stableHostIdentity(HostView host) {
        String ip = host.ip();
        if (ip != null && !ip.isBlank() && !"-".equals(ip)) {
            return ip;
        }
        return host.agentId();
    }

    private static HostView canonicalHostView(HostView host) {
        String identity = stableHostIdentity(host);
        if (Objects.equals(identity, host.agentId()) && Objects.equals(identity, host.host())) {
            return host;
        }
        Map<String, Object> canonicalState = new LinkedHashMap<>(host.state());
        canonicalState.put("host", identity);
        canonicalState.put("ip", identity);
        return new HostView(
                identity,
                host.epoch(),
                host.seq(),
                host.ttlMs(),
                host.observedAtMs(),
                host.expireAtMs(),
                host.lastObservedAgeMs(),
                host.heartbeatConfirmations(),
                host.status(),
                host.source(),
                host.coordinatorId(),
                host.groupId(),
                host.groupMode(),
                host.leaderAgentId(),
                host.leaderUrl(),
                host.groupSize(),
                host.groupSizeLimit(),
                identity,
                host.ip(),
                host.cluster(),
                host.area(),
                host.zone(),
                host.role(),
                host.load(),
                Map.copyOf(canonicalState));
    }

    private static HostView preferredHostView(HostView left, HostView right) {
        int leftRank = hostViewRank(left);
        int rightRank = hostViewRank(right);
        if (rightRank != leftRank) {
            return rightRank > leftRank ? right : left;
        }
        if (right.observedAtMs() != left.observedAtMs()) {
            return right.observedAtMs() > left.observedAtMs() ? right : left;
        }
        return right.seq() > left.seq() ? right : left;
    }

    private static int hostViewRank(HostView host) {
        int rank = "alive".equals(host.status()) ? 4 : "warming".equals(host.status()) ? 3 : 1;
        if (Objects.equals(host.agentId(), host.ip())) {
            rank += 2;
        }
        if (Objects.equals(host.host(), host.ip())) {
            rank += 1;
        }
        return rank;
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
            if ("unknown".equals(cluster)) {
                continue;
            }
            buckets.computeIfAbsent(cluster, ignored -> new ArrayList<>()).add(host);
        }

        Map<String, AgentGroupPlan> nextPlans = new HashMap<>();
        Map<String, GroupView> nextGroups = new HashMap<>();
        for (Map.Entry<String, List<HostView>> entry : buckets.entrySet()) {
            String cluster = entry.getKey();
            List<HostView> members = entry.getValue().stream()
                    .sorted(Comparator.comparing(CoordinatorService::locationSortKey).thenComparing(HostView::agentId))
                    .toList();
            if (members.size() < MIN_AGENTS_FOR_GROUP_LEADER) {
                continue;
            }
            int groupCount = Math.max(1, (int) Math.floor(Math.sqrt(members.size())));
            List<List<HostView>> shards = locationAwareShards(members, groupCount);
            for (int shard = 0; shard < shards.size(); shard++) {
                registerGroup(cluster, shard, shards.get(shard), nextPlans, nextGroups);
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

    private void registerGroup(
            String cluster,
            int shard,
            List<HostView> groupMembers,
            Map<String, AgentGroupPlan> nextPlans,
            Map<String, GroupView> nextGroups) {
        if (groupMembers.isEmpty()) {
            return;
        }
        String area = groupArea(groupMembers);
        String groupId = "%s/%s/%03d".formatted(cluster, area, shard);
        HostView leader = groupMembers.stream()
                .filter(member -> "alive".equals(member.status()))
                .findFirst()
                .orElse(groupMembers.get(0));
        String leaderUrl = leaderUrl(leader);
        List<String> memberIds = groupMembers.stream().map(HostView::agentId).toList();
        long generation = planGeneration(cluster, area, groupId, leader.agentId(), memberIds);
        nextGroups.put(groupId, new GroupView(
                groupId,
                cluster,
                area,
                leader.agentId(),
                leaderUrl,
                memberIds,
                memberIds.size(),
                groupMembers.size()));
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
                    groupMembers.size(),
                    generation));
        }
    }

    private static long planGeneration(String cluster, String area, String groupId, String leaderAgentId, List<String> memberIds) {
        return Integer.toUnsignedLong(Objects.hash(cluster, area, groupId, leaderAgentId, memberIds));
    }

    private static List<List<HostView>> locationAwareShards(List<HostView> members, int groupCount) {
        Map<String, List<HostView>> byArea = new LinkedHashMap<>();
        for (HostView member : members) {
            byArea.computeIfAbsent(blankToUnknown(member.area()), ignored -> new ArrayList<>()).add(member);
        }
        if (byArea.size() > groupCount) {
            return continuousShards(members, groupCount);
        }

        Map<String, Integer> groupCounts = allocateAreaGroupCounts(byArea, groupCount, members.size());
        List<List<HostView>> shards = new ArrayList<>(groupCount);
        for (Map.Entry<String, List<HostView>> area : byArea.entrySet()) {
            int areaGroupCount = groupCounts.getOrDefault(area.getKey(), 0);
            shards.addAll(continuousShards(area.getValue(), areaGroupCount));
        }
        return shards;
    }

    private static Map<String, Integer> allocateAreaGroupCounts(
            Map<String, List<HostView>> byArea,
            int groupCount,
            int totalMembers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Double> remainders = new LinkedHashMap<>();
        int allocated = 0;
        for (Map.Entry<String, List<HostView>> entry : byArea.entrySet()) {
            double exact = (double) entry.getValue().size() * groupCount / totalMembers;
            int count = Math.max(1, (int) Math.floor(exact));
            counts.put(entry.getKey(), count);
            remainders.put(entry.getKey(), exact - Math.floor(exact));
            allocated += count;
        }
        while (allocated < groupCount) {
            String selected = remainders.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            counts.put(selected, counts.get(selected) + 1);
            remainders.put(selected, -1.0);
            allocated++;
        }
        while (allocated > groupCount) {
            String selected = counts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .min(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            counts.put(selected, counts.get(selected) - 1);
            allocated--;
        }
        return counts;
    }

    private static List<List<HostView>> continuousShards(List<HostView> members, int groupCount) {
        List<List<HostView>> shards = new ArrayList<>(groupCount);
        for (int shard = 0; shard < groupCount; shard++) {
            int start = shard * members.size() / groupCount;
            int end = (shard + 1) * members.size() / groupCount;
            shards.add(members.subList(start, end));
        }
        return shards;
    }

    private static String groupArea(List<HostView> members) {
        if (members.isEmpty()) {
            return "unknown";
        }
        String first = blankToUnknown(members.get(0).area());
        boolean sameArea = members.stream()
                .map(host -> blankToUnknown(host.area()))
                .allMatch(first::equals);
        return sameArea ? first : "mixed";
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

    private static String locationSortKey(HostView host) {
        return blankToUnknown(host.area()) + "/" + ipSortKey(host);
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
        private final String coordinatorId;
        private final Map<String, Object> state;

        private NodeState(
                String agentId,
                long epoch,
                long seq,
                long ttlMs,
                long observedAtMs,
                List<HeartbeatConfirmation> confirmations,
                String source,
                String coordinatorId,
                Map<String, Object> state) {
            this.agentId = agentId;
            this.epoch = epoch;
            this.seq = seq;
            this.ttlMs = ttlMs;
            this.observedAtMs = observedAtMs;
            this.expireAtMs = observedAtMs + ttlMs;
            this.confirmations = List.copyOf(confirmations);
            this.source = source;
            this.coordinatorId = coordinatorId == null ? "" : coordinatorId;
            this.state = Map.copyOf(state);
        }

        private static NodeState fromHeartbeat(
                AgentHeartbeat heartbeat,
                String source,
                long observedAtMs,
                List<PulseMessage> messages,
                String coordinatorId,
                Map<String, Object> previousState) {
            return new NodeState(
                    heartbeat.agentId(),
                    heartbeat.epoch(),
                    heartbeat.seq(),
                    heartbeat.ttlMs(),
                    observedAtMs,
                    List.of(new HeartbeatConfirmation(heartbeat.epoch(), heartbeat.seq(), observedAtMs)),
                    source,
                    coordinatorId,
                    stateOrPrevious(messages, previousState));
        }

        private static NodeState fromForwardState(
                ForwardState state,
                String source,
                String coordinatorId,
                List<PulseMessage> messages,
                Map<String, Object> previousState) {
            return new NodeState(
                    state.agentId(),
                    state.epoch(),
                    state.seq(),
                    state.ttlMs(),
                    state.observedAtMs(),
                    List.of(new HeartbeatConfirmation(state.epoch(), state.seq(), state.observedAtMs())),
                    source,
                    coordinatorId,
                    stateOrPrevious(messages, previousState));
        }

        private static Map<String, Object> stateOrPrevious(List<PulseMessage> messages, Map<String, Object> previousState) {
            Map<String, Object> state = extractState(messages);
            if (!state.isEmpty() || previousState == null || previousState.isEmpty()) {
                return state;
            }
            return previousState;
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
            return new NodeState(agentId, epoch, seq, ttlMs, observedAtMs, nextConfirmations, source, coordinatorId, state);
        }

        private HostView toHostView(long now, AgentGroupPlan plan) {
            int confirmations = recentConfirmations(now);
            String status = now > expireAtMs ? "expired" : confirmations >= ALIVE_CONFIRMATION_THRESHOLD ? "alive" : "warming";
            AgentGroupPlan debugPlan = plan == null ? AgentGroupPlan.direct(agentId) : plan;
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
                    coordinatorId,
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

    private record FileDistributionMetric(
            long responseBytes,
            long filePayloadBytes,
            long filePayloadBase64Bytes,
            long fileCommandCopyCount,
            long fileUniqueContentCount,
            long fileSharedLowerBoundBytes) {
    }
}
