package com.bytedance.pulse;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class SegmentedMetricStorage implements MetricStorage {
    private static final String SHARD_PREFIX = "metrics-raw-";
    private static final String SHARD_SUFFIX = ".db";
    private static final String CUTOVER_FILE = "legacy-cutover-ms";
    private static final long WIDE_QUERY_ROLLUP_THRESHOLD_MS = Duration.ofHours(1).toMillis();
    private static final long ROLLUP_QUERY_STEP_MS = Duration.ofMinutes(1).toMillis();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_ROLLUP_RETENTION_DAYS = 30;
    private static final long DEFAULT_ROLLUP_MAX_BYTES = 64L * 1024 * 1024 * 1024;

    private final Path shardDir;
    private final Path legacyDbPath;
    private final Clock clock;
    private final ArrayBlockingQueue<MetricWriteCommand> queue;
    private final int batchSize;
    private final Duration flushInterval;
    private final int retentionDays;
    private final Duration maintenanceInterval;
    private final long maxBytes;
    private final long legacyCutoverMs;
    private final RollupMetricStorage rollupStorage;
    private final Map<RollupKey, RollupAccumulator> pendingRollups = new LinkedHashMap<>();
    private final AtomicLong acceptedCommands = new AtomicLong();
    private final AtomicLong writtenCommands = new AtomicLong();
    private final AtomicLong droppedCommands = new AtomicLong();
    private final AtomicLong failedCommands = new AtomicLong();
    private final AtomicLong maintenanceCommands = new AtomicLong();
    private final AtomicLong deletedShards = new AtomicLong();
    private final AtomicLong checkpointCommands = new AtomicLong();
    private final AtomicLong transactionBatches = new AtomicLong();
    private final AtomicLong capacityDroppedCommands = new AtomicLong();
    private final AtomicInteger queueHighWatermark = new AtomicInteger();
    private final Thread writerThread;
    private volatile boolean running = true;
    private volatile boolean capacityExceeded;
    private volatile String lastError = "";
    private volatile long nextMaintenanceAtMs;
    private volatile long lastMaintenanceDurationMs;
    private volatile long retentionLagMs;
    private long latestRollupBucketMs = Long.MIN_VALUE;
    private LocalMetricStorage activeStorage;
    private LocalDate activeDate;

    private SegmentedMetricStorage(
            Path shardDir,
            Path legacyDbPath,
            int queueSize,
            int batchSize,
            Duration flushInterval,
            int retentionDays,
            Duration maintenanceInterval,
            long maxBytes,
            int rollupRetentionDays,
            long rollupMaxBytes,
            Clock clock) throws Exception {
        this.shardDir = shardDir.toAbsolutePath();
        this.legacyDbPath = legacyDbPath == null ? null : legacyDbPath.toAbsolutePath();
        this.clock = Objects.requireNonNull(clock);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.batchSize = Math.max(1, batchSize);
        this.flushInterval = positiveDuration(flushInterval, Duration.ofSeconds(1));
        this.retentionDays = Math.max(1, retentionDays);
        this.maintenanceInterval = positiveDuration(maintenanceInterval, Duration.ofMinutes(5));
        this.maxBytes = Math.max(1, maxBytes);
        Files.createDirectories(this.shardDir);
        this.rollupStorage =
                new RollupMetricStorage(this.shardDir, rollupRetentionDays, rollupMaxBytes, clock);
        this.legacyCutoverMs = loadOrCreateCutover();
        for (Path existingShard : shardPaths()) {
            try (LocalMetricStorage storage = LocalMetricStorage.open(existingShard)) {
                storage.initializeSegmentIndexes();
            }
        }
        try (LocalMetricStorage ignored = LocalMetricStorage.open(shardPath(dateAt(clock.millis())))) {
            ignored.initializeSegmentIndexes();
        }
        this.nextMaintenanceAtMs = clock.millis() + this.maintenanceInterval.toMillis();
        this.writerThread = new Thread(this::writerLoop, "pulse-sqlite-segment-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    static SegmentedMetricStorage open(
            Path shardDir,
            Path legacyDbPath,
            int queueSize,
            int batchSize,
            Duration flushInterval,
            int retentionDays,
            Duration maintenanceInterval,
            long maxBytes) throws Exception {
        return open(
                shardDir,
                legacyDbPath,
                queueSize,
                batchSize,
                flushInterval,
                retentionDays,
                maintenanceInterval,
                maxBytes,
                DEFAULT_ROLLUP_RETENTION_DAYS,
                DEFAULT_ROLLUP_MAX_BYTES,
                Clock.systemUTC());
    }

    static SegmentedMetricStorage open(
            Path shardDir,
            Path legacyDbPath,
            int queueSize,
            int batchSize,
            Duration flushInterval,
            int retentionDays,
            Duration maintenanceInterval,
            long maxBytes,
            int rollupRetentionDays,
            long rollupMaxBytes) throws Exception {
        return open(
                shardDir,
                legacyDbPath,
                queueSize,
                batchSize,
                flushInterval,
                retentionDays,
                maintenanceInterval,
                maxBytes,
                rollupRetentionDays,
                rollupMaxBytes,
                Clock.systemUTC());
    }

    static SegmentedMetricStorage open(
            Path shardDir,
            Path legacyDbPath,
            int queueSize,
            int batchSize,
            Duration flushInterval,
            int retentionDays,
            Duration maintenanceInterval,
            long maxBytes,
            Clock clock) throws Exception {
        return open(
                shardDir,
                legacyDbPath,
                queueSize,
                batchSize,
                flushInterval,
                retentionDays,
                maintenanceInterval,
                maxBytes,
                DEFAULT_ROLLUP_RETENTION_DAYS,
                DEFAULT_ROLLUP_MAX_BYTES,
                clock);
    }

    static SegmentedMetricStorage open(
            Path shardDir,
            Path legacyDbPath,
            int queueSize,
            int batchSize,
            Duration flushInterval,
            int retentionDays,
            Duration maintenanceInterval,
            long maxBytes,
            int rollupRetentionDays,
            long rollupMaxBytes,
            Clock clock) throws Exception {
        return new SegmentedMetricStorage(
                shardDir,
                legacyDbPath,
                queueSize,
                batchSize,
                flushInterval,
                retentionDays,
                maintenanceInterval,
                maxBytes,
                rollupRetentionDays,
                rollupMaxBytes,
                clock);
    }

    @Override
    public void writeHeartbeat(HeartbeatMetricSample sample) {
        Objects.requireNonNull(sample);
        offer(new InsertHeartbeatCommand(sample));
    }

    @Override
    public void writeGroupLeader(GroupLeaderMetricSample sample) {
        Objects.requireNonNull(sample);
        offer(new InsertGroupLeaderCommand(sample));
    }

    @Override
    public void writeHostEvent(HostEvent event) {
        Objects.requireNonNull(event);
        offer(new InsertHostEventCommand(event));
    }

    private void offer(MetricWriteCommand command) {
        if (!running || capacityExceeded) {
            droppedCommands.incrementAndGet();
            if (capacityExceeded) {
                capacityDroppedCommands.incrementAndGet();
            }
            return;
        }
        if (queue.offer(command)) {
            acceptedCommands.incrementAndGet();
            queueHighWatermark.accumulateAndGet(queue.size(), Math::max);
        } else {
            droppedCommands.incrementAndGet();
        }
    }

    @Override
    public MetricQueryResult queryRange(MetricQuery query) throws Exception {
        LocalDate oldestRawDate = dateAt(clock.millis()).minusDays(retentionDays - 1L);
        long oldestRawMs = oldestRawDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        boolean legacyCoversQuery =
                legacyDbPath != null && Files.isRegularFile(legacyDbPath) && query.startMs() <= legacyCutoverMs;
        if (!legacyCoversQuery && shouldUseRollupForWideQuery(query)) {
            MetricQueryResult rollup = rollupStorage.query(rollupQuery(query));
            if (!rollup.series().isEmpty()) {
                return rollup;
            }
        }
        if (!legacyCoversQuery && query.startMs() < oldestRawMs && rollupStorage.shardCount() > 0) {
            return rollupStorage.query(query);
        }
        try (LocalMetricStorage storage = LocalMetricStorage.openFederated(queryPaths(query.startMs(), query.endMs()))) {
            return storage.queryRange(query);
        }
    }

    private boolean shouldUseRollupForWideQuery(MetricQuery query) {
        return rollupStorage.shardCount() > 0
                && query.endMs() > query.startMs()
                && query.endMs() - query.startMs() >= WIDE_QUERY_ROLLUP_THRESHOLD_MS;
    }

    private static MetricQuery rollupQuery(MetricQuery query) {
        return new MetricQuery(
                query.metric(),
                query.agentIds(),
                query.startMs(),
                query.endMs(),
                Math.max(ROLLUP_QUERY_STEP_MS, query.stepMs()),
                query.seriesLimit(),
                query.pointLimit(),
                query.topN(),
                query.cluster());
    }

    @Override
    public List<HostEvent> queryEvents(MetricEventQuery query) throws Exception {
        try (LocalMetricStorage storage = LocalMetricStorage.openFederated(queryPaths(query.startMs(), query.endMs()))) {
            return storage.queryEvents(query);
        }
    }

    @Override
    public MetricStorageHealth health() {
        long managedBytes = managedBytes() + rollupStorage.bytes();
        long legacyBytes = databaseBytes(legacyDbPath);
        String status = failedCommands.get() > 0
                        || droppedCommands.get() > 0
                        || capacityExceeded
                        || queue.size() >= Math.max(1, queue.remainingCapacity())
                ? "degraded"
                : "ok";
        return new MetricStorageHealth(
                status,
                queue.size(),
                acceptedCommands.get(),
                writtenCommands.get(),
                droppedCommands.get(),
                failedCommands.get(),
                maintenanceCommands.get(),
                0,
                checkpointCommands.get(),
                transactionBatches.get(),
                lastError,
                managedBytes,
                legacyBytes,
                maxBytes + rollupStorage.maxBytes(),
                shardPaths().size() + rollupStorage.shardCount(),
                deletedShards.get() + rollupStorage.deletedShards(),
                capacityDroppedCommands.get(),
                queueHighWatermark.get(),
                lastMaintenanceDurationMs,
                retentionLagMs);
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

    Path shardPath(LocalDate date) {
        return shardDir.resolve(SHARD_PREFIX + DATE_FORMAT.format(date) + SHARD_SUFFIX);
    }

    private void writerLoop() {
        try {
            while (running || !queue.isEmpty()) {
                MetricWriteCommand first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    runMaintenanceIfDue();
                    continue;
                }
                List<MetricWriteCommand> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                writeBatch(batch);
                runMaintenanceIfDue();
            }
        } catch (Exception exception) {
            failedCommands.incrementAndGet();
            lastError = errorMessage(exception);
        } finally {
            closeActiveStorage();
        }
    }

    private void writeBatch(List<MetricWriteCommand> batch) {
        Map<LocalDate, List<MetricWriteCommand>> byDate = new LinkedHashMap<>();
        for (MetricWriteCommand command : batch) {
            byDate.computeIfAbsent(dateAt(command.observedAtMs()), ignored -> new ArrayList<>()).add(command);
        }
        for (Map.Entry<LocalDate, List<MetricWriteCommand>> entry : byDate.entrySet()) {
            List<MetricWriteCommand> shardBatch = entry.getValue();
            boolean committed = false;
            try {
                LocalMetricStorage storage = storageFor(entry.getKey());
                storage.beginTransaction();
                for (MetricWriteCommand command : shardBatch) {
                    command.write(storage);
                }
                storage.commitTransaction();
                writtenCommands.addAndGet(shardBatch.size());
                transactionBatches.incrementAndGet();
                committed = true;
            } catch (Exception exception) {
                rollbackActiveStorage(exception);
                failedCommands.addAndGet(shardBatch.size());
                lastError = errorMessage(exception);
            }
            if (committed) {
                try {
                    accumulateRollups(shardBatch);
                } catch (Exception exception) {
                    failedCommands.incrementAndGet();
                    lastError = "rollup: " + errorMessage(exception);
                }
            }
        }
    }

    private LocalMetricStorage storageFor(LocalDate date) throws Exception {
        if (activeStorage != null && date.equals(activeDate)) {
            return activeStorage;
        }
        if (activeStorage != null) {
            activeStorage.checkpointWalTruncate();
            checkpointCommands.incrementAndGet();
            activeStorage.close();
            activeStorage = null;
            activeDate = null;
        }
        activeStorage = LocalMetricStorage.open(shardPath(date));
        activeStorage.initializeSegmentIndexes();
        activeDate = date;
        return activeStorage;
    }

    private void rollbackActiveStorage(Exception original) {
        if (activeStorage == null) {
            return;
        }
        try {
            activeStorage.rollbackTransaction();
        } catch (Exception rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    private void runMaintenanceIfDue() {
        long now = clock.millis();
        if (now < nextMaintenanceAtMs) {
            return;
        }
        nextMaintenanceAtMs = now + maintenanceInterval.toMillis();
        long startedAt = System.nanoTime();
        try {
            LocalDate currentDate = dateAt(now);
            if (activeStorage != null && !currentDate.equals(activeDate)) {
                storageFor(currentDate);
            }
            if (activeStorage != null) {
                activeStorage.checkpointWal();
                checkpointCommands.incrementAndGet();
            }
            flushRollupsBefore(minuteBucket(now));
            rollupStorage.maintain(currentDate);
            LocalDate oldestRetainedDate = currentDate.minusDays(retentionDays - 1L);
            List<Path> shards = shardPaths();
            for (Path shard : shards) {
                LocalDate shardDate = shardDate(shard);
                if (shardDate.isBefore(oldestRetainedDate) && !shardDate.equals(activeDate)) {
                    deleteShard(shard);
                }
            }
            enforceCapacity(currentDate);
            retentionLagMs = retentionLagMs(oldestRetainedDate);
            maintenanceCommands.incrementAndGet();
        } catch (Exception exception) {
            failedCommands.incrementAndGet();
            lastError = errorMessage(exception);
        } finally {
            lastMaintenanceDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        }
    }

    private void enforceCapacity(LocalDate currentDate) throws Exception {
        List<Path> shards = shardPaths().stream()
                .sorted(Comparator.comparing(this::shardDate))
                .toList();
        long bytes = managedBytes();
        for (Path shard : shards) {
            if (bytes <= maxBytes) {
                break;
            }
            LocalDate date = shardDate(shard);
            if (date.equals(currentDate) || date.equals(activeDate)) {
                continue;
            }
            deleteShard(shard);
            bytes = managedBytes();
        }
        capacityExceeded = bytes > maxBytes || rollupStorage.overCapacity();
    }

    private void deleteShard(Path shard) throws Exception {
        Files.deleteIfExists(Path.of(shard + "-wal"));
        Files.deleteIfExists(Path.of(shard + "-shm"));
        if (Files.deleteIfExists(shard)) {
            deletedShards.incrementAndGet();
        }
    }

    private List<Path> queryPaths(long queryStartMs, long queryEndMs) {
        List<Path> paths = new ArrayList<>();
        if (legacyDbPath != null && Files.isRegularFile(legacyDbPath) && queryStartMs <= legacyCutoverMs) {
            paths.add(legacyDbPath);
        }
        LocalDate startDate = dateAt(queryStartMs);
        LocalDate endDate = dateAt(Math.max(queryStartMs, queryEndMs));
        paths.addAll(shardPaths().stream()
                .filter(path -> {
                    LocalDate date = shardDate(path);
                    return !date.isBefore(startDate) && !date.isAfter(endDate);
                })
                .toList());
        if (paths.isEmpty()) {
            paths.add(shardPath(dateAt(clock.millis())));
        }
        return List.copyOf(paths);
    }

    private List<Path> shardPaths() {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shardDir, SHARD_PREFIX + "*" + SHARD_SUFFIX)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    paths.add(path);
                }
            }
        } catch (Exception exception) {
            lastError = errorMessage(exception);
        }
        paths.sort(Comparator.comparing(this::shardDate));
        return List.copyOf(paths);
    }

    private long managedBytes() {
        return shardPaths().stream().mapToLong(SegmentedMetricStorage::databaseBytes).sum();
    }

    private static long databaseBytes(Path path) {
        if (path == null) {
            return 0;
        }
        return fileSize(path) + fileSize(Path.of(path + "-wal")) + fileSize(Path.of(path + "-shm"));
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long retentionLagMs(LocalDate oldestRetainedDate) {
        return shardPaths().stream()
                .map(this::shardDate)
                .min(LocalDate::compareTo)
                .filter(date -> date.isBefore(oldestRetainedDate))
                .map(date -> Duration.between(
                                date.atStartOfDay().toInstant(ZoneOffset.UTC),
                                oldestRetainedDate.atStartOfDay().toInstant(ZoneOffset.UTC))
                        .toMillis())
                .orElse(0L);
    }

    private long loadOrCreateCutover() throws Exception {
        Path marker = shardDir.resolve(CUTOVER_FILE);
        if (Files.isRegularFile(marker)) {
            long cutover = Long.parseLong(Files.readString(marker).trim());
            if (databaseLatestMtime(legacyDbPath) <= cutover) {
                return cutover;
            }
            long nextCutover = clock.millis();
            writeCutover(marker, nextCutover);
            return nextCutover;
        }
        long cutover = clock.millis();
        try {
            Files.writeString(marker, String.valueOf(cutover), StandardOpenOption.CREATE_NEW);
            return cutover;
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            return Long.parseLong(Files.readString(marker).trim());
        }
    }

    private static void writeCutover(Path marker, long cutover) throws Exception {
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                String.valueOf(cutover),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long databaseLatestMtime(Path path) {
        if (path == null) {
            return 0;
        }
        return Math.max(
                fileMtime(path),
                Math.max(fileMtime(Path.of(path + "-wal")), fileMtime(Path.of(path + "-shm"))));
    }

    private static long fileMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private LocalDate dateAt(long timestampMs) {
        return Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private LocalDate shardDate(Path shard) {
        String fileName = shard.getFileName().toString();
        String value = fileName.substring(SHARD_PREFIX.length(), fileName.length() - SHARD_SUFFIX.length());
        return LocalDate.parse(value, DATE_FORMAT);
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static String errorMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void closeActiveStorage() {
        Exception failure = null;
        try {
            flushAllRollups();
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            rollupStorage.close();
        } catch (Exception exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (activeStorage != null) {
            try {
                activeStorage.checkpointWalTruncate();
                checkpointCommands.incrementAndGet();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                activeStorage.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            failedCommands.incrementAndGet();
            lastError = errorMessage(failure);
        }
        activeStorage = null;
        activeDate = null;
    }

    @Override
    public void close() throws Exception {
        running = false;
        writerThread.join(Math.max(2_000L, flushInterval.toMillis() * 3));
        if (writerThread.isAlive()) {
            throw new IllegalStateException("segmented metric writer did not stop");
        }
    }

    private sealed interface MetricWriteCommand
            permits InsertHeartbeatCommand, InsertGroupLeaderCommand, InsertHostEventCommand {
        long observedAtMs();

        void write(LocalMetricStorage storage) throws Exception;

        void accumulate(SegmentedMetricStorage storage);
    }

    private record InsertHeartbeatCommand(HeartbeatMetricSample sample) implements MetricWriteCommand {
        @Override
        public long observedAtMs() {
            return sample.observedAtMs();
        }

        @Override
        public void write(LocalMetricStorage storage) throws Exception {
            storage.writeHeartbeat(sample);
        }

        @Override
        public void accumulate(SegmentedMetricStorage storage) {
            storage.accumulate(sample);
        }
    }

    private record InsertGroupLeaderCommand(GroupLeaderMetricSample sample) implements MetricWriteCommand {
        @Override
        public long observedAtMs() {
            return sample.observedAtMs();
        }

        @Override
        public void write(LocalMetricStorage storage) throws Exception {
            storage.writeGroupLeader(sample);
        }

        @Override
        public void accumulate(SegmentedMetricStorage storage) {
            storage.accumulate(sample);
        }
    }

    private record InsertHostEventCommand(HostEvent event) implements MetricWriteCommand {
        @Override
        public long observedAtMs() {
            return event.observedAtMs();
        }

        @Override
        public void write(LocalMetricStorage storage) throws Exception {
            storage.writeHostEvent(event);
        }

        @Override
        public void accumulate(SegmentedMetricStorage storage) {
            // Events are retained as discrete records and are not rolled up.
        }
    }

    private void accumulateRollups(List<MetricWriteCommand> commands) throws Exception {
        for (MetricWriteCommand command : commands) {
            command.accumulate(this);
            latestRollupBucketMs = Math.max(latestRollupBucketMs, minuteBucket(command.observedAtMs()));
        }
        flushRollupsBefore(latestRollupBucketMs);
    }

    private void accumulate(HeartbeatMetricSample sample) {
        Map<String, String> labels = Map.of("agent_id", sample.agentId(), "cluster", sample.cluster());
        Map<String, Object> metadata = Map.of(
                "heartbeat_path", sample.heartbeatPath(),
                "group_mode", sample.groupMode(),
                "epoch", sample.epoch(),
                "seq", sample.seq());
        addRollup("heartbeat.arrival_gap_ms", labels, sample.arrivalGapMs(), metadata, sample.observedAtMs());
        addRollup("heartbeat.seq_gap", labels, sample.seqGap(), metadata, sample.observedAtMs());
        addRollup("heartbeat.agent_collect_ms", labels, sample.agentCollectMs(), metadata, sample.observedAtMs());
        addRollup("heartbeat.agent_encode_ms", labels, sample.agentEncodeMs(), metadata, sample.observedAtMs());
        addRollup("heartbeat.agent_send_ms", labels, sample.agentSendMs(), metadata, sample.observedAtMs());
        addRollup("agent.thread_count", labels, sample.agentThreadCount(), metadata, sample.observedAtMs());
        addRollup("agent.rss_kb", labels, sample.agentRssKb(), metadata, sample.observedAtMs());

        Object disksValue = sample.state().get("disks");
        if (disksValue instanceof List<?> disks) {
            for (Object diskValue : disks) {
                if (!(diskValue instanceof Map<?, ?> disk)) {
                    continue;
                }
                String device = stringValue(disk.get("device"));
                Map<String, String> diskLabels =
                        Map.of("agent_id", sample.agentId(), "device", device, "cluster", sample.cluster());
                addRollup(
                        "disk.io_util_pct",
                        diskLabels,
                        doubleValue(disk.get("io_util_pct")),
                        Map.of(),
                        sample.observedAtMs());
                addRollup(
                        "disk.saturated_for_ms",
                        diskLabels,
                        longValue(disk.get("saturated_for_ms")),
                        Map.of(),
                        sample.observedAtMs());
            }
        }

        Object workersValue = sample.state().get("tide_workers");
        if (!(workersValue instanceof List<?> workers)) {
            return;
        }
        for (Object workerValue : workers) {
            if (!(workerValue instanceof Map<?, ?> worker)) {
                continue;
            }
            String pid = String.valueOf(longValue(worker.get("pid")));
            Map<String, String> workerLabels = Map.of(
                    "agent_id", sample.agentId(),
                    "pid", pid,
                    "version", stringValue(worker.get("component_version")),
                    "role", stringValue(worker.get("role")),
                    "cluster", sample.cluster());
            addRollup(
                    "tide_worker.cpu_pct",
                    workerLabels,
                    doubleValue(worker.get("cpu_percent")),
                    Map.of(),
                    sample.observedAtMs());
            addRollup(
                    "tide_worker.rss_kb",
                    workerLabels,
                    longValue(worker.get("rss_kb")),
                    Map.of(),
                    sample.observedAtMs());
            addRollup(
                    "tide_worker.thread_count",
                    workerLabels,
                    longValue(worker.get("threads")),
                    Map.of(),
                    sample.observedAtMs());
        }
    }

    private void accumulate(GroupLeaderMetricSample sample) {
        Map<String, String> labels = Map.of(
                "group_id", sample.groupId(),
                "leader_agent_id", sample.leaderAgentId(),
                "cluster", sample.cluster(),
                "area", sample.area(),
                "status", sample.status());
        long planMismatch = longValue(sample.debug().get("plan_mismatch"));
        long planLag = sample.debug().containsKey("plan_lag")
                ? longValue(sample.debug().get("plan_lag"))
                : planMismatch;
        addRollup("group.member_count", labels, sample.memberCount(), Map.of(), sample.observedAtMs());
        addRollup("group.submitted_agent_count", labels, sample.submittedAgentCount(), Map.of(), sample.observedAtMs());
        addRollup("group.accepted_agent_count", labels, sample.acceptedAgentCount(), Map.of(), sample.observedAtMs());
        addRollup("group.missing_member_count", labels, sample.missingMemberCount(), Map.of(), sample.observedAtMs());
        addRollup("group.stale_member_count", labels, sample.staleMemberCount(), Map.of(), sample.observedAtMs());
        addRollup(
                "group.direct_fallback_count", labels, sample.directFallbackCount(), Map.of(), sample.observedAtMs());
        addRollup(
                "group.status_unhealthy",
                labels,
                "ok".equals(sample.status()) ? 0 : 1,
                Map.of(),
                sample.observedAtMs());
        addRollup("group.plan_generation", labels, sample.groupGeneration(), Map.of(), sample.observedAtMs());
        addRollup("group.plan_mismatch", labels, planMismatch, Map.of(), sample.observedAtMs());
        addRollup("group.plan_lag", labels, planLag, Map.of(), sample.observedAtMs());
        addRollup("group.leader_collect_ms", labels, sample.leaderCollectMs(), Map.of(), sample.observedAtMs());
        addRollup("group.group_latency_ms", labels, sample.groupLatencyMs(), Map.of(), sample.observedAtMs());
        addRollup("group.arrival_gap_ms", labels, sample.arrivalGapMs(), Map.of(), sample.observedAtMs());
        addRollup("group.response_bytes", labels, sample.responseBytes(), Map.of(), sample.observedAtMs());
        addRollup("group.file_payload_bytes", labels, sample.filePayloadBytes(), Map.of(), sample.observedAtMs());
        addRollup(
                "group.file_payload_base64_bytes",
                labels,
                sample.filePayloadBase64Bytes(),
                Map.of(),
                sample.observedAtMs());
        addRollup(
                "group.file_command_copy_count",
                labels,
                sample.fileCommandCopyCount(),
                Map.of(),
                sample.observedAtMs());
        addRollup(
                "group.file_unique_content_count",
                labels,
                sample.fileUniqueContentCount(),
                Map.of(),
                sample.observedAtMs());
        addRollup(
                "group.file_shared_lower_bound_bytes",
                labels,
                sample.fileSharedLowerBoundBytes(),
                Map.of(),
                sample.observedAtMs());
    }

    private void addRollup(
            String metric, Map<String, String> labels, double value, Map<String, Object> metadata, long observedAtMs) {
        long bucketMs = minuteBucket(observedAtMs);
        String seriesKey = labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\u0000" + right)
                .orElse("");
        RollupKey key = new RollupKey(bucketMs, metric, seriesKey);
        pendingRollups
                .computeIfAbsent(key, ignored -> new RollupAccumulator(labels))
                .add(value, metadata);
    }

    private void flushRollupsBefore(long cutoffBucketMs) throws Exception {
        if (pendingRollups.isEmpty()) {
            return;
        }
        List<RollupRecord> records = new ArrayList<>();
        var iterator = pendingRollups.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RollupKey, RollupAccumulator> entry = iterator.next();
            if (entry.getKey().bucketMs() >= cutoffBucketMs) {
                continue;
            }
            records.add(entry.getValue().toRecord(entry.getKey()));
            iterator.remove();
        }
        if (!records.isEmpty()) {
            rollupStorage.write(records);
        }
    }

    private void flushAllRollups() throws Exception {
        if (pendingRollups.isEmpty()) {
            return;
        }
        List<RollupRecord> records = pendingRollups.entrySet().stream()
                .map(entry -> entry.getValue().toRecord(entry.getKey()))
                .toList();
        rollupStorage.write(records);
        pendingRollups.clear();
    }

    private static long minuteBucket(long observedAtMs) {
        return observedAtMs - Math.floorMod(observedAtMs, 60_000L);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record RollupKey(long bucketMs, String metric, String seriesKey) {}

    private static final class RollupAccumulator {
        private final Map<String, String> labels;
        private double sum;
        private long count;
        private Map<String, Object> metadata = Map.of();

        private RollupAccumulator(Map<String, String> labels) {
            this.labels = Map.copyOf(labels);
        }

        private void add(double value, Map<String, Object> nextMetadata) {
            sum += value;
            count++;
            metadata = nextMetadata == null ? Map.of() : Map.copyOf(nextMetadata);
        }

        private RollupRecord toRecord(RollupKey key) {
            return new RollupRecord(
                    key.bucketMs(), key.metric(), key.seriesKey(), labels, sum, count, metadata);
        }
    }
}
