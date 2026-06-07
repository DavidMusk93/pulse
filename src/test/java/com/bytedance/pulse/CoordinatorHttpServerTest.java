package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinatorHttpServerTest {
    private final ObjectMapper mapper = JsonSupport.objectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir
    Path tempDir;
    private CoordinatorHttpServer server;
    private LocalMetricStorage metricStorage;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        metricStorage = LocalMetricStorage.open(tempDir.resolve("metrics.db"));
        CoordinatorService service = new CoordinatorService(
                "coordinator-a",
                Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC),
                metricStorage);
        server = new CoordinatorHttpServer(service, "127.0.0.1", 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        if (metricStorage != null) {
            try {
                metricStorage.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void heartbeatEndpointStoresHostAndReturnsOk() throws Exception {
        HttpResponse<String> response = postJson("/heartbeat", """
                {
                  "agent_id": "agent-1",
                  "epoch": 1,
                  "seq": 42,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-1-42",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {
                        "host": "host-a",
                        "ip": "10.0.0.1",
                        "zone": "az-a",
                        "role": "worker",
                        "load": "0.42"
                      }
                    }
                  ]
                }
                """);

        assertEquals(200, response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        assertTrue(json.get("ok").asBoolean());
        assertEquals(42, json.get("accepted_seq").asLong());

        HttpResponse<String> hosts = get("/api/hosts");
        assertEquals(200, hosts.statusCode());
        assertTrue(hosts.body().contains("host-a"));
        assertTrue(hosts.body().contains("last_observed_age_ms"));
        assertTrue(hosts.body().contains("group_id"));
        assertTrue(hosts.body().contains("group_mode"));
        assertTrue(hosts.body().contains("leader_agent_id"));
        assertTrue(hosts.body().contains("group_size_limit"));
    }

    @Test
    void metricsEndpointsExposeCatalogAndQueryHeartbeatRange() throws Exception {
        postJson("/heartbeat", """
                {
                  "agent_id": "agent-1",
                  "epoch": 1,
                  "seq": 40,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-1-40",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {
                        "host": "host-a",
                        "ip": "10.0.0.1",
                        "cluster": "cluster-a",
                        "area": "area-a",
                        "agent_thread_count": 19
                      }
                    }
                  ]
                }
                """);

        HttpResponse<String> catalog = get("/api/metrics/catalog");
        assertEquals(200, catalog.statusCode());
        assertTrue(catalog.body().contains("heartbeat.arrival_gap_ms"));
        assertTrue(catalog.body().contains("agent.thread_count"));

        HttpResponse<String> range = get("/api/metrics/query_range?metric=agent.thread_count&agents=agent-1&start_ms=1710000000000&end_ms=1710000000000&step_ms=1000&point_limit=10");
        assertEquals(200, range.statusCode());
        JsonNode body = mapper.readTree(range.body());
        assertEquals("agent.thread_count", body.get("metric").asText());
        assertEquals("avg", body.get("sample_policy").asText());
        assertEquals(false, body.get("truncated").asBoolean());
        assertEquals("agent-1", body.get("series").get(0).get("labels").get("agent_id").asText());
        assertEquals(19.0, body.get("series").get(0).get("points").get(0).get("value").asDouble());
    }

    @Test
    void hostsPageRendersFlatSquareChineseHeartbeatConsole() throws Exception {
        postJson("/heartbeat", """
                {
                  "agent_id": "agent-1",
                  "epoch": 1,
                  "seq": 42,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-1-42",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {"host": "low-load-host", "ip": "10.0.0.8", "cluster": "cluster-a", "area": "area-a", "role": "worker", "load": "0.10"}
                    }
                  ]
                }
                """);
        postJson("/heartbeat", """
                {
                  "agent_id": "agent-2",
                  "epoch": 1,
                  "seq": 43,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-2-43",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {"host": "high-load-host", "ip": "10.0.0.9", "cluster": "cluster-a", "area": "area-a", "role": "worker", "load": "9.90"}
                    }
                  ]
                }
                """);

        HttpResponse<String> response = get("/hosts");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Pulse 心跳平台"));
        assertTrue(response.body().contains("data-ui-library=\"Ant Design\""));
        assertTrue(response.body().contains("data-framework=\"React\""));
        assertTrue(response.body().contains("/assets/pulse-hosts.css"));
        assertTrue(response.body().contains("/assets/pulse-hosts.js"));
        assertTrue(!response.body().contains("https://"));
        assertTrue(!response.body().contains("hostname"));
        assertTrue(!response.body().contains(".byted.org"));
        assertTrue(!response.body().contains("http-equiv=\"refresh\""));

        HttpResponse<String> js = get("/assets/pulse-hosts.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("/api/hosts"));
        assertTrue(js.body().contains("prepare_disk_layout_dry_run"));
        assertTrue(js.body().contains("analyze_block_layout_dry_run"));
        assertTrue(js.body().contains("磁盘布局"));
        assertTrue(js.body().contains("块分布"));
        assertTrue(js.body().contains("自定义参数"));
        assertTrue(js.body().contains("默认参数为 --dry-run"));
        assertTrue(js.body().contains("批任务"));
        assertTrue(js.body().contains("sampledAtMs"));
        assertTrue(js.body().contains("agent 执行中"));
        assertTrue(js.body().contains("run-button"));
        assertTrue(js.body().contains("data-status"));
        assertTrue(js.body().contains("getFullYear"));
        assertTrue(js.body().contains("ResizeObserver"));
        assertTrue(js.body().contains("auto-fit"));
        assertTrue(js.body().contains("confirmations"));
        assertTrue(js.body().contains("20s确认"));
        assertTrue(js.body().contains("last_observed_age_ms"));
        assertTrue(js.body().contains("group_id"));
        assertTrue(js.body().contains("leader_url"));
        assertTrue(js.body().contains("debug-panel"));
        assertTrue(js.body().contains("调试"));
        assertTrue(js.body().contains("pulse.cluster-collapse.v1"));
        assertTrue(js.body().contains("localStorage"));
        assertTrue(js.body().contains("warming"));
        assertTrue(js.body().contains("timed_out"));
        assertTrue(js.body().contains("异常展开"));
        assertTrue(js.body().contains("折叠"));
        assertTrue(js.body().contains("展开"));
        assertTrue(js.body().contains("component_version"));
        assertTrue(js.body().contains("user_cpu_percent"));
        assertTrue(js.body().contains("sys_cpu_percent"));
        assertTrue(js.body().contains("rss_kb"));
        assertTrue(js.body().contains("threads"));
        assertTrue(js.body().contains("completion-viewer"));
        assertTrue(js.body().contains("json-output"));
        assertTrue(js.body().contains("Markdown"));
        assertTrue(js.body().contains("搜索输出"));
        assertTrue(js.body().contains("暂无命令输出"));
        assertTrue(js.body().contains("尚未收到命令输出"));
        assertTrue(js.body().contains("正在展示实时命令输出"));
        assertTrue(js.body().contains("不换行"));
        assertTrue(js.body().contains("拷贝"));
        assertTrue(js.body().contains("/tasks/completions/"));
        assertTrue(js.body().contains("/pop"));
        assertTrue(js.body().contains(".slice(0,4)"));
        assertTrue(js.body().contains("task_id"));
        assertTrue(js.body().contains("output_streams"));
        assertTrue(js.body().contains("暂无命令输出"));
        assertTrue(js.body().contains("队列中"));
        assertTrue(js.body().contains("KiB"));
        assertTrue(js.body().contains("Trace"));
        assertTrue(js.body().contains("centered:!0"));
        assertTrue(js.body().contains("width:`min(61.8vw"));
        assertTrue(!js.body().contains("min(1480px"));
        assertTrue(!js.body().contains("status-led"));
        assertTrue(!js.body().contains("tile-seen"));
        assertTrue(!js.body().contains("PlayCircleOutlined"));
        assertTrue(!js.body().contains("sum: sample"));
        assertTrue(!js.body().contains("count: 1,"));
        assertTrue(!js.body().contains("shell command"));

        HttpResponse<String> css = get("/assets/pulse-hosts.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("aspect-ratio:1"));
        assertTrue(css.body().contains("top:auto!important"));
        assertTrue(css.body().contains("grid-template-columns:minmax(300px,.618fr) minmax(0,1fr)"));
        assertTrue(css.body().contains("height:min(61.8vh,calc(100vh - 148px))")
                || css.body().contains("height:min(61.8vh,100vh - 148px)"));
        assertTrue(!css.body().contains("height:min(920px"));
        assertTrue(!css.body().contains("minmax(340px"));
        assertTrue(css.body().contains(".task-modal .ant-modal-content"));
        assertTrue(css.body().contains("minmax(244px,1fr)"));
        assertTrue(css.body().contains("white-space:nowrap"));
        assertTrue(css.body().contains("text-overflow:ellipsis"));
        assertTrue(css.body().contains(".tile-id-block"));
        assertTrue(css.body().contains(".tile-metrics"));
        assertTrue(!css.body().contains(".tile-metric-inline"));
        assertTrue(css.body().contains(".ip-title-row"));
        assertTrue(css.body().contains(".ip-copy-button"));
        assertTrue(css.body().contains(".auto-fit"));
        assertTrue(css.body().contains(".metric-fit-value"));
        assertTrue(css.body().contains(".hero-metrics"));
        assertTrue(css.body().contains(".hero-metrics>.ant-card"));
        assertTrue(css.body().contains(".hero-metrics .ant-statistic"));
        assertTrue(css.body().contains(".cluster-toggle-button"));
        assertTrue(css.body().contains(".cluster-run-button"));
        assertTrue(css.body().contains(".cluster-section.cluster-section-collapsed"));
        assertTrue(css.body().contains("writing-mode:horizontal-tb"));
        assertTrue(css.body().contains("overflow:hidden auto") || css.body().contains("overflow-y:auto"));
        assertTrue(css.body().contains("flex:1 1 0"));
        assertTrue(css.body().contains("white-space:nowrap"));
        assertTrue(css.body().contains(".run-button[data-status=success]"));
        assertTrue(!css.body().contains(".status-led"));
        assertTrue(css.body().contains(".worker-card"));
        assertTrue(css.body().contains(".debug-panel"));
        assertTrue(css.body().contains(".debug-grid"));
        assertTrue(css.body().contains("grid-template-columns:48px minmax(0,1fr)"));
        assertTrue(css.body().contains(".completion-viewer"));
        assertTrue(css.body().contains(".output-status-notice"));
        assertTrue(css.body().contains(".output-status-empty"));
        assertTrue(css.body().contains(".trace-list"));
        assertTrue(css.body().contains(".completion-pane"));
        assertTrue(css.body().contains(".task-trace-card"));
        assertTrue(css.body().contains(".task-id-text"));
        assertTrue(css.body().contains(".task-workspace .ant-card-head"));
        assertTrue(css.body().contains(".task-workspace .ant-card-head-title"));
        assertTrue(css.body().contains(".completion-toolbar"));
        assertTrue(css.body().contains(".output-line-number"));
        assertTrue(css.body().contains(".output-line-error"));
        assertTrue(css.body().contains(".markdown-output"));
        assertTrue(css.body().contains(".json-key"));
        assertTrue(!response.body().contains("http-equiv=\"refresh\""));
    }

    @Test
    void deployScriptShipsSingleAnalyzerAndRepairTask() throws Exception {
        String deploy = Files.readString(Path.of("docs/script/pulse-cdn-new-deploy.sh"));

        assertTrue(Files.exists(Path.of("docs/task/analyze-block-layout.py")));
        assertTrue(Files.exists(Path.of("docs/task/repair-corrupt-sqlite3.sh")));
        assertTrue(deploy.contains("analyze-block-layout.py"));
        assertTrue(deploy.contains("repair-corrupt-sqlite3.sh"));
        assertTrue(!deploy.contains("\"$task_dir\"/analyze-block-layout-py35.py"));
        assertTrue(deploy.contains("rm -f \"$install_root/tasks/analyze-block-layout-py35.py\""));
        assertTrue(deploy.contains("python3_version"));
        assertTrue(deploy.contains("TASK_SCRIPT analyze-block-layout.py variant=standard"));
    }

    @Test
    void taskApiOnlyAcceptsAllowlistedDryRunTasks() throws Exception {
        HttpResponse<String> unknown = postJson("/api/agents/agent-1/tasks", """
                {"task_type":"rm_rf"}
                """);
        assertEquals(400, unknown.statusCode());

        HttpResponse<String> response = postJson("/api/agents/agent-1/tasks", """
                {"task_type":"analyze_block_layout_dry_run"}
                """);

        assertEquals(200, response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        assertEquals("agent-1", json.get("agent_id").asText());
        assertEquals("analyze_block_layout_dry_run", json.get("execution_queue").get(0).get("task_type").asText());
        assertEquals("--dry-run", json.get("execution_queue").get(0).get("args").get(0).asText());

        HttpResponse<String> customArgs = postJson("/api/agents/agent-1/tasks", """
                {"task_type":"analyze_block_layout_dry_run","args":["--dry-run","--limit","10"]}
                """);

        assertEquals(200, customArgs.statusCode());
        JsonNode custom = mapper.readTree(customArgs.body());
        assertEquals("--limit", custom.get("execution_queue").get(1).get("args").get(1).asText());

        HttpResponse<String> repair = postJson("/api/agents/agent-1/tasks", """
                {"task_type":"repair_corrupt_sqlite3_dry_run","args":["--dry-run","--port","12345"]}
                """);

        assertEquals(200, repair.statusCode());
        JsonNode repairJson = mapper.readTree(repair.body());
        assertEquals("repair_corrupt_sqlite3_dry_run", repairJson.get("execution_queue").get(2).get("task_type").asText());
        assertEquals("--port", repairJson.get("execution_queue").get(2).get("args").get(1).asText());
    }

    @Test
    void taskStreamEndpointServesSseSnapshots() throws Exception {
        postJson("/api/agents/agent-1/tasks", """
                {"task_type":"analyze_block_layout_dry_run"}
                """);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/agents/agent-1/tasks/stream"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            StringBuilder firstLines = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                firstLines.append(line).append('\n');
                if (firstLines.toString().contains("analyze_block_layout_dry_run")) {
                    break;
                }
            }
            String body = firstLines.toString();
            assertTrue(body.contains("event: hello"));
            assertTrue(body.contains("event: task.snapshot"));
            assertTrue(body.contains("\"agent_id\":\"agent-1\""));
            assertTrue(body.contains("analyze_block_layout_dry_run"));
        }
    }

    @Test
    void batchTaskStreamEndpointServesSnapshotsForMultipleAgents() throws Exception {
        postJson("/api/agents/agent-1/tasks", """
                {"task_type":"analyze_block_layout_dry_run"}
                """);
        postJson("/api/agents/agent-2/tasks", """
                {"task_type":"prepare_disk_layout_dry_run"}
                """);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tasks/stream?agents=agent-1,agent-2"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
            StringBuilder firstLines = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                firstLines.append(line).append('\n');
                String body = firstLines.toString();
                if (body.contains("\"agent_id\":\"agent-1\"") && body.contains("\"agent_id\":\"agent-2\"")) {
                    break;
                }
            }
            String body = firstLines.toString();
            assertTrue(body.contains("event: hello"));
            assertTrue(body.contains("\"agent_count\":2"));
            assertTrue(body.contains("\"agent_id\":\"agent-1\""));
            assertTrue(body.contains("\"agent_id\":\"agent-2\""));
            assertTrue(body.contains("analyze_block_layout_dry_run"));
            assertTrue(body.contains("prepare_disk_layout_dry_run"));
        }
    }

    @Test
    void taskApiEnqueuesFilePutAndShellScriptWithoutNewEndpoint() throws Exception {
        String content = "hello";
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sha = TaskOutputCodec.sha256(content);

        HttpResponse<String> filePut = postJson("/api/agents/agent-1/tasks", """
                {"operation":"file_put","file_name":"hello.txt","file_role":"generic_file","target_dir":"files","content_base64":"%s","content_sha256":"%s","content_bytes":5}
                """.formatted(encoded, sha));

        assertEquals(200, filePut.statusCode());
        JsonNode fileJson = mapper.readTree(filePut.body());
        assertEquals("queued", fileJson.get("file_transfers").get(0).get("status").asText());

        JsonNode fileCommand = mapper.readTree(postJson("/heartbeat", """
                {"agent_id":"agent-1","epoch":1,"seq":10,"ttl_ms":15000,"messages":[]}
                """).body()).get("messages").get(1);
        assertEquals("cmd.file_put", fileCommand.get("type").asText());
        assertEquals("hello.txt", fileCommand.get("payload").get("file_name").asText());

        String shell = "echo shell";
        String shellEncoded = Base64.getEncoder().encodeToString(shell.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String shellSha = TaskOutputCodec.sha256(shell);
        HttpResponse<String> shellPost = postJson("/api/agents/agent-1/tasks", """
                {"operation":"shell_script","file_name":"script.sh","content_base64":"%s","content_sha256":"%s","content_bytes":10,"args":["--dry-run"]}
                """.formatted(shellEncoded, shellSha));

        assertEquals(200, shellPost.statusCode());
        JsonNode shellJson = mapper.readTree(shellPost.body());
        boolean hasShellScript = false;
        for (JsonNode transfer : shellJson.get("file_transfers")) {
            hasShellScript = hasShellScript || "shell_script".equals(transfer.get("file_role").asText());
        }
        assertTrue(hasShellScript);

        String noArgsShell = "echo no args";
        String noArgsEncoded = Base64.getEncoder().encodeToString(noArgsShell.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String noArgsSha = TaskOutputCodec.sha256(noArgsShell);
        HttpResponse<String> noArgsPost = postJson("/api/agents/agent-2/tasks", """
                {"operation":"shell_script","file_name":"no-args.sh","content_base64":"%s","content_sha256":"%s","content_bytes":12}
                """.formatted(noArgsEncoded, noArgsSha));

        assertEquals(200, noArgsPost.statusCode());
        JsonNode noArgsJson = mapper.readTree(noArgsPost.body());
        JsonNode shellTrace = null;
        for (JsonNode trace : noArgsJson.get("traces")) {
            if ("shell.enqueued".equals(trace.get("event").asText())
                    && "no-args.sh".equals(trace.get("detail").get("file_name").asText())) {
                shellTrace = trace;
            }
        }
        assertTrue(shellTrace != null);
        assertEquals(0, shellTrace.get("detail").get("args").size());
    }

    @Test
    void heartbeatResponseCarriesGroupPlanMessage() throws Exception {
        for (int i = 1; i <= 5; i++) {
            postAlive("agent-" + i, "host-" + i, "10.0.0." + i);
        }

        HttpResponse<String> response = postJson("/heartbeat", """
                {
                  "agent_id": "agent-5",
                  "epoch": 1,
                  "seq": 44,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-5-44",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {"host": "host-5", "ip": "10.0.0.5", "cluster": "cluster-a", "area": "area-a"}
                    }
                  ]
                }
                """);

        assertEquals(200, response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        JsonNode payload = json.get("messages").get(0).get("payload");
        assertEquals("cmd.group_plan", json.get("messages").get(0).get("type").asText());
        assertEquals("follower", payload.get("group_mode").asText());
        assertEquals("agent-3", payload.get("leader_agent_id").asText());
        assertEquals(3, payload.get("size_limit").asInt());
    }

    @Test
    void heartbeatEndpointForwardsStateToPeers() throws Exception {
        server.stop();
        RecordingPeerForwarder forwarder = new RecordingPeerForwarder();
        CoordinatorService service = new CoordinatorService(
                "coordinator-a",
                Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC));
        server = new CoordinatorHttpServer(service, "127.0.0.1", 0, forwarder);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();

        HttpResponse<String> response = postJson("/heartbeat", """
                {
                  "agent_id": "agent-1",
                  "epoch": 1,
                  "seq": 42,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-1-42",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {"host": "host-a"}
                    }
                  ]
                }
                """);

        assertEquals(200, response.statusCode());
        assertEquals(1, forwarder.requests.size());
        assertEquals("agent-1", forwarder.requests.get(0).agentId());
    }

    @Test
    void peerForwarderOnlyBuildsStateMessages() {
        CoordinatorHttpServer.HttpPeerForwarder forwarder = new CoordinatorHttpServer.HttpPeerForwarder(
                "coordinator-a",
                List.of("http://127.0.0.1:1"),
                java.time.Duration.ofMillis(1));
        HeartbeatRequest request = new HeartbeatRequest(
                null,
                "agent-1",
                1L,
                42L,
                15_000L,
                List.of(
                        new PulseMessage("state-1", "state.heartbeat", 1, null, null, Map.of("host", "host-a")),
                        new PulseMessage("cmd-1", "cmd.group_plan", 1, null, null, Map.of("ignored", true))),
                List.of());

        HeartbeatForwardRequest forwardRequest = forwarder.toForwardRequest(request);

        assertEquals("coordinator-a", forwardRequest.sourceCoordinatorId());
        assertEquals(1, forwardRequest.states().size());
        assertEquals("direct", forwardRequest.states().get(0).source());
        assertEquals(1, forwardRequest.states().get(0).messages().size());
        assertEquals("state.heartbeat", forwardRequest.states().get(0).messages().get(0).type());
    }

    @Test
    void agentHeartbeatClientWritesOnlyOneCoordinator() throws Exception {
        AtomicInteger firstHits = new AtomicInteger();
        AtomicInteger secondHits = new AtomicInteger();
        HttpServer first = heartbeatStub(firstHits);
        HttpServer second = heartbeatStub(secondHits);
        first.start();
        second.start();
        try {
            PulseAgentApp.HeartbeatClient heartbeatClient = new PulseAgentApp.HeartbeatClient(
                    List.of(
                            "http://127.0.0.1:" + first.getAddress().getPort(),
                            "http://127.0.0.1:" + second.getAddress().getPort()),
                    Duration.ofSeconds(1));

            HeartbeatResponse response = heartbeatClient.sendForResponse(
                    "/heartbeat",
                    new HeartbeatRequest(null, "agent-1", 1L, 42L, 15_000L, List.of(), List.of()));

            assertTrue(response.ok());
            assertEquals(1, firstHits.get() + secondHits.get());
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    @Test
    void dynamicFollowerReusesLeaderHeartbeatClient() {
        PulseAgentApp.FollowerLeaderClientCache cache =
                new PulseAgentApp.FollowerLeaderClientCache(Duration.ofSeconds(1));

        PulseAgentApp.HeartbeatClient first = cache.clientFor("http://127.0.0.1:9977");
        PulseAgentApp.HeartbeatClient second = cache.clientFor("http://127.0.0.1:9977");
        PulseAgentApp.HeartbeatClient changed = cache.clientFor("http://127.0.0.1:9978");

        assertSame(first, second);
        assertNotSame(first, changed);
    }

    @Test
    void groupLeaderRejectsFollowersOutsideCurrentPlan() throws Exception {
        PulseAgentApp.GroupHeartbeatReceiver receiver = new PulseAgentApp.GroupHeartbeatReceiver(
                "127.0.0.1",
                0,
                new GroupHeartbeatCollector());
        receiver.setAcceptingFollowers(true, Set.of("agent-allowed"));
        receiver.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiver.port() + "/group/heartbeat"))
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(1))
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {
                              "agent_id": "agent-stale",
                              "epoch": 1,
                              "seq": 42,
                              "ttl_ms": 15000,
                              "messages": []
                            }
                            """))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(409, response.statusCode());
            assertTrue(response.body().contains("not_group_member"));
        } finally {
            receiver.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void postAlive(String agentId, String host, String ip) throws Exception {
        for (int seq = 1; seq <= 3; seq++) {
            postJson("/heartbeat", """
                    {
                      "agent_id": "__AGENT_ID__",
                      "epoch": 1,
                      "seq": __SEQ__,
                      "ttl_ms": 15000,
                      "messages": [
                        {
                          "message_id": "msg-__AGENT_ID__-__SEQ__",
                          "type": "state.heartbeat",
                          "version": 1,
                          "payload": {"host": "__HOST__", "ip": "__IP__", "cluster": "cluster-a", "area": "area-a"}
                        }
                      ]
                    }
                    """
                    .replace("__AGENT_ID__", agentId)
                    .replace("__HOST__", host)
                    .replace("__IP__", ip)
                    .replace("__SEQ__", String.valueOf(seq)));
        }
    }

    private static HttpServer heartbeatStub(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/heartbeat", exchange -> {
            hits.incrementAndGet();
            byte[] body = """
                    {"ok":true,"coordinator_id":"stub","accepted_seq":42,"messages":[],"agents":[]}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
    }

    private static final class RecordingPeerForwarder implements CoordinatorHttpServer.PeerForwarder {
        private final List<HeartbeatRequest> requests = new ArrayList<>();

        @Override
        public void forward(HeartbeatRequest request) {
            requests.add(request);
        }
    }
}
