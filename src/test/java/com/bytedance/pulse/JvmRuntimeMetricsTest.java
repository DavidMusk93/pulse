package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JvmRuntimeMetricsTest {
    @Test
    void samplesJvmMemoryGcThreadsCpuAndAllocation() {
        JvmRuntimeHealth first = JvmRuntimeMetrics.system().sample();
        byte[] allocation = new byte[64 * 1024];
        JvmRuntimeHealth second = JvmRuntimeMetrics.system().sample();

        assertTrue(first.observedAtMs() > 0);
        assertTrue(first.uptimeMs() >= 0);
        assertTrue(first.availableProcessors() > 0);
        assertTrue(first.heapUsedBytes() >= 0);
        assertTrue(first.heapCommittedBytes() >= first.heapUsedBytes());
        assertTrue(first.heapMaxBytes() >= first.heapCommittedBytes() || first.heapMaxBytes() == -1);
        assertTrue(first.nonHeapUsedBytes() >= 0);
        assertTrue(first.gcCollectionCount() >= 0);
        assertTrue(first.gcCollectionTimeMs() >= 0);
        assertTrue(first.gcPauseMaxMs() >= 0);
        assertTrue(first.liveThreads() > 0);
        assertTrue(first.peakThreads() >= first.liveThreads());
        assertTrue(first.processCpuLoad() >= -1 && first.processCpuLoad() <= 1);
        assertTrue(first.processCpuTimeNs() >= -1);
        assertTrue(first.processRssBytes() >= -1);
        assertFalse(first.gcCollectors().isEmpty());
        assertTrue(second.allocationRateBytesPerSecond() >= -1);
        assertTrue(second.allocationSampleIntervalMs() >= 0);
        assertTrue(allocation.length > 0);
    }
}
