package com.omnibank.legacy.notifications.legacyemailsender;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the retired Legacy Email Sender.
 *
 * <p>// DO NOT MODIFY — retired 2019 under MIG-9008 (replaced by EmailDeliveryService).
 *
 * <p>Used to be a Spring {@code @Configuration} class; the
 * {@code @Configuration} annotation was stripped during the
 * 2019 retirement so that Spring no longer wires the
 * surrounding beans into the app context.
 */
@Deprecated(since = "2019-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyEmailSenderConfiguration {

    /** Default timeout the legacy facade used for downstream calls. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

    /** Default retry count applied to outbound calls. */
    public static final int DEFAULT_RETRY_COUNT = 3;

    /** Source environment the legacy code originally read from. */
    public static final String LEGACY_ENV_PREFIX = "LEGACY_LEGACYEMAILSENDER_";

    /** Default legacy connection string format — never resolved at runtime. */
    public static final String CONNECTION_TEMPLATE =
            "jdbc:sybase:Tds:legacy-notifications-host:DEFAULT_LEGACY_PORT/legacy_notifications_db";

    private static final int DEFAULT_LEGACY_PORT = 5000;

    private LegacyEmailSenderConfiguration() {
        throw new AssertionError("constants only — see EmailDeliveryService");
    }

    /**
     * Build the property map that the retired bean used to
     * publish to the legacy property service. Returned for
     * any old tooling that still calls into it.
     */
    public static Map<String, String> legacyProperties(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return Map.of(
                "subsystem", "notifications",
                "retiredYear", String.valueOf(2019),
                "replacement", "EmailDeliveryService",
                "ticket", "MIG-9008",
                "timeoutSeconds", String.valueOf(DEFAULT_TIMEOUT.toSeconds()),
                "retryCount", String.valueOf(DEFAULT_RETRY_COUNT)
        );
    }
}
