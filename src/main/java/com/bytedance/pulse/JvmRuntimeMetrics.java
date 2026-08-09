package com.bytedance.pulse;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

final class JvmRuntimeMetrics {
    private static final JvmRuntimeMetrics SYSTEM = new JvmRuntimeMetrics();

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final com.sun.management.OperatingSystemMXBean operatingSystem;
    private final com.sun.management.ThreadMXBean allocatedThreads;
    private final boolean allocationTrackingSupported;
    private final long pauseTrackingSinceMs = System.currentTimeMillis();
    private final AtomicLong gcPauseEvents = new AtomicLong();
    private final AtomicLong gcPauseLastMs = new AtomicLong();
    private final AtomicLong gcPauseMaxMs = new AtomicLong();
    private Map<Long, Long> previousThreadAllocatedBytes = Map.of();
    private long previousAllocationSampleNanos;

    private JvmRuntimeMetrics() {
        java.lang.management.OperatingSystemMXBean systemBean = ManagementFactory.getOperatingSystemMXBean();
        operatingSystem = systemBean instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        allocatedThreads = threads instanceof com.sun.management.ThreadMXBean bean ? bean : null;
        allocationTrackingSupported = enableAllocationTracking(allocatedThreads);
        collectors.forEach(this::registerPauseListener);
    }

    static JvmRuntimeMetrics system() {
        return SYSTEM;
    }

