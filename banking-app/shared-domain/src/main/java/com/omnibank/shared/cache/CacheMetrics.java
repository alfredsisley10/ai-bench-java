package com.omnibank.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects per-region cache metrics: hits, misses, evictions, and latency
 * percentiles. Designed for high-throughput, low-contention recording using
 * {@link LongAdder} accumulators.
 *
 * <p>Thread-safe. A single instance is shared across all cache tiers and
 * regions within a JVM.
 *
 * <p>Percentile computation uses a simple histogram-bucket approach rather
 * than a streaming estimator like T-Digest, which keeps the dependency
 * footprint at zero while still providing actionable p50/p95/p99 numbers
 * for cache-operation latency.
 */
public final class CacheMetrics {

    private static final Logger log = LoggerFactory.getLogger(CacheMetrics.class);

    // -------------------------------------------------------------------
    //  Per-region counters
    // -------------------------------------------------------------------

    private static final class RegionCounters {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final LongAdder evictions = new LongAdder();
        final LongAdder puts = new LongAdder();
        final LatencyHistogram latency = new LatencyHistogram();
    }

    private final ConcurrentHashMap<String, RegionCounters> counters = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Recording
    // -------------------------------------------------------------------

    public void recordHit(String region) {
        countersFor(region).hits.increment();
    }

    public void recordMiss(String region) {
        countersFor(region).misses.increment();
    }

    public void recordEviction(String region) {
        countersFor(region).evictions.increment();
    }

    public void recordPut(String region) {
        countersFor(region).puts.increment();
    }

    /**
     * Records the latency of a single cache operation (get or compute).
     *
     * @param region  region name
     * @param latency elapsed duration of the operation
     */
    public void recordLatency(String region, Duration latency) {
        countersFor(region).latency.record(latency.toNanos());
    }

    // -------------------------------------------------------------------
    //  Snapshot
    // -------------------------------------------------------------------

    /**
     * Immutable point-in-time snapshot of a region's metrics.
     *
     * @param region       region name
     * @param hits         total cache hits since start
     * @param misses       total cache misses since start
     * @param evictions    total evictions (expired + size-cap) since start
     * @param puts         total put operations since start
     * @param hitRate      hit / (hit + miss), or 0.0 if no requests
     * @param p50LatencyUs median latency in microseconds
     * @param p95LatencyUs 95th-percentile latency in microseconds
     * @param p99LatencyUs 99th-percentile latency in microseconds
     * @param snapshotAt   when this snapshot was taken
     */
    public record RegionSnapshot(
            String region,
            long hits,
            long misses,
            long evictions,
            long puts,
            double hitRate,
            long p50LatencyUs,
            long p95LatencyUs,
            long p99LatencyUs,
            Instant snapshotAt
    ) {
        @Override
        public String toString() {
            return String.format(
                    "[%s] hits=%d misses=%d evictions=%d puts=%d hitRate=%.2f%% p50=%dus p95=%dus p99=%dus",
                    region, hits, misses, evictions, puts, hitRate * 100.0,
                    p50LatencyUs, p95LatencyUs, p99LatencyUs);
        }
    }

    /**
     * Returns a snapshot for the given region, or an empty snapshot if no
     * metrics have been recorded.
     */
    public RegionSnapshot snapshot(String region) {
        Objects.requireNonNull(region, "region");
        RegionCounters c = counters.get(region);
        if (c == null) {
            return new RegionSnapshot(region, 0, 0, 0, 0, 0.0, 0, 0, 0, Instant.now());
        }
        long h = c.hits.sum();
        long m = c.misses.sum();
        long total = h + m;
        double rate = total == 0 ? 0.0 : (double) h / total;
        return new RegionSnapshot(
                region, h, m, c.evictions.sum(), c.puts.sum(), rate,
                c.latency.percentileMicros(50),
                c.latency.percentileMicros(95),
                c.latency.percentileMicros(99),
                Instant.now());
    }

    /**
     * Returns snapshots for all regions that have recorded at least one event.
     */
    public Map<String, RegionSnapshot> snapshotAll() {
        SortedMap<String, RegionSnapshot> result = new TreeMap<>();
        for (String region : counters.keySet()) {
            result.put(region, snapshot(region));
        }
        return Collections.unmodifiableSortedMap(result);
    }

    /**
     * Resets all counters. Typically used during tests or after a metrics
     * scrape cycle for delta-based reporters.
     */
    public void reset() {
        counters.clear();
        log.debug("All cache metrics counters reset");
    }

    /**
     * Resets counters for a single region.
     */
    public void reset(String region) {
        counters.remove(region);
    }

    /**
     * Logs a human-readable summary to SLF4J at INFO level. Intended for
     * periodic diagnostics (e.g. every 60 seconds via a scheduled task).
     */
    public void logSummary() {
        snapshotAll().values().forEach(s -> log.info("CacheMetrics {}", s));
    }

    // -------------------------------------------------------------------
    //  Internals
    // -------------------------------------------------------------------

    private RegionCounters countersFor(String region) {
        return counters.computeIfAbsent(region, k -> new RegionCounters());
    }

    // -------------------------------------------------------------------
    //  Simple histogram for latency percentiles
    // -------------------------------------------------------------------

    /**
     * Lock-free histogram with logarithmic buckets. Bucket boundaries are
     * powers of two from 1us to ~134 seconds, which covers the range of
     * typical cache operations (sub-microsecond local, low-millisecond Redis).
     */
    static final class LatencyHistogram {

        // 28 buckets: 2^0 us to 2^27 us (~134 seconds)
        private static final int BUCKET_COUNT = 28;
        private final LongAdder[] buckets;
        private final LongAdder totalCount = new LongAdder();

        LatencyHistogram() {
            buckets = new LongAdder[BUCKET_COUNT];
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = new LongAdder();
            }
        }

        void record(long nanos) {
            long micros = Math.max(1, nanos / 1_000);
            int bucket = 63 - Long.numberOfLeadingZeros(micros);
            bucket = Math.min(bucket, BUCKET_COUNT - 1);
            buckets[bucket].increment();
            totalCount.increment();
        }

        /**
         * Computes an approximate percentile value in microseconds.
         *
         * @param percentile e.g. 50, 95, 99
         */
        long percentileMicros(int percentile) {
            long total = totalCount.sum();
            if (total == 0) return 0;
            long threshold = (long) Math.ceil(total * percentile / 100.0);
            long cumulative = 0;
            for (int i = 0; i < BUCKET_COUNT; i++) {
                cumulative += buckets[i].sum();
                if (cumulative >= threshold) {
                    return 1L << i; // bucket upper bound in microseconds
                }
            }
            return 1L << (BUCKET_COUNT - 1);
        }
    }
}
