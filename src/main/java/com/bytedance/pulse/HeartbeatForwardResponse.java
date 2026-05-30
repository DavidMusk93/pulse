package com.bytedance.pulse;

public record HeartbeatForwardResponse(
        boolean ok,
        String coordinatorId,
        int accepted,
        int merged) {}
