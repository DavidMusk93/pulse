package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Map.entry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTaskRunnerTest {
    @TempDir
    Path taskDir;

    @Test
    void acceptsValidatedCustomArgsForAllowedTask() throws Exception {
        Path script = taskDir.resolve("prepare-disk-layout.sh");
        Files.writeString(script, "printf 'ARGS:%s\\n' \"$*\"\n");
        AgentTaskRunner runner = new AgentTaskRunner("agent-1", fixedClock(), taskDir.toString());

        runner.handleMessages(List.of(command("task-1", "prepare_disk_layout_dry_run", script.toString(), List.of("--help"))));

        List<PulseMessage> acceptedReplies = runner.drainReplies();
        assertTrue(acceptedReplies.stream().anyMatch(message -> "reply.task_accepted".equals(message.type())));

        PulseMessage result = awaitResult(runner);
        assertEquals("completed", result.payload().get("status"));
        assertEquals("ARGS:--help\n", result.payload().get("output"));
    }

    @Test
    void rejectsInvalidCustomArgsBeforeExecution() throws Exception {
        Path script = taskDir.resolve("prepare-disk-layout.sh");
        Files.writeString(script, "echo should-not-run\n");
        AgentTaskRunner runner = new AgentTaskRunner("agent-1", fixedClock(), taskDir.toString());

        runner.handleMessages(List.of(command("task-1", "prepare_disk_layout_dry_run", script.toString(), List.of("bad\narg"))));

        PulseMessage result = awaitResult(runner);
        assertEquals("rejected", result.payload().get("status"));
        assertEquals("invalid task arg", result.payload().get("runner_error"));
    }

    @Test
    void acceptsRepairCorruptSqlite3Task() throws Exception {
        Path script = taskDir.resolve("repair-corrupt-sqlite3.sh");
        Files.writeString(script, "printf 'REPAIR:%s\\n' \"$*\"\n");
        AgentTaskRunner runner = new AgentTaskRunner("agent-1", fixedClock(), taskDir.toString());

        runner.handleMessages(List.of(command(
                "task-1",
                "repair_corrupt_sqlite3_dry_run",
                script.toString(),
                List.of("--dry-run", "--port", "12345"))));

        PulseMessage result = awaitResult(runner);
        assertEquals("completed", result.payload().get("status"));
        assertEquals("REPAIR:--dry-run --port 12345\n", result.payload().get("output"));
    }

    @Test
    void receivesFileAndRunsStagedShellScript() throws Exception {
        String script = "printf 'SHELL:%s:%s\\n' \"$PWD\" \"$*\"\n";
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        AgentTaskRunner runner = new AgentTaskRunner("agent-1", fixedClock(), taskDir.toString());

        runner.handleMessages(List.of(filePut("file-1", "task-shell", "script.sh", bytes)));

        PulseMessage fileReply = awaitReply(runner, "reply.file_received");
        assertEquals("received", fileReply.payload().get("status"));
        assertTrue(Files.exists(Path.of(fileReply.payload().get("local_path").toString())));

        runner.handleMessages(List.of(shellExecute("task-shell", "file-1", TaskOutputCodec.sha256(bytes), List.of("--dry-run"))));

        PulseMessage result = awaitResult(runner);
        assertEquals("completed", result.payload().get("status"));
        assertTrue(result.payload().get("output").toString().contains("SHELL:"));
        assertTrue(result.payload().get("output").toString().contains("--dry-run"));
    }

    @Test
    void stagedShellScriptDoesNotInjectDryRunWhenArgsAreEmpty() throws Exception {
        String script = "printf 'ARG_COUNT:%s\\n' \"$#\"\n";
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        AgentTaskRunner runner = new AgentTaskRunner("agent-1", fixedClock(), taskDir.toString());

        runner.handleMessages(List.of(filePut("file-1", "task-shell", "script.sh", bytes)));
        awaitReply(runner, "reply.file_received");

        runner.handleMessages(List.of(shellExecute("task-shell", "file-1", TaskOutputCodec.sha256(bytes), List.of())));

        PulseMessage result = awaitResult(runner);
        assertEquals("completed", result.payload().get("status"));
        assertEquals("ARG_COUNT:0\n", result.payload().get("output"));
    }

    @Test
    void keepsFullFinalTaskOutput() throws Exception {
        Path script = taskDir.resolve("prepare-disk-layout.sh");
        Files.writeString(script, "python3 - <<'PY'\nprint('x' * 10000)\nPY\n");
        AgentTaskRunner runner = new AgentTaskRunner("agent-1", fixedClock(), taskDir.toString(), 1, 128);

        runner.handleMessages(List.of(command("task-1", "prepare_disk_layout_dry_run", script.toString(), List.of())));

        PulseMessage result = awaitResult(runner);
        String output = TaskOutputCodec.decode(
                result.payload().get("output").toString(),
                result.payload().get("output_encoding").toString());
        assertEquals("completed", result.payload().get("status"));
        assertTrue(output.contains("x".repeat(10_000)));
        assertTrue(!output.contains("pulse output truncated"));
    }

    private static PulseMessage command(String taskId, String taskType, String scriptPath, List<String> args) {
        return new PulseMessage(
                "cmd-" + taskId,
                "cmd.task_execute",
                1,
                null,
                null,
                Map.of(
                        "task_id", taskId,
                        "trace_id", "trace-" + taskId,
                        "agent_id", "agent-1",
                        "task_type", taskType,
                        "script_path", scriptPath,
                        "args", args));
    }

    private static PulseMessage filePut(String fileId, String taskId, String fileName, byte[] content) {
        return new PulseMessage(
                "cmd-file-" + fileId,
                "cmd.file_put",
                1,
                null,
                null,
                Map.ofEntries(
                        entry("task_id", taskId),
                        entry("file_id", fileId),
                        entry("agent_id", "agent-1"),
                        entry("file_name", fileName),
                        entry("file_role", "shell_script"),
                        entry("target_dir", "workspace"),
                        entry("content_encoding", "base64"),
                        entry("content", Base64.getEncoder().encodeToString(content)),
                        entry("content_sha256", TaskOutputCodec.sha256(content)),
                        entry("content_bytes", content.length),
                        entry("mode", "0700")));
    }

    private static PulseMessage shellExecute(String taskId, String fileId, String sha256, List<String> args) {
        return new PulseMessage(
                "cmd-shell-" + taskId,
                "cmd.shell_execute",
                1,
                null,
                null,
                Map.of(
                        "task_id", taskId,
                        "trace_id", "trace-" + taskId,
                        "agent_id", "agent-1",
                        "task_type", "shell_script",
                        "script_file_id", fileId,
                        "script_sha256", sha256,
                        "work_dir", "workspace",
                        "args", args));
    }

    private static PulseMessage awaitResult(AgentTaskRunner runner) throws InterruptedException {
        return awaitReply(runner, "reply.task_result");
    }

    private static PulseMessage awaitReply(AgentTaskRunner runner, String type) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            for (PulseMessage reply : runner.drainReplies()) {
                if (type.equals(reply.type())) {
                    return reply;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError(type + " not observed");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
    }
}
