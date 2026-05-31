package com.bytedance.pulse;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteTaskService {
    private static final int MAX_COMPLETIONS_PER_AGENT = 50;
    private static final long DEFAULT_TASK_TIMEOUT_MS = 600_000;
    private final Clock clock;
    private final Map<String, Queue<RemoteTask>> executionQueues = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RemoteTask>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<TaskResult>> completionQueues = new ConcurrentHashMap<>();
    private final Map<String, List<TaskTraceLogEntry>> traceLogs = new ConcurrentHashMap<>();
    private final Map<String, ResultAssembly> pendingResults = new ConcurrentHashMap<>();
    private final Map<String, TaskDefinition> taskDefinitions;

    public RemoteTaskService(Clock clock) {
        this.clock = clock;
        String taskDir = System.getenv().getOrDefault("PULSE_TASK_DIR", "/data24/otf/pulse/tasks");
        this.taskDefinitions = Map.of(
                "prepare_disk_layout_dry_run",
                new TaskDefinition(
                        "prepare_disk_layout_dry_run",
                        taskDir + "/prepare-disk-layout.sh",
                        List.of("--dry-run")),
                "analyze_block_layout_dry_run",
                new TaskDefinition(
                        "analyze_block_layout_dry_run",
                        taskDir + "/analyze-block-layout.py",
                        List.of("--dry-run")));
    }

    public synchronized TaskSnapshot snapshot(String agentId) {
        return new TaskSnapshot(
                agentId,
                List.copyOf(queue(agentId)),
                List.copyOf(completions(agentId)),
                traceLogs.values().stream()
                        .flatMap(List::stream)
                        .filter(entry -> agentId.equals(entry.agentId()))
                        .sorted((left, right) -> Long.compare(right.observedAtMs(), left.observedAtMs()))
                        .limit(50)
                        .toList(),
                List.copyOf(taskDefinitions.keySet()));
    }

    public synchronized TaskSnapshot enqueue(String agentId, String taskType) {
        TaskDefinition definition = taskDefinitions.get(taskType);
        if (definition == null) {
            throw new IllegalArgumentException("unknown task_type: " + taskType);
        }
        long now = clock.millis();
        String taskId = "task-" + UUID.randomUUID();
        String traceId = "trace-" + UUID.randomUUID();
        RemoteTask task = new RemoteTask(
                taskId,
                traceId,
                agentId,
                taskType,
                definition.scriptPath(),
                definition.args(),
                "queued",
                now,
                null,
                null,
                null,
                null,
                now + DEFAULT_TASK_TIMEOUT_MS,
                "pulse-ui",
                1);
        queue(agentId).add(task);
        trace(task, "task.enqueued", "ui", "pulse-ui", Map.of("task_type", taskType));
        return snapshot(agentId);
    }

    public synchronized Optional<PulseMessage> nextCommand(String agentId) {
        Queue<RemoteTask> queue = queue(agentId);
        RemoteTask task = queue.poll();
        if (task == null) {
            return Optional.empty();
        }
        long now = clock.millis();
        RemoteTask delivered = task.withStatus("delivered", now, task.acceptedAtMs(), task.startedAtMs(), task.finishedAtMs());
        inFlight(agentId).put(task.taskId(), delivered);
        trace(delivered, "task.dequeued_for_delivery", "coordinator", "coordinator", Map.of());
        return Optional.of(new PulseMessage(
                "cmd-task-" + task.taskId(),
                "cmd.task_execute",
                1,
                null,
                delivered.deadlineMs(),
                Map.of(
                        "task_id", delivered.taskId(),
                        "trace_id", delivered.traceId(),
                        "agent_id", delivered.agentId(),
                        "task_type", delivered.taskType(),
                        "script_path", delivered.scriptPath(),
                        "args", delivered.args(),
                        "timeout_ms", DEFAULT_TASK_TIMEOUT_MS,
                        "created_at_ms", delivered.createdAtMs())));
    }

    public synchronized void handleReplies(String agentId, List<PulseMessage> messages) {
        for (PulseMessage message : messages) {
            if (!"reply.task_accepted".equals(message.type())
                    && !"reply.task_result".equals(message.type())
                    && !"reply.task_result_chunk".equals(message.type())) {
                continue;
            }
            Map<String, Object> payload = message.payload();
            if (payload == null) {
                continue;
            }
            String taskId = stringValue(payload, "task_id");
            String traceId = stringValue(payload, "trace_id");
            RemoteTask task = inFlight(agentId).get(taskId);
            if (task == null && !taskId.isBlank()) {
                task = new RemoteTask(
                        taskId,
                        traceId.isBlank() ? "trace-unknown" : traceId,
                        agentId,
                        stringValue(payload, "task_type"),
                        "",
                        List.of("--dry-run"),
                        "unknown",
                        clock.millis(),
                        null,
                        null,
                        null,
                        null,
                        clock.millis(),
                        "agent",
                        1);
            }
            if ("reply.task_accepted".equals(message.type())) {
                RemoteTask accepted = task.withStatus("accepted", task.deliveredAtMs(), clock.millis(), task.startedAtMs(), task.finishedAtMs());
                inFlight(agentId).put(taskId, accepted);
                trace(accepted, "task.accepted_by_agent", "agent", agentId, Map.of());
            } else if ("reply.task_result_chunk".equals(message.type())) {
                Optional<TaskResult> result = acceptChunk(agentId, payload);
                if (result.isPresent()) {
                    complete(agentId, task, result.get());
                }
            } else {
                Optional<TaskResult> result = acceptResult(agentId, payload);
                if (result.isPresent()) {
                    complete(agentId, task, result.get());
                }
            }
        }
    }

    public synchronized TaskSnapshot keepCompletion(String agentId, String taskId) {
        latestCompletion(agentId, taskId).ifPresent(result ->
                trace(result, "task.completion_kept", "ui", "pulse-ui", Map.of()));
        return snapshot(agentId);
    }

    public synchronized TaskSnapshot popCompletion(String agentId, String taskId) {
        ArrayDeque<TaskResult> completions = completions(agentId);
        completions.removeIf(result -> result.taskId().equals(taskId));
        trace(new TaskResult(taskId, "trace-unknown", agentId, "", "popped", null, 0, 0, 0, "", "text", TaskOutputCodec.IDENTITY, TaskOutputCodec.sha256(""), 0, null),
                "task.completion_popped",
                "ui",
                "pulse-ui",
                Map.of());
        return snapshot(agentId);
    }

    private Optional<TaskResult> latestCompletion(String agentId, String taskId) {
        return completions(agentId).stream()
                .filter(result -> result.taskId().equals(taskId))
                .findFirst();
    }

    private Queue<RemoteTask> queue(String agentId) {
        return executionQueues.computeIfAbsent(agentId, ignored -> new ArrayDeque<>());
    }

    private Map<String, RemoteTask> inFlight(String agentId) {
        return inFlight.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>());
    }

    private ArrayDeque<TaskResult> completions(String agentId) {
        return completionQueues.computeIfAbsent(agentId, ignored -> new ArrayDeque<>());
    }

    private void trace(RemoteTask task, String event, String actor, String sourceId, Map<String, Object> detail) {
        traceLogs.computeIfAbsent(task.traceId(), ignored -> new ArrayList<>())
                .add(new TaskTraceLogEntry(
                        task.traceId(),
                        task.taskId(),
                        task.agentId(),
                        event,
                        actor,
                        sourceId,
                        clock.millis(),
                        detail));
    }

    private void trace(TaskResult result, String event, String actor, String sourceId, Map<String, Object> detail) {
        traceLogs.computeIfAbsent(result.traceId(), ignored -> new ArrayList<>())
                .add(new TaskTraceLogEntry(
                        result.traceId(),
                        result.taskId(),
                        result.agentId(),
                        event,
                        actor,
                        sourceId,
                        clock.millis(),
                        detail));
    }

    private void complete(String agentId, RemoteTask task, TaskResult result) {
        inFlight(agentId).remove(result.taskId());
        pendingResults.remove(assemblyKey(agentId, result.taskId()));
        ArrayDeque<TaskResult> completions = completions(agentId);
        completions.removeIf(existing -> existing.taskId().equals(result.taskId()));
        completions.addLast(result);
        while (completions.size() > MAX_COMPLETIONS_PER_AGENT) {
            completions.removeFirst();
        }
        trace(task, "task.result_received", "agent", agentId, Map.of("status", result.status(), "output_sha256", result.outputSha256()));
    }

    private Optional<TaskResult> acceptResult(String agentId, Map<String, Object> payload) {
        if (!booleanValue(payload, "output_chunked")) {
            return Optional.of(resultFrom(agentId, payload, stringValue(payload, "output")));
        }
        ResultAssembly assembly = assembly(agentId, stringValue(payload, "task_id"));
        assembly.metadata = new LinkedHashMap<>(payload);
        return assembly.tryBuild(agentId);
    }

    private Optional<TaskResult> acceptChunk(String agentId, Map<String, Object> payload) {
        String taskId = stringValue(payload, "task_id");
        ResultAssembly assembly = assembly(agentId, taskId);
        int chunkIndex = intValue(payload, "chunk_index") == null ? -1 : intValue(payload, "chunk_index");
        int chunkCount = intValue(payload, "chunk_count") == null ? -1 : intValue(payload, "chunk_count");
        String chunk = stringValue(payload, "payload");
        String expectedPayloadSha = stringValue(payload, "payload_sha256");
        if (chunkIndex < 0 || chunkCount <= 0 || !TaskOutputCodec.sha256(chunk).equals(expectedPayloadSha)) {
            return Optional.empty();
        }
        assembly.chunkCount = chunkCount;
        assembly.chunks.put(chunkIndex, chunk);
        return assembly.tryBuild(agentId);
    }

    private ResultAssembly assembly(String agentId, String taskId) {
        return pendingResults.computeIfAbsent(assemblyKey(agentId, taskId), ignored -> new ResultAssembly());
    }

    private static String assemblyKey(String agentId, String taskId) {
        return agentId + ":" + taskId;
    }

    static TaskResult resultFrom(String agentId, Map<String, Object> payload, String encodedOutput) {
        String encoding = stringValue(payload, "output_encoding");
        String output = TaskOutputCodec.decode(encodedOutput, encoding);
        String outputSha = stringValue(payload, "output_sha256");
        long outputBytes = longValue(payload, "output_bytes");
        if (!TaskOutputCodec.sha256(output).equals(outputSha)) {
            throw new IllegalArgumentException("task output sha256 mismatch");
        }
        if (output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != outputBytes) {
            throw new IllegalArgumentException("task output byte size mismatch");
        }
        return new TaskResult(
                stringValue(payload, "task_id"),
                stringValue(payload, "trace_id"),
                agentId,
                stringValue(payload, "task_type"),
                stringValue(payload, "status"),
                intValue(payload, "exit_code"),
                longValue(payload, "started_at_ms"),
                longValue(payload, "finished_at_ms"),
                longValue(payload, "duration_ms"),
                output,
                stringValue(payload, "output_type"),
                encoding,
                outputSha,
                outputBytes,
                stringValue(payload, "runner_error"));
    }

    private static String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    private static Integer intValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null || value.toString().isBlank() ? null : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long longValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || value.toString().isBlank() ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean booleanValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }
}

