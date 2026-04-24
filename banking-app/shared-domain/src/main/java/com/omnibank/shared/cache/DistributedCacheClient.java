package com.omnibank.shared.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Abstraction over a distributed (L2) cache such as Redis, Hazelcast, or
 * Memcached. Implementations must be thread-safe.
 *
 * <p>The interface supports three write-propagation patterns:
 * <ul>
 *   <li><b>Cache-aside</b> — callers use {@link #getOrCompute} to lazily
 *       populate the cache on read misses.</li>
 *   <li><b>Write-through</b> — callers use {@link #put} to write to both the
 *       cache and the backing store within the same call path.</li>
 *   <li><b>Write-behind</b> — implementations may buffer {@link #put} calls
 *       and flush to the backing store asynchronously.</li>
 * </ul>
 *
 * <p>Serialisation is pluggable via {@link SerializationStrategy}. The default
 * strategy assumes Jackson JSON, but callers may supply Kryo, Protobuf, or
 * any other codec.
 *
 * @param <V> the value type this client instance manages
 */
public interface DistributedCacheClient<V> {

    // -------------------------------------------------------------------
    //  Serialisation strategy
    // -------------------------------------------------------------------

    /**
     * Pluggable codec that converts cache values to/from a byte representation
     * suitable for network transport or on-disk storage.
     *
     * @param <T> value type
     */
    interface SerializationStrategy<T> {

        byte[] serialize(T value);

        T deserialize(byte[] data, Class<T> type);

        /** Strategy name used in metrics and diagnostics (e.g. "jackson-json"). */
        String name();
    }

    // -------------------------------------------------------------------
    //  Write mode
    // -------------------------------------------------------------------

    /**
     * Determines how mutations propagate to the underlying data store.
     */
    sealed interface WriteMode permits WriteMode.WriteThrough,
                                       WriteMode.WriteBehind,
                                       WriteMode.CacheAside {

        /** Synchronous write to both cache and backing store. */
        record WriteThrough() implements WriteMode {}

        /**
         * Asynchronous buffered write.
         *
         * @param flushInterval how often the write-behind buffer is flushed
         * @param maxBatchSize  maximum number of entries per flush batch
         */
        record WriteBehind(Duration flushInterval, int maxBatchSize) implements WriteMode {
            public WriteBehind {
                Objects.requireNonNull(flushInterval, "flushInterval");
                if (maxBatchSize <= 0) throw new IllegalArgumentException("maxBatchSize must be > 0");
            }
        }

        /** Population on read miss only — no automatic write-back. */
        record CacheAside() implements WriteMode {}
    }

    // -------------------------------------------------------------------
    //  Core operations
    // -------------------------------------------------------------------

    /**
     * Retrieves a cached value.
     *
     * @param key the cache key (already namespaced / versioned via
     *            {@link CacheKeyBuilder})
     * @return the value, or empty if there is no entry or it has expired
     */
    Optional<V> get(String key);

    /**
     * Stores a value with the specified time-to-live.
     *
     * @param key   the cache key
     * @param value the value to cache
     * @param ttl   time after which the entry should be evicted
     */
    void put(String key, V value, Duration ttl);

    /**
     * Atomic conditional put. Stores the value only if no live entry exists
     * for the key. This is the building block for distributed locks and
     * leader-election via the cache layer.
     *
     * @return {@code true} if the entry was inserted; {@code false} if a live
     *         entry already existed
     */
    boolean putIfAbsent(String key, V value, Duration ttl);

    /**
     * Removes an entry.  Implementations must guarantee that a subsequent
     * {@link #get(String)} for the same key returns empty, even if the entry
     * was cached locally.
     */
    void invalidate(String key);

    /**
     * Bulk invalidation of all entries whose keys match the given prefix.
     * Useful for region-wide flush (e.g. after a schema migration).
     */
    void invalidateByPrefix(String keyPrefix);

    // -------------------------------------------------------------------
    //  Cache-aside convenience
    // -------------------------------------------------------------------

    /**
     * Gets the value if cached; otherwise computes it via {@code loader},
     * caches the result, and returns it. This is the idiomatic cache-aside
     * pattern method.
     *
     * @param key    the cache key
     * @param ttl    TTL for the newly computed entry
     * @param loader function that loads the value from the authoritative store
     * @return the cached (or newly computed) value
     */
    V getOrCompute(String key, Duration ttl, Function<String, V> loader);

    // -------------------------------------------------------------------
    //  Bulk operations
    // -------------------------------------------------------------------

    /**
     * Batch get. Returns only the entries that were found (no nulls).
     */
    Map<String, V> getAll(Collection<String> keys);

    /**
     * Batch put with a uniform TTL.
     */
    void putAll(Map<String, V> entries, Duration ttl);

    // -------------------------------------------------------------------
    //  Async variants (non-blocking I/O for high-throughput paths)
    // -------------------------------------------------------------------

    /**
     * Non-blocking version of {@link #get(String)}.
     */
    CompletableFuture<Optional<V>> getAsync(String key);

    /**
     * Non-blocking version of {@link #put(String, Object, Duration)}.
     */
    CompletableFuture<Void> putAsync(String key, V value, Duration ttl);

    // -------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------

    /**
     * Returns basic health/connectivity information. Implementations may
     * use this to surface Redis PING latency, connection-pool stats, etc.
     */
    HealthStatus health();

    /**
     * Health check result.
     *
     * @param healthy     {@code true} if the cache tier is reachable
     * @param latencyMs   round-trip ping latency in milliseconds
     * @param details     free-form diagnostic string
     */
    record HealthStatus(boolean healthy, long latencyMs, String details) {}
}
