package com.omnibank.audit.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of audit documents: archival of aging records to cold
 * storage and purging of records that have exceeded their regulatory retention
 * period.
 *
 * <p>Retention periods are configurable per {@link AuditDocument.Category}:
 * <ul>
 *   <li><b>REGULATORY</b> — 7 years (mandated by banking regulations like
 *       SOX, GDPR financial record-keeping)</li>
 *   <li><b>SECURITY</b> — 3 years</li>
 *   <li><b>FINANCIAL</b> — 7 years</li>
 *   <li><b>OPERATIONAL</b> — 90 days</li>
 *   <li><b>CONFIGURATION</b> — 1 year</li>
 * </ul>
 *
 * <p>The engine distinguishes between <em>archival</em> (moving records from
 * the hot store to compressed cold storage) and <em>purging</em> (permanent
 * deletion after the retention period expires). Archival typically runs at a
 * shorter threshold so the hot store stays lean while data remains recoverable.
 *
 * <p>Thread-safe. Can be started as a periodic background task via
 * {@link #startScheduled}.
 */
public final class AuditRetentionPolicy {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionPolicy.class);

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * Per-category retention configuration.
     *
     * @param retentionPeriod   how long documents are kept before purging
     * @param archiveAfter      how long before documents are archived to cold storage
     * @param archiveDestination  URI or path for the cold-storage target
     * @param compressArchive    whether to compress archived batches
     */
    public record CategoryRetention(
            Duration retentionPeriod,
            Duration archiveAfter,
            String archiveDestination,
            boolean compressArchive
    ) {
        public CategoryRetention {
            Objects.requireNonNull(retentionPeriod, "retentionPeriod");
            Objects.requireNonNull(archiveAfter, "archiveAfter");
            Objects.requireNonNull(archiveDestination, "archiveDestination");
            if (archiveAfter.compareTo(retentionPeriod) > 0) {
                throw new IllegalArgumentException(
                        "archiveAfter must not exceed retentionPeriod");
            }
        }
    }

    /**
     * Overall policy configuration.
     *
     * @param categoryRetentions per-category retention rules
     * @param defaultRetention   fallback for categories without explicit config
     * @param batchSize          max documents processed per sweep cycle
     * @param dryRun             if true, log actions without executing them
     */
    public record PolicyConfig(
            Map<AuditDocument.Category, CategoryRetention> categoryRetentions,
            CategoryRetention defaultRetention,
            int batchSize,
            boolean dryRun
    ) {
        public PolicyConfig {
            Objects.requireNonNull(categoryRetentions, "categoryRetentions");
            Objects.requireNonNull(defaultRetention, "defaultRetention");
            categoryRetentions = Collections.unmodifiableMap(
                    new EnumMap<>(categoryRetentions));
            if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        }

        public CategoryRetention forCategory(AuditDocument.Category category) {
            return categoryRetentions.getOrDefault(category, defaultRetention);
        }
    }

    /**
     * Result of a single retention sweep.
     *
     * @param archived total documents moved to cold storage
     * @param purged   total documents permanently deleted
     * @param errors   number of collections/categories that encountered errors
     * @param elapsed  total time for the sweep
     */
    public record SweepResult(long archived, long purged, int errors, Duration elapsed) {
        @Override
        public String toString() {
            return String.format("SweepResult[archived=%d, purged=%d, errors=%d, elapsed=%s]",
                    archived, purged, errors, elapsed);
        }
    }

    // -------------------------------------------------------------------
    //  Production defaults
    // -------------------------------------------------------------------

    /**
     * Builds the production-recommended retention policy aligned with
     * banking regulatory requirements.
     */
    public static PolicyConfig productionDefaults() {
        Map<AuditDocument.Category, CategoryRetention> retentions = new EnumMap<>(AuditDocument.Category.class);

        retentions.put(AuditDocument.Category.REGULATORY, new CategoryRetention(
                Duration.ofDays(7 * 365), // 7 years
                Duration.ofDays(90),      // archive after 90 days
                "s3://omnibank-audit-archive/regulatory/",
                true));

        retentions.put(AuditDocument.Category.SECURITY, new CategoryRetention(
                Duration.ofDays(3 * 365), // 3 years
                Duration.ofDays(60),
                "s3://omnibank-audit-archive/security/",
                true));

        retentions.put(AuditDocument.Category.FINANCIAL, new CategoryRetention(
                Duration.ofDays(7 * 365), // 7 years
                Duration.ofDays(90),
                "s3://omnibank-audit-archive/financial/",
                true));

        retentions.put(AuditDocument.Category.OPERATIONAL, new CategoryRetention(
                Duration.ofDays(90),  // 90 days
                Duration.ofDays(30),
                "s3://omnibank-audit-archive/operational/",
                false));

        retentions.put(AuditDocument.Category.CONFIGURATION, new CategoryRetention(
                Duration.ofDays(365), // 1 year
                Duration.ofDays(90),
                "s3://omnibank-audit-archive/configuration/",
                true));

        CategoryRetention defaultRet = new CategoryRetention(
                Duration.ofDays(365),
                Duration.ofDays(90),
                "s3://omnibank-audit-archive/default/",
                true);

        return new PolicyConfig(retentions, defaultRet, 10_000, false);
    }

    // -------------------------------------------------------------------
    //  Instance state
    // -------------------------------------------------------------------

    private final AuditEventStore store;
    private final PolicyConfig config;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    public AuditRetentionPolicy(AuditEventStore store, PolicyConfig config) {
        this(store, config, Clock.systemUTC());
    }

    public AuditRetentionPolicy(AuditEventStore store, PolicyConfig config, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // -------------------------------------------------------------------
    //  Sweep execution
    // -------------------------------------------------------------------

    /**
     * Runs a full retention sweep across all collections and categories.
     * This is the main entry point, callable on-demand or from a scheduler.
     */
    public SweepResult sweep() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Retention sweep already in progress, skipping");
            return new SweepResult(0, 0, 0, Duration.ZERO);
        }

        Instant start = clock.instant();
        long totalArchived = 0;
        long totalPurged = 0;
        int errors = 0;

        try {
            for (AuditEventStore.CollectionName collection : AuditEventStore.CollectionName.values()) {
                for (AuditDocument.Category category : AuditDocument.Category.values()) {
                    try {
                        CategoryRetention retention = config.forCategory(category);
                        long archived = sweepArchive(collection, retention);
                        long purged = sweepPurge(collection, retention);
                        totalArchived += archived;
                        totalPurged += purged;
                    } catch (Exception e) {
                        errors++;
                        log.error("Retention sweep failed for collection={} category={}",
                                collection, category, e);
                    }
                }
            }
        } finally {
            running.set(false);
        }

        Duration elapsed = Duration.between(start, clock.instant());
        SweepResult result = new SweepResult(totalArchived, totalPurged, errors, elapsed);
        log.info("Retention sweep completed: {}", result);
        return result;
    }

    /**
     * Archives documents that have passed the archive threshold but are still
     * within the retention period.
     */
    private long sweepArchive(AuditEventStore.CollectionName collection,
                              CategoryRetention retention) {
        Instant archiveCutoff = clock.instant().minus(retention.archiveAfter());
        Instant retentionCutoff = clock.instant().minus(retention.retentionPeriod());

        // Archive window: [retentionCutoff, archiveCutoff)
        if (!retentionCutoff.isBefore(archiveCutoff)) {
            return 0; // no valid archive window
        }

        if (config.dryRun()) {
            log.info("[DRY RUN] Would archive {} documents from {} before {}",
                    collection, retentionCutoff, archiveCutoff);
            return 0;
        }

        long archived = store.archiveRange(
                collection, retentionCutoff, archiveCutoff,
                retention.archiveDestination());
        if (archived > 0) {
            log.info("Archived {} documents from {} to {}",
                    archived, collection, retention.archiveDestination());
        }
        return archived;
    }

    /**
     * Permanently deletes documents that have exceeded the retention period.
     */
    private long sweepPurge(AuditEventStore.CollectionName collection,
                            CategoryRetention retention) {
        Instant purgeCutoff = clock.instant().minus(retention.retentionPeriod());

        if (config.dryRun()) {
            log.info("[DRY RUN] Would purge {} documents from {} before {}",
                    collection, collection, purgeCutoff);
            return 0;
        }

        long purged = store.deleteOlderThan(collection, purgeCutoff);
        if (purged > 0) {
            log.info("Purged {} documents from {} older than {}",
                    purged, collection, purgeCutoff);
        }
        return purged;
    }

    // -------------------------------------------------------------------
    //  Scheduled execution
    // -------------------------------------------------------------------

    /**
     * Starts a background scheduler that runs the retention sweep at the
     * given interval.
     *
     * @param interval how often to run (e.g. every 6 hours)
     */
    public void startScheduled(Duration interval) {
        if (scheduler != null) {
            throw new IllegalStateException("Scheduler already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-retention-sweep");
            t.setDaemon(true);
            return t;
        });
        long millis = interval.toMillis();
        scheduler.scheduleAtFixedRate(this::sweep, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Audit retention scheduler started with interval {}", interval);
    }

    /**
     * Shuts down the background scheduler, waiting up to 30 seconds for
     * an in-progress sweep to complete.
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    log.warn("Retention scheduler did not terminate within 30 seconds");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Audit retention scheduler shut down");
        }
    }

    /** Returns the current policy configuration. */
    public PolicyConfig config() {
        return config;
    }
}