    synchronized JvmRuntimeHealth sample() {
        long observedAtMs = System.currentTimeMillis();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        AllocationSample allocation = allocationSample();
        ProcMemory procMemory = procMemory();
        List<JvmGcCollectorHealth> gcCollectors = new ArrayList<>(collectors.size());
        long gcCollectionCount = 0;
        long gcCollectionTimeMs = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long count = collector.getCollectionCount();
            long timeMs = collector.getCollectionTime();
            if (count >= 0) {
                gcCollectionCount += count;
            }
            if (timeMs >= 0) {
                gcCollectionTimeMs += timeMs;
            }
            gcCollectors.add(new JvmGcCollectorHealth(collector.getName(), count, timeMs));
        }
        double processCpuLoad = operatingSystem == null ? -1 : finiteOrUnavailable(operatingSystem.getProcessCpuLoad());
        long processCpuTimeNs = operatingSystem == null ? -1 : operatingSystem.getProcessCpuTime();
        long committedVirtualMemoryBytes =
                operatingSystem == null ? -1 : operatingSystem.getCommittedVirtualMemorySize();
        boolean complete = processCpuLoad >= 0
                && processCpuTimeNs >= 0
                && procMemory.rssBytes() >= 0
                && allocationTrackingSupported;
        return new JvmRuntimeHealth(
                complete ? "ok" : "partial",
                observedAtMs,
                runtime.getUptime(),
                Runtime.getRuntime().availableProcessors(),
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                nonHeap.getCommitted(),
                gcCollectionCount,
                gcCollectionTimeMs,
                gcPauseEvents.get(),
                gcPauseLastMs.get(),
                gcPauseMaxMs.get(),
                pauseTrackingSinceMs,
                threads.getThreadCount(),
                threads.getPeakThreadCount(),
                threads.getDaemonThreadCount(),
                threads.getTotalStartedThreadCount(),
                processCpuLoad,
                processCpuTimeNs,
                procMemory.rssBytes(),
                procMemory.virtualMemoryBytes(),
                committedVirtualMemoryBytes,
                allocation.rateBytesPerSecond(),
                allocation.intervalMs(),
                allocationTrackingSupported,
                List.copyOf(gcCollectors));
    }

    private AllocationSample allocationSample() {
        if (!allocationTrackingSupported) {
            return new AllocationSample(-1, 0);
        }
        long nowNanos = System.nanoTime();
        long[] threadIds = threads.getAllThreadIds();
        long[] allocatedBytes = allocatedThreads.getThreadAllocatedBytes(threadIds);
        Map<Long, Long> current = new HashMap<>(threadIds.length * 2);
        long deltaBytes = 0;
        for (int index = 0; index < threadIds.length; index++) {
            long value = allocatedBytes[index];
            if (value < 0) {
                continue;
            }
            long threadId = threadIds[index];
            current.put(threadId, value);
            Long previous = previousThreadAllocatedBytes.get(threadId);
            if (previous != null && value >= previous) {
                deltaBytes += value - previous;
            }
        }
        long intervalNanos = previousAllocationSampleNanos == 0 ? 0 : nowNanos - previousAllocationSampleNanos;
        previousThreadAllocatedBytes = current;
        previousAllocationSampleNanos = nowNanos;
        if (intervalNanos <= 0) {
            return new AllocationSample(0, 0);
        }
        long rate = Math.round(deltaBytes * 1_000_000_000.0 / intervalNanos);
        return new AllocationSample(rate, intervalNanos / 1_000_000);
    }

    private void registerPauseListener(GarbageCollectorMXBean collector) {
        if (!(collector instanceof NotificationEmitter emitter)) {
            return;
        }
        emitter.addNotificationListener((notification, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())
                    || !(notification.getUserData() instanceof CompositeData data)) {
                return;
            }
            long pauseMs = GarbageCollectionNotificationInfo.from(data).getGcInfo().getDuration();
            gcPauseEvents.incrementAndGet();
            gcPauseLastMs.set(pauseMs);
            gcPauseMaxMs.accumulateAndGet(pauseMs, Math::max);
        }, null, null);
    }

    private static boolean enableAllocationTracking(com.sun.management.ThreadMXBean bean) {
        if (bean == null || !bean.isThreadAllocatedMemorySupported()) {
            return false;
        }
        try {
            if (!bean.isThreadAllocatedMemoryEnabled()) {
                bean.setThreadAllocatedMemoryEnabled(true);
            }
            return bean.isThreadAllocatedMemoryEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static double finiteOrUnavailable(double value) {
        return Double.isFinite(value) && value >= 0 ? value : -1;
    }

    private static ProcMemory procMemory() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return new ProcMemory(-1, -1);
        }
        long rssBytes = -1;
        long virtualMemoryBytes = -1;
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    rssBytes = procKilobytes(line);
                } else if (line.startsWith("VmSize:")) {
                    virtualMemoryBytes = procKilobytes(line);
                }
            }
        } catch (Exception ignored) {
            return new ProcMemory(-1, -1);
        }
        return new ProcMemory(rssBytes, virtualMemoryBytes);
    }

    private static long procKilobytes(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 2) {
            return -1;
        }
        try {
            return Math.multiplyExact(Long.parseLong(fields[1]), 1024);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1;
        }
    }

    private record AllocationSample(long rateBytesPerSecond, long intervalMs) {}

    private record ProcMemory(long rssBytes, long virtualMemoryBytes) {}
}

record JvmRuntimeHealth(
        String status,
        long observedAtMs,
        long uptimeMs,
        int availableProcessors,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long nonHeapCommittedBytes,
        long gcCollectionCount,
        long gcCollectionTimeMs,
        long gcPauseEvents,
        long gcPauseLastMs,
        long gcPauseMaxMs,
        long gcPauseTrackingSinceMs,
        int liveThreads,
        int peakThreads,
        int daemonThreads,
        long totalStartedThreads,
        double processCpuLoad,
        long processCpuTimeNs,
        long processRssBytes,
        long processVirtualMemoryBytes,
        long committedVirtualMemoryBytes,
        long allocationRateBytesPerSecond,
        long allocationSampleIntervalMs,
        boolean allocationTrackingSupported,
        List<JvmGcCollectorHealth> gcCollectors) {}

record JvmGcCollectorHealth(String name, long collectionCount, long collectionTimeMs) {}
