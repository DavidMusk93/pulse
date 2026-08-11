package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FanoutServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void periodicallySendsActiveDigestAndOneRecoveryDigest() throws Exception {
        MutableClock clock = new MutableClock(1_710_000_000_000L);
        AtomicReference<List<HostEvent>> active = new AtomicReference<>(List.of(activeEvent()));
        FakeLarkClient lark = new FakeLarkClient();
        Path config = tempDir.resolve("fanout.json");

        try (FanoutService service = new FanoutService(config, clock, active::get, lark, false)) {
            FanoutSource source = service.register(new FanoutRegistration("lark_chat", "Pulse 告警群", 1_000));
            assertEquals(FanoutService.MIN_INTERVAL_MS, source.intervalMs());
            assertEquals("oc_pulse", source.targetId());

            service.dispatchDue();
            service.dispatchDue();
            assertEquals(1, lark.messages.size());
            assertTrue(lark.messages.get(0).message().contains("活动事件: 1"));

            clock.advance(source.intervalMs());
            service.dispatchDue();
            assertEquals(2, lark.messages.size());

            active.set(List.of());
            clock.advance(source.intervalMs());
            service.dispatchDue();
            assertEquals(3, lark.messages.size());
            assertTrue(lark.messages.get(2).message().contains("全部恢复"));

            clock.advance(source.intervalMs());
            service.dispatchDue();
            assertEquals(3, lark.messages.size());
        }

        try (FanoutService reloaded = new FanoutService(config, clock, active::get, lark, false)) {
            assertEquals(1, reloaded.sources().size());
            assertEquals("Pulse 告警群", reloaded.sources().get(0).name());
            assertEquals(0, reloaded.sources().get(0).lastActiveCount());
        }
    }

    private static HostEvent activeEvent() {
        return new HostEvent(
                "event-1",
                1_710_000_000_000L,
                "agent-1",
                "error",
                DiskIoEventDetector.EVENT_TYPE,
                "saturated",
                Map.of(
                        "ip", "10.0.0.1",
                        "device", "nvme0n1",
                        "io_util_pct", 98.2,
                        "saturated_for_ms", 20_000));
    }

    private static final class FakeLarkClient implements FanoutService.LarkClient {
        private final List<SentMessage> messages = new ArrayList<>();

        @Override
        public LarkTarget resolveChat(String query) {
            return new LarkTarget("oc_pulse", query);
        }

        @Override
        public void send(String chatId, String message, String idempotencyKey) {
            messages.add(new SentMessage(chatId, message, idempotencyKey));
        }
    }

    private record SentMessage(String chatId, String message, String idempotencyKey) {}

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMs) {
            millis += deltaMs;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
