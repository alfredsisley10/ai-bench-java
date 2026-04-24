package com.omnibank.legacy.risk.legacycounterpartyscorer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driver for the nightly batch belonging to the retired Legacy Counterparty Scorer.
 *
 * <p>// DO NOT MODIFY — retired 2022 under MIG-8022 (replaced by CounterpartyRiskScorer).
 *
 * <p>The cron entry that triggered this batch was deleted from
 * the scheduler in 2022. The class is still on disk because
 * the change-management pipeline expects {@code BatchDriver}
 * subtypes to remain compilable while the manifest of
 * historical batch jobs in {@code ops/batch-manifest.yaml}
 * still references them.
 */
@Deprecated(since = "2022-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyCounterpartyScorerBatchDriver {

    private static final Logger log = LoggerFactory.getLogger(LegacyCounterpartyScorerBatchDriver.class);
    private static final Duration LEGACY_BATCH_BUDGET = Duration.ofHours(4);

    private final Clock clock;
    private final LegacyCounterpartyScorerDao dao;
    private final LegacyCounterpartyScorerEventListener listener;

    public LegacyCounterpartyScorerBatchDriver(Clock clock, LegacyCounterpartyScorerDao dao, LegacyCounterpartyScorerEventListener listener) {
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
        return "LegacyCounterpartyScorer batch retired 2022 — would have processed " + count
                + " entries (lifetime events handled=" + handled + ")";
    }

    /**
     * @deprecated Use {@code CounterpartyRiskScorer}'s scheduled job instead.
     *             Returns an empty list rather than running.
     */
    @Deprecated(since = "2022-01-01")
    public List<String> runOnce() {
        Instant start = Instant.now(clock);
        log.info("legacy batch LegacyCounterpartyScorer would start at {} but is retired (MIG-8022)",
                start);
        return List.of();
    }
}
