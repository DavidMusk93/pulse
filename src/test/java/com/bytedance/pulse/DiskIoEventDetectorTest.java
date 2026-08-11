package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiskIoEventDetectorTest {
    @Test
    void emitsOneFiringEventAfterSustainedThresholdAndOneResolution() {
        DiskIoEventDetector detector = new DiskIoEventDetector(95, 10_000);
        long now = 1_710_000_010_000L;

        assertEquals(0, detector.evaluate("agent-1", now - 5_000, state(96, 5_000)).size());
        List<HostEvent> firing = detector.evaluate("agent-1", now, state(97, 10_000));

        assertEquals(1, firing.size());
        assertEquals(DiskIoEventDetector.EVENT_TYPE, firing.get(0).eventType());
        assertEquals("firing", firing.get(0).details().get("status"));
        assertEquals(1_710_000_000_000L, firing.get(0).details().get("started_at_ms"));
        assertEquals(1, detector.activeEvents().size());
        assertEquals(0, detector.evaluate("agent-1", now + 5_000, state(99, 15_000)).size());

        List<HostEvent> resolved = detector.evaluate("agent-1", now + 10_000, state(40, 0));

        assertEquals(1, resolved.size());
        assertEquals("resolved", resolved.get(0).details().get("status"));
        assertEquals(firing.get(0).details().get("incident_id"), resolved.get(0).details().get("incident_id"));
        assertEquals(0, detector.activeEvents().size());
    }

    @Test
    void eventIdentityIsStableAcrossDetectorRestart() {
        long now = 1_710_000_010_000L;
        HostEvent first = new DiskIoEventDetector(95, 10_000)
                .evaluate("agent-1", now, state(97, 10_000))
                .get(0);
        HostEvent replay = new DiskIoEventDetector(95, 10_000)
                .evaluate("agent-1", now + 5_000, state(98, 15_000))
                .get(0);

        assertEquals(first.details().get("incident_id"), replay.details().get("incident_id"));
        assertEquals(first.eventId(), replay.eventId());
    }

    private static Map<String, Object> state(double utilizationPct, long saturatedForMs) {
        return Map.of(
                "host", "host-1",
                "ip", "10.0.0.1",
                "cluster", "cdn_new",
                "area", "area-a",
                "disks", List.of(Map.of(
                        "device", "nvme0n1",
                        "io_util_pct", utilizationPct,
                        "saturated_for_ms", saturatedForMs)));
    }
}
