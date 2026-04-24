package com.omnibank.shared.cache;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-tier caching configuration for the OmniBank platform.
 *
 * <p>L1 is a process-local cache (Caffeine-style) used to eliminate redundant
 * network round-trips for hot data. L2 is a distributed cache (Redis-compatible
 * abstraction) that provides cluster-wide consistency with a slightly higher
 * latency budget.
 *
 * <p>Each logical cache region has its own TTL, maximum size, and eviction
 * policy so that business-critical data (GL accounts, compliance lists) can
 * be tuned independently from high-churn data (exchange rates).
 *
 * <p>Immutable once built. Use {@link Builder} to construct.
 */
public final class CacheConfig {

    // -----------------------------------------------------------------------
    //  Cache regions
    // -----------------------------------------------------------------------

    /**
     * Named cache regions aligned with OmniBank's bounded contexts.
     */
    public enum Region {
        GL_ACCOUNTS,
        EXCHANGE_RATES,
        CUSTOMER_PROFILES,
        PAYMENT_ROUTING_RULES,
        COMPLIANCE_LISTS
    }

    /**
     * Eviction strategy applied when the cache region reaches its size limit.
     */
    public enum EvictionPolicy {
        /** Least-recently-used — good general default. */
        LRU,
        /** Least-frequently-used — better for skewed-popularity workloads. */
        LFU,
        /** Time-based only — entries live until TTL expires, no size cap eviction. */
        TIME_ONLY
    }

    /**
     * Write-propagation strategy for the L2 distributed tier.
     */
    public enum WriteStrategy {
        /** Write to cache and backing store in the same call path. */
        WRITE_THROUGH,
        /** Buffer writes and flush to backing store asynchronously. */
        WRITE_BEHIND,
        /** Only populate cache on read misses (cache-aside). */
        CACHE_ASIDE
    }

    // -----------------------------------------------------------------------
    //  Per-region settings (record for compactness)
    // -----------------------------------------------------------------------

    /**
     * Configuration knobs for a single cache region at a single tier.
     *
     * @param ttl            how long entries survive after write
     * @param maxEntries     upper bound on entries; 0 = unbounded (time-only)
     * @param evictionPolicy eviction strategy when maxEntries is reached
     */
    public record TierConfig(
            Duration ttl,
            long maxEntries,
            EvictionPolicy evictionPolicy
    ) {
        public TierConfig {
            Objects.requireNonNull(ttl, "ttl");
            Objects.requireNonNull(evictionPolicy, "evictionPolicy");
            if (ttl.isNegative()) throw new IllegalArgumentException("TTL must be non-negative");
            if (maxEntries < 0) throw new IllegalArgumentException("maxEntries must be >= 0");
        }
    }

    /**
     * Full region configuration covering both cache tiers plus the write
     * strategy for the distributed tier.
     *
     * @param l1            L1 (local / Caffeine) tier settings
     * @param l2            L2 (distributed / Redis) tier settings
     * @param writeStrategy how writes are propagated to the backing store
     * @param l2Enabled     whether the distributed tier is active for this region
     */
    public record RegionConfig(
            TierConfig l1,
            TierConfig l2,
            WriteStrategy writeStrategy,
            boolean l2Enabled
    ) {
        public RegionConfig {
            Objects.requireNonNull(l1, "l1");
            Objects.requireNonNull(l2, "l2");
            Objects.requireNonNull(writeStrategy, "writeStrategy");
        }
    }

    // -----------------------------------------------------------------------
    //  Instance state
    // -----------------------------------------------------------------------

    private final Map<Region, RegionConfig> regions;
    private final String keyPrefix;
    private final int schemaVersion;
    private final boolean metricsEnabled;

    private CacheConfig(Map<Region, RegionConfig> regions,
                        String keyPrefix,
                        int schemaVersion,
                        boolean metricsEnabled) {
        this.regions = Collections.unmodifiableMap(new EnumMap<>(regions));
        this.keyPrefix = keyPrefix;
        this.schemaVersion = schemaVersion;
        this.metricsEnabled = metricsEnabled;
    }

    /**
     * Returns the configuration for the given region, or the platform-wide
     * default if the region was not explicitly configured.
     */
    public RegionConfig forRegion(Region region) {
        return regions.getOrDefault(region, defaultRegionConfig());
    }

    public Map<Region, RegionConfig> allRegions() {
        return regions;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public boolean metricsEnabled() {
        return metricsEnabled;
    }

    // -----------------------------------------------------------------------
    //  Sensible defaults
    // -----------------------------------------------------------------------

    /**
     * Creates the production-recommended defaults. Every region is pre-tuned
     * for its typical access pattern.
     */
    public static CacheConfig productionDefaults() {
        return new Builder()
                .keyPrefix("omnibank")
                .schemaVersion(1)
                .metricsEnabled(true)
                .region(Region.GL_ACCOUNTS, new RegionConfig(
                        new TierConfig(Duration.ofMinutes(10), 50_000, EvictionPolicy.LRU),
                        new TierConfig(Duration.ofMinutes(30), 500_000, EvictionPolicy.LRU),
                        WriteStrategy.WRITE_THROUGH,
                        true))
                .region(Region.EXCHANGE_RATES, new RegionConfig(
                        new TierConfig(Duration.ofSeconds(30), 5_000, EvictionPolicy.LFU),
                        new TierConfig(Duration.ofMinutes(2), 20_000, EvictionPolicy.LFU),
                        WriteStrategy.CACHE_ASIDE,
                        true))
                .region(Region.CUSTOMER_PROFILES, new RegionConfig(
                        new TierConfig(Duration.ofMinutes(5), 100_000, EvictionPolicy.LRU),
                        new TierConfig(Duration.ofMinutes(15), 1_000_000, EvictionPolicy.LRU),
                        WriteStrategy.WRITE_THROUGH,
                        true))
                .region(Region.PAYMENT_ROUTING_RULES, new RegionConfig(
                        new TierConfig(Duration.ofMinutes(60), 10_000, EvictionPolicy.TIME_ONLY),
                        new TierConfig(Duration.ofHours(4), 50_000, EvictionPolicy.TIME_ONLY),
                        WriteStrategy.CACHE_ASIDE,
                        true))
                .region(Region.COMPLIANCE_LISTS, new RegionConfig(
                        new TierConfig(Duration.ofMinutes(15), 200_000, EvictionPolicy.LFU),
                        new TierConfig(Duration.ofHours(1), 2_000_000, EvictionPolicy.LFU),
                        WriteStrategy.WRITE_THROUGH,
                        true))
                .build();
    }

    private static RegionConfig defaultRegionConfig() {
        return new RegionConfig(
                new TierConfig(Duration.ofMinutes(5), 10_000, EvictionPolicy.LRU),
                new TierConfig(Duration.ofMinutes(15), 100_000, EvictionPolicy.LRU),
                WriteStrategy.CACHE_ASIDE,
                false);
    }

    // -----------------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------------

    public static final class Builder {

        private final EnumMap<Region, RegionConfig> regions = new EnumMap<>(Region.class);
        private String keyPrefix = "omnibank";
        private int schemaVersion = 1;
        private boolean metricsEnabled = true;

        public Builder region(Region region, RegionConfig config) {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(config, "config");
            regions.put(region, config);
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
            return this;
        }

        public Builder schemaVersion(int schemaVersion) {
            if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be >= 1");
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder metricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(regions, keyPrefix, schemaVersion, metricsEnabled);
        }
    }
}
