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
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class RemoteTaskService {
    private static final int MAX_COMPLETIONS_PER_AGENT = 50;
    private static final int MAX_TRACES_PER_AGENT = 4;
    private static final long MAX_INLINE_FILE_BYTES = 256 * 1024;
    private static final long DEFAULT_TASK_TIMEOUT_MS = 600_000;
    private final Clock clock;
    private final Map<String, Queue<RemoteTask>> executionQueues = new ConcurrentHashMap<>();
    private final Map<String, Queue<ControlCommand>> controlQueues = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RemoteTask>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Map<String, FileTransferStatus>> fileTransfers = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<TaskResult>> completionQueues = new ConcurrentHashMap<>();
    private final Map<String, List<TaskTraceLogEntry>> traceLogs = new ConcurrentHashMap<>();
    private final Map<String, ResultAssembly> pendingResults = new ConcurrentHashMap<>();
    private final Map<String, TaskStreamLog> streamLogs = new ConcurrentHashMap<>();
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
                        List.of("--dry-run")),
                "repair_corrupt_sqlite3_dry_run",
                new TaskDefinition(
                        "repair_corrupt_sqlite3_dry_run",
                        taskDir + "/repair-corrupt-sqlite3.sh",
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
                        .limit(MAX_TRACES_PER_AGENT)
                        .toList(),
                List.copyOf(taskDefinitions.keySet()),
                fileTransfers(agentId).values().stream()
                        .sorted((left, right) -> Long.compare(right.updatedAtMs(), left.updatedAtMs()))
                        .toList(),
                streamLogs.values().stream()
                        .filter(log -> agentId.equals(log.agentId()))
                        .map(TaskStreamLog::snapshot)
                        .toList());
    }

    public synchronized TaskSnapshot enqueue(String agentId, String taskType) {
        return enqueue(agentId, taskType, null);
    }

    public synchronized TaskSnapshot enqueue(String agentId, String taskType, List<String> requestedArgs) {
        TaskDefinition definition = taskDefinitions.get(taskType);
        if (definition == null) {
            throw new IllegalArgumentException("unknown task_type: " + taskType);
        }
        List<String> args = normalizeArgs(requestedArgs, definition.args());
        long now = clock.millis();
        String taskId = "task-" + UUID.randomUUID();
        String traceId = "trace-" + UUID.randomUUID();
        RemoteTask task = new RemoteTask(
                taskId,
                traceId,
                agentId,
                taskType,
                definition.scriptPath(),
                args,
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
        trace(task, "task.enqueued", "ui", "pulse-ui", Map.of("task_type", taskType, "args", args));
        return snapshot(agentId);
    }

    public synchronized TaskSnapshot enqueueFilePut(
            String agentId,
            String fileName,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            String targetDir,
            String fileRole) {
        byte[] content = decodeContent(contentBase64, contentSha256, contentBytes);
        return enqueueFilePutDecoded(agentId, fileName, content, contentSha256, targetDir, fileRole);
    }

    public synchronized Map<String, TaskSnapshot> enqueueFilePutBatch(
            List<String> agentIds,
            String fileName,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            String targetDir,
            String fileRole) {
        if (agentIds == null || agentIds.isEmpty()) {
            throw new IllegalArgumentException("agent_ids is required");
        }
        byte[] content = decodeContent(contentBase64, contentSha256, contentBytes);
        Map<String, TaskSnapshot> snapshots = new LinkedHashMap<>();
        for (String agentId : agentIds) {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agent_id is required");
            }
            snapshots.put(agentId, enqueueFilePutDecoded(agentId, fileName, content, contentSha256, targetDir, fileRole));
        }
        return snapshots;
    }

    private TaskSnapshot enqueueFilePutDecoded(
            String agentId,
            String fileName,
            byte[] content,
            String contentSha256,
            String targetDir,
            String fileRole) {
        String safeName = safeFileName(fileName);
        String normalizedTarget = normalizeTargetDir(targetDir);
        String normalizedRole = normalizeFileRole(fileRole);
        long now = clock.millis();
        String fileId = "file-" + UUID.randomUUID();
        ControlCommand command = ControlCommand.filePut(
                "cmd-file-put-" + fileId,
                fileId,
                "",
                safeName,
                normalizedRole,
                normalizedTarget,
                Base64.getEncoder().encodeToString(content),
                contentSha256,
                content.length,
                "shell_script".equals(normalizedRole) ? "0700" : "0644",
                now + DEFAULT_TASK_TIMEOUT_MS);
        controlQueue(agentId).add(command);
        fileTransfers(agentId).put(fileId, new FileTransferStatus(
                fileId,
                "",
                agentId,
                safeName,
                normalizedRole,
                normalizedTarget,
                "queued",
                contentSha256,
                content.length,
                "",
                "",
                now,
                now));
        traceFile(agentId, fileId, "", "file.enqueued", Map.of("file_name", safeName, "bytes", content.length, "role", normalizedRole));
        return snapshot(agentId);
    }

    public synchronized TaskSnapshot enqueueShellScript(
            String agentId,
            String fileName,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            List<String> requestedArgs) {
        byte[] content = decodeContent(contentBase64, contentSha256, contentBytes);
        String safeName = safeFileName(fileName == null || fileName.isBlank() ? "script.sh" : fileName);
        List<String> args = normalizeArgs(requestedArgs, List.of());
        long now = clock.millis();
        String taskId = "task-" + UUID.randomUUID();
        String traceId = "trace-" + UUID.randomUUID();
        String fileId = "file-" + UUID.randomUUID();
        ControlCommand fileCommand = ControlCommand.filePut(
                "cmd-file-put-" + fileId,
                fileId,
                taskId,
                safeName,
                "shell_script",
                "workspace",
                Base64.getEncoder().encodeToString(content),
                contentSha256,
                content.length,
                "0700",
                now + DEFAULT_TASK_TIMEOUT_MS);
        RemoteTask task = new RemoteTask(
                taskId,
                traceId,
                agentId,
                "shell_script",
                "",
                args,
                "queued",
                now,
                null,
                null,
                null,
                null,
                now + DEFAULT_TASK_TIMEOUT_MS,
                "pulse-ui",
                1);
        ControlCommand shellCommand = ControlCommand.shellExecute(
                "cmd-shell-execute-" + taskId,
                task,
                fileId,
                contentSha256,
                "workspace",
                DEFAULT_TASK_TIMEOUT_MS);
        Queue<ControlCommand> queue = controlQueue(agentId);
        queue.add(fileCommand);
        queue.add(shellCommand);
        fileTransfers(agentId).put(fileId, new FileTransferStatus(
                fileId,
                taskId,
                agentId,
                safeName,
                "shell_script",
                "workspace",
                "queued",
                contentSha256,
                content.length,
                "",
                "",
                now,
                now));
        trace(task, "shell.enqueued", "ui", "pulse-ui", Map.of("file_id", fileId, "file_name", safeName, "args", args));
        traceFile(agentId, fileId, taskId, "file.enqueued", Map.of("file_name", safeName, "bytes", content.length, "role", "shell_script"));
        return snapshot(agentId);
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

    public synchronized Optional<PulseMessage> nextCommand(String agentId) {
        Queue<ControlCommand> controlQueue = controlQueue(agentId);
        while (true) {
            ControlCommand control = controlQueue.peek();
            if (control == null) {
                break;
            }
            if ("file_put".equals(control.kind())) {
                if (isFileReady(agentId, control.fileId())) {
                    controlQueue.poll();
                    continue;
                }
                markFileStatus(agentId, control.fileId(), "delivering", "", "");
                return Optional.of(control.toFilePutMessage(agentId));
            }
            if ("shell_execute".equals(control.kind())) {
                if (!isFileReady(agentId, control.fileId())) {
                    return Optional.empty();
                }
                controlQueue.poll();
                RemoteTask task = control.task();
                long now = clock.millis();
                RemoteTask delivered = task.withStatus("delivered", now, task.acceptedAtMs(), task.startedAtMs(), task.finishedAtMs());
                inFlight(agentId).put(task.taskId(), delivered);
                trace(delivered, "shell.dequeued_for_delivery", "coordinator", "coordinator", Map.of(
                        "script_file_id", control.fileId(),
                        "work_dir", control.workDir()));
                return Optional.of(control.toShellExecuteMessage(agentId, delivered));
            }
            controlQueue.poll();
        }
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

    private boolean isFileReady(String agentId, String fileId) {
        FileTransferStatus status = fileTransfers(agentId).get(fileId);
        return status != null && "received".equals(status.status());
    }

    public synchronized void handleReplies(String agentId, List<PulseMessage> messages) {
        for (PulseMessage message : messages) {
            if ("state.heartbeat".equals(message.type()) && message.payload() != null) {
                syncAsyncTasks(agentId, message.payload());
            }
            if ("reply.file_received".equals(message.type())) {
                acceptFileReceived(agentId, message.payload());
                continue;
            }
            if (!"reply.task_accepted".equals(message.type())
                    && !"reply.task_result".equals(message.type())
                    && !"reply.task_result_chunk".equals(message.type())
                    && !"reply.task_output_append".equals(message.type())) {
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
            if ("reply.task_output_append".equals(message.type())) {
                acceptStream(agentId, payload);
            } else if ("reply.task_accepted".equals(message.type())) {
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

    @SuppressWarnings("unchecked")
    private void syncAsyncTasks(String agentId, Map<String, Object> heartbeatPayload) {
        Object value = heartbeatPayload.get("async_tasks");
        if (!(value instanceof List<?> tasks)) {
            return;
        }
        for (Object entry : tasks) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> taskState = (Map<String, Object>) raw;
            String taskId = stringValue(taskState, "task_id");
            if (taskId.isBlank()) {
                continue;
            }
            RemoteTask existing = inFlight(agentId).get(taskId);
            String status = stringValue(taskState, "status");
            long startedAt = longValue(taskState, "started_at_ms");
            if (existing != null) {
                Long nextStartedAt = startedAt <= 0 ? existing.startedAtMs() : Long.valueOf(startedAt);
                RemoteTask updated = existing.withStatus(
                        status.isBlank() ? existing.status() : status,
                        existing.deliveredAtMs(),
                        existing.acceptedAtMs(),
                        nextStartedAt,
                        existing.finishedAtMs());
                inFlight(agentId).put(taskId, updated);
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
        TaskResult head = completions.peekFirst();
        if (head != null && head.taskId().equals(taskId)) {
            completions.removeFirst();
            trace(head, "task.completion_popped", "ui", "pulse-ui", Map.of("queue_head", true));
        }
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

    private Queue<ControlCommand> controlQueue(String agentId) {
        return controlQueues.computeIfAbsent(agentId, ignored -> new ArrayDeque<>());
    }

    private Map<String, RemoteTask> inFlight(String agentId) {
        return inFlight.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>());
    }

    private Map<String, FileTransferStatus> fileTransfers(String agentId) {
        return fileTransfers.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>());
    }

    private ArrayDeque<TaskResult> completions(String agentId) {
        return completionQueues.computeIfAbsent(agentId, ignored -> new ArrayDeque<>());
    }

    private void acceptFileReceived(String agentId, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String fileId = stringValue(payload, "file_id");
        String status = stringValue(payload, "status");
        String localPath = stringValue(payload, "local_path");
        String error = stringValue(payload, "runner_error");
        if (fileId.isBlank()) {
            return;
        }
        markFileStatus(agentId, fileId, status.isBlank() ? "unknown" : status, localPath, error);
        traceFile(agentId, fileId, stringValue(payload, "task_id"), "file.received_by_agent", Map.of(
                "status", status,
                "local_path", localPath,
                "runner_error", error));
    }

    private void markFileStatus(String agentId, String fileId, String status, String localPath, String error) {
        FileTransferStatus existing = fileTransfers(agentId).get(fileId);
        if (existing == null) {
            return;
        }
        fileTransfers(agentId).put(fileId, existing.withStatus(status, localPath, error, clock.millis()));
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

    private void traceFile(String agentId, String fileId, String taskId, String event, Map<String, Object> detail) {
        traceLogs.computeIfAbsent("file-" + agentId + "-" + fileId, ignored -> new ArrayList<>())
                .add(new TaskTraceLogEntry(
                        "file-" + fileId,
                        taskId == null ? "" : taskId,
                        agentId,
                        event,
                        "agent".equals(event) ? "agent" : "coordinator",
                        agentId,
                        clock.millis(),
                        detail));
    }

    private void complete(String agentId, RemoteTask task, TaskResult result) {
        inFlight(agentId).remove(result.taskId());
        pendingResults.remove(assemblyKey(agentId, result.taskId()));
        TaskStreamLog streamLog = streamLogs.get(assemblyKey(agentId, result.taskId()));
        ArrayDeque<TaskResult> completions = completions(agentId);
        completions.removeIf(existing -> existing.taskId().equals(result.taskId()));
        completions.addLast(result);
        while (completions.size() > MAX_COMPLETIONS_PER_AGENT) {
            completions.removeFirst();
        }
        trace(task, "task.result_received", "agent", agentId, Map.of(
                "status", result.status(),
                "output_sha256", result.outputSha256(),
                "stream_bytes", streamLog == null ? 0 : streamLog.streamBytes(),
                "stream_chunks", streamLog == null ? 0 : streamLog.streamChunks()));
    }

    private void acceptStream(String agentId, Map<String, Object> payload) {
        String taskId = stringValue(payload, "task_id");
        if (taskId.isBlank()) {
            return;
        }
        String streamId = stringValue(payload, "stream_id");
        if (!streamId.isBlank() && !"output".equals(streamId)) {
            return;
        }
        long streamSeq = longValue(payload, "stream_seq");
        String chunk = stringValue(payload, "payload");
        String expectedSha = stringValue(payload, "payload_sha256");
        if (!TaskOutputCodec.sha256(chunk).equals(expectedSha)) {
            return;
        }
        TaskStreamLog log = streamLogs.computeIfAbsent(
                assemblyKey(agentId, taskId),
                ignored -> new TaskStreamLog(agentId, taskId, stringValue(payload, "task_type")));
        if (log.accept(streamSeq, longValue(payload, "stream_offset"), chunk, longValue(payload, "observed_at_ms"))) {
            traceLogs.computeIfAbsent("stream-" + agentId + "-" + taskId, ignored -> new ArrayList<>())
                    .add(new TaskTraceLogEntry(
                            "stream-" + taskId,
                            taskId,
                            agentId,
                            "task.output_appended",
                            "agent",
                            agentId,
                            clock.millis(),
                            Map.of("stream_seq", streamSeq, "bytes", chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
        }
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
                longValue(payload, "output_lines"),
                stringValue(payload, "runner_error"));
    }

    private static byte[] decodeContent(String contentBase64, String contentSha256, long contentBytes) {
        if (contentBase64 == null || contentBase64.isBlank()) {
            throw new IllegalArgumentException("content_base64 is required");
        }
        byte[] content = Base64.getDecoder().decode(contentBase64);
        if (content.length == 0 || content.length > MAX_INLINE_FILE_BYTES) {
            throw new IllegalArgumentException("file size out of range");
        }
        if (contentBytes > 0 && content.length != contentBytes) {
            throw new IllegalArgumentException("file byte size mismatch");
        }
        if (contentSha256 == null || contentSha256.isBlank() || !TaskOutputCodec.sha256(content).equals(contentSha256)) {
            throw new IllegalArgumentException("file sha256 mismatch");
        }
        return content;
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

    private static String normalizeTargetDir(String targetDir) {
        if (targetDir == null || targetDir.isBlank()) {
            return "files";
        }
        return switch (targetDir) {
            case "files", "workspace" -> targetDir;
            default -> throw new IllegalArgumentException("invalid target_dir");
        };
    }

    private static String normalizeFileRole(String fileRole) {
        if (fileRole == null || fileRole.isBlank()) {
            return "generic_file";
        }
        return switch (fileRole) {
            case "generic_file", "shell_script" -> fileRole;
            default -> throw new IllegalArgumentException("invalid file_role");
        };
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

record ControlCommand(
        String kind,
        String messageId,
        String fileId,
        String taskId,
        String fileName,
        String fileRole,
        String targetDir,
        String contentBase64,
        String contentSha256,
        long contentBytes,
        String mode,
        RemoteTask task,
        String workDir,
        long timeoutMs,
        long deadlineMs) {
    static ControlCommand filePut(
            String messageId,
            String fileId,
            String taskId,
            String fileName,
            String fileRole,
            String targetDir,
            String contentBase64,
            String contentSha256,
            long contentBytes,
            String mode,
            long deadlineMs) {
        return new ControlCommand(
                "file_put",
                messageId,
                fileId,
                taskId,
                fileName,
                fileRole,
                targetDir,
                contentBase64,
                contentSha256,
                contentBytes,
                mode,
                null,
                "",
                0,
                deadlineMs);
    }

    static ControlCommand shellExecute(
            String messageId,
            RemoteTask task,
            String fileId,
            String scriptSha256,
            String workDir,
            long timeoutMs) {
        return new ControlCommand(
                "shell_execute",
                messageId,
                fileId,
                task.taskId(),
                "",
                "shell_script",
                "workspace",
                "",
                scriptSha256,
                0,
                "0700",
                task,
                workDir,
                timeoutMs,
                task.deadlineMs());
    }

    PulseMessage toFilePutMessage(String agentId) {
        return new PulseMessage(
                messageId,
                "cmd.file_put",
                1,
                null,
                deadlineMs,
                Map.ofEntries(
                        Map.entry("task_id", taskId == null ? "" : taskId),
                        Map.entry("file_id", fileId),
                        Map.entry("agent_id", agentId),
                        Map.entry("file_name", fileName),
                        Map.entry("file_role", fileRole),
                        Map.entry("target_dir", targetDir),
                        Map.entry("content_encoding", "base64"),
                        Map.entry("content", contentBase64),
                        Map.entry("content_sha256", contentSha256),
                        Map.entry("content_bytes", contentBytes),
                        Map.entry("mode", mode)));
    }

    PulseMessage toShellExecuteMessage(String agentId, RemoteTask delivered) {
        return new PulseMessage(
                messageId,
                "cmd.shell_execute",
                1,
                null,
                deadlineMs,
                Map.of(
                        "task_id", delivered.taskId(),
                        "trace_id", delivered.traceId(),
                        "agent_id", agentId,
                        "task_type", delivered.taskType(),
                        "script_file_id", fileId,
                        "script_sha256", contentSha256,
                        "work_dir", workDir,
                        "args", delivered.args(),
                        "timeout_ms", timeoutMs,
                        "created_at_ms", delivered.createdAtMs()));
    }
}

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
        long outputLines,
        String runnerError) {}

record FileTransferStatus(
        String fileId,
        String taskId,
        String agentId,
        String fileName,
        String fileRole,
        String targetDir,
        String status,
        String contentSha256,
        long contentBytes,
        String localPath,
        String runnerError,
        long createdAtMs,
        long updatedAtMs) {
    FileTransferStatus withStatus(String nextStatus, String nextLocalPath, String nextError, long now) {
        return new FileTransferStatus(
                fileId,
                taskId,
                agentId,
                fileName,
                fileRole,
                targetDir,
                nextStatus,
                contentSha256,
                contentBytes,
                nextLocalPath == null || nextLocalPath.isBlank() ? localPath : nextLocalPath,
                nextError == null ? "" : nextError,
                createdAtMs,
                now);
    }
}

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
        List<String> taskTypes,
        List<FileTransferStatus> fileTransfers,
        List<TaskStreamSnapshot> outputStreams) {}

final class TaskStreamLog {
    private final String agentId;
    private final String taskId;
    private final String taskType;
    private final StringBuilder output = new StringBuilder();
    private final java.util.Set<Long> acceptedSeq = new java.util.HashSet<>();
    private long firstStreamSeq = -1;
    private long lastStreamSeq = -1;
    private long streamBytes;
    private long streamLines;
    private long lastOutputAtMs;

    TaskStreamLog(String agentId, String taskId, String taskType) {
        this.agentId = agentId;
        this.taskId = taskId;
        this.taskType = taskType;
    }

    boolean accept(long streamSeq, long streamOffset, String chunk, long observedAtMs) {
        if (acceptedSeq.contains(streamSeq)) {
            return false;
        }
        if (streamOffset != streamBytes) {
            return false;
        }
        acceptedSeq.add(streamSeq);
        output.append(chunk);
        if (firstStreamSeq < 0) {
            firstStreamSeq = streamSeq;
        }
        lastStreamSeq = Math.max(lastStreamSeq, streamSeq);
        streamBytes += chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        streamLines += lineCount(chunk);
        lastOutputAtMs = observedAtMs;
        return true;
    }

    String agentId() {
        return agentId;
    }

    long streamBytes() {
        return streamBytes;
    }

    long streamChunks() {
        return acceptedSeq.size();
    }

    TaskStreamSnapshot snapshot() {
        return new TaskStreamSnapshot(
                taskId,
                taskType,
                "output",
                firstStreamSeq,
                lastStreamSeq,
                streamBytes,
                streamLines,
                acceptedSeq.size(),
                lastOutputAtMs,
                output.toString(),
                0,
                0,
                false);
    }

    private static long lineCount(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return value.chars().filter(ch -> ch == '\n').count() + (value.endsWith("\n") ? 0 : 1);
    }
}

record TaskStreamSnapshot(
        String taskId,
        String taskType,
        String streamId,
        long firstStreamSeq,
        long lastStreamSeq,
        long streamBytes,
        long streamLines,
        long streamChunks,
        long lastOutputAtMs,
        String output,
        long spooledBytes,
        long pendingBytes,
        boolean backpressureActive) {}
