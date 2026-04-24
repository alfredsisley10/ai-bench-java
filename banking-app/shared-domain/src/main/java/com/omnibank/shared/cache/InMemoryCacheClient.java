package com.omnibank.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Process-local implementation of {@link DistributedCacheClient} backed by a
 * {@link ConcurrentHashMap}. Suitable for single-node deployments, integration
 * tests, and local development where standing up Redis is unnecessary.
 *
 * <p>TTL enforcement is performed via a {@link ScheduledExecutorService} that
 * sweeps expired entries on a configurable interval. Between sweeps, reads
 * also perform a lazy expiry check so stale data is never returned.
 *
 * <p>This class is thread-safe. All mutation methods are atomic at the
 * entry level; bulk operations are <em>not</em> globally atomic.
 *
 * @param <V> the cached value type
 */
public final class InMemoryCacheClient<V> implements DistributedCacheClient<V> {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCacheClient.class);

    // -----------------------------------------------------------------------
    //  Internal entry wrapper
    // -----------------------------------------------------------------------

    private record Entry<V>(V value, Instant expiresAt) {
        boolean isExpired(Clock clock) {
            return clock.instant().isAfter(expiresAt);
        }
    }

    // -----------------------------------------------------------------------
    //  State
    // -----------------------------------------------------------------------

    private final ConcurrentHashMap<String, Entry<V>> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService sweeper;
    private final CacheMetrics metrics;
    private final String regionName;
    private final long maxEntries;

    /**
     * Creates an in-memory cache client.
     *
     * @param regionName    logical region name, used in metrics and logging
     * @param maxEntries    maximum entries before oldest are evicted (0 = unbounded)
     * @param sweepInterval how often the background sweeper runs
     * @param clock         clock used for TTL evaluation (inject a fixed clock in tests)
     * @param metrics       optional metrics collector; may be {@code null}
     */
    public InMemoryCacheClient(String regionName,
                               long maxEntries,
                               Duration sweepInterval,
                               Clock clock,
                               CacheMetrics metrics) {
        this.regionName = Objects.requireNonNull(regionName, "regionName");
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = metrics;

        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-sweeper-" + regionName);
            t.setDaemon(true);
            return t;
        });

        long intervalMs = sweepInterval.toMillis();
        this.sweeper.scheduleAtFixedRate(this::evictExpired, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("InMemoryCacheClient [{}] started — maxEntries={}, sweep={}ms",
                regionName, maxEntries == 0 ? "unbounded" : maxEntries, intervalMs);
    }

    /** Convenience constructor with system clock and 30-second sweep. */
    public InMemoryCacheClient(String regionName, long maxEntries, CacheMetrics metrics) {
        this(regionName, maxEntries, Duration.ofSeconds(30), Clock.systemUTC(), metrics);
    }

    // -------------------------------------------------------------------
    //  Core operations
    // -------------------------------------------------------------------

    @Override
    public Optional<V> get(String key) {
        Objects.requireNonNull(key, "key");
        Entry<V> entry = store.get(key);
        if (entry == null) {
            recordMiss();
            return Optional.empty();
        }
        if (entry.isExpired(clock)) {
            store.remove(key, entry); // lazy eviction
            recordMiss();
            recordEviction();
            return Optional.empty();
        }
        recordHit();
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");
        enforceSizeLimit();
        store.put(key, new Entry<>(value, clock.instant().plus(ttl)));
    }

    @Override
    public boolean putIfAbsent(String key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");

        Entry<V> existing = store.get(key);
        if (existing != null && !existing.isExpired(clock)) {
            return false;
        }
        // CAS: only insert if there is truly no live entry
        Entry<V> newEntry = new Entry<>(value, clock.instant().plus(ttl));
        Entry<V> prev = store.putIfAbsent(key, newEntry);
        if (prev != null && !prev.isExpired(clock)) {
            // Another thread raced us and won
            store.put(key, prev); // restore the winner
            return false;
        }
        if (prev != null) {
            // Was expired; we replace it
            store.put(key, newEntry);
        }
        return true;
    }

    @Override
    public void invalidate(String key) {
        Objects.requireNonNull(key, "key");
        if (store.remove(key) != null) {
            recordEviction();
        }
    }

    @Override
    public void invalidateByPrefix(String keyPrefix) {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        store.keySet().removeIf(k -> {
            if (k.startsWith(keyPrefix)) {
                recordEviction();
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------
    //  Cache-aside
    // -------------------------------------------------------------------

    @Override
    public V getOrCompute(String key, Duration ttl, Function<String, V> loader) {
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        V computed = loader.apply(key);
        if (computed != null) {
            put(key, computed, ttl);
        }
        return computed;
    }

    // -------------------------------------------------------------------
    //  Bulk
    // -------------------------------------------------------------------

    @Override
    public Map<String, V> getAll(Collection<String> keys) {
        Objects.requireNonNull(keys, "keys");
        Map<String, V> result = new HashMap<>();
        for (String key : keys) {
            get(key).ifPresent(v -> result.put(key, v));
        }
        return result;
    }

    @Override
    public void putAll(Map<String, V> entries, Duration ttl) {
        Objects.requireNonNull(entries, "entries");
        entries.forEach((k, v) -> put(k, v, ttl));
    }

    // -------------------------------------------------------------------
    //  Async (trivially completed for in-memory)
    // -------------------------------------------------------------------

    @Override
    public CompletableFuture<Optional<V>> getAsync(String key) {
        return CompletableFuture.completedFuture(get(key));
    }

    @Override
    public CompletableFuture<Void> putAsync(String key, V value, Duration ttl) {
        put(key, value, ttl);
        return CompletableFuture.completedFuture(null);
    }

    // -------------------------------------------------------------------
    //  Health
    // -------------------------------------------------------------------

    @Override
    public HealthStatus health() {
        return new HealthStatus(true, 0L,
                "in-memory: " + store.size() + " entries in region " + regionName);
    }

    // -------------------------------------------------------------------
    //  Internals
    // -------------------------------------------------------------------

    /** Scheduled sweep — removes all expired entries. */
    private void evictExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> {
            if (e.getValue().isExpired(clock)) {
                recordEviction();
                return true;
            }
            return false;
        });
        int evicted = before - store.size();
        if (evicted > 0) {
            log.debug("[{}] sweep evicted {} expired entries", regionName, evicted);
        }
    }

    /**
     * If a size limit is configured and we have reached it, evict the entry
     * with the earliest expiry (crude LRU approximation — production L1 caches
     * use Caffeine's Window-TinyLFU, but this is fine for the in-memory test
     * double).
     */
    private void enforceSizeLimit() {
        if (maxEntries <= 0 || store.size() < maxEntries) return;

        store.entrySet().stream()
                .min((a, b) -> a.getValue().expiresAt().compareTo(b.getValue().expiresAt()))
                .ifPresent(oldest -> {
                    store.remove(oldest.getKey());
                    recordEviction();
                });
    }

    /** Shuts down the background sweeper. Call on application shutdown. */
    public void shutdown() {
        sweeper.shutdown();
        try {
            if (!sweeper.awaitTermination(5, TimeUnit.SECONDS)) {
                sweeper.shutdownNow();
            }
        } catch (InterruptedException e) {
            sweeper.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("InMemoryCacheClient [{}] shut down", regionName);
    }

    /** Visible-for-testing: current entry count (including potentially expired). */
    int rawSize() {
        return store.size();
    }

    // -------------------------------------------------------------------
    //  Metrics helpers
    // -------------------------------------------------------------------

    private void recordHit() {
        if (metrics != null) metrics.recordHit(regionName);
    }

    private void recordMiss() {
        if (metrics != null) metrics.recordMiss(regionName);
    }

    private void recordEviction() {
        if (metrics != null) metrics.recordEviction(regionName);
    }
}
