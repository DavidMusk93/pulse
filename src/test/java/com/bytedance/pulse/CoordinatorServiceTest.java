package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinatorServiceTest {
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
    @TempDir
    Path tempDir;

    @Test
    void heartbeatStoresHostState() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);

        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 40, "host-a", "10.0.0.1"));
        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 41, "host-a", "10.0.0.1"));
        HeartbeatResponse response = service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-a", "10.0.0.1"));

        assertTrue(response.ok());
        assertEquals(42, response.acceptedSeq());
        HostView host = service.hosts().get(0);
        assertEquals("agent-1", host.agentId());
        assertEquals("host-a", host.host());
        assertEquals("10.0.0.1", host.ip());
        assertEquals("cluster-a", host.cluster());
        assertEquals("area-a", host.area());
        assertEquals("alive", host.status());
        assertEquals(3, host.heartbeatConfirmations());
        assertEquals(0, host.lastObservedAgeMs());
        assertEquals("direct", host.groupId());
        assertEquals("direct", host.groupMode());
        assertEquals("agent-1", host.leaderAgentId());
        assertEquals("", host.leaderUrl());
        assertEquals(1, host.groupSize());
        assertEquals(1, host.groupSizeLimit());
        assertEquals("cmd.group_plan", response.messages().get(0).type());
    }

    @Test
    void heartbeatRequiresThreeConfirmationsWithinWindowToBeAlive() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);

        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-a", "10.0.0.1"));

        HostView host = service.hosts().get(0);
        assertEquals("warming", host.status());
        assertEquals(1, host.heartbeatConfirmations());
    }

    @Test
    void heartbeatWritesDiagnosticMetricSamples() throws Exception {
        MutableClock mutableClock = new MutableClock(Instant.ofEpochMilli(1_710_000_000_000L));
        try (LocalMetricStorage storage = LocalMetricStorage.open(tempDir.resolve("metrics.db"))) {
            CoordinatorService service = new CoordinatorService("coordinator-a", mutableClock, storage);

            service.handleHeartbeat(diagnosticHeartbeat("agent-1", 1, 40, 4, 5, 6, 19, 72_000));
            mutableClock.advance(Duration.ofSeconds(10));
            service.handleHeartbeat(diagnosticHeartbeat("agent-1", 1, 43, 7, 8, 9, 21, 73_000));

            MetricQueryResult gaps = storage.queryRange(new MetricQuery(
                    "heartbeat.seq_gap",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));
            MetricQueryResult arrivals = storage.queryRange(new MetricQuery(
                    "heartbeat.arrival_gap_ms",
                    List.of("agent-1"),
                    1_710_000_000_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));

            assertEquals(List.of(0.0, 2.0), gaps.series().get(0).points().stream().map(MetricPoint::value).toList());
            assertEquals(List.of(0.0, 10_000.0), arrivals.series().get(0).points().stream().map(MetricPoint::value).toList());
            assertEquals(List.of(19.0, 21.0), storage.queryRange(new MetricQuery(
                            "agent.thread_count",
                            List.of("agent-1"),
                            1_710_000_000_000L,
                            1_710_000_010_000L,
                            1_000,
                            10))
                    .series()
                    .get(0)
                    .points()
                    .stream()
                    .map(MetricPoint::value)
                    .toList());
        }
    }

    @Test
    void olderSequenceDoesNotOverwriteNewerState() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-new", "10.0.0.42"));

        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 41, "host-old", "10.0.0.41"));

        HostView host = service.hosts().get(0);
        assertEquals(42, host.seq());
        assertEquals("host-new", host.host());
    }

    @Test
    void higherEpochOverwritesLowerEpoch() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.handleHeartbeat(singleHeartbeat("agent-1", 1, 42, "host-old", "10.0.0.42"));

        service.handleHeartbeat(singleHeartbeat("agent-1", 2, 1, "host-new-epoch", "10.0.0.2"));

        HostView host = service.hosts().get(0);
        assertEquals(2, host.epoch());
        assertEquals(1, host.seq());
        assertEquals("host-new-epoch", host.host());
    }

    @Test
    void batchHeartbeatReturnsPerAgentAcceptedSequence() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        HeartbeatRequest request = new HeartbeatRequest(
                "group-a",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(
                        agent("agent-1", 1, 10, "host-1", "10.0.0.1"),
                        agent("agent-2", 1, 11, "host-2", "10.0.0.2")));

        HeartbeatResponse response = service.handleHeartbeat(request);

        assertTrue(response.ok());
        assertEquals(2, response.agents().size());
        assertEquals(10, response.agents().get(0).acceptedSeq());
        assertEquals(11, response.agents().get(1).acceptedSeq());
        assertEquals(2, service.hosts().size());
    }

    @Test
    void batchHeartbeatWritesGroupLeaderMetricSample() throws Exception {
        MutableClock mutableClock = new MutableClock(Instant.ofEpochMilli(1_710_000_000_000L));
        try (LocalMetricStorage storage = LocalMetricStorage.open(tempDir.resolve("metrics.db"))) {
            CoordinatorService service = new CoordinatorService("coordinator-a", mutableClock, storage);
            for (int i = 1; i <= 5; i++) {
                confirmAlive(service, "agent-" + i, "host-" + i, "10.0.0." + i);
            }
            GroupView group = service.groups().get(0);
            mutableClock.advance(Duration.ofSeconds(10));
            String fallbackAgent = group.members().stream()
                    .filter(agentId -> !agentId.equals(group.leaderAgentId()))
                    .findFirst()
                    .orElseThrow();
            service.handleHeartbeat(singleHeartbeat(
                    fallbackAgent,
                    1,
                    21,
                    "host-" + fallbackAgent,
                    "10.0.0." + fallbackAgent.replace("agent-", "")));

            service.handleHeartbeat(new HeartbeatRequest(
                    group.groupId(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    group.members().stream()
                            .map(agentId -> agentId.equals(group.leaderAgentId())
                                    ? agentWithState(
                                            agentId,
                                            1,
                                            20,
                                            "host-" + agentId,
                                            "10.0.0." + agentId.replace("agent-", ""),
                                            Map.of("leader_collect_ms", 7L, "group_sent_at_ms", 1_710_000_009_992L))
                                    : agent(agentId, 1, 20, "host-" + agentId, "10.0.0." + agentId.replace("agent-", "")))
                            .toList()));

            MetricQueryResult result = storage.queryRange(new MetricQuery(
                    "group.submitted_agent_count",
                    List.of(),
                    1_710_000_010_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));

            assertEquals(1, result.series().size());
            assertEquals(group.groupId(), result.series().get(0).labels().get("group_id"));
            assertEquals((double) group.members().size(), result.series().get(0).points().get(0).value());

            MetricQueryResult collect = storage.queryRange(new MetricQuery(
                    "group.leader_collect_ms",
                    List.of(),
                    1_710_000_010_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));
            MetricQueryResult latency = storage.queryRange(new MetricQuery(
                    "group.group_latency_ms",
                    List.of(),
                    1_710_000_010_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));
            assertEquals(7.0, collect.series().get(0).points().get(0).value());
            assertEquals(8.0, latency.series().get(0).points().get(0).value());
            MetricQueryResult directFallback = storage.queryRange(new MetricQuery(
                    "group.direct_fallback_count",
                    List.of(),
                    1_710_000_010_000L,
                    1_710_000_010_000L,
                    1_000,
                    10));
            assertEquals(1.0, directFallback.series().get(0).points().get(0).value());
        }
    }

    @Test
    void coordinatorUsesSqrtLeaderCountAndLocationAwareShards() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        for (int i = 1; i <= 8; i++) {
            confirmAlive(service, "agent-" + i, "host-" + i, "10.0.0." + i);
        }

        List<GroupView> groups = service.groups();
        HeartbeatResponse followerResponse = service.handleHeartbeat(singleHeartbeat("agent-2", 1, 20, "host-2", "10.0.0.2"));
        Map<String, Object> payload = followerResponse.messages().get(0).payload();

        assertEquals(2, groups.size());
        assertEquals(4, groups.get(0).size());
        assertEquals(4, groups.get(1).size());
        assertEquals("follower", payload.get("group_mode"));
        assertEquals(4, payload.get("size_limit"));
        assertEquals("agent-1", payload.get("leader_agent_id"));
        assertEquals("http://10.0.0.1:9977", payload.get("leader_url"));
    }

    @Test
    void clusterWithLessThanFiveAgentsStaysDirect() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        for (int i = 1; i <= 4; i++) {
            confirmAlive(service, "agent-" + i, "host-" + i, "10.0.0." + i);
        }

        assertEquals(0, service.groups().size());
        assertEquals("direct", planPayload(service, "agent-1").get("group_mode"));
    }

    @Test
    void sixteenAgentsCreateFourGroups() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        for (int i = 1; i <= 16; i++) {
            confirmAlive(service, "agent-" + i, "host-" + i, "fdbd:dc02:1a:3c::" + i);
        }

        List<GroupView> groups = service.groups();

        assertEquals(4, groups.size());
        assertTrue(groups.stream().allMatch(group -> group.size() == 4));
    }

    @Test
    void fiftyAgentsCreateSevenGroups() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        for (int i = 1; i <= 50; i++) {
            confirmAlive(service, "agent-" + i, "host-" + i, "fdbd:dc02:1a:3c::" + i);
        }

        List<GroupView> groups = service.groups();

        assertEquals(7, groups.size());
        assertEquals(50, groups.stream().mapToInt(GroupView::size).sum());
    }

    @Test
    void multiAreaGroupsAvoidCrossAreaWhenLeaderCountCanCoverAreas() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        for (int i = 1; i <= 10; i++) {
            confirmAlive(service, "agent-a-" + i, "host-a-" + i, "fdbd:dc02:1a:3c::" + i, "area-a");
        }
        for (int i = 1; i <= 6; i++) {
            confirmAlive(service, "agent-b-" + i, "host-b-" + i, "fdbd:dc05:2:90b::" + i, "area-b");
        }

        List<GroupView> groups = service.groups();

        assertEquals(4, groups.size());
        assertTrue(groups.stream().noneMatch(group -> "mixed".equals(group.area())));
        assertEquals(2, groups.stream().filter(group -> "area-a".equals(group.area())).count());
        assertEquals(2, groups.stream().filter(group -> "area-b".equals(group.area())).count());
    }

    @Test
    void batchHeartbeatReturnsPerAgentGroupPlanMessages() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        HeartbeatRequest request = new HeartbeatRequest(
                "group-a",
                null,
                null,
                null,
                null,
                List.of(),
                List.of(
                        agent("agent-1", 1, 10, "host-1", "10.0.0.1"),
                        agent("agent-2", 1, 11, "host-2", "10.0.0.2")));

        HeartbeatResponse response = service.handleHeartbeat(request);

        assertEquals("cmd.group_plan", response.agents().get(0).messages().get(0).type());
        assertEquals("cmd.group_plan", response.agents().get(1).messages().get(0).type());
    }

    @Test
    void existingGroupMemberStaysGroupedWhileWarmingUntilExpired() {
        MutableClock mutableClock = new MutableClock(Instant.ofEpochMilli(1_710_000_000_000L));
        CoordinatorService service = new CoordinatorService("coordinator-a", mutableClock);
        for (int i = 1; i <= 8; i++) {
            confirmAlive(service, "agent-" + i, "host-" + i, "10.0.0." + i);
        }

        assertEquals("cluster-a/area-a/001", planPayload(service, "agent-7").get("group_id"));
        assertEquals("cluster-a/area-a/001", planPayload(service, "agent-8").get("group_id"));

        mutableClock.advance(Duration.ofMillis(21_000));
        service.handleHeartbeat(singleHeartbeat("agent-8", 1, 4, "host-8", "10.0.0.8"));

        HostView warmedMember = service.hosts().stream()
                .filter(host -> "agent-7".equals(host.agentId()))
                .findFirst()
                .orElseThrow();
        assertEquals("warming", warmedMember.status());
        assertEquals("cluster-a/area-a/001", warmedMember.groupId());
        assertEquals("follower", warmedMember.groupMode());
        assertEquals("cluster-a/area-a/001", planPayload(service, "agent-8").get("group_id"));

        mutableClock.advance(Duration.ofMillis(10_000));
        for (int i = 1; i <= 6; i++) {
            confirmAliveFromSeq(service, "agent-" + i, "host-" + i, "10.0.0." + i, 10);
        }
        confirmAliveFromSeq(service, "agent-8", "host-8", "10.0.0.8", 101);

        HostView expiredMember = service.hosts().stream()
                .filter(host -> "agent-7".equals(host.agentId()))
                .findFirst()
                .orElseThrow();
        assertEquals("expired", expiredMember.status());
        assertEquals("cluster-a/area-a/001", planPayload(service, "agent-8").get("group_id"));
    }

    @Test
    void remoteTaskFlowsThroughHeartbeatAndCompletionQueue() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.enqueueTask("agent-1", "prepare_disk_layout_dry_run");

        HeartbeatResponse commandResponse = service.handleHeartbeat(singleHeartbeat("agent-1", 1, 10, "host-1", "10.0.0.1"));
        PulseMessage command = commandResponse.messages().stream()
                .filter(message -> "cmd.task_execute".equals(message.type()))
                .findFirst()
                .orElseThrow();

        assertEquals("prepare_disk_layout_dry_run", command.payload().get("task_type"));
        assertEquals(List.of("--dry-run"), command.payload().get("args"));

        HeartbeatResponse readyResponse = service.handleHeartbeat(new HeartbeatRequest(
                null,
                "agent-1",
                1L,
                11L,
                15_000L,
                List.of(
                        new PulseMessage("state-agent-1-11", "state.heartbeat", 1, null, null, Map.of("host", "host-1")),
                        new PulseMessage(
                                "result-agent-1",
                                "reply.task_result",
                                1,
                                command.messageId(),
                                null,
                                Map.ofEntries(
                                        Map.entry("task_id", command.payload().get("task_id")),
                                        Map.entry("trace_id", command.payload().get("trace_id")),
                                        Map.entry("task_type", "prepare_disk_layout_dry_run"),
                                        Map.entry("status", "completed"),
                                        Map.entry("exit_code", 0),
                                        Map.entry("started_at_ms", clock.millis()),
                                        Map.entry("finished_at_ms", clock.millis() + 1),
                                        Map.entry("duration_ms", 1),
                                        Map.entry("output", "ok"),
                                        Map.entry("output_type", "text"),
                                        Map.entry("output_encoding", "identity"),
                                        Map.entry("output_sha256", TaskOutputCodec.sha256("ok")),
                                        Map.entry("output_bytes", 2),
                                        Map.entry("output_chunked", false),
                                        Map.entry("output_chunk_count", 0),
                                        Map.entry("runner_error", "")))),
                List.of()));

        TaskSnapshot snapshot = service.taskSnapshot("agent-1");
        assertEquals(1, snapshot.completionQueue().size());
        assertEquals("completed", snapshot.completionQueue().get(0).status());
        assertEquals("ok", snapshot.completionQueue().get(0).output());
    }

    @Test
    void shellExecuteWaitsUntilStagedFileIsReceived() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        String script = "echo ok\n";
        service.enqueueShellScript(
                "agent-1",
                "script.sh",
                java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                TaskOutputCodec.sha256(script),
                script.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                List.of());

        PulseMessage filePut = service.handleHeartbeat(singleHeartbeat("agent-1", 1, 10, "host-1", "10.0.0.1"))
                .messages()
                .stream()
                .filter(message -> "cmd.file_put".equals(message.type()))
                .findFirst()
                .orElseThrow();

        HeartbeatResponse waitingResponse = service.handleHeartbeat(singleHeartbeat("agent-1", 1, 11, "host-1", "10.0.0.1"));
        assertTrue(waitingResponse.messages().stream().noneMatch(message -> "cmd.shell_execute".equals(message.type())));
        PulseMessage retryFilePut = waitingResponse.messages()
                .stream()
                .filter(message -> "cmd.file_put".equals(message.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(filePut.payload().get("file_id"), retryFilePut.payload().get("file_id"));

        HeartbeatResponse readyResponse = service.handleHeartbeat(new HeartbeatRequest(
                null,
                "agent-1",
                1L,
                12L,
                15_000L,
                List.of(
                        new PulseMessage("state-agent-1-12", "state.heartbeat", 1, null, null, Map.of("host", "host-1")),
                        new PulseMessage(
                                "file-received-agent-1",
                                "reply.file_received",
                                1,
                                filePut.messageId(),
                                null,
                                Map.of(
                                        "file_id", filePut.payload().get("file_id"),
                                        "task_id", filePut.payload().get("task_id"),
                                        "status", "received",
                                        "local_path", "/data24/otf/pulse/agent/workspace/scripts/task/script.sh",
                                        "runner_error", ""))),
                List.of()));

        PulseMessage shellExecute = readyResponse
                .messages()
                .stream()
                .filter(message -> "cmd.shell_execute".equals(message.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(filePut.payload().get("file_id"), shellExecute.payload().get("script_file_id"));
    }

    @Test
    void popCompletionRemovesOnlyQueueHeadAndKeepsNextResultVisible() {
        MutableClock mutableClock = new MutableClock(Instant.ofEpochMilli(1_710_000_000_000L));
        CoordinatorService service = new CoordinatorService("coordinator-a", mutableClock);
        service.enqueueTask("agent-1", "prepare_disk_layout_dry_run");
        PulseMessage first = taskCommand(service, "agent-1", 10);
        completeTask(service, first, "first-result", 11);
        mutableClock.advance(Duration.ofMillis(1));
        service.enqueueTask("agent-1", "analyze_block_layout_dry_run");
        PulseMessage second = taskCommand(service, "agent-1", 12);
        completeTask(service, second, "second-result", 13);
        mutableClock.advance(Duration.ofMillis(1));

        TaskSnapshot beforePop = service.taskSnapshot("agent-1");
        assertEquals(2, beforePop.completionQueue().size());
        assertEquals("first-result", beforePop.completionQueue().get(0).output());
        assertEquals("second-result", beforePop.completionQueue().get(1).output());

        TaskSnapshot afterPop = service.popCompletion("agent-1", beforePop.completionQueue().get(0).taskId());

        assertEquals(1, afterPop.completionQueue().size());
        assertEquals("second-result", afterPop.completionQueue().get(0).output());
        assertTrue(afterPop.traces().size() <= 4);
        assertTrue(afterPop.traces().stream().anyMatch(trace -> "task.completion_popped".equals(trace.event())));
    }

    @Test
    void remoteTaskResultChunksAreReassembledLosslessly() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.enqueueTask("agent-1", "analyze_block_layout_dry_run");
        PulseMessage command = service.handleHeartbeat(singleHeartbeat("agent-1", 1, 10, "host-1", "10.0.0.1"))
                .messages()
                .stream()
                .filter(message -> "cmd.task_execute".equals(message.type()))
                .findFirst()
                .orElseThrow();
        String output = "{\"rows\":[" + "1234567890,".repeat(6_000) + "0]}";
        String first = output.substring(0, output.length() / 2);
        String second = output.substring(output.length() / 2);

        service.handleHeartbeat(new HeartbeatRequest(
                null,
                "agent-1",
                1L,
                11L,
                15_000L,
                List.of(
                        new PulseMessage("state-agent-1-11", "state.heartbeat", 1, null, null, Map.of("host", "host-1")),
                        new PulseMessage(
                                "result-agent-1",
                                "reply.task_result",
                                1,
                                command.messageId(),
                                null,
                                Map.ofEntries(
                                        Map.entry("task_id", command.payload().get("task_id")),
                                        Map.entry("trace_id", command.payload().get("trace_id")),
                                        Map.entry("task_type", "analyze_block_layout_dry_run"),
                                        Map.entry("status", "completed"),
                                        Map.entry("exit_code", 0),
                                        Map.entry("started_at_ms", clock.millis()),
                                        Map.entry("finished_at_ms", clock.millis() + 1),
                                        Map.entry("duration_ms", 1),
                                        Map.entry("output_type", "json"),
                                        Map.entry("output_encoding", "identity"),
                                        Map.entry("output_sha256", TaskOutputCodec.sha256(output)),
                                        Map.entry("output_bytes", output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                                        Map.entry("output_chunked", true),
                                        Map.entry("output_chunk_count", 2),
                                        Map.entry("runner_error", ""))),
                        chunk(command, 1, second, output),
                        chunk(command, 0, first, output)),
                List.of()));

        TaskSnapshot snapshot = service.taskSnapshot("agent-1");
        assertEquals(1, snapshot.completionQueue().size());
        assertEquals(output, snapshot.completionQueue().get(0).output());
        assertEquals(TaskOutputCodec.sha256(output), snapshot.completionQueue().get(0).outputSha256());
    }

    @Test
    void taskOutputAppendMaintainsStreamLogWithoutCompletionQueue() {
        CoordinatorService service = new CoordinatorService("coordinator-a", clock);
        service.enqueueTask("agent-1", "analyze_block_layout_dry_run");
        PulseMessage command = taskCommand(service, "agent-1", 10);

        service.handleHeartbeat(new HeartbeatRequest(
                null,
                "agent-1",
                1L,
                11L,
                15_000L,
                List.of(
                        new PulseMessage("state-agent-1-11", "state.heartbeat", 1, null, null, Map.of(
                                "host", "host-1",
                                "async_tasks", List.of(Map.of(
                                        "task_id", command.payload().get("task_id"),
                                        "task_type", command.payload().get("task_type"),
                                        "status", "running",
                                        "runtime_ms", 1_000,
                                        "stream_bytes", 12,
                                        "stream_chunks", 1,
                                        "stream_lines", 1)))),
                        new PulseMessage(
                                "stream-agent-1-0",
                                "reply.task_output_append",
                                1,
                                command.messageId(),
                                null,
                                Map.ofEntries(
                                        Map.entry("task_id", command.payload().get("task_id")),
                                        Map.entry("agent_id", "agent-1"),
                                        Map.entry("task_type", command.payload().get("task_type")),
                                        Map.entry("stream_id", "output"),
                                        Map.entry("stream_seq", 0),
                                        Map.entry("stream_offset", 0),
                                        Map.entry("output_encoding", "identity"),
                                        Map.entry("output_type", "text"),
                                        Map.entry("payload", "scan line\n"),
                                        Map.entry("payload_sha256", TaskOutputCodec.sha256("scan line\n")),
                                        Map.entry("observed_at_ms", clock.millis())))),
                List.of()));

        TaskSnapshot snapshot = service.taskSnapshot("agent-1");
        assertEquals(0, snapshot.completionQueue().size());
        assertEquals(1, snapshot.outputStreams().size());
        TaskStreamSnapshot stream = snapshot.outputStreams().get(0);
        assertEquals(command.payload().get("task_id"), stream.taskId());
        assertEquals("scan line\n", stream.output());
        assertEquals(10, stream.streamBytes());
        assertEquals(1, stream.streamLines());
        assertEquals(1, stream.streamChunks());
    }

    @Test
    void forwardOnlyMergesStateMessages() {
        CoordinatorService service = new CoordinatorService("coordinator-b", clock);
        ForwardState state = new ForwardState(
                "agent-1",
                1,
                10,
                15_000,
                clock.millis(),
                "cdn2/yg/000",
                List.of(
                        new PulseMessage("state-1", "state.heartbeat", 1, null, null, Map.of("host", "host-1")),
                        new PulseMessage("cmd-1", "cmd.update_config", 1, null, null, Map.of("ignored", true))));

        HeartbeatForwardResponse response = service.handleForward(
                new HeartbeatForwardRequest("coordinator-a", List.of(state)));

        assertTrue(response.ok());
        assertEquals(1, response.accepted());
        assertEquals(1, response.merged());
        HostView host = service.hosts().get(0);
        assertEquals("host-1", host.host());
        assertEquals("cdn2/yg/000", host.source());
        assertEquals("direct", host.groupId());
        assertEquals("direct", host.groupMode());
        assertEquals(1, host.state().size());
    }

    private static HeartbeatRequest singleHeartbeat(String agentId, long epoch, long seq, String host, String ip) {
        AgentHeartbeat heartbeat = agent(agentId, epoch, seq, host, ip);
        return new HeartbeatRequest(
                null,
                heartbeat.agentId(),
                heartbeat.epoch(),
                heartbeat.seq(),
                heartbeat.ttlMs(),
                heartbeat.messages(),
                List.of());
    }

    private static HeartbeatRequest singleHeartbeat(String agentId, long epoch, long seq, String host, String ip, String area) {
        AgentHeartbeat heartbeat = agent(agentId, epoch, seq, host, ip, area);
        return new HeartbeatRequest(
                null,
                heartbeat.agentId(),
                heartbeat.epoch(),
                heartbeat.seq(),
                heartbeat.ttlMs(),
                heartbeat.messages(),
                List.of());
    }

    private static void confirmAlive(CoordinatorService service, String agentId, String host, String ip) {
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 1, host, ip));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 2, host, ip));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 3, host, ip));
    }

    private static void confirmAlive(CoordinatorService service, String agentId, String host, String ip, String area) {
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 1, host, ip, area));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 2, host, ip, area));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, 3, host, ip, area));
    }

    private static void confirmAliveFromSeq(CoordinatorService service, String agentId, String host, String ip, long firstSeq) {
        service.handleHeartbeat(singleHeartbeat(agentId, 1, firstSeq, host, ip));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, firstSeq + 1, host, ip));
        service.handleHeartbeat(singleHeartbeat(agentId, 1, firstSeq + 2, host, ip));
    }

    private static PulseMessage taskCommand(CoordinatorService service, String agentId, long seq) {
        return service.handleHeartbeat(singleHeartbeat(agentId, 1, seq, "host-1", "10.0.0.1"))
                .messages()
                .stream()
                .filter(message -> "cmd.task_execute".equals(message.type()))
                .findFirst()
                .orElseThrow();
    }

    private static HeartbeatRequest diagnosticHeartbeat(
            String agentId,
            long epoch,
            long seq,
            long collectMs,
            long encodeMs,
            long sendMs,
            long threadCount,
            long rssKb) {
        return new HeartbeatRequest(
                null,
                agentId,
                epoch,
                seq,
                30_000L,
                List.of(new PulseMessage(
                        "state-" + agentId + "-" + seq,
                        "state.heartbeat",
                        1,
                        null,
                        null,
                        Map.ofEntries(
                                Map.entry("host", "host-1"),
                                Map.entry("ip", "10.0.0.1"),
                                Map.entry("cluster", "cluster-a"),
                                Map.entry("area", "area-a"),
                                Map.entry("agent_collect_ms", collectMs),
                                Map.entry("agent_encode_ms", encodeMs),
                                Map.entry("agent_send_ms", sendMs),
                                Map.entry("agent_thread_count", threadCount),
                                Map.entry("agent_rss_kb", rssKb)))),
                List.of());
    }

    private static void completeTask(CoordinatorService service, PulseMessage command, String output, long seq) {
        service.handleHeartbeat(new HeartbeatRequest(
                null,
                command.payload().get("agent_id").toString(),
                1L,
                seq,
                30_000L,
                List.of(
                        new PulseMessage("state-agent-1-" + seq, "state.heartbeat", 1, null, null, Map.of("host", "host-1")),
                        new PulseMessage(
                                "result-agent-1-" + seq,
                                "reply.task_result",
                                1,
                                command.messageId(),
                                null,
                                Map.ofEntries(
                                        Map.entry("task_id", command.payload().get("task_id")),
                                        Map.entry("trace_id", command.payload().get("trace_id")),
                                        Map.entry("task_type", command.payload().get("task_type")),
                                        Map.entry("status", "completed"),
                                        Map.entry("exit_code", 0),
                                        Map.entry("started_at_ms", 1),
                                        Map.entry("finished_at_ms", 2),
                                        Map.entry("duration_ms", 1),
                                        Map.entry("output", output),
                                        Map.entry("output_type", "text"),
                                        Map.entry("output_encoding", "identity"),
                                        Map.entry("output_sha256", TaskOutputCodec.sha256(output)),
                                        Map.entry("output_bytes", output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                                        Map.entry("output_chunked", false),
                                        Map.entry("output_chunk_count", 0),
                                        Map.entry("runner_error", "")))),
                List.of()));
    }

    private static Map<String, Object> planPayload(CoordinatorService service, String agentId) {
        return service.handleHeartbeat(singleHeartbeat(agentId, 1, 100, "host-" + agentId, "10.0.0." + agentId.replace("agent-", "")))
                .messages()
                .stream()
                .filter(message -> "cmd.group_plan".equals(message.type()))
                .findFirst()
                .orElseThrow()
                .payload();
    }

    private static AgentHeartbeat agent(String agentId, long epoch, long seq, String host, String ip) {
        return agent(agentId, epoch, seq, host, ip, "area-a");
    }

    private static AgentHeartbeat agent(String agentId, long epoch, long seq, String host, String ip, String area) {
        return agentWithState(agentId, epoch, seq, host, ip, Map.of(), area);
    }

    private static AgentHeartbeat agentWithState(
            String agentId, long epoch, long seq, String host, String ip, Map<String, Object> extraState) {
        return agentWithState(agentId, epoch, seq, host, ip, extraState, "area-a");
    }

    private static AgentHeartbeat agentWithState(
            String agentId, long epoch, long seq, String host, String ip, Map<String, Object> extraState, String area) {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("host", host);
        state.put("ip", ip);
        state.put("cluster", "cluster-a");
        state.put("area", area);
        state.put("zone", "az-a");
        state.put("role", "worker");
        state.put("load", "0.42");
        state.putAll(extraState);
        return new AgentHeartbeat(
                agentId,
                epoch,
                seq,
                30_000,
                List.of(new PulseMessage(
                        "msg-" + agentId + "-" + seq,
                        "state.heartbeat",
                        1,
                        null,
                        null,
                        state)));
    }

    private static PulseMessage chunk(PulseMessage command, int index, String payload, String fullOutput) {
        return new PulseMessage(
                "chunk-" + index,
                "reply.task_result_chunk",
                1,
                command.messageId(),
                null,
                Map.ofEntries(
                        Map.entry("task_id", command.payload().get("task_id")),
                        Map.entry("trace_id", command.payload().get("trace_id")),
                        Map.entry("chunk_index", index),
                        Map.entry("chunk_count", 2),
                        Map.entry("output_encoding", "identity"),
                        Map.entry("payload", payload),
                        Map.entry("payload_sha256", TaskOutputCodec.sha256(payload)),
                        Map.entry("output_sha256", TaskOutputCodec.sha256(fullOutput)),
                        Map.entry("output_bytes", fullOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
