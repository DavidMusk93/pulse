package com.bytedance.pulse;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteTaskService {
    private static final int MAX_COMPLETIONS_PER_AGENT = 50;
    private final Clock clock;
    private final Map<String, Queue<RemoteTask>> executionQueues = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RemoteTask>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<TaskResult>> completionQueues = new ConcurrentHashMap<>();
    private final Map<String, List<TaskTraceLogEntry>> traceLogs = new ConcurrentHashMap<>();
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
                now + 120_000,
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
                        "timeout_ms", 120_000,
                        "created_at_ms", delivered.createdAtMs())));
    }

    public synchronized void handleReplies(String agentId, List<PulseMessage> messages) {
        for (PulseMessage message : messages) {
            if (!"reply.task_accepted".equals(message.type()) && !"reply.task_result".equals(message.type())) {
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
            } else {
                TaskResult result = resultFrom(agentId, payload);
                inFlight(agentId).remove(taskId);
                ArrayDeque<TaskResult> completions = completions(agentId);
                completions.addLast(result);
                while (completions.size() > MAX_COMPLETIONS_PER_AGENT) {
                    completions.removeFirst();
                }
                trace(task, "task.result_received", "agent", agentId, Map.of("status", result.status()));
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
        trace(new TaskResult(taskId, "trace-unknown", agentId, "", "popped", null, 0, 0, 0, "", "", false, null),
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

    private static TaskResult resultFrom(String agentId, Map<String, Object> payload) {
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
                stringValue(payload, "stdout_tail"),
                stringValue(payload, "stderr_tail"),
                booleanValue(payload, "output_truncated"),
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
        String stdoutTail,
        String stderrTail,
        boolean outputTruncated,
        String runnerError) {}

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
