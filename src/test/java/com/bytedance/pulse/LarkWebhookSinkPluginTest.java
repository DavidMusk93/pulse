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
        assertEquals("red", payload.at("/card/header/template").asText());
        assertEquals(
                LarkWebhookSinkPlugin.sign(now / 1_000, "private-secret"),
                payload.get("sign").asText());
        assertTrue(payload.toString().contains("nvme0n1"));
        assertTrue(payload.toString().contains("98.20%"));
        assertTrue(payload.toString().contains("95.00%"));
        assertTrue(payload.toString().contains("20.0s"));
        assertTrue(payload.at("/card/elements/0/fields").isArray());
        assertTrue(!payload.toString().contains("cdn_new"));
        assertTrue(!payload.toString().contains("route-a"));
        assertTrue(!payload.toString().contains("delivery-a"));
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
        assertTrue(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains("已折叠"));
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
                        "saturated_for_ms", 20_000,
                        "cluster", "cdn_new",
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
