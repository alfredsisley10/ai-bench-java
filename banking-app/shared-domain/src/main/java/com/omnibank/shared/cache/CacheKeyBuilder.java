package com.omnibank.shared.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Type-safe, fluent builder for cache keys. Produces deterministic,
 * human-readable keys with a consistent structure:
 *
 * <pre>
 *   {prefix}:v{version}:{region}:{segments...}
 * </pre>
 *
 * For example:
 * <pre>
 *   omnibank:v2:gl-accounts:USD:1001234
 *   omnibank:v1:customer-profiles:cust-9876:preferences
 * </pre>
 *
 * <h3>Design rationale</h3>
 * <ul>
 *   <li><b>Namespace prefix</b> prevents key collisions when multiple
 *       applications share the same Redis cluster.</li>
 *   <li><b>Version tag</b> allows zero-downtime cache invalidation after
 *       schema changes — just bump the version and old keys are orphaned
 *       (and will expire naturally).</li>
 *   <li><b>Region segment</b> enables prefix-based bulk invalidation
 *       (e.g. {@code SCAN omnibank:v2:exchange-rates:*}).</li>
 *   <li><b>Composite segments</b> support arbitrary depth for compound
 *       natural keys (account + currency, customer + product, etc.).</li>
 * </ul>
 *
 * <p>Instances are immutable after {@link #build()}. The builder itself
 * is <em>not</em> thread-safe but the resulting key strings are plain
 * {@link String} and therefore inherently safe.
 */
public final class CacheKeyBuilder {

    private static final String SEPARATOR = ":";
    private static final int MAX_KEY_LENGTH = 512; // Redis recommendation

    private final String prefix;
    private final int version;
    private String region;
    private final List<String> segments = new ArrayList<>(4);
    private boolean hashLongKeys = true;

    // -------------------------------------------------------------------
    //  Construction
    // -------------------------------------------------------------------

    private CacheKeyBuilder(String prefix, int version) {
        this.prefix = prefix;
        this.version = version;
    }

    /**
     * Creates a builder using the given config's prefix and schema version.
     */
    public static CacheKeyBuilder from(CacheConfig config) {
        Objects.requireNonNull(config, "config");
        return new CacheKeyBuilder(config.keyPrefix(), config.schemaVersion());
    }

    /**
     * Creates a builder with explicit prefix and version.
     */
    public static CacheKeyBuilder of(String prefix, int version) {
        Objects.requireNonNull(prefix, "prefix");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        return new CacheKeyBuilder(prefix, version);
    }

    // -------------------------------------------------------------------
    //  Fluent setters
    // -------------------------------------------------------------------

    /**
     * Sets the cache region using the enum constant.
     */
    public CacheKeyBuilder region(CacheConfig.Region region) {
        this.region = regionToString(region);
        return this;
    }

    /**
     * Sets the cache region using a free-form string.
     */
    public CacheKeyBuilder region(String region) {
        this.region = Objects.requireNonNull(region, "region");
        return this;
    }

    /**
     * Appends a key segment. Segments are joined with the separator.
     */
    public CacheKeyBuilder segment(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Segment must not contain the separator '" + SEPARATOR + "': " + segment);
        }
        segments.add(segment);
        return this;
    }

    /**
     * Appends a key segment from a typed identifier (uses
     * {@link Object#toString()}).
     */
    public CacheKeyBuilder segment(Object segment) {
        return segment(Objects.requireNonNull(segment, "segment").toString());
    }

    /**
     * Appends multiple segments at once (composite natural key).
     */
    public CacheKeyBuilder segments(String... parts) {
        for (String part : parts) {
            segment(part);
        }
        return this;
    }

    /**
     * Disables automatic SHA-256 hashing of keys that exceed
     * {@value #MAX_KEY_LENGTH} characters.
     */
    public CacheKeyBuilder disableHashLongKeys() {
        this.hashLongKeys = false;
        return this;
    }

    // -------------------------------------------------------------------
    //  Build
    // -------------------------------------------------------------------

    /**
     * Builds the final key string. If the raw key exceeds
     * {@value #MAX_KEY_LENGTH} characters and {@link #hashLongKeys} is enabled,
     * the segment portion is replaced with a SHA-256 hash to keep the key
     * within Redis's recommended limits while preserving the prefix/version/
     * region for scanability.
     *
     * @throws IllegalStateException if region or at least one segment has not
     *                               been set
     */
    public String build() {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("Region must be set before building a cache key");
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("At least one key segment is required");
        }

        String rawSegments = String.join(SEPARATOR, segments);
        String rawKey = prefix + SEPARATOR
                + "v" + version + SEPARATOR
                + region + SEPARATOR
                + rawSegments;

        if (hashLongKeys && rawKey.length() > MAX_KEY_LENGTH) {
            String hash = sha256Hex(rawSegments);
            return prefix + SEPARATOR
                    + "v" + version + SEPARATOR
                    + region + SEPARATOR
                    + "sha256-" + hash;
        }

        return rawKey;
    }

    // -------------------------------------------------------------------
    //  Pre-built convenience methods for common OmniBank key patterns
    // -------------------------------------------------------------------

    /**
     * Convenience: builds a GL account cache key.
     *
     * @param accountId the account identifier
     * @return fully-qualified cache key
     */
    public static String glAccount(CacheConfig config, String accountId) {
        return from(config)
                .region(CacheConfig.Region.GL_ACCOUNTS)
                .segment(accountId)
                .build();
    }

    /**
     * Convenience: builds an exchange-rate cache key for a currency pair.
     *
     * @param baseCurrency  e.g. "USD"
     * @param quoteCurrency e.g. "EUR"
     * @return fully-qualified cache key
     */
    public static String exchangeRate(CacheConfig config,
                                      String baseCurrency,
                                      String quoteCurrency) {
        return from(config)
                .region(CacheConfig.Region.EXCHANGE_RATES)
                .segments(baseCurrency, quoteCurrency)
                .build();
    }

    /**
     * Convenience: builds a customer profile cache key.
     *
     * @param customerId the customer identifier
     * @return fully-qualified cache key
     */
    public static String customerProfile(CacheConfig config, String customerId) {
        return from(config)
                .region(CacheConfig.Region.CUSTOMER_PROFILES)
                .segment(customerId)
                .build();
    }

    /**
     * Convenience: builds a payment routing-rule cache key.
     *
     * @param corridor e.g. "US-GB" (origin-destination corridor)
     * @param channel  e.g. "SWIFT", "ACH"
     * @return fully-qualified cache key
     */
    public static String paymentRoutingRule(CacheConfig config,
                                            String corridor,
                                            String channel) {
        return from(config)
                .region(CacheConfig.Region.PAYMENT_ROUTING_RULES)
                .segments(corridor, channel)
                .build();
    }

    /**
     * Convenience: builds a compliance-list cache key.
     *
     * @param listType e.g. "OFAC", "EU-SANCTIONS", "PEP"
     * @param entryId  entry identifier within the list
     * @return fully-qualified cache key
     */
    public static String complianceList(CacheConfig config,
                                        String listType,
                                        String entryId) {
        return from(config)
                .region(CacheConfig.Region.COMPLIANCE_LISTS)
                .segments(listType, entryId)
                .build();
    }

    // -------------------------------------------------------------------
    //  Prefix helpers (for bulk invalidation)
    // -------------------------------------------------------------------

    /**
     * Returns the key prefix for an entire region. Useful with
     * {@link DistributedCacheClient#invalidateByPrefix(String)} for region-wide
     * cache busts.
     */
    public static String regionPrefix(CacheConfig config, CacheConfig.Region region) {
        return config.keyPrefix() + SEPARATOR
                + "v" + config.schemaVersion() + SEPARATOR
                + regionToString(region);
    }

    // -------------------------------------------------------------------
    //  Internals
    // -------------------------------------------------------------------

    private static String regionToString(CacheConfig.Region region) {
        return switch (region) {
            case GL_ACCOUNTS -> "gl-accounts";
            case EXCHANGE_RATES -> "exchange-rates";
            case CUSTOMER_PROFILES -> "customer-profiles";
            case PAYMENT_ROUTING_RULES -> "payment-routing-rules";
            case COMPLIANCE_LISTS -> "compliance-lists";
        };
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec — this cannot happen.
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
