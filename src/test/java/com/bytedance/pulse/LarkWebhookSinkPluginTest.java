package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LarkWebhookSinkPluginTest {
    @Test
    void sendsSignedInteractiveCardWithoutExposingWebhook() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        LarkWebhookSinkPlugin plugin = new LarkWebhookSinkPlugin(transport);
        long now = 1_710_000_000_000L;
        EventPlugin.Delivery delivery = new EventPlugin.Delivery(
                "route-a",
                "sink-a",
                "delivery-a",
                now,
                false,
                List.of(event("nvme0n1")));

        EventPlugin.DeliveryReceipt receipt = plugin.deliver(Map.of(
                "webhook_url", "https://open.feishu.cn/open-apis/bot/v2/hook/private-token",
                "signing_secret", "private-secret",
                "title", "Pulse 磁盘告警"), delivery);

        assertEquals("interactive", receipt.format());
        assertEquals(1, transport.bodies.size());
        JsonNode payload = JsonSupport.objectMapper().readTree(transport.bodies.get(0));
        assertEquals("interactive", payload.get("msg_type").asText());
        assertEquals("2.0", payload.at("/card/schema").asText());
        assertTrue(payload.get("card").get("elements") == null);
        assertEquals("red", payload.at("/card/header/template").asText());
        assertEquals(
                LarkWebhookSinkPlugin.sign(now / 1_000, "private-secret"),
                payload.get("sign").asText());
        assertEquals("table", payload.at("/card/body/elements/1/tag").asText());
        assertEquals("cdn_new", payload.at("/card/body/elements/1/rows/0/cluster").asText());
        assertTrue(payload.toString().contains("nvme0n1"));
        assertEquals(98.2, payload.at("/card/body/elements/1/rows/0/util").asDouble());
        assertEquals(95.0, payload.at("/card/body/elements/1/rows/0/threshold").asDouble());
        assertTrue(payload.toString().contains("20.0s"));
        assertTrue(!payload.toString().contains("north"));
        assertTrue(!payload.toString().contains("route-a"));
        assertTrue(!payload.toString().contains("delivery-a"));
    }

    @Test
    void sortsAllTableRowsBySaturationDurationDescending() throws Exception {
        LarkWebhookSinkPlugin plugin = new LarkWebhookSinkPlugin(new RecordingTransport());
        List<EventPlugin.Event> events = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            events.add(event("disk-" + index, (index + 1L) * 1_000, "cluster-" + index % 2));
        }

        JsonNode payload = JsonSupport.objectMapper().readTree(plugin.render(
                Map.of("title", "Pulse"),
                new EventPlugin.Delivery(
                        "route", "sink", "delivery", 1_710_000_000_000L, false, events)));
        JsonNode table = payload.at("/card/body/elements/1");

        assertEquals(12, table.get("rows").size());
        assertEquals("disk-11", table.at("/rows/0/device").asText());
        assertEquals("12.0s", table.at("/rows/0/duration").asText());
        assertEquals("disk-0", table.at("/rows/11/device").asText());
        assertEquals(10, table.get("page_size").asInt());
        assertTrue(table.get("freeze_first_column").asBoolean());
    }

    @Test
    void foldsLargeCardsBelowLarkLimit() throws Exception {
        LarkWebhookSinkPlugin plugin = new LarkWebhookSinkPlugin(new RecordingTransport());
        List<EventPlugin.Event> events = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            events.add(event("disk-" + index + "-" + "x".repeat(4_000)));
        }
        byte[] body = plugin.render(
                Map.of("title", "Pulse"),
                new EventPlugin.Delivery("route", "sink", "delivery", 1_710_000_000_000L, false, events));

        assertTrue(body.length <= LarkWebhookSinkPlugin.MAX_BODY_BYTES);
        JsonNode payload = JsonSupport.objectMapper().readTree(body);
        assertTrue(payload.at("/card/body/elements/1/rows").size() < events.size());
        assertTrue(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("20 KB"));
    }

    @Test
    void rejectsNonOfficialWebhookBeforeNetworkCall() {
        RecordingTransport transport = new RecordingTransport();
        LarkWebhookSinkPlugin plugin = new LarkWebhookSinkPlugin(transport);

        assertThrows(IllegalArgumentException.class, () -> plugin.deliver(
                Map.of("webhook_url", "https://example.com/open-apis/bot/v2/hook/token"),
                new EventPlugin.Delivery(
                        "route", "sink", "delivery", 1_710_000_000_000L, false, List.of(event("disk")))));
        assertTrue(transport.bodies.isEmpty());
    }

    private static EventPlugin.Event event(String subject) {
        return event(subject, 20_000, "cdn_new");
    }

    private static EventPlugin.Event event(String subject, long durationMs, String cluster) {
        return new EventPlugin.Event(
                "event-" + subject,
                "incident-" + subject,
                "disk.io_saturation",
                "disk-source",
                subject,
                "agent-1",
                "error",
                "firing",
                1_710_000_000_000L,
                "Disk IO saturated",
                Map.of(
                        "ip", "10.0.0.1",
                        "io_util_pct", 98.2,
                        "threshold", 95,
                        "saturated_for_ms", durationMs,
                        "cluster", cluster,
                        "area", "north"));
    }

    private static final class RecordingTransport implements LarkWebhookSinkPlugin.Transport {
        private final List<byte[]> bodies = new ArrayList<>();

        @Override
        public LarkWebhookSinkPlugin.Response post(URI uri, byte[] body, Duration timeout) {
            bodies.add(body);
            return new LarkWebhookSinkPlugin.Response(200, "{\"code\":0,\"msg\":\"success\"}");
        }
    }
}
