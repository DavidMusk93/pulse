package com.bytedance.pulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AgentTaskRunner {
    private static final int RESULT_INLINE_CHARS = 48 * 1024;
    private static final int RESULT_CHUNK_CHARS = 48 * 1024;
    private static final int REPLY_REPLAY_SENDS = 3;
    private static final long DEFAULT_TASK_TIMEOUT_MS = 600_000;
    private final String agentId;
    private final Clock clock;
    private final String taskDir;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<PendingReply> pendingReplies = new ConcurrentLinkedQueue<>();
    private final Set<String> acceptedTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, RunningTask> runningTasks = new ConcurrentHashMap<>();

    public AgentTaskRunner(String agentId, Clock clock) {
        this.agentId = agentId;
        this.clock = clock;
        this.taskDir = System.getenv().getOrDefault("PULSE_TASK_DIR", "/data24/otf/pulse/tasks");
        int concurrency = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("PULSE_TASK_MAX_CONCURRENCY", "1")));
        this.executor = Executors.newFixedThreadPool(concurrency);
    }

    public void handleMessages(List<PulseMessage> messages) {
        for (PulseMessage message : messages) {
            if (!"cmd.task_execute".equals(message.type()) || message.payload() == null) {
                continue;
            }
            handleTask(message);
        }
    }

    public List<PulseMessage> drainReplies() {
        List<PulseMessage> replies = new ArrayList<>();
        int size = pendingReplies.size();
        for (int index = 0; index < size; index++) {
            PendingReply reply = pendingReplies.poll();
            if (reply == null) {
                break;
            }
            replies.add(reply.message());
            if (reply.remainingSends() > 1) {
                pendingReplies.add(new PendingReply(reply.message(), reply.remainingSends() - 1));
            }
        }
        return replies;
    }

    public List<Map<String, Object>> runningTasks() {
        return runningTasks.values().stream()
                .sorted(Comparator.comparingLong(RunningTask::acceptedAtMs))
                .map(RunningTask::toPayload)
                .toList();
    }

    private void handleTask(PulseMessage message) {
        Map<String, Object> payload = message.payload();
        String taskId = stringValue(payload, "task_id");
        String traceId = stringValue(payload, "trace_id");
        String taskType = stringValue(payload, "task_type");
        String scriptPath = stringValue(payload, "script_path");
        long timeoutMs = longValue(payload, "timeout_ms", DEFAULT_TASK_TIMEOUT_MS);
        if (taskId.isBlank() || !acceptedTasks.add(taskId)) {
            return;
        }
        TaskDefinition definition = definition(taskType);
        if (definition == null || !definition.scriptPath().equals(scriptPath) || !definition.args().equals(argsValue(payload))) {
            enqueueResultMessages(resultMessages(message.messageId(), taskId, traceId, taskType, "rejected", null, 0, 0, "", "task is not in agent allowlist"));
            return;
        }
        long acceptedAt = clock.millis();
        runningTasks.put(taskId, new RunningTask(taskId, traceId, taskType, "accepted", acceptedAt, null, timeoutMs));
        pendingReplies.add(new PendingReply(new PulseMessage(
                "reply-task-accepted-" + agentId + "-" + taskId,
                "reply.task_accepted",
                1,
                message.messageId(),
                null,
                Map.of(
                        "task_id", taskId,
                        "trace_id", traceId,
                        "agent_id", agentId,
                        "task_type", taskType,
                        "status", "accepted",
                        "accepted_at_ms", acceptedAt)), REPLY_REPLAY_SENDS));
        executor.submit(() -> execute(message.messageId(), taskId, traceId, definition, timeoutMs));
    }

    private void execute(String replyTo, String taskId, String traceId, TaskDefinition definition, long timeoutMs) {
        long started = clock.millis();
        runningTasks.computeIfPresent(taskId, (ignored, task) -> task.withRunning(started));
        Process process = null;
        Path stdoutFile = null;
        Path stderrFile = null;
        try {
            if (!Files.isRegularFile(Path.of(definition.scriptPath()))) {
                enqueueResultMessages(resultMessages(replyTo, taskId, traceId, definition.taskType(), "failed", null, started, clock.millis(), "", "script not found"));
                return;
            }
            List<String> command = new ArrayList<>();
            if (definition.scriptPath().endsWith(".py")) {
                command.add("python3");
            } else {
                command.add("bash");
            }
            command.add(definition.scriptPath());
            command.addAll(definition.args());
            stdoutFile = Files.createTempFile("pulse-task-" + taskId + "-", ".stdout");
            stderrFile = Files.createTempFile("pulse-task-" + taskId + "-", ".stderr");
            process = new ProcessBuilder(command)
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();
            boolean finished = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            long finishedAt = clock.millis();
            if (!finished) {
                process.destroyForcibly();
                enqueueResultMessages(resultMessages(
                        replyTo,
                        taskId,
                        traceId,
                        definition.taskType(),
                        "timed_out",
                        null,
                        started,
                        finishedAt,
                        readUtf8(stdoutFile),
                        appendError(readUtf8(stderrFile), "task timed out")));
                return;
            }
            int exitCode = process.exitValue();
            enqueueResultMessages(resultMessages(
                    replyTo,
                    taskId,
                    traceId,
                    definition.taskType(),
                    exitCode == 0 ? "completed" : "failed",
                    exitCode,
                    started,
                    finishedAt,
                    readUtf8(stdoutFile),
                    readUtf8(stderrFile)));
        } catch (Exception exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            enqueueResultMessages(resultMessages(replyTo, taskId, traceId, definition.taskType(), "failed", null, started, clock.millis(), "", exception.getMessage()));
        } finally {
            deleteQuietly(stdoutFile);
            deleteQuietly(stderrFile);
            runningTasks.remove(taskId);
        }
    }

    private void enqueueResultMessages(List<PulseMessage> messages) {
        messages.forEach(message -> pendingReplies.add(new PendingReply(message, REPLY_REPLAY_SENDS)));
    }

    private List<PulseMessage> resultMessages(
            String replyTo,
            String taskId,
            String traceId,
            String taskType,
            String status,
            Integer exitCode,
            long started,
            long finished,
            String output,
            String runnerError) {
        TaskOutputCodec.EncodedOutput encoded = TaskOutputCodec.encode(output);
        boolean chunked = encoded.value().length() > RESULT_INLINE_CHARS;
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("task_id", taskId);
        resultPayload.put("trace_id", traceId);
        resultPayload.put("agent_id", agentId);
        resultPayload.put("task_type", taskType);
        resultPayload.put("status", status);
        resultPayload.put("exit_code", exitCode == null ? "" : exitCode);
        resultPayload.put("started_at_ms", started);
        resultPayload.put("finished_at_ms", finished);
        resultPayload.put("duration_ms", Math.max(0, finished - started));
        resultPayload.put("output_type", detectOutputType(output));
        resultPayload.put("output_encoding", encoded.encoding());
        resultPayload.put("output_sha256", encoded.sha256());
        resultPayload.put("output_bytes", encoded.bytes());
        resultPayload.put("output_chunked", chunked);
        resultPayload.put("output_chunk_count", chunked ? chunkCount(encoded.value()) : 0);
        resultPayload.put("runner_error", runnerError == null ? "" : runnerError);
        if (!chunked) {
            resultPayload.put("output", encoded.value());
        }

        List<PulseMessage> messages = new ArrayList<>();
        messages.add(new PulseMessage(
                "reply-task-result-" + agentId + "-" + taskId,
                "reply.task_result",
                1,
                replyTo,
                null,
                resultPayload));
        if (chunked) {
            for (int index = 0; index < chunkCount(encoded.value()); index++) {
                String chunk = chunk(encoded.value(), index);
                messages.add(new PulseMessage(
                        "reply-task-result-chunk-" + agentId + "-" + taskId + "-" + index,
                        "reply.task_result_chunk",
                        1,
                        replyTo,
                        null,
                        Map.ofEntries(
                                Map.entry("task_id", taskId),
                                Map.entry("trace_id", traceId),
                                Map.entry("agent_id", agentId),
                                Map.entry("chunk_index", index),
                                Map.entry("chunk_count", chunkCount(encoded.value())),
                                Map.entry("output_encoding", encoded.encoding()),
                                Map.entry("payload", chunk),
                                Map.entry("payload_sha256", TaskOutputCodec.sha256(chunk)),
                                Map.entry("output_sha256", encoded.sha256()),
                                Map.entry("output_bytes", encoded.bytes()))));
            }
        }
        return messages;
    }

    private TaskDefinition definition(String taskType) {
        return switch (taskType) {
            case "prepare_disk_layout_dry_run" -> new TaskDefinition(
                    "prepare_disk_layout_dry_run",
                    taskDir + "/prepare-disk-layout.sh",
                    List.of("--dry-run"));
            case "analyze_block_layout_dry_run" -> new TaskDefinition(
                    "analyze_block_layout_dry_run",
                    taskDir + "/analyze-block-layout.py",
                    List.of("--dry-run"));
            default -> null;
        };
    }

    private static List<String> argsValue(Map<String, Object> payload) {
        Object value = payload.get("args");
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private static String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    private static long longValue(Map<String, Object> payload, String key, long defaultValue) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || value.toString().isBlank() ? defaultValue : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String readUtf8(Path path) throws java.io.IOException {
        return path == null ? "" : Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String appendError(String stderr, String error) {
        if (stderr == null || stderr.isBlank()) {
            return error;
        }
        return stderr + "\n" + error;
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // Temporary capture files are best-effort cleanup.
        }
    }

    private static String detectOutputType(String output) {
        String value = output == null ? "" : output.trim();
        return (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]")) ? "json" : "text";
    }

    private static int chunkCount(String value) {
        return Math.max(1, (value.length() + RESULT_CHUNK_CHARS - 1) / RESULT_CHUNK_CHARS);
    }

    private static String chunk(String value, int index) {
        int start = index * RESULT_CHUNK_CHARS;
        int end = Math.min(value.length(), start + RESULT_CHUNK_CHARS);
        return value.substring(start, end);
    }

    private record PendingReply(PulseMessage message, int remainingSends) {}

    private record RunningTask(
            String taskId,
            String traceId,
            String taskType,
            String status,
            long acceptedAtMs,
            Long startedAtMs,
            long timeoutMs) {
        RunningTask withRunning(long startedAtMs) {
            return new RunningTask(taskId, traceId, taskType, "running", acceptedAtMs, startedAtMs, timeoutMs);
        }

        Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task_id", taskId);
            payload.put("trace_id", traceId);
            payload.put("task_type", taskType);
            payload.put("status", status);
            payload.put("accepted_at_ms", acceptedAtMs);
            payload.put("started_at_ms", startedAtMs == null ? "" : startedAtMs);
            payload.put("timeout_ms", timeoutMs);
            return payload;
        }
    }
}
