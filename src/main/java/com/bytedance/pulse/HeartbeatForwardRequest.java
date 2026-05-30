package com.bytedance.pulse;

import java.util.List;

public record HeartbeatForwardRequest(
        String sourceCoordinatorId,
        List<ForwardState> states) {
    public HeartbeatForwardRequest {
        states = states == null ? List.of() : List.copyOf(states);
    }
}
