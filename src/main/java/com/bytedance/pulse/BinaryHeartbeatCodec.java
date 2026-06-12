package com.bytedance.pulse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class BinaryHeartbeatCodec {
    static final String CONTENT_TYPE = "application/vnd.pulse.binary";
    private static final String HEADER_PREFIX = "X-Pulse-";
    private static final TypeReference<List<PulseMessage>> MESSAGE_LIST = new TypeReference<>() {};

    private BinaryHeartbeatCodec() {}

    static boolean writeIfBinary(HttpExchange exchange, HeartbeatResponse response, ObjectMapper mapper)
            throws IOException {
        Optional<EncodedBinaryHeartbeat> encoded = encode(response, mapper);
        if (encoded.isEmpty()) {
            return false;
        }
        Headers headers = exchange.getResponseHeaders();
        encoded.get().headers().forEach(headers::set);
        byte[] body = encoded.get().body();
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
        return true;
    }

    static Optional<EncodedBinaryHeartbeat> encode(HeartbeatResponse response, ObjectMapper mapper)
            throws IOException {
        if (response == null || !response.agents().isEmpty()) {
            return Optional.empty();
        }
        List<PulseMessage> messages = response.messages();
        List<PulseMessage> fileMessages = messages.stream()
                .filter(BinaryHeartbeatCodec::hasInlineFileContent)
                .toList();
        if (fileMessages.size() != 1) {
            return Optional.empty();
        }
        PulseMessage fileMessage = fileMessages.get(0);
        Map<String, Object> payload = fileMessage.payload();
        byte[] body = Base64.getDecoder().decode(stringValue(payload.get("content")));
        long declaredLength = longValue(payload.get("content_bytes"), body.length);
        if (declaredLength != body.length) {
            throw new IllegalArgumentException("binary heartbeat content byte size mismatch");
        }
        String sha256 = stringValue(payload.get("content_sha256"));
        if (!sha256.isBlank() && !TaskOutputCodec.sha256(body).equals(sha256)) {
            throw new IllegalArgumentException("binary heartbeat content sha256 mismatch");
        }
        List<PulseMessage> companionMessages = messages.stream()
                .filter(message -> message != fileMessage)
                .toList();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", CONTENT_TYPE);
        headers.put(HEADER_PREFIX + "Envelope-Version", "1");
        headers.put(HEADER_PREFIX + "Coordinator-Id", headerValue(response.coordinatorId()));
        headers.put(HEADER_PREFIX + "Accepted-Seq", response.acceptedSeq() == null ? "" : response.acceptedSeq().toString());
        headers.put(HEADER_PREFIX + "Message-Id", headerValue(fileMessage.messageId()));
        headers.put(HEADER_PREFIX + "Message-Type", headerValue(fileMessage.type()));
        headers.put(HEADER_PREFIX + "Message-Version", Integer.toString(fileMessage.version()));
        headers.put(HEADER_PREFIX + "Reply-To", headerValue(fileMessage.replyTo()));
        headers.put(HEADER_PREFIX + "Deadline-Ms", fileMessage.deadlineMs() == null ? "" : fileMessage.deadlineMs().toString());
        headers.put(HEADER_PREFIX + "Agent-Id", headerValue(payload.get("agent_id")));
        headers.put(HEADER_PREFIX + "Task-Id", headerValue(payload.get("task_id")));
        headers.put(HEADER_PREFIX + "File-Id", headerValue(payload.get("file_id")));
        headers.put(HEADER_PREFIX + "File-Name", headerValue(payload.get("file_name")));
        headers.put(HEADER_PREFIX + "File-Role", headerValue(payload.get("file_role")));
        headers.put(HEADER_PREFIX + "Target-Dir", headerValue(payload.get("target_dir")));
        headers.put(HEADER_PREFIX + "Mode", headerValue(payload.get("mode")));
        headers.put(HEADER_PREFIX + "Content-Encoding", "identity");
        headers.put(HEADER_PREFIX + "Content-Length", Long.toString(body.length));
        headers.put(HEADER_PREFIX + "Content-Sha256", headerValue(sha256));
        if (!companionMessages.isEmpty()) {
            headers.put(
                    HEADER_PREFIX + "Companion-Messages",
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(mapper.writeValueAsBytes(companionMessages)));
        }
        return Optional.of(new EncodedBinaryHeartbeat(headers, body));
    }

    static boolean isBinary(HttpHeaders headers) {
        return headers.firstValue("content-type")
                .map(value -> value.toLowerCase().startsWith(CONTENT_TYPE))
                .orElse(false);
    }

    static HeartbeatResponse decode(HttpResponseLike response, ObjectMapper mapper) throws IOException {
        byte[] body = response.body();
        Map<String, List<String>> headers = response.headers();
        long declaredLength = longValue(first(headers, HEADER_PREFIX + "Content-Length"), -1);
        if (declaredLength >= 0 && declaredLength != body.length) {
            throw new IllegalArgumentException("binary heartbeat content byte size mismatch");
        }
        String sha256 = first(headers, HEADER_PREFIX + "Content-Sha256");
        if (!sha256.isBlank() && !TaskOutputCodec.sha256(body).equals(sha256)) {
            throw new IllegalArgumentException("binary heartbeat content sha256 mismatch");
        }
        List<PulseMessage> messages = new ArrayList<>();
        String companion = first(headers, HEADER_PREFIX + "Companion-Messages");
        if (!companion.isBlank()) {
            messages.addAll(mapper.readValue(Base64.getUrlDecoder().decode(companion), MESSAGE_LIST));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "task_id", first(headers, HEADER_PREFIX + "Task-Id"));
        putIfPresent(payload, "file_id", first(headers, HEADER_PREFIX + "File-Id"));
        putIfPresent(payload, "agent_id", first(headers, HEADER_PREFIX + "Agent-Id"));
        putIfPresent(payload, "file_name", first(headers, HEADER_PREFIX + "File-Name"));
        putIfPresent(payload, "file_role", first(headers, HEADER_PREFIX + "File-Role"));
        putIfPresent(payload, "target_dir", first(headers, HEADER_PREFIX + "Target-Dir"));
        payload.put("content_encoding", "base64");
        payload.put("content", Base64.getEncoder().encodeToString(body));
        payload.put("content_sha256", sha256);
        payload.put("content_bytes", body.length);
        putIfPresent(payload, "mode", first(headers, HEADER_PREFIX + "Mode"));
        messages.add(new PulseMessage(
                first(headers, HEADER_PREFIX + "Message-Id"),
                first(headers, HEADER_PREFIX + "Message-Type"),
                (int) longValue(first(headers, HEADER_PREFIX + "Message-Version"), 1),
                blankToNull(first(headers, HEADER_PREFIX + "Reply-To")),
                longObject(first(headers, HEADER_PREFIX + "Deadline-Ms")),
                payload));
        return HeartbeatResponse.single(
                first(headers, HEADER_PREFIX + "Coordinator-Id"),
                longValue(first(headers, HEADER_PREFIX + "Accepted-Seq"), 0),
                messages);
    }

    private static boolean hasInlineFileContent(PulseMessage message) {
        return "cmd.file_put".equals(message.type())
                && message.payload() != null
                && "base64".equals(stringValue(message.payload().get("content_encoding")))
                && !stringValue(message.payload().get("content")).isBlank();
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (!value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.getOrDefault(name, headers.getOrDefault(name.toLowerCase(), List.of()));
        return values.isEmpty() ? "" : values.get(0);
    }

    private static String headerValue(Object value) {
        return stringValue(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static Long longObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record EncodedBinaryHeartbeat(Map<String, String> headers, byte[] body) {}

    record HttpResponseLike(Map<String, List<String>> headers, byte[] body) {
        static HttpResponseLike of(HttpHeaders headers, byte[] body) {
            return new HttpResponseLike(headers.map(), body);
        }
    }
}
