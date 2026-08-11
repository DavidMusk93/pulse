package com.bytedance.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class LarkWebhookSinkPlugin implements EventPlugin.Sink {
    static final String TYPE = "lark_webhook";
    static final int MAX_BODY_BYTES = 20 * 1024;

    private static final ObjectMapper MAPPER = JsonSupport.objectMapper();
    private final Transport transport;

    LarkWebhookSinkPlugin() {
        this(new HttpTransport());
    }

    LarkWebhookSinkPlugin(Transport transport) {
        this.transport = transport;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "sink",
                "飞书自定义机器人",
                "通过群机器人 Webhook 投递结构化 interactive 告警卡片。",
                List.of(
                        field("webhook_url", "Webhook URL", "password", true, true, null, "仅接受飞书官方机器人地址"),
                        field("signing_secret", "签名密钥", "password", false, true, null, "机器人启用签名校验时填写"),
                        field("title", "卡片标题", "text", false, false, "Pulse 事件中心", "显示在卡片顶部"),
                        field("mention_all", "@所有人", "boolean", false, false, false, "仅建议用于高优先级路由"),
                        field("dashboard_url", "详情页 URL", "text", false, false, "", "可选的事件详情入口")));
    }

    @Override
    public DeliveryReceipt deliver(Map<String, Object> config, Delivery delivery) throws Exception {
        String webhookUrl = text(config, "webhook_url", "");
        validateWebhook(webhookUrl);
        byte[] body = render(config, delivery);
        Response response = transport.post(
                URI.create(webhookUrl),
                body,
                Duration.ofMillis(longValue(config.get("timeout_ms"), 10_000)));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Lark webhook HTTP " + response.statusCode());
        }
        JsonNode result = MAPPER.readTree(response.body());
        int code = result.path("code").asInt(result.path("StatusCode").asInt(-1));
        if (code != 0) {
            throw new IllegalStateException(
                    "Lark webhook rejected message: code=" + code + " msg=" + result.path("msg").asText("unknown"));
        }
        return new DeliveryReceipt(
                "",
                "interactive",
                delivery.events().size(),
                Map.of("http_status", response.statusCode(), "body_bytes", body.length));
    }

    byte[] render(Map<String, Object> config, Delivery delivery) throws Exception {
        int visibleEvents = Math.min(10, delivery.events().size());
        byte[] body;
        do {
            Map<String, Object> payload = card(config, delivery, visibleEvents);
            String secret = text(config, "signing_secret", "");
            if (!secret.isBlank()) {
                long timestamp = delivery.createdAtMs() / 1_000;
                payload.put("timestamp", String.valueOf(timestamp));
                payload.put("sign", sign(timestamp, secret));
            }
            body = MAPPER.writeValueAsBytes(payload);
            visibleEvents--;
        } while (body.length > MAX_BODY_BYTES && visibleEvents >= 0);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Lark card exceeds 20 KB");
        }
        return body;
    }

    private static Map<String, Object> card(
            Map<String, Object> config,
            Delivery delivery,
            int visibleEvents) {
        boolean recovery = delivery.recovery();
        String title = text(config, "title", "Pulse 事件中心");
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown(recovery
                ? "**状态：** 全部恢复\n**活动事件：** 0"
                : "**状态：** 触发中\n**活动事件：** " + delivery.events().size()
                        + "\n**最高级别：** " + highestSeverity(delivery.events())));
        elements.add(Map.of("tag", "hr"));
        for (Event event : delivery.events().stream().limit(Math.max(0, visibleEvents)).toList()) {
            String host = attribute(event, "ip", attribute(event, "host", event.agentId()));
            String value = attribute(event, "value", "-");
            String threshold = attribute(event, "threshold", "-");
            String duration = attribute(event, "sustained_for_ms", "0");
            elements.add(markdown(
                    "**" + escape(host) + " / " + escape(event.subject()) + "**\n"
                            + escape(event.eventType()) + " · " + escape(event.severity())
                            + "\n值 `" + escape(value) + "` / 门槛 `" + escape(threshold)
                            + "` / 持续 `" + escape(duration) + "ms`"));
        }
        int folded = delivery.events().size() - Math.max(0, visibleEvents);
        if (folded > 0) {
            elements.add(markdown("其余 **" + folded + "** 个活动事件已折叠。"));
        }
        if (booleanValue(config.get("mention_all"), false) && !recovery) {
            elements.add(markdown("<at id=all></at> 请关注以上事件。"));
        }
        String dashboardUrl = text(config, "dashboard_url", "");
        if (!dashboardUrl.isBlank()) {
            elements.add(Map.of(
                    "tag", "action",
                    "actions", List.of(Map.of(
                            "tag", "button",
                            "text", Map.of("tag", "plain_text", "content", "查看事件详情"),
                            "type", "primary",
                            "url", dashboardUrl))));
        }
        elements.add(Map.of(
                "tag", "note",
                "elements", List.of(Map.of(
                        "tag", "plain_text",
                        "content", "route=" + delivery.routeId() + " · delivery=" + delivery.idempotencyKey()))));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true, "enable_forward", true));
        card.put("header", Map.of(
                "template", recovery ? "green" : severityTemplate(delivery.events()),
                "title", Map.of("tag", "plain_text", "content", title + (recovery ? " · 已恢复" : " · 告警摘要"))));
        card.put("elements", elements);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);
        return payload;
    }

    private static Map<String, Object> markdown(String content) {
        return Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", content));
    }

    private static String highestSeverity(List<Event> events) {
        if (events.stream().anyMatch(event -> "critical".equalsIgnoreCase(event.severity()))) {
            return "critical";
        }
        if (events.stream().anyMatch(event -> "error".equalsIgnoreCase(event.severity()))) {
            return "error";
        }
        if (events.stream().anyMatch(event -> "warn".equalsIgnoreCase(event.severity()))) {
            return "warn";
        }
        return events.isEmpty() ? "info" : events.get(0).severity();
    }

    private static String severityTemplate(List<Event> events) {
        return switch (highestSeverity(events).toLowerCase(java.util.Locale.ROOT)) {
            case "critical", "error" -> "red";
            case "warn", "warning" -> "orange";
            default -> "blue";
        };
    }

    static String sign(long timestamp, String secret) throws Exception {
        String value = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }

    private static void validateWebhook(String webhookUrl) {
        URI uri;
        try {
            uri = URI.create(webhookUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid Lark webhook URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean officialHost = host.equals("open.feishu.cn")
                || host.equals("open.larksuite.com")
                || host.equals("open.larkoffice.com");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !officialHost
                || uri.getPath() == null
                || !uri.getPath().startsWith("/open-apis/bot/v2/hook/")) {
            throw new IllegalArgumentException("webhook_url must be an official HTTPS Lark bot webhook");
        }
    }

    private static ConfigField field(
            String key,
            String label,
            String type,
            boolean required,
            boolean secret,
            Object defaultValue,
            String description) {
        return new ConfigField(key, label, type, required, secret, defaultValue, List.of(), description);
    }

    private static String text(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static String attribute(Event event, String key, String fallback) {
        Object value = event.attributes().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*");
    }

    interface Transport {
        Response post(URI uri, byte[] body, Duration timeout) throws Exception;
    }

    record Response(int statusCode, String body) {
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public Response post(URI uri, byte[] body, Duration timeout) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("content-type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }
    }
}
