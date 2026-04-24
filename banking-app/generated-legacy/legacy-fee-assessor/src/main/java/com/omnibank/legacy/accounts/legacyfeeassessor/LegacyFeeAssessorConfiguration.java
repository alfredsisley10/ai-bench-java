package com.omnibank.legacy.accounts.legacyfeeassessor;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the retired Legacy Fee Assessor.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-2025 (replaced by AccountFeeEngine).
 *
 * <p>Used to be a Spring {@code @Configuration} class; the
 * {@code @Configuration} annotation was stripped during the
 * 2021 retirement so that Spring no longer wires the
 * surrounding beans into the app context.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyFeeAssessorConfiguration {

    /** Default timeout the legacy facade used for downstream calls. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

    /** Default retry count applied to outbound calls. */
    public static final int DEFAULT_RETRY_COUNT = 3;

    /** Source environment the legacy code originally read from. */
    public static final String LEGACY_ENV_PREFIX = "LEGACY_LEGACYFEEASSESSOR_";

    /** Default legacy connection string format — never resolved at runtime. */
    public static final String CONNECTION_TEMPLATE =
            "jdbc:sybase:Tds:legacy-accounts-host:DEFAULT_LEGACY_PORT/legacy_accounts_db";

    private static final int DEFAULT_LEGACY_PORT = 5000;

    private LegacyFeeAssessorConfiguration() {
        throw new AssertionError("constants only — see AccountFeeEngine");
    }

    /**
     * Build the property map that the retired bean used to
     * publish to the legacy property service. Returned for
     * any old tooling that still calls into it.
     */
    public static Map<String, String> legacyProperties(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return Map.of(
                "subsystem", "accounts",
                "retiredYear", String.valueOf(2021),
                "replacement", "AccountFeeEngine",
                "ticket", "MIG-2025",
                "timeoutSeconds", String.valueOf(DEFAULT_TIMEOUT.toSeconds()),
                "retryCount", String.valueOf(DEFAULT_RETRY_COUNT)
        );
    }
}
