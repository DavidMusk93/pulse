package com.bytedance.pulse;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

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
    private final Map<Long, ProcessSample> tideSamples = new ConcurrentHashMap<>();
    private final long clockTickPerSecond = clockTickPerSecond();

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
        return nextHeartbeat(List.of());
    }

    public HeartbeatRequest nextHeartbeat(List<PulseMessage> extraMessages) {
        long nextSeq = seq.incrementAndGet();
        PulseMessage message = new PulseMessage(
                "msg-" + agentId + "-" + epoch + "-" + nextSeq + "-state",
                "state.heartbeat",
                1,
                null,
                null,
                heartbeatPayload());
        List<PulseMessage> messages = new ArrayList<>();
        messages.add(message);
        if (extraMessages != null) {
            messages.addAll(extraMessages);
        }
        return new HeartbeatRequest(null, agentId, epoch, nextSeq, ttlMs, messages, List.of());
    }

    private Map<String, Object> heartbeatPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "alive");
        payload.put("host", host);
        payload.put("ip", ip);
        payload.put("cluster", blankToUnknown(cluster));
        payload.put("area", blankToUnknown(area));
        payload.put("zone", blankToUnknown(zone));
        payload.put("role", role);
        payload.put("load", loadAverage());
        payload.put("tide_workers", tideWorkers());
        payload.put("agent_time_ms", clock.millis());
        return payload;
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

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() || "-".equals(value) ? "unknown" : value;
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

    private List<Map<String, Object>> tideWorkers() {
        Path proc = Path.of("/proc");
        if (!Files.isDirectory(proc)) {
            return List.of();
        }
        long now = clock.millis();
        long memTotalKb = memTotalKb();
        List<Map<String, Object>> workers = new ArrayList<>();
        try (Stream<Path> entries = Files.list(proc)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().chars().allMatch(Character::isDigit))
                    .sorted(Comparator.comparing(path -> Long.parseLong(path.getFileName().toString())))
                    .forEach(path -> readTideWorker(path, now, memTotalKb).ifPresent(workers::add));
        } catch (Exception ignored) {
            return List.of();
        }
        return workers;
    }

    private Optional<Map<String, Object>> readTideWorker(Path procDir, long now, long memTotalKb) {
        long pid;
        try {
            pid = Long.parseLong(procDir.getFileName().toString());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        String cmdline = readString(procDir.resolve("cmdline")).replace('\0', ' ').trim();
        if (!cmdline.contains("tide_worker")) {
            return Optional.empty();
        }

        Map<String, String> env = readEnviron(procDir.resolve("environ"));
        long cpuTicks = processCpuTicks(procDir.resolve("stat")).orElse(0L);
        long rssKb = rssKb(procDir.resolve("status")).orElse(0L);
        double cpuPercent = cpuPercent(pid, cpuTicks, now);
        double memPercent = memTotalKb <= 0 ? 0 : rssKb * 100.0 / memTotalKb;

        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("pid", pid);
        worker.put("cpu_percent", formatPercent(cpuPercent));
        worker.put("mem_percent", formatPercent(memPercent));
        worker.put("port1", env.getOrDefault("PORT1", ""));
        worker.put("component_version", env.getOrDefault("TIDELET_COMPONENT_VERSION", ""));
        return Optional.of(worker);
    }

    private double cpuPercent(long pid, long cpuTicks, long now) {
        ProcessSample previous = tideSamples.put(pid, new ProcessSample(cpuTicks, now));
        if (previous == null || now <= previous.observedAtMs || cpuTicks < previous.cpuTicks) {
            return 0;
        }
        double elapsedSeconds = (now - previous.observedAtMs) / 1000.0;
        double cpuSeconds = (cpuTicks - previous.cpuTicks) / (double) clockTickPerSecond;
        return elapsedSeconds <= 0 ? 0 : Math.max(0, cpuSeconds * 100.0 / elapsedSeconds);
    }

    private static Optional<Long> processCpuTicks(Path statPath) {
        String stat = readString(statPath);
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) {
            return Optional.empty();
        }
        String[] fields = stat.substring(close + 2).trim().split("\\s+");
        if (fields.length <= 12) {
            return Optional.empty();
        }
        try {
            long utime = Long.parseLong(fields[11]);
            long stime = Long.parseLong(fields[12]);
            return Optional.of(utime + stime);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Long> rssKb(Path statusPath) {
        return readString(statusPath).lines()
                .filter(line -> line.startsWith("VmRSS:"))
                .findFirst()
                .flatMap(AgentHeartbeatFactory::firstNumber);
    }

    private static long memTotalKb() {
        return readString(Path.of("/proc/meminfo")).lines()
                .filter(line -> line.startsWith("MemTotal:"))
                .findFirst()
                .flatMap(AgentHeartbeatFactory::firstNumber)
                .orElse(0L);
    }

    private static Optional<Long> firstNumber(String line) {
        String[] parts = line.trim().split("\\s+");
        for (String part : parts) {
            try {
                return Optional.of(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
                // keep scanning
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> readEnviron(Path environPath) {
        Map<String, String> env = new LinkedHashMap<>();
        String environ = readString(environPath);
        for (String entry : environ.split("\0")) {
            int separator = entry.indexOf('=');
            if (separator > 0) {
                env.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        return env;
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static long clockTickPerSecond() {
        String configured = System.getenv("PULSE_CLK_TCK");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Long.parseLong(configured));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            Process process = new ProcessBuilder("getconf", "CLK_TCK").start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return Math.max(1, Long.parseLong(output));
            }
        } catch (Exception ignored) {
            // Linux commonly uses 100 ticks per second.
        }
        return 100;
    }

    private record ProcessSample(long cpuTicks, long observedAtMs) {}
}
