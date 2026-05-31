package com.bytedance.pulse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int OUTPUT_TAIL_BYTES = 64 * 1024;
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
            pendingReplies.add(new PendingReply(resultMessage(message.messageId(), taskId, traceId, taskType, "rejected", null, 0, 0, "", "", false, "task is not in agent allowlist"), REPLY_REPLAY_SENDS));
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
        try {
            if (!Files.isRegularFile(Path.of(definition.scriptPath()))) {
                pendingReplies.add(new PendingReply(resultMessage(replyTo, taskId, traceId, definition.taskType(), "failed", null, started, clock.millis(), "", "", false, "script not found"), REPLY_REPLAY_SENDS));
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
            process = new ProcessBuilder(command).start();
            Process running = process;
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stdoutThread = new Thread(() -> copyTail(running.getInputStream(), stdout));
            Thread stderrThread = new Thread(() -> copyTail(running.getErrorStream(), stderr));
            stdoutThread.start();
            stderrThread.start();
            boolean finished = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            long finishedAt = clock.millis();
            if (!finished) {
                process.destroyForcibly();
                pendingReplies.add(new PendingReply(resultMessage(replyTo, taskId, traceId, definition.taskType(), "timed_out", null, started, finishedAt, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), false, "task timed out"), REPLY_REPLAY_SENDS));
                return;
            }
            stdoutThread.join(1000);
            stderrThread.join(1000);
            int exitCode = process.exitValue();
            pendingReplies.add(new PendingReply(resultMessage(
                    replyTo,
                    taskId,
                    traceId,
                    definition.taskType(),
                    exitCode == 0 ? "completed" : "failed",
                    exitCode,
                    started,
                    finishedAt,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    false,
                    ""), REPLY_REPLAY_SENDS));
        } catch (Exception exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            pendingReplies.add(new PendingReply(resultMessage(replyTo, taskId, traceId, definition.taskType(), "failed", null, started, clock.millis(), "", "", false, exception.getMessage()), REPLY_REPLAY_SENDS));
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private PulseMessage resultMessage(
            String replyTo,
            String taskId,
            String traceId,
            String taskType,
            String status,
            Integer exitCode,
            long started,
            long finished,
            String stdout,
            String stderr,
            boolean truncated,
            String runnerError) {
        return new PulseMessage(
                "reply-task-result-" + agentId + "-" + taskId,
                "reply.task_result",
                1,
                replyTo,
                null,
                Map.ofEntries(
                        Map.entry("task_id", taskId),
                        Map.entry("trace_id", traceId),
                        Map.entry("agent_id", agentId),
                        Map.entry("task_type", taskType),
                        Map.entry("status", status),
                        Map.entry("exit_code", exitCode == null ? "" : exitCode),
                        Map.entry("started_at_ms", started),
                        Map.entry("finished_at_ms", finished),
                        Map.entry("duration_ms", Math.max(0, finished - started)),
                        Map.entry("stdout_tail", tail(stdout)),
                        Map.entry("stderr_tail", tail(stderr)),
                        Map.entry("output_truncated", truncated || stdout.length() > OUTPUT_TAIL_BYTES || stderr.length() > OUTPUT_TAIL_BYTES),
                        Map.entry("runner_error", runnerError == null ? "" : runnerError)));
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

    private static String tail(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= OUTPUT_TAIL_BYTES) {
            return value;
        }
        return new String(bytes, bytes.length - OUTPUT_TAIL_BYTES, OUTPUT_TAIL_BYTES, StandardCharsets.UTF_8);
    }

    private static void copyTail(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > OUTPUT_TAIL_BYTES * 2) {
                    byte[] bytes = output.toByteArray();
                    output.reset();
                    output.write(bytes, bytes.length - OUTPUT_TAIL_BYTES, OUTPUT_TAIL_BYTES);
                }
            }
        } catch (Exception ignored) {
            // Best-effort output capture must not fail the task runner.
        }
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
