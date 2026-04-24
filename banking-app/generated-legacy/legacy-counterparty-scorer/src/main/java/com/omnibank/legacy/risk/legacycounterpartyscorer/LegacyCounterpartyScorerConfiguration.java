package com.omnibank.legacy.risk.legacycounterpartyscorer;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the retired Legacy Counterparty Scorer.
 *
 * <p>// DO NOT MODIFY — retired 2022 under MIG-8022 (replaced by CounterpartyRiskScorer).
 *
 * <p>Used to be a Spring {@code @Configuration} class; the
 * {@code @Configuration} annotation was stripped during the
 * 2022 retirement so that Spring no longer wires the
 * surrounding beans into the app context.
 */
@Deprecated(since = "2022-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyCounterpartyScorerConfiguration {

    /** Default timeout the legacy facade used for downstream calls. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

    /** Default retry count applied to outbound calls. */
    public static final int DEFAULT_RETRY_COUNT = 3;

    /** Source environment the legacy code originally read from. */
    public static final String LEGACY_ENV_PREFIX = "LEGACY_LEGACYCOUNTERPARTYSCORER_";

    /** Default legacy connection string format — never resolved at runtime. */
    public static final String CONNECTION_TEMPLATE =
            "jdbc:sybase:Tds:legacy-risk-host:DEFAULT_LEGACY_PORT/legacy_risk_db";

    private static final int DEFAULT_LEGACY_PORT = 5000;

    private LegacyCounterpartyScorerConfiguration() {
        throw new AssertionError("constants only — see CounterpartyRiskScorer");
    }

    /**
     * Build the property map that the retired bean used to
     * publish to the legacy property service. Returned for
     * any old tooling that still calls into it.
     */
    public static Map<String, String> legacyProperties(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return Map.of(
                "subsystem", "risk",
                "retiredYear", String.valueOf(2022),
                "replacement", "CounterpartyRiskScorer",
                "ticket", "MIG-8022",
                "timeoutSeconds", String.valueOf(DEFAULT_TIMEOUT.toSeconds()),
                "retryCount", String.valueOf(DEFAULT_RETRY_COUNT)
        );
    }
}
