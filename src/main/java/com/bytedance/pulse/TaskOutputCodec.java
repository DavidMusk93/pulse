package com.bytedance.pulse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class TaskOutputCodec {
    static final String IDENTITY = "identity";
    static final String GZIP_BASE64 = "gzip+base64";

    private TaskOutputCodec() {}

    static EncodedOutput encode(String value) {
        byte[] raw = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(raw);
        String identity = new String(raw, StandardCharsets.UTF_8);
        String compressed = Base64.getEncoder().encodeToString(gzipped);
        if (compressed.length() < identity.length()) {
            return new EncodedOutput(compressed, GZIP_BASE64, sha256(raw), raw.length);
        }
        return new EncodedOutput(identity, IDENTITY, sha256(raw), raw.length);
    }

    static String decode(String value, String encoding) {
        if (encoding == null || encoding.isBlank() || IDENTITY.equals(encoding)) {
            return value == null ? "" : value;
        }
        if (GZIP_BASE64.equals(encoding)) {
            byte[] compressed = Base64.getDecoder().decode(value == null ? "" : value);
            return new String(gunzip(compressed), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("unsupported output_encoding: " + encoding);
    }

    static String sha256(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("sha256 unavailable", exception);
        }
    }

    private static byte[] gzip(byte[] bytes) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(bytes);
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("gzip failed", exception);
        }
    }

    private static byte[] gunzip(byte[] bytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return gzip.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("gzip decode failed", exception);
        }
    }

    record EncodedOutput(String value, String encoding, String sha256, int bytes) {}
}
