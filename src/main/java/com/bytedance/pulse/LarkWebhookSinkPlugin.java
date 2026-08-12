package com.bytedance.pulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
                        field("dashboard_url", "详情页 URL", "text", false, false, "", "可选的事件详情入口"),
                        field("timeout_seconds", "请求超时 (秒)", "number", false, false, 10, "取值 1-60 秒")));
    }

    @Override
    public DeliveryReceipt deliver(Map<String, Object> config, Delivery delivery) throws Exception {
        String webhookUrl = text(config, "webhook_url", "");
        validateWebhook(webhookUrl);
        byte[] body = render(config, delivery);
        Response response = transport.post(
                URI.create(webhookUrl),
                body,
                Duration.ofSeconds(longValue(config.get("timeout_seconds"), 10)));
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
        List<Event> events = delivery.events().stream()
                .sorted(Comparator
                        .comparingLong(LarkWebhookSinkPlugin::durationMs)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(Event::observedAtMs).reversed()))
                .toList();
        int low = 0;
        int high = events.size();
        byte[] best = null;
        while (low <= high) {
            int visibleEvents = low + (high - low) / 2;
            byte[] candidate = serialize(config, delivery, events, visibleEvents);
            if (candidate.length <= MAX_BODY_BYTES) {
                best = candidate;
                low = visibleEvents + 1;
            } else {
                high = visibleEvents - 1;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Lark card exceeds 20 KB");
        }
        return best;
    }

    private static byte[] serialize(
            Map<String, Object> config,
            Delivery delivery,
            List<Event> events,
            int visibleEvents) throws Exception {
        Map<String, Object> payload = card(config, delivery, events, visibleEvents);
        String secret = text(config, "signing_secret", "");
        if (!secret.isBlank()) {
            long timestamp = delivery.createdAtMs() / 1_000;
            payload.put("timestamp", String.valueOf(timestamp));
            payload.put("sign", sign(timestamp, secret));
        }
        return MAPPER.writeValueAsBytes(payload);
    }

    private static Map<String, Object> card(
            Map<String, Object> config,
            Delivery delivery,
            List<Event> events,
            int visibleEvents) {
        boolean recovery = delivery.recovery();
        String title = text(config, "title", "Pulse 事件中心");
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(summary(events));
        elements.add(eventTable(events.subList(0, Math.min(visibleEvents, events.size())), recovery));
        int folded = events.size() - Math.max(0, visibleEvents);
        if (folded > 0) {
            elements.add(markdown(
                    "已按持续时间降序展示 **" + visibleEvents + " / " + events.size()
                            + "** 条；其余 **" + folded + "** 条受 20 KB 消息上限折叠。",
                    "notation"));
        }
        if (booleanValue(config.get("mention_all"), false) && !recovery) {
            elements.add(markdown("<at id=all></at> 请关注以上事件。", "normal"));
        }
        String dashboardUrl = text(config, "dashboard_url", "");
        if (!dashboardUrl.isBlank()) {
            elements.add(Map.of(
                    "tag", "button",
                    "text", Map.of("tag", "plain_text", "content", "查看监控详情"),
                    "type", "primary",
                    "size", "small",
                    "behaviors", List.of(Map.of(
                            "type", "open_url",
                            "default_url", dashboardUrl))));
        }
        elements.add(markdown(
                "Pulse EventBus · " + formatTime(delivery.createdAtMs())
                        + " · 送达后自动清理待推送事件",
                "notation"));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", Map.of(
                "enable_forward", true,
                "update_multi", true,
                "width_mode", "fill",
                "summary", Map.of(
                        "content", title + " · " + events.size() + " 个磁盘 IO 事件")));
        card.put("header", Map.of(
                "template", recovery ? "green" : severityTemplate(events),
                "title", Map.of(
                        "tag", "plain_text",
                        "content", title + (recovery ? " · 已恢复" : " · 磁盘 IO 饱和")),
                "subtitle", Map.of(
                        "tag", "plain_text",
                        "content", "按持续时间降序 · " + events.size() + " 个活动事件"),
                "padding", "10px 12px"));
        card.put("body", Map.of(
                "direction", "vertical",
                "padding", "8px",
                "vertical_spacing", "6px",
                "elements", elements));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", "interactive");
        payload.put("card", card);
        return payload;
    }

    private static Map<String, Object> summary(List<Event> events) {
        long maxDuration = events.stream().mapToLong(LarkWebhookSinkPlugin::durationMs).max().orElse(0);
        double maxUtilization = events.stream().mapToDouble(LarkWebhookSinkPlugin::utilizationPct).max().orElse(0);
        long clusters = events.stream()
                .map(event -> attribute(event, "cluster", "unknown"))
                .distinct()
                .count();
        return Map.of(
                "tag", "column_set",
                "flex_mode", "bisect",
                "background_style", "grey",
                "horizontal_spacing", "small",
                "columns", List.of(
                        summaryColumn("活动事件", Integer.toString(events.size())),
                        summaryColumn("涉及集群", Long.toString(clusters)),
                        summaryColumn("最长持续", formatDuration(maxDuration)),
                        summaryColumn("最高 IO", formatPercent(maxUtilization))));
    }

    private static Map<String, Object> summaryColumn(String label, String value) {
        return Map.of(
                "tag", "column",
                "width", "weighted",
                "weight", 1,
                "padding", "6px 8px",
                "elements", List.of(Map.of(
                        "tag", "markdown",
                        "content", label + "\n**" + value + "**",
                        "text_align", "center",
                        "text_size", "normal")));
    }

    private static Map<String, Object> eventTable(List<Event> events, boolean recovery) {
        List<Map<String, Object>> rows = events.stream()
                .map(event -> eventRow(event, recovery))
                .toList();
        return Map.of(
                "tag", "table",
                "page_size", 10,
                "row_height", "low",
                "freeze_first_column", true,
                "header_style", Map.of(
                        "text_align", "left",
                        "text_size", "normal",
                        "background_style", "grey",
                        "text_color", "grey",
                        "bold", true,
                        "lines", 1),
                "columns", List.of(
                        column("cluster", "集群", "text", "auto", "left"),
                        column("host", "主机 / IP", "text", "auto", "left"),
                        column("device", "设备", "text", "80px", "left"),
                        numberColumn("util", "IO%", "80px", 2),
                        numberColumn("threshold", "阈值%", "80px", 2),
                        column("duration", "持续", "text", "80px", "right"),
                        dateColumn(),
                        column("status", "状态", "options", "80px", "center")),
                "rows", rows);
    }

    private static Map<String, Object> eventRow(Event event, boolean recovery) {
        String host = attribute(event, "host", "");
        String ip = attribute(event, "ip", event.agentId());
        String machine = host.isBlank() || host.equals(ip) ? ip : host + " · " + ip;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cluster", attribute(event, "cluster", "unknown"));
        row.put("host", machine);
        row.put("device", attribute(event, "device", event.subject()));
        row.put("util", utilizationPct(event));
        row.put("threshold", numberAttribute(event, "threshold", 0));
        row.put("duration", formatDuration(durationMs(event)));
        row.put("time", event.observedAtMs());
        row.put("status", List.of(Map.of(
                "text", recovery || "resolved".equalsIgnoreCase(event.status()) ? "已恢复" : "告警",
                "color", recovery || "resolved".equalsIgnoreCase(event.status()) ? "green" : "red")));
        return row;
    }

    private static Map<String, Object> column(
            String name,
            String displayName,
            String dataType,
            String width,
            String align) {
        return Map.of(
                "name", name,
                "display_name", displayName,
                "data_type", dataType,
                "width", width,
                "horizontal_align", align);
    }

    private static Map<String, Object> numberColumn(
            String name,
            String displayName,
            String width,
            int precision) {
        Map<String, Object> column = new LinkedHashMap<>(column(name, displayName, "number", width, "right"));
        column.put("format", Map.of("precision", precision, "separator", false));
        return column;
    }

    private static Map<String, Object> dateColumn() {
        Map<String, Object> column = new LinkedHashMap<>(column("time", "事件时间", "date", "140px", "left"));
        column.put("date_format", "MM-DD HH:mm:ss");
        return column;
    }

    private static Map<String, Object> markdown(String content, String textSize) {
        return Map.of(
                "tag", "markdown",
                "content", content,
                "text_size", textSize);
    }

    private static String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", value);
    }

    private static String formatDuration(long millis) {
        if (millis < 1_000) {
            return millis + "ms";
        }
        long seconds = millis / 1_000;
        if (seconds < 60) {
            return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1_000.0);
        }
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes + "m" + remainingSeconds + "s";
    }

    private static String formatTime(long timestampMs) {
        return Instant.ofEpochMilli(timestampMs).toString();
    }

    private static long durationMs(Event event) {
        return (long) numberAttribute(
                event,
                "saturated_for_ms",
                numberAttribute(event, "sustained_for_ms", 0));
    }

    private static double utilizationPct(Event event) {
        return numberAttribute(
                event,
                "io_util_pct",
                numberAttribute(event, "value", 0));
    }

    private static double numberAttribute(Event event, String key, double fallback) {
        Object value = event.attributes().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
