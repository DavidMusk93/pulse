package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventBusServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void thresholdSourceFeedsPeriodicRouteAndSingleRecovery() throws Exception {
        MutableClock clock = new MutableClock(1_710_000_010_000L);
        List<HostEvent> recorded = new ArrayList<>();
        RecordingSink sinkPlugin = new RecordingSink();
        Path statePath = tempDir.resolve("eventbus.json");
        try (EventBusService eventBus = new EventBusService(
                statePath, clock, recorded::add, false, List.of(sinkPlugin))) {
            EventBusConfig defaults = eventBus.view().config();
            eventBus.update(new EventBusConfig(
                    1,
                    defaults.eventTypes(),
                    defaults.sources(),
                    List.of(new EventSinkDefinition(
                            "sink-a", "Recording", RecordingSink.TYPE, true, Map.of())),
                    List.of(new EventRouteDefinition(
                            "route-a",
                            "Disk alerts",
                            true,
                            List.of("disk-io-saturation"),
                            List.of("disk.io_saturation"),
                            List.of("sink-a"),
                            PeriodicDigestGatePlugin.TYPE,
                            Map.of("interval_ms", 300_000, "publish_recovery", true)))));

            eventBus.ingest("agent-1", clock.millis() - 5_000, state(96, 5_000));
            assertTrue(recorded.isEmpty());
            eventBus.ingest("agent-1", clock.millis(), state(97, 10_000));

            assertEquals(1, recorded.size());
            assertEquals("firing", recorded.get(0).details().get("status"));
            assertEquals(1, eventBus.view().activeEvents().size());

            eventBus.dispatchDue();
            eventBus.dispatchDue();
            assertEquals(1, sinkPlugin.deliveries.size());
            assertFalse(sinkPlugin.deliveries.get(0).recovery());

            clock.advance(300_000);
            eventBus.dispatchDue();
            assertEquals(2, sinkPlugin.deliveries.size());

            eventBus.ingest("agent-1", clock.millis(), state(20, 0));
            assertEquals(2, recorded.stream()
                    .filter(event -> "disk.io_saturation".equals(event.eventType()))
                    .count());
            assertTrue(eventBus.view().activeEvents().isEmpty());
            eventBus.dispatchDue();
            assertEquals(2, sinkPlugin.deliveries.size());

            clock.advance(300_000);
            eventBus.dispatchDue();
            assertEquals(3, sinkPlugin.deliveries.size());
            assertTrue(sinkPlugin.deliveries.get(2).recovery());

            clock.advance(300_000);
            eventBus.dispatchDue();
            assertEquals(3, sinkPlugin.deliveries.size());
            assertEquals(3, recorded.stream()
                    .filter(event -> "eventbus.delivery".equals(event.eventType()))
                    .count());
        }

        assertTrue(Files.readString(statePath).contains("\"route-a::sink-a\""));
    }

    @Test
    void secretsAreRedactedAndMaskedUpdatePreservesStoredValue() throws Exception {
        Path statePath = tempDir.resolve("eventbus.json");
        try (EventBusService eventBus = new EventBusService(
                statePath, Clock.systemUTC(), event -> {}, false)) {
            EventBusConfig defaults = eventBus.view().config();
            EventBusConfig configured = new EventBusConfig(
                    1,
                    defaults.eventTypes(),
                    defaults.sources(),
                    List.of(new EventSinkDefinition(
                            "lark-a",
                            "飞书告警群",
                            LarkWebhookSinkPlugin.TYPE,
                            true,
                            Map.of(
                                    "webhook_url", "https://open.feishu.cn/open-apis/bot/v2/hook/private-token",
                                    "signing_secret", "private-secret",
                                    "title", "Pulse"))),
                    List.of());
            EventBusView first = eventBus.update(configured);

            assertEquals(
                    EventBusService.SECRET_MASK,
                    first.config().sinks().get(0).config().get("webhook_url"));
            assertFalse(first.toString().contains("private-token"));

            EventBusView second = eventBus.update(first.config());
            assertEquals(
                    EventBusService.SECRET_MASK,
                    second.config().sinks().get(0).config().get("signing_secret"));
        }

        String persisted = Files.readString(statePath);
        assertTrue(persisted.contains("private-token"));
        assertTrue(persisted.contains("private-secret"));
        assertFalse(persisted.contains(EventBusService.SECRET_MASK));
    }

    @Test
    void authenticatedWebhookSourcePublishesFiringAndResolvedEvents() throws Exception {
        List<HostEvent> recorded = new ArrayList<>();
        try (EventBusService eventBus = new EventBusService(
                tempDir.resolve("webhook-eventbus.json"),
                Clock.systemUTC(),
                recorded::add,
                false)) {
            EventBusConfig defaults = eventBus.view().config();
            eventBus.update(new EventBusConfig(
                    1,
                    defaults.eventTypes(),
                    List.of(new EventSourceDefinition(
                            "external-disk",
                            "External disk events",
                            WebhookEventSourcePlugin.TYPE,
                            "disk.io_saturation",
                            true,
                            Map.of(
                                    "ingest_token", "source-secret",
                                    "subject_field", "disk",
                                    "summary_field", "message",
                                    "status_field", "state",
                                    "incident_id_field", "incident"))),
                    List.of(),
                    List.of()));

            assertThrows(SecurityException.class, () -> eventBus.publish(
                    "external-disk", "wrong", Map.of("disk", "nvme0n1")));
            eventBus.publish("external-disk", "source-secret", Map.of(
                    "agent_id", "agent-1",
                    "disk", "nvme0n1",
                    "message", "disk saturated",
                    "state", "firing",
                    "incident", "disk-incident-1"));
            eventBus.publish("external-disk", "source-secret", Map.of(
                    "agent_id", "agent-1",
                    "disk", "nvme0n1",
                    "message", "disk recovered",
                    "state", "resolved",
                    "incident", "disk-incident-1"));

            assertEquals(List.of("firing", "resolved"), recorded.stream()
                    .map(event -> event.details().get("status").toString())
                    .toList());
            assertTrue(eventBus.view().activeEvents().isEmpty());
            assertEquals(
                    EventBusService.SECRET_MASK,
                    eventBus.view().config().sources().get(0).config().get("ingest_token"));
        }
    }

    @Test
    void activeIncidentsSurviveCoordinatorRestart() throws Exception {
        Path statePath = tempDir.resolve("restart-eventbus.json");
        try (EventBusService eventBus = new EventBusService(
                statePath, Clock.systemUTC(), event -> {}, false)) {
            EventBusConfig defaults = eventBus.view().config();
            eventBus.update(new EventBusConfig(
                    1,
                    defaults.eventTypes(),
                    List.of(new EventSourceDefinition(
                            "external",
                            "External",
                            WebhookEventSourcePlugin.TYPE,
                            "disk.io_saturation",
                            true,
                            Map.of(
                                    "ingest_token", "secret",
                                    "subject_field", "subject",
                                    "summary_field", "summary",
                                    "status_field", "status"))),
                    List.of(),
                    List.of()));
            eventBus.publish("external", "secret", Map.of(
                    "subject", "disk-a",
                    "summary", "saturated",
                    "status", "firing",
                    "incident_id", "incident-a"));
            assertEquals(1, eventBus.view().activeEvents().size());
        }

        try (EventBusService reloaded = new EventBusService(
                statePath, Clock.systemUTC(), event -> {}, false)) {
            assertEquals(1, reloaded.view().activeEvents().size());
            assertEquals("incident-a", reloaded.view().activeEvents().get(0).incidentId());
        }
    }

    private static Map<String, Object> state(double utilizationPct, long saturatedForMs) {
        return Map.of(
                "host", "host-1",
                "ip", "10.0.0.1",
                "cluster", "cdn_new",
                "disks", List.of(Map.of(
                        "device", "nvme0n1",
                        "io_util_pct", utilizationPct,
                        "saturated_for_ms", saturatedForMs)));
    }

    private static final class RecordingSink implements EventPlugin.Sink {
        private static final String TYPE = "recording";
        private final List<Delivery> deliveries = new ArrayList<>();

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(TYPE, "sink", "Recording", "Test sink", List.of());
        }

        @Override
        public DeliveryReceipt deliver(Map<String, Object> config, Delivery delivery) {
            deliveries.add(delivery);
            return new DeliveryReceipt("", "test", delivery.events().size(), Map.of());
        }
    }

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
