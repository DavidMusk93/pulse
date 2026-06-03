package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
                        "args", args,
                        "timeout_ms", 60_000));
    }

    private static PulseMessage awaitResult(AgentTaskRunner runner) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            for (PulseMessage reply : runner.drainReplies()) {
                if ("reply.task_result".equals(reply.type())) {
                    return reply;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("reply.task_result not observed");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC);
    }
}
