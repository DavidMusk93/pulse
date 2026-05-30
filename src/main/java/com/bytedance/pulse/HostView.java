package com.bytedance.pulse;

import java.util.Map;

public record HostView(
        String agentId,
        long epoch,
        long seq,
        long ttlMs,
        long observedAtMs,
        long expireAtMs,
        String status,
        String source,
        String host,
        String ip,
        String cluster,
        String area,
        String zone,
        String role,
        String load,
        Map<String, Object> state) {}
