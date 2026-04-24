package com.omnibank.legacy.regreporting.legacycallreportbuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driver for the nightly batch belonging to the retired Legacy Call Report Builder.
 *
 * <p>// DO NOT MODIFY — retired 2019 under MIG-5001 (replaced by FfiecCallReportGenerator).
 *
 * <p>The cron entry that triggered this batch was deleted from
 * the scheduler in 2019. The class is still on disk because
 * the change-management pipeline expects {@code BatchDriver}
 * subtypes to remain compilable while the manifest of
 * historical batch jobs in {@code ops/batch-manifest.yaml}
 * still references them.
 */
@Deprecated(since = "2019-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyCallReportBuilderBatchDriver {

    private static final Logger log = LoggerFactory.getLogger(LegacyCallReportBuilderBatchDriver.class);
    private static final Duration LEGACY_BATCH_BUDGET = Duration.ofHours(4);

    private final Clock clock;
    private final LegacyCallReportBuilderDao dao;
    private final LegacyCallReportBuilderEventListener listener;

    public LegacyCallReportBuilderBatchDriver(Clock clock, LegacyCallReportBuilderDao dao, LegacyCallReportBuilderEventListener listener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dao = Objects.requireNonNull(dao, "dao");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Returns a one-line summary of what the batch *would* have
     * processed if it were still scheduled. Pure introspection.
     */
    public String summarize() {
        long count = dao.count();
        long handled = listener.handledCount();
        return "LegacyCallReportBuilder batch retired 2019 — would have processed " + count
                + " entries (lifetime events handled=" + handled + ")";
    }

    /**
     * @deprecated Use {@code FfiecCallReportGenerator}'s scheduled job instead.
     *             Returns an empty list rather than running.
     */
    @Deprecated(since = "2019-01-01")
    public List<String> runOnce() {
        Instant start = Instant.now(clock);
        log.info("legacy batch LegacyCallReportBuilder would start at {} but is retired (MIG-5001)",
                start);
        return List.of();
    }
}
