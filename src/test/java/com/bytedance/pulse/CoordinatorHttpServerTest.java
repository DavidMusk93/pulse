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
        assertTrue(hosts.body().contains("last_observed_age_ms"));
        assertTrue(hosts.body().contains("group_id"));
        assertTrue(hosts.body().contains("group_mode"));
        assertTrue(hosts.body().contains("leader_agent_id"));
        assertTrue(hosts.body().contains("group_size_limit"));
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
        assertTrue(js.body().contains("磁盘布局 dry-run"));
        assertTrue(js.body().contains("块分布 dry-run"));
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
        assertTrue(js.body().contains("格式化"));
        assertTrue(js.body().contains("拷贝"));
        assertTrue(js.body().contains("/tasks/completions/"));
        assertTrue(js.body().contains("/keep"));
        assertTrue(js.body().contains("task_id"));
        assertTrue(js.body().contains("Trace"));
        assertTrue(!js.body().contains("status-led"));
        assertTrue(!js.body().contains("tile-seen"));
        assertTrue(!js.body().contains("PlayCircleOutlined"));
        assertTrue(!js.body().contains("sum: sample"));
        assertTrue(!js.body().contains("count: 1,"));
        assertTrue(!js.body().contains("shell command"));

        HttpResponse<String> css = get("/assets/pulse-hosts.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("aspect-ratio:1"));
        assertTrue(css.body().contains("grid-template-columns:minmax(300px,1fr) minmax(0,1.618fr)"));
        assertTrue(css.body().contains("height:min(820px,61.8vh)"));
        assertTrue(css.body().contains("minmax(210px,1fr)"));
        assertTrue(css.body().contains("white-space:nowrap"));
        assertTrue(css.body().contains("text-overflow:ellipsis"));
        assertTrue(css.body().contains(".auto-fit"));
        assertTrue(css.body().contains(".metric-fit-value"));
        assertTrue(css.body().contains(".metric-row>.ant-col"));
        assertTrue(css.body().contains(".metric-row>.ant-col>.ant-card"));
        assertTrue(css.body().contains(".metric-row .ant-statistic"));
        assertTrue(css.body().contains(".cluster-toggle-button"));
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
        assertTrue(css.body().contains(".completion-viewer"));
        assertTrue(css.body().contains(".agent-async-alert"));
        assertTrue(css.body().contains(".trace-list"));
        assertTrue(css.body().contains(".completion-pane"));
        assertTrue(css.body().contains(".task-trace-card"));
        assertTrue(css.body().contains(".task-id-text"));
        assertTrue(css.body().contains(".task-workspace .ant-card-head"));
        assertTrue(css.body().contains(".task-workspace .ant-card-head-title"));
        assertTrue(css.body().contains(".completion-toolbar"));
        assertTrue(css.body().contains(".json-key"));
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