record TaskDefinition(String taskType, String scriptPath, List<String> args) {}

record RemoteTask(
        String taskId,
        String traceId,
        String agentId,
        String taskType,
        String scriptPath,
        List<String> args,
        String status,
        long createdAtMs,
        Long deliveredAtMs,
        Long acceptedAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        long deadlineMs,
        String createdBy,
        int attempt) {
    RemoteTask withStatus(String nextStatus, Long nextDeliveredAtMs, Long nextAcceptedAtMs, Long nextStartedAtMs, Long nextFinishedAtMs) {
        return new RemoteTask(
                taskId,
                traceId,
                agentId,
                taskType,
                scriptPath,
                args,
                nextStatus,
                createdAtMs,
                nextDeliveredAtMs,
                nextAcceptedAtMs,
                nextStartedAtMs,
                nextFinishedAtMs,
                deadlineMs,
                createdBy,
                attempt);
    }
}

record TaskResult(
        String taskId,
        String traceId,
        String agentId,
        String taskType,
        String status,
        Integer exitCode,
        long startedAtMs,
        long finishedAtMs,
        long durationMs,
        String output,
        String outputType,
        String outputEncoding,
        String outputSha256,
        long outputBytes,
        String runnerError) {}

final class ResultAssembly {
    Map<String, Object> metadata;
    int chunkCount = -1;
    final Map<Integer, String> chunks = new HashMap<>();

    Optional<TaskResult> tryBuild(String agentId) {
        if (metadata == null || chunkCount <= 0 || chunks.size() < chunkCount) {
            return Optional.empty();
        }
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < chunkCount; index++) {
            String chunk = chunks.get(index);
            if (chunk == null) {
                return Optional.empty();
            }
            output.append(chunk);
        }
        return Optional.of(RemoteTaskService.resultFrom(agentId, metadata, output.toString()));
    }
}

record TaskTraceLogEntry(
        String traceId,
        String taskId,
        String agentId,
        String event,
        String actor,
        String sourceId,
        long observedAtMs,
        Map<String, Object> detail) {}

record TaskSnapshot(
        String agentId,
        List<RemoteTask> executionQueue,
        List<TaskResult> completionQueue,
        List<TaskTraceLogEntry> traces,
        List<String> taskTypes) {}
