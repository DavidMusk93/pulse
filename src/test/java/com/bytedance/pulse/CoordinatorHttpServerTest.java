package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    void hostsPageRendersWindowsPhoneStyleTiles() throws Exception {
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
                      "payload": {"host": "tile-host", "ip": "10.0.0.8", "cluster": "cluster-a", "area": "area-a", "role": "worker"}
                    }
                  ]
                }
                """);

        HttpResponse<String> response = get("/hosts");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("tile-grid"));
        assertTrue(response.body().contains("cluster-section"));
        assertTrue(response.body().contains("cluster-a"));
        assertTrue(response.body().contains("tile-host"));
        assertTrue(response.body().contains("Windows Phone"));
    }

    @Test
    void exposesDynamicGroupPlanApis() throws Exception {
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
                      "payload": {"host": "host-1", "ip": "10.0.0.1", "cluster": "cluster-a", "area": "area-a"}
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
                      "payload": {"host": "host-2", "ip": "10.0.0.2", "cluster": "cluster-a", "area": "area-a"}
                    }
                  ]
                }
                """);

        HttpResponse<String> groups = get("/api/groups");
        HttpResponse<String> plan = get("/api/agent-plan?agent_id=agent-2");

        assertEquals(200, groups.statusCode());
        assertTrue(groups.body().contains("cluster-a/area-a/000"));
        assertEquals(200, plan.statusCode());
        JsonNode planJson = mapper.readTree(plan.body());
        assertEquals("follower", planJson.get("group_mode").asText());
        assertEquals("agent-1", planJson.get("leader_agent_id").asText());
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
}
