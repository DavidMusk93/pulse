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
    void diskSourceExposesValidatedAgentRuntimeConfig() throws Exception {
        Path statePath = tempDir.resolve("source-config-eventbus.json");
        Files.writeString(statePath, JsonSupport.objectMapper().writeValueAsString(new EventBusState(
                new EventBusConfig(
                        1,
                        List.of(new EventTypeDefinition(
                                "disk.io_saturation", "Disk", "", "error", true)),
                        List.of(new EventSourceDefinition(
                                "disk-io-saturation",
                                "Disk source",
                                PulseMessageEventSourcePlugin.TYPE,
                                "disk.io_saturation",
                                true,
                                Map.of(
                                        "threshold_pct", 95,
                                        "sustain_ms", 10_000))),
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
                                Map.of("interval_ms", 300_000, "publish_recovery", true)))),
                Map.of(),
                List.of())));

        try (EventBusService eventBus = new EventBusService(
                statePath, Clock.systemUTC(), event -> {}, false, List.of(new RecordingSink()))) {
            EventSourceDefinition source = eventBus.view().config().sources().get(0);
            assertEquals(AgentDiskIoEventSourcePlugin.TYPE, source.pluginType());
            assertEquals(95.0, ((Number) source.config().get("threshold_pct")).doubleValue());
            assertEquals(10L, source.config().get("sustain_seconds"));
            assertFalse(source.config().containsKey("sustain_ms"));
            Map<String, Object> gateConfig = eventBus.view().config().routes().get(0).gateConfig();
            assertEquals(300L, gateConfig.get("interval_seconds"));
            assertFalse(gateConfig.containsKey("interval_ms"));
            assertFalse(gateConfig.containsKey("publish_recovery"));

            PulseMessage command = eventBus.agentSourceConfigMessage();
            assertEquals("cmd.event_source_config", command.type());
            assertEquals(20, command.payload().get("generation").toString().length());
            assertTrue(command.payload().toString().contains("threshold_pct=95"));

            EventBusConfig current = eventBus.view().config();
            assertThrows(IllegalArgumentException.class, () -> eventBus.update(new EventBusConfig(
                    current.version(),
                    current.eventTypes(),
                    List.of(new EventSourceDefinition(
                            source.id(),
                            source.name(),
                            source.pluginType(),
                            source.eventType(),
                            true,
                            Map.of("threshold_pct", 101, "sustain_seconds", 10))),
                    current.sinks(),
                    current.routes())));
        }
    }

    @Test
    void pendingEventsClearOnlyAfterEveryPipelineSinkSucceeds() throws Exception {
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
                    List.of(
                            new EventSinkDefinition(
                                    "sink-a", "Recording A", RecordingSink.TYPE, true, Map.of()),
                            new EventSinkDefinition(
                                    "sink-b", "Recording B", RecordingSink.TYPE, true, Map.of("fail", true))),
                    List.of(new EventRouteDefinition(
                            "route-a",
                            "Disk alerts",
                            true,
                            List.of("disk-io-saturation"),
                            List.of("disk.io_saturation"),
                            List.of("sink-a", "sink-b"),
                            PeriodicDigestGatePlugin.TYPE,
                            Map.of("interval_seconds", 300)))));

            eventBus.ingestMessages(
                    "agent-1",
                    clock.millis(),
                    List.of(eventMessage("firing", clock.millis())));

            assertEquals(1, recorded.size());
            assertEquals("firing", recorded.get(0).details().get("status"));
            assertEquals(1, eventBus.view().activeEvents().size());

            eventBus.dispatchDue();
            assertEquals(2, sinkPlugin.deliveries.size());
            assertEquals(1, eventBus.view().activeEvents().size());
            assertEquals(1, eventBus.view().pendingByRoute().get("route-a"));
            assertTrue(eventBus.view().routeStatus().get("route-a::sink-b").lastError().contains("test failure"));

            EventBusConfig configured = eventBus.view().config();
            eventBus.update(new EventBusConfig(
                    configured.version(),
                    configured.eventTypes(),
                    configured.sources(),
                    List.of(
                            new EventSinkDefinition(
                                    "sink-a", "Recording A", RecordingSink.TYPE, true, Map.of()),
                            new EventSinkDefinition(
                                    "sink-b", "Recording B", RecordingSink.TYPE, true, Map.of())),
                    configured.routes()));
            clock.advance(300_000);
            eventBus.dispatchDue();
            assertEquals(3, sinkPlugin.deliveries.size());
            assertEquals(1, sinkPlugin.deliveries.stream()
                    .filter(delivery -> "sink-a".equals(delivery.sinkId()))
                    .count());
            assertEquals(2, sinkPlugin.deliveries.stream()
                    .filter(delivery -> "sink-b".equals(delivery.sinkId()))
                    .count());
            assertTrue(eventBus.view().activeEvents().isEmpty());
            assertEquals(0, eventBus.view().pendingByRoute().get("route-a"));

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

    private static PulseMessage eventMessage(String status, long observedAtMs) {
        return new PulseMessage(
                "incident-1:" + status,
                AgentDiskIoEventEmitter.MESSAGE_TYPE,
                1,
                null,
                null,
                Map.ofEntries(
                        Map.entry("event_id", "incident-1:" + status),
                        Map.entry("incident_id", "incident-1"),
                        Map.entry("event_type", AgentDiskIoEventEmitter.EVENT_TYPE),
                        Map.entry("source_id", AgentDiskIoEventEmitter.SOURCE_ID),
                        Map.entry("subject", "nvme0n1"),
                        Map.entry("agent_id", "agent-1"),
                        Map.entry("severity", "firing".equals(status) ? "error" : "info"),
                        Map.entry("status", status),
                        Map.entry("observed_at_ms", observedAtMs),
                        Map.entry("summary", "disk " + status),
                        Map.entry("attributes", Map.of(
                                "ip", "10.0.0.1",
                                "io_util_pct", "firing".equals(status) ? 97 : 20))));
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
            if (Boolean.parseBoolean(String.valueOf(config.getOrDefault("fail", false)))) {
                throw new IllegalStateException("test failure");
            }
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
