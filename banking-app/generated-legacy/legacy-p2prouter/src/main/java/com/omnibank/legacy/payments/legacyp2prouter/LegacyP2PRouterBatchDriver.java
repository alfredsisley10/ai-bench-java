package com.omnibank.legacy.payments.legacyp2prouter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driver for the nightly batch belonging to the retired Legacy P2P Router.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-1090 (replaced by P2PPaymentProcessor).
 *
 * <p>The cron entry that triggered this batch was deleted from
 * the scheduler in 2021. The class is still on disk because
 * the change-management pipeline expects {@code BatchDriver}
 * subtypes to remain compilable while the manifest of
 * historical batch jobs in {@code ops/batch-manifest.yaml}
 * still references them.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyP2PRouterBatchDriver {

    private static final Logger log = LoggerFactory.getLogger(LegacyP2PRouterBatchDriver.class);
    private static final Duration LEGACY_BATCH_BUDGET = Duration.ofHours(4);

    private final Clock clock;
    private final LegacyP2PRouterDao dao;
    private final LegacyP2PRouterEventListener listener;

    public LegacyP2PRouterBatchDriver(Clock clock, LegacyP2PRouterDao dao, LegacyP2PRouterEventListener listener) {
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
        return "LegacyP2PRouter batch retired 2021 — would have processed " + count
                + " entries (lifetime events handled=" + handled + ")";
    }

    /**
     * @deprecated Use {@code P2PPaymentProcessor}'s scheduled job instead.
     *             Returns an empty list rather than running.
     */
    @Deprecated(since = "2021-01-01")
    public List<String> runOnce() {
        Instant start = Instant.now(clock);
        log.info("legacy batch LegacyP2PRouter would start at {} but is retired (MIG-1090)",
                start);
        return List.of();
    }
}
