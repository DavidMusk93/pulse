package com.bytedance.pulse;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class AgentHeartbeatFactory {
    private final String agentId;
    private final String host;
    private final String ip;
    private final String cluster;
    private final String area;
    private final String zone;
    private final String role;
    private final long epoch;
    private final long ttlMs;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();

    public AgentHeartbeatFactory(
            String agentId,
            String host,
            String ip,
            String cluster,
            String area,
            String zone,
            String role,
            long epoch,
            long ttlMs,
            Clock clock) {
        this.agentId = agentId;
        this.host = host;
        this.ip = ip;
        this.cluster = cluster;
        this.area = area;
        this.zone = zone;
        this.role = role;
        this.epoch = epoch;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    public HeartbeatRequest nextHeartbeat() {
        long nextSeq = seq.incrementAndGet();
        PulseMessage message = new PulseMessage(
                "msg-" + agentId + "-" + epoch + "-" + nextSeq + "-state",
                "state.heartbeat",
                1,
                null,
                null,
                Map.of(
                        "status", "alive",
                        "host", host,
                        "ip", ip,
                        "cluster", cluster,
                        "area", area,
                        "zone", zone,
                        "role", role,
                        "load", loadAverage(),
                        "agent_time_ms", clock.millis()));
        return new HeartbeatRequest(null, agentId, epoch, nextSeq, ttlMs, List.of(message), List.of());
    }

    public static AgentHeartbeatFactory fromEnvironment(Clock clock) {
        String host = env("PULSE_AGENT_HOST", localHostName());
        String ip = env("PULSE_AGENT_IP", firstNonLoopbackAddress().orElse("-"));
        String agentId = env("PULSE_AGENT_ID", host);
        String cluster = env("PULSE_AGENT_CLUSTER", "unknown");
        String area = env("PULSE_AGENT_AREA", "unknown");
        String zone = env("PULSE_AGENT_ZONE", area);
        String role = env("PULSE_AGENT_ROLE", "agent");
        long epoch = Long.parseLong(env("PULSE_AGENT_EPOCH", String.valueOf(clock.millis())));
        long ttlMs = Long.parseLong(env("PULSE_TTL_MS", "15000"));
        return new AgentHeartbeatFactory(agentId, host, ip, cluster, area, zone, role, epoch, ttlMs, clock);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }

    private static Optional<String> firstNonLoopbackAddress() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(networkInterface -> {
                        try {
                            return networkInterface.isUp() && !networkInterface.isLoopback();
                        } catch (SocketException ignored) {
                            return false;
                        }
                    })
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                    .sorted(Comparator.comparing(address -> address instanceof java.net.Inet6Address ? 0 : 1))
                    .map(InetAddress::getHostAddress)
                    .findFirst();
        } catch (SocketException ignored) {
            return Optional.empty();
        }
    }

    private static String loadAverage() {
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        if (load < 0) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", load);
    }
}
