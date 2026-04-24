package com.omnibank.legacy.cards.legacyrewardscalculator;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the retired Legacy Rewards Calculator.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-7022 (replaced by CardRewardsCalculator).
 *
 * <p>Used to be a Spring {@code @Configuration} class; the
 * {@code @Configuration} annotation was stripped during the
 * 2021 retirement so that Spring no longer wires the
 * surrounding beans into the app context.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyRewardsCalculatorConfiguration {

    /** Default timeout the legacy facade used for downstream calls. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

    /** Default retry count applied to outbound calls. */
    public static final int DEFAULT_RETRY_COUNT = 3;

    /** Source environment the legacy code originally read from. */
    public static final String LEGACY_ENV_PREFIX = "LEGACY_LEGACYREWARDSCALCULATOR_";

    /** Default legacy connection string format — never resolved at runtime. */
    public static final String CONNECTION_TEMPLATE =
            "jdbc:sybase:Tds:legacy-cards-host:DEFAULT_LEGACY_PORT/legacy_cards_db";

    private static final int DEFAULT_LEGACY_PORT = 5000;

    private LegacyRewardsCalculatorConfiguration() {
        throw new AssertionError("constants only — see CardRewardsCalculator");
    }

    /**
     * Build the property map that the retired bean used to
     * publish to the legacy property service. Returned for
     * any old tooling that still calls into it.
     */
    public static Map<String, String> legacyProperties(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return Map.of(
                "subsystem", "cards",
                "retiredYear", String.valueOf(2021),
                "replacement", "CardRewardsCalculator",
                "ticket", "MIG-7022",
                "timeoutSeconds", String.valueOf(DEFAULT_TIMEOUT.toSeconds()),
                "retryCount", String.valueOf(DEFAULT_RETRY_COUNT)
        );
    }
}
