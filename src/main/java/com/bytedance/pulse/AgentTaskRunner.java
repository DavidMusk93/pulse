package com.bytedance.pulse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
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
    private static final int STREAM_CHUNK_CHARS = 32 * 1024;
    private static final int MAX_STREAM_CHUNKS_PER_DRAIN = 32;
    private static final int REPLY_REPLAY_SENDS = 3;
    private static final long DEFAULT_TASK_TIMEOUT_MS = 600_000;
    private final String agentId;
    private final Clock clock;
    private final String taskDir;
    private final Path workDir;
    private final Path filesDir;
    private final Path workspaceDir;
    private final Path spoolDir;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<PendingReply> pendingReplies = new ConcurrentLinkedQueue<>();
    private final Set<String> acceptedTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, RunningTask> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, ReceivedFile> receivedFiles = new ConcurrentHashMap<>();

    public AgentTaskRunner(String agentId, Clock clock) {
        this(
                agentId,
                clock,
                System.getenv().getOrDefault("PULSE_TASK_DIR", "/data24/otf/pulse/tasks"),
                Math.max(1, Integer.parseInt(System.getenv().getOrDefault("PULSE_TASK_MAX_CONCURRENCY", "1"))));
    }

    AgentTaskRunner(String agentId, Clock clock, String taskDir) {
        this(agentId, clock, taskDir, 1);
    }

    private AgentTaskRunner(String agentId, Clock clock, String taskDir, int concurrency) {
        this.agentId = agentId;
        this.clock = clock;
        this.taskDir = taskDir;
        String defaultWorkDir = Path.of(taskDir).getParent() == null
                ? "/data24/otf/pulse/agent"
                : Path.of(taskDir).getParent().resolve("agent").toString();
        this.workDir = Path.of(System.getenv().getOrDefault("PULSE_AGENT_WORK_DIR", defaultWorkDir));
        this.filesDir = workDir.resolve("files");
        this.workspaceDir = workDir.resolve("workspace");
        this.spoolDir = workDir.resolve("spool");
        this.executor = Executors.newFixedThreadPool(concurrency);
        try {
            Files.createDirectories(filesDir);
            Files.createDirectories(workspaceDir);
            Files.createDirectories(spoolDir.resolve("incoming"));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize agent work dir", exception);
        }
    }

    public void handleMessages(List<PulseMessage> messages) {
        for (PulseMessage message : messages) {
            if ("cmd.file_put".equals(message.type()) && message.payload() != null) {
                handleFilePut(message);
            } else if ("cmd.shell_execute".equals(message.type()) && message.payload() != null) {
                handleShellExecute(message);
            } else if ("cmd.task_execute".equals(message.type()) && message.payload() != null) {
                handleTask(message);
            }
        }
    }

    public List<PulseMessage> drainReplies() {
        drainOutputChunksToPendingReplies();
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
                .map(task -> task.toPayload(clock.millis()))
                .toList();
    }

    private void handleFilePut(PulseMessage message) {
        Map<String, Object> payload = message.payload();
        String fileId = stringValue(payload, "file_id");
        String taskId = stringValue(payload, "task_id");
        String fileName = stringValue(payload, "file_name");
        String fileRole = stringValue(payload, "file_role");
        String targetDir = stringValue(payload, "target_dir");
        String expectedSha = stringValue(payload, "content_sha256");
        long expectedBytes = longValue(payload, "content_bytes", -1);
        try {
            if (fileId.isBlank()) {
                throw new IllegalArgumentException("file_id is required");
            }
            String safeName = safeFileName(fileName);
            byte[] content = Base64.getDecoder().decode(stringValue(payload, "content"));
            if (expectedBytes >= 0 && content.length != expectedBytes) {
                throw new IllegalArgumentException("content byte size mismatch");
            }
            if (!TaskOutputCodec.sha256(content).equals(expectedSha)) {
                throw new IllegalArgumentException("content sha256 mismatch");
            }
            Path target = targetPath(fileId, taskId, safeName, fileRole, targetDir);
            Files.createDirectories(target.getParent());
            Path tmp = spoolDir.resolve("incoming").resolve(fileId + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            target.toFile().setReadable(true, true);
            target.toFile().setWritable(true, true);
            target.toFile().setExecutable("shell_script".equals(fileRole), true);
            ReceivedFile received = new ReceivedFile(fileId, taskId, target, expectedSha, content.length, "shell_script".equals(fileRole));
            receivedFiles.put(fileId, received);
            enqueueFileReply(message.messageId(), taskId, fileId, safeName, "received", target.toString(), expectedSha, content.length, "");
        } catch (Exception exception) {
            enqueueFileReply(message.messageId(), taskId, fileId, fileName, "rejected", "", expectedSha, Math.max(0, expectedBytes), exception.getMessage());
        }
    }

    private void handleShellExecute(PulseMessage message) {
        Map<String, Object> payload = message.payload();
        String taskId = stringValue(payload, "task_id");
        String traceId = stringValue(payload, "trace_id");
        String fileId = stringValue(payload, "script_file_id");
        String expectedSha = stringValue(payload, "script_sha256");
        long timeoutMs = longValue(payload, "timeout_ms", DEFAULT_TASK_TIMEOUT_MS);
        if (taskId.isBlank() || !acceptedTasks.add(taskId)) {
            return;
        }
        ReceivedFile file = receivedFiles.get(fileId);
        if (file == null || !file.executable() || !file.sha256().equals(expectedSha)) {
            enqueueResultMessages(resultMessages(message.messageId(), taskId, traceId, "shell_script", "rejected", null, 0, 0, "", "script file is not staged"));
            return;
        }
        List<String> args;
        try {
            args = normalizeArgs(argsValue(payload), List.of());
        } catch (IllegalArgumentException exception) {
            enqueueResultMessages(resultMessages(message.messageId(), taskId, traceId, "shell_script", "rejected", null, 0, 0, "", exception.getMessage()));
            return;
        }
        long acceptedAt = clock.millis();
        runningTasks.put(taskId, new RunningTask(taskId, traceId, "shell_script", "accepted", acceptedAt, null, timeoutMs));
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
                        "task_type", "shell_script",
                        "status", "accepted",
                        "accepted_at_ms", acceptedAt)), REPLY_REPLAY_SENDS));
        executor.submit(() -> executeScript(message.messageId(), taskId, traceId, "shell_script", file.path(), args, timeoutMs, workspaceDir));
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
        if (definition == null || !definition.scriptPath().equals(scriptPath)) {
            enqueueResultMessages(resultMessages(message.messageId(), taskId, traceId, taskType, "rejected", null, 0, 0, "", "task is not in agent allowlist"));
            return;
        }
        List<String> args;
        try {
            args = normalizeArgs(argsValue(payload), definition.args());
        } catch (IllegalArgumentException exception) {
            enqueueResultMessages(resultMessages(message.messageId(), taskId, traceId, taskType, "rejected", null, 0, 0, "", exception.getMessage()));
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
        executor.submit(() -> execute(message.messageId(), taskId, traceId, definition, args, timeoutMs));
    }

    private void execute(String replyTo, String taskId, String traceId, TaskDefinition definition, List<String> args, long timeoutMs) {
        executeScript(replyTo, taskId, traceId, definition.taskType(), Path.of(definition.scriptPath()), args, timeoutMs, null);
    }

    private void executeScript(
            String replyTo,
            String taskId,
            String traceId,
            String taskType,
            Path scriptPath,
            List<String> args,
            long timeoutMs,
            Path processWorkDir) {
        long started = clock.millis();
        runningTasks.computeIfPresent(taskId, (ignored, task) -> task.withRunning(started));
        Process process = null;
        StringBuilder output = new StringBuilder();
        Thread outputReader = null;
        try {
            if (!Files.isRegularFile(scriptPath)) {
                enqueueResultMessages(resultMessages(replyTo, taskId, traceId, taskType, "failed", null, started, clock.millis(), "", "script not found"));
                return;
            }
            List<String> command = new ArrayList<>();
            if (scriptPath.toString().endsWith(".py")) {
                command.add("python3");
            } else {
                command.add("bash");
            }
            command.add(scriptPath.toString());
            command.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (processWorkDir != null) {
                builder.directory(processWorkDir.toFile());
            }
            process = builder.start();
            Process runningProcess = process;
            outputReader = new Thread(() -> readOutput(taskId, runningProcess, output), "pulse-task-output-" + taskId);
            outputReader.setDaemon(true);
            outputReader.start();
            boolean finished = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            long finishedAt = clock.millis();
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(outputReader);
                String currentOutput = output.toString();
                enqueueResultMessages(resultMessages(
                        replyTo,
                        taskId,
                        traceId,
                        taskType,
                        "timed_out",
                        null,
                        started,
                        finishedAt,
                        appendError(currentOutput, "task timed out"),
                        "task timed out"));
                return;
            }
            joinQuietly(outputReader);
            int exitCode = process.exitValue();
            String finalOutput = output.toString();
            enqueueResultMessages(resultMessages(
                    replyTo,
                    taskId,
                    traceId,
                    taskType,
                    exitCode == 0 ? "completed" : "failed",
                    exitCode,
                    started,
                    finishedAt,
                    finalOutput,
                    exitCode == 0 ? "" : "exit_code=" + exitCode));
        } catch (Exception exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            enqueueResultMessages(resultMessages(replyTo, taskId, traceId, taskType, "failed", null, started, clock.millis(), "", exception.getMessage()));
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private void readOutput(String taskId, Process process, StringBuilder output) {
        char[] buffer = new char[STREAM_CHUNK_CHARS];
        try (java.io.Reader reader = new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                String chunk = new String(buffer, 0, read);
                output.append(chunk);
                RunningTask task = runningTasks.get(taskId);
                if (task != null) {
                    task.enqueueOutput(chunk, clock.millis());
                }
            }
        } catch (Exception exception) {
            RunningTask task = runningTasks.get(taskId);
            if (task != null) {
                task.markBackpressure();
            }
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainOutputChunksToPendingReplies() {
        for (RunningTask task : runningTasks.values()) {
            int drained = 0;
            OutputChunk chunk;
            while (drained < MAX_STREAM_CHUNKS_PER_DRAIN && (chunk = task.pollOutput()) != null) {
                drained++;
                pendingReplies.add(new PendingReply(streamMessage(task, chunk), REPLY_REPLAY_SENDS));
            }
        }
    }

    private PulseMessage streamMessage(RunningTask task, OutputChunk chunk) {
        return new PulseMessage(
                "reply-task-output-" + agentId + "-" + task.taskId() + "-" + chunk.streamSeq(),
                "reply.task_output_append",
                1,
                "cmd-task-" + task.taskId(),
                null,
                Map.ofEntries(
                        Map.entry("task_id", task.taskId()),
                        Map.entry("agent_id", agentId),
                        Map.entry("task_type", task.taskType()),
                        Map.entry("stream_id", "output"),
                        Map.entry("stream_seq", chunk.streamSeq()),
                        Map.entry("stream_offset", chunk.streamOffset()),
                        Map.entry("output_encoding", TaskOutputCodec.IDENTITY),
                        Map.entry("output_type", "text"),
                        Map.entry("payload", chunk.payload()),
                        Map.entry("payload_sha256", TaskOutputCodec.sha256(chunk.payload())),
                        Map.entry("payload_bytes", chunk.payloadBytes()),
                        Map.entry("payload_lines", chunk.payloadLines()),
                        Map.entry("observed_at_ms", chunk.observedAtMs())));
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
        resultPayload.put("output_lines", lineCount(output));
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
            case "repair_corrupt_sqlite3_dry_run" -> new TaskDefinition(
                    "repair_corrupt_sqlite3_dry_run",
                    taskDir + "/repair-corrupt-sqlite3.sh",
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

    private void enqueueFileReply(
            String replyTo,
            String taskId,
            String fileId,
            String fileName,
            String status,
            String localPath,
            String contentSha256,
            long contentBytes,
            String error) {
        pendingReplies.add(new PendingReply(new PulseMessage(
                "reply-file-received-" + agentId + "-" + fileId,
                "reply.file_received",
                1,
                replyTo,
                null,
                Map.of(
                        "task_id", taskId == null ? "" : taskId,
                        "file_id", fileId == null ? "" : fileId,
                        "agent_id", agentId,
                        "status", status,
                        "file_name", fileName == null ? "" : fileName,
                        "local_path", localPath == null ? "" : localPath,
                        "content_sha256", contentSha256 == null ? "" : contentSha256,
                        "content_bytes", contentBytes,
                        "received_at_ms", clock.millis(),
                        "runner_error", error == null ? "" : error)), REPLY_REPLAY_SENDS));
    }

    private Path targetPath(String fileId, String taskId, String safeName, String fileRole, String targetDir) throws Exception {
        Path base;
        if ("shell_script".equals(fileRole)) {
            String safeTaskId = taskId == null || taskId.isBlank() ? fileId : safeFileName(taskId);
            base = workspaceDir.resolve("scripts").resolve(safeTaskId);
            safeName = "script.sh";
        } else if ("workspace".equals(targetDir)) {
            base = workspaceDir.resolve("files");
        } else {
            base = filesDir;
        }
        Path canonicalBase = base.toAbsolutePath().normalize();
        Path target = canonicalBase.resolve(safeName).normalize();
        if (!target.startsWith(canonicalBase)) {
            throw new IllegalArgumentException("target path escapes work dir");
        }
        return target;
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("file_name is required");
        }
        String value = fileName.replace('\\', '/');
        int lastSlash = value.lastIndexOf('/');
        if (lastSlash >= 0) {
            value = value.substring(lastSlash + 1);
        }
        if (value.isBlank()
                || value.equals(".")
                || value.equals("..")
                || value.contains("..")
                || value.indexOf('\0') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.length() > 128) {
            throw new IllegalArgumentException("invalid file_name");
        }
        return value;
    }

    private static List<String> normalizeArgs(List<String> requestedArgs, List<String> defaultArgs) {
        List<String> fallbackArgs = defaultArgs == null ? List.of() : defaultArgs;
        List<String> args = requestedArgs == null || requestedArgs.isEmpty() ? fallbackArgs : requestedArgs;
        if (args.size() > 32) {
            throw new IllegalArgumentException("too many task args");
        }
        List<String> normalized = new ArrayList<>();
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.length() > 256 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0 || arg.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid task arg");
            }
            normalized.add(arg);
        }
        return normalized.isEmpty() ? List.copyOf(fallbackArgs) : List.copyOf(normalized);
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

    private static String appendError(String stderr, String error) {
        if (stderr == null || stderr.isBlank()) {
            return error;
        }
        return stderr + "\n" + error;
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

    private static long lineCount(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return value.chars().filter(ch -> ch == '\n').count() + (value.endsWith("\n") ? 0 : 1);
    }

    private record PendingReply(PulseMessage message, int remainingSends) {}

    private record ReceivedFile(String fileId, String taskId, Path path, String sha256, long bytes, boolean executable) {}

    private static final class RunningTask {
        private final String taskId;
        private final String traceId;
        private final String taskType;
        private final String status;
        private final long acceptedAtMs;
        private final Long startedAtMs;
        private final long timeoutMs;
        private final SpscArrayQueue<OutputChunk> outputQueue = new SpscArrayQueue<>(1024);
        private long streamSeq;
        private long streamOffset;
        private long streamBytes;
        private long streamLines;
        private long streamChunks;
        private Long lastOutputAtMs;
        private boolean backpressureActive;

        private RunningTask(String taskId, String traceId, String taskType, String status, long acceptedAtMs, Long startedAtMs, long timeoutMs) {
            this.taskId = taskId;
            this.traceId = traceId;
            this.taskType = taskType;
            this.status = status;
            this.acceptedAtMs = acceptedAtMs;
            this.startedAtMs = startedAtMs;
            this.timeoutMs = timeoutMs;
        }

        RunningTask withRunning(long startedAtMs) {
            return new RunningTask(taskId, traceId, taskType, "running", acceptedAtMs, startedAtMs, timeoutMs);
        }

        long acceptedAtMs() {
            return acceptedAtMs;
        }

        String taskId() {
            return taskId;
        }

        String taskType() {
            return taskType;
        }

        void enqueueOutput(String payload, long observedAtMs) {
            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long seq = streamSeq++;
            OutputChunk chunk = new OutputChunk(seq, streamOffset, payload, bytes.length, lineCount(payload), observedAtMs);
            streamOffset += bytes.length;
            streamBytes += bytes.length;
            streamLines += chunk.payloadLines();
            streamChunks++;
            lastOutputAtMs = observedAtMs;
            if (!outputQueue.offer(chunk)) {
                backpressureActive = true;
            }
        }

        OutputChunk pollOutput() {
            return outputQueue.poll();
        }

        void markBackpressure() {
            backpressureActive = true;
        }

        Map<String, Object> toPayload(long now) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("task_id", taskId);
            payload.put("trace_id", traceId);
            payload.put("task_type", taskType);
            payload.put("status", status);
            payload.put("accepted_at_ms", acceptedAtMs);
            payload.put("started_at_ms", startedAtMs == null ? "" : startedAtMs);
            payload.put("updated_at_ms", now);
            payload.put("runtime_ms", startedAtMs == null ? 0 : Math.max(0, now - startedAtMs));
            payload.put("timeout_ms", timeoutMs);
            payload.put("stream_bytes", streamBytes);
            payload.put("stream_chunks", streamChunks);
            payload.put("stream_lines", streamLines);
            payload.put("last_output_at_ms", lastOutputAtMs == null ? "" : lastOutputAtMs);
            payload.put("spooled_bytes", 0);
            payload.put("pending_bytes", outputQueue.size());
            payload.put("backpressure_active", backpressureActive);
            return payload;
        }
    }

    private record OutputChunk(
            long streamSeq,
            long streamOffset,
            String payload,
            long payloadBytes,
            long payloadLines,
            long observedAtMs) {}

    static final class SpscArrayQueue<T> {
        private final Object[] elements;
        private volatile int head;
        private volatile int tail;

        SpscArrayQueue(int capacity) {
            if (capacity < 2) {
                throw new IllegalArgumentException("capacity must be >= 2");
            }
            this.elements = new Object[capacity + 1];
        }

        boolean offer(T value) {
            int nextTail = (tail + 1) % elements.length;
            if (nextTail == head) {
                return false;
            }
            elements[tail] = value;
            tail = nextTail;
            return true;
        }

        @SuppressWarnings("unchecked")
        T poll() {
            int currentHead = head;
            if (currentHead == tail) {
                return null;
            }
            Object value = elements[currentHead];
            elements[currentHead] = null;
            head = (currentHead + 1) % elements.length;
            return (T) value;
        }

        int size() {
            int currentTail = tail;
            int currentHead = head;
            return currentTail >= currentHead ? currentTail - currentHead : elements.length - currentHead + currentTail;
        }
    }
}
