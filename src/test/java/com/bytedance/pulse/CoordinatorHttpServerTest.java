package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordinatorHttpServerTest {
    private final ObjectMapper mapper = JsonSupport.objectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private CoordinatorHttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        CoordinatorService service = new CoordinatorService(
                "coordinator-a",
                Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC));
        server = new CoordinatorHttpServer(service, "127.0.0.1", 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void tearDown() {
        server.stop();
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
        assertTrue(response.body().contains("tile-grid"));
        assertTrue(response.body().contains("Pulse 心跳平台"));
        assertTrue(response.body().contains("data-framework=\"PulseView\""));
        assertTrue(response.body().contains("fetch('/api/hosts'"));
        assertTrue(response.body().contains("心跳平台，连接运维现场"));
        assertTrue(response.body().contains("任务、资源、监控与告警，沿一条消息链自然流动。"));
        assertTrue(response.body().contains("cluster-section"));
        assertTrue(response.body().contains("aspect-ratio: 1 / 1"));
        assertTrue(response.body().contains("tile-scroll"));
        assertTrue(response.body().contains("load-bar"));
        assertTrue(response.body().contains("clusterSections: new Map()"));
        assertTrue(response.body().contains("tiles: new Map()"));
        assertTrue(response.body().contains("updateClusters"));
        assertTrue(response.body().contains("updateTiles"));
        assertTrue(response.body().contains("getOrCreateTile"));
        assertTrue(response.body().contains("restoreViewportScroll"));
        assertTrue(response.body().contains("window.scrollTo(viewport.left, viewport.top)"));
        assertTrue(response.body().contains("placeChild"));
        assertTrue(response.body().contains("tide_worker"));
        assertTrue(response.body().contains("pid ${escapeHtml(worker.pid || '')}"));
        assertTrue(response.body().contains("host.heartbeat_confirmations"));
        assertTrue(response.body().contains("data-field=\"seen\""));
        assertTrue(response.body().contains("data-action=\"run-task\""));
        assertTrue(response.body().contains("task-modal"));
        assertTrue(!response.body().contains("任务执行"));
        assertTrue(response.body().contains("task-window-controls"));
        assertTrue(response.body().contains("task-panel-close"));
        assertTrue(response.body().contains("task-summary"));
        assertTrue(response.body().contains("结果队列"));
        assertTrue(response.body().contains("目标节点"));
        assertTrue(response.body().contains("结果查看"));
        assertTrue(response.body().contains("monaco-editor@0.49.0"));
        assertTrue(response.body().contains("setupMonacoEditor"));
        assertTrue(response.body().contains("editor.action.formatDocument"));
        assertTrue(response.body().contains("加载完整结果"));
        assertTrue(response.body().contains("completion-strip"));
        assertTrue(response.body().contains("output_sha256"));
        assertTrue(response.body().contains("agent 执行中"));
        assertTrue(response.body().contains("task-progress-row"));
        assertTrue(response.body().contains("runningTaskText"));
        assertTrue(response.body().contains("statusLabel"));
        assertTrue(response.body().contains("startTaskPolling"));
        assertTrue(response.body().contains("window.setInterval(() =>"));
        assertTrue(response.body().contains("每 2 秒自动刷新一次"));
        assertTrue(response.body().contains("prepare_disk_layout_dry_run"));
        assertTrue(response.body().contains("analyze_block_layout_dry_run"));
        assertTrue(response.body().contains("磁盘布局 dry-run"));
        assertTrue(response.body().contains("块分布 dry-run"));
        assertTrue(!response.body().contains("shell command"));
        assertTrue(response.body().contains("border-radius: 28px"));
        assertTrue(response.body().contains("border-radius: 16px"));
        assertTrue(response.body().contains("border-radius: 999px"));
        assertTrue(response.body().contains("background: rgba(15, 23, 42, .24)"));
        assertTrue(response.body().contains("background: hsl(var(--cluster-hue) 48% 24%)"));
        assertTrue(!response.body().contains("data-field=\"host\""));
        assertTrue(!response.body().contains("data-field=\"zone\""));
        assertTrue(!response.body().contains("data-field=\"identity\""));
        assertTrue(!response.body().contains("data-field=\"role\""));
        assertTrue(!response.body().contains("data-field=\"source\""));
        assertTrue(!response.body().contains("<span>Seq</span>"));
        assertTrue(!response.body().contains("<span>Rank</span>"));
        assertTrue(!response.body().contains("<span>Role</span>"));
        assertTrue(!response.body().contains("<span>Source</span>"));
        assertTrue(!response.body().contains("<span>Zone</span>"));
        assertTrue(!response.body().contains("<span>Agent</span>"));
        assertTrue(!response.body().contains("<span>Load</span>"));
        assertTrue(response.body().contains("<span>5min AVG</span>"));
        assertTrue(response.body().contains("normalizeAddress"));
        assertTrue(response.body().contains("window.location.host"));
        assertTrue(response.body().contains("hashKey"));
        assertTrue(response.body().contains("width: min(1320px, calc(100vw - 44px))"));
        assertTrue(response.body().contains("height: min(820px, 61.8vh)"));
        assertTrue(response.body().contains("grid-template-columns: minmax(300px, 1fr) minmax(0, 1.618fr)"));
        assertTrue(response.body().contains("white-space: nowrap"));
        assertTrue(response.body().contains("writing-mode: horizontal-tb"));
        assertTrue(!response.body().contains("__COORDINATOR_ID__"));
        assertTrue(!response.body().contains("data-coordinator-id"));
        assertTrue(!response.body().contains("hostname"));
        assertTrue(!response.body().contains(".byted.org"));
        assertTrue(!response.body().contains("Keep result"));
        assertTrue(!response.body().contains("id=\"task-keep\""));
        assertTrue(!response.body().contains("STDOUT"));
        assertTrue(!response.body().contains("STDERR"));
        assertTrue(!response.body().contains("stdout_tail"));
        assertTrue(!response.body().contains("stderr_tail"));
        assertTrue(!response.body().contains("output_truncated"));
        assertTrue(!response.body().contains("box-shadow"));
        assertTrue(!response.body().contains("backdrop-filter"));
        assertTrue(!response.body().contains("gradient"));
        assertTrue(!response.body().contains("radial-gradient"));
        assertTrue(!response.body().contains("liquid-flow"));
        assertTrue(!response.body().contains("water-ripple"));
        assertTrue(!response.body().contains("jelly-scroll"));
        assertTrue(!response.body().contains("playJelly"));
        assertTrue(response.body().contains("loadSortValue(right) - loadSortValue(left)"));
        assertTrue(response.body().contains("window.PulseView = PulseView"));
        assertTrue(response.body().contains("data-agent-key"));
        assertTrue(!response.body().contains("data-agent-id"));
        assertTrue(response.body().contains("const palette = [205, 188, 168, 146, 126, 95, 48, 215, 200, 178]"));
        assertTrue(response.body().contains("const loadWindows = new Map()"));
        assertTrue(response.body().contains("windowStart"));
        assertTrue(response.body().contains("displayAvg"));
        assertTrue(response.body().contains("sampledAtMs"));
        assertTrue(response.body().contains("recordLoadSamples(this.state.hosts)"));
        assertTrue(!response.body().contains("""
                        updateTiles(grid, hosts) {
                          recordLoadSamples(hosts);
                """));
        assertTrue(!response.body().contains("sum: sample"));
        assertTrue(!response.body().contains("count: 1,"));
        assertTrue(!response.body().contains("265, 338"));
        assertTrue(!response.body().contains("http-equiv=\"refresh\""));
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
    }

    @Test
    void heartbeatResponseCarriesGroupPlanMessage() throws Exception {
        postAlive("agent-1", "host-1", "10.0.0.1");
        postAlive("agent-2", "host-2", "10.0.0.2");

        HttpResponse<String> response = postJson("/heartbeat", """
                {
                  "agent_id": "agent-2",
                  "epoch": 1,
                  "seq": 44,
                  "ttl_ms": 15000,
                  "messages": [
                    {
                      "message_id": "msg-agent-2-44",
                      "type": "state.heartbeat",
                      "version": 1,
                      "payload": {"host": "host-2", "ip": "10.0.0.2", "cluster": "cluster-a", "area": "area-a"}
                    }
                  ]
                }
                """);

        assertEquals(200, response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        JsonNode payload = json.get("messages").get(0).get("payload");
        assertEquals("cmd.group_plan", json.get("messages").get(0).get("type").asText());
        assertEquals("follower", payload.get("group_mode").asText());
        assertEquals("agent-1", payload.get("leader_agent_id").asText());
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
