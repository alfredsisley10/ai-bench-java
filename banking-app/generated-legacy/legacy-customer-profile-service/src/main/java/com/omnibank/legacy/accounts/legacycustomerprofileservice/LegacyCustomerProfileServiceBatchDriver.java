package com.omnibank.legacy.accounts.legacycustomerprofileservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Driver for the nightly batch belonging to the retired Legacy Customer Profile Service.
 *
 * <p>// DO NOT MODIFY — retired 2018 under MIG-2001 (replaced by ConsumerAccountService).
 *
 * <p>The cron entry that triggered this batch was deleted from
 * the scheduler in 2018. The class is still on disk because
 * the change-management pipeline expects {@code BatchDriver}
 * subtypes to remain compilable while the manifest of
 * historical batch jobs in {@code ops/batch-manifest.yaml}
 * still references them.
 */
@Deprecated(since = "2018-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyCustomerProfileServiceBatchDriver {

    private static final Logger log = LoggerFactory.getLogger(LegacyCustomerProfileServiceBatchDriver.class);
    private static final Duration LEGACY_BATCH_BUDGET = Duration.ofHours(4);

    private final Clock clock;
    private final LegacyCustomerProfileServiceDao dao;
    private final LegacyCustomerProfileServiceEventListener listener;

    public LegacyCustomerProfileServiceBatchDriver(Clock clock, LegacyCustomerProfileServiceDao dao, LegacyCustomerProfileServiceEventListener listener) {
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
        return "LegacyCustomerProfileService batch retired 2018 — would have processed " + count
                + " entries (lifetime events handled=" + handled + ")";
    }

    /**
     * @deprecated Use {@code ConsumerAccountService}'s scheduled job instead.
     *             Returns an empty list rather than running.
     */
    @Deprecated(since = "2018-01-01")
    public List<String> runOnce() {
        Instant start = Instant.now(clock);
        log.info("legacy batch LegacyCustomerProfileService would start at {} but is retired (MIG-2001)",
                start);
        return List.of();
    }
}
