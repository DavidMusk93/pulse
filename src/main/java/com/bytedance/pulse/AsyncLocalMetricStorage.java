package com.bytedance.pulse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class AsyncLocalMetricStorage implements MetricStorage {
    private final Path dbPath;
    private final ArrayBlockingQueue<MetricWriteCommand> queue;
    private final int batchSize;
    private final Duration flushInterval;
    private final AtomicLong acceptedCommands = new AtomicLong();
    private final AtomicLong writtenCommands = new AtomicLong();
    private final AtomicLong droppedCommands = new AtomicLong();
    private final AtomicLong failedCommands = new AtomicLong();
    private final Thread writerThread;
    private volatile boolean running = true;
    private volatile String lastError = "";

    private AsyncLocalMetricStorage(Path dbPath, int queueSize, int batchSize, Duration flushInterval) throws Exception {
        this.dbPath = dbPath;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.batchSize = Math.max(1, batchSize);
        this.flushInterval = flushInterval == null || flushInterval.isNegative() || flushInterval.isZero()
                ? Duration.ofSeconds(1)
                : flushInterval;
        // Initialize schema before accepting writes so startup fails early on bad storage paths.
        try (LocalMetricStorage ignored = LocalMetricStorage.open(dbPath)) {
            // schema initialized
        }
        this.writerThread = new Thread(this::writerLoop, "pulse-sqlite-metric-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    static AsyncLocalMetricStorage open(Path dbPath, int queueSize, int batchSize, Duration flushInterval) throws Exception {
        return new AsyncLocalMetricStorage(dbPath, queueSize, batchSize, flushInterval);
    }

    @Override
    public void writeHeartbeat(HeartbeatMetricSample sample) {
        Objects.requireNonNull(sample, "sample");
        offer(new InsertHeartbeatCommand(sample));
    }

    @Override
    public void writeGroupLeader(GroupLeaderMetricSample sample) {
        Objects.requireNonNull(sample, "sample");
        offer(new InsertGroupLeaderCommand(sample));
    }

    private void offer(MetricWriteCommand command) {
        if (!running) {
            droppedCommands.incrementAndGet();
            return;
        }
        if (queue.offer(command)) {
            acceptedCommands.incrementAndGet();
        } else {
            droppedCommands.incrementAndGet();
        }
    }

    @Override
    public MetricQueryResult queryRange(MetricQuery query) throws Exception {
        try (LocalMetricStorage storage = LocalMetricStorage.open(dbPath)) {
            return storage.queryRange(query);
        }
    }

    @Override
    public List<HostEvent> queryEvents(MetricEventQuery query) throws Exception {
        try (LocalMetricStorage storage = LocalMetricStorage.open(dbPath)) {
            return storage.queryEvents(query);
        }
    }

    @Override
    public MetricStorageHealth health() {
        String status = failedCommands.get() > 0 || droppedCommands.get() > 0 ? "degraded" : "ok";
        return new MetricStorageHealth(
                status,
                queue.size(),
                acceptedCommands.get(),
                writtenCommands.get(),
                droppedCommands.get(),
                failedCommands.get(),
                lastError);
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && writtenCommands.get() + failedCommands.get() >= acceptedCommands.get()) {
                return true;
            }
            Thread.sleep(10);
        }
        return queue.isEmpty() && writtenCommands.get() + failedCommands.get() >= acceptedCommands.get();
    }

    private void writerLoop() {
        try (LocalMetricStorage storage = LocalMetricStorage.open(dbPath)) {
            while (running || !queue.isEmpty()) {
                MetricWriteCommand first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                writeCommand(storage, first);
                for (int i = 1; i < batchSize; i++) {
                    MetricWriteCommand next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    writeCommand(storage, next);
                }
            }
        } catch (Exception exception) {
            failedCommands.incrementAndGet();
            lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private void writeCommand(LocalMetricStorage storage, MetricWriteCommand command) {
        try {
            if (command instanceof InsertHeartbeatCommand heartbeat) {
                storage.writeHeartbeat(heartbeat.sample());
            } else if (command instanceof InsertGroupLeaderCommand groupLeader) {
                storage.writeGroupLeader(groupLeader.sample());
            }
            writtenCommands.incrementAndGet();
        } catch (Exception exception) {
            failedCommands.incrementAndGet();
            lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        writerThread.join(Math.max(1_000L, flushInterval.toMillis() * 2));
    }

    private sealed interface MetricWriteCommand permits InsertHeartbeatCommand, InsertGroupLeaderCommand {}

    private record InsertHeartbeatCommand(HeartbeatMetricSample sample) implements MetricWriteCommand {}

    private record InsertGroupLeaderCommand(GroupLeaderMetricSample sample) implements MetricWriteCommand {}
}

record MetricStorageHealth(
        String status,
        int queueDepth,
        long acceptedCommands,
        long writtenCommands,
        long droppedCommands,
        long failedCommands,
        String lastError) {}
