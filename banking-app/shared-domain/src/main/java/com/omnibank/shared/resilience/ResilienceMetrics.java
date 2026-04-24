package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates metrics from all resilience components (circuit breakers,
 * bulkheads, rate limiters, time limiters, retry policies) into a unified
 * view. Designed for integration with Spring Boot Actuator and Micrometer.
 *
 * <p>Provides:
 * <ul>
 *   <li>Per-component success / failure / rejection / timeout counts</li>
 *   <li>Latency histogram buckets for call durations</li>
 *   <li>Circuit breaker state distribution</li>
 *   <li>Bulkhead utilisation percentages</li>
 *   <li>Rate limiter rejection rates</li>
 *   <li>Periodic snapshots for time-series export</li>
 * </ul>
 *
 * <p>In production, a Micrometer {@code MeterRegistry} would be injected
 * and gauges/counters registered. This class provides the raw data that
 * feeds those meters.
 */

public class ResilienceMetrics {

    private static final Logger log = LoggerFactory.getLogger(ResilienceMetrics.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final TimeLimiter timeLimiter;

    /** Stores named ResilienceChain metrics for aggregation. */
    private final ConcurrentHashMap<String, ResilienceChain.ChainMetrics> chainMetrics =
            new ConcurrentHashMap<>();

    /** Latency histogram: buckets in milliseconds. */
    private static final long[] HISTOGRAM_BUCKETS_MS = {
            1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000
    };

    /** Per-operation latency histogram counters. */
    private final ConcurrentHashMap<String, long[]> latencyHistograms = new ConcurrentHashMap<>();

    /** Background scheduler for periodic snapshot collection. */
    private final ScheduledExecutorService snapshotScheduler;

    /** Most recent collected snapshot. */
    private volatile AggregateSnapshot latestSnapshot;

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public ResilienceMetrics(CircuitBreakerRegistry cbRegistry,
                             BulkheadRegistry bhRegistry,
                             RateLimiterRegistry rlRegistry,
                             TimeLimiter timeLimiter) {
        this.circuitBreakerRegistry = Objects.requireNonNull(cbRegistry);
        this.bulkheadRegistry = Objects.requireNonNull(bhRegistry);
        this.rateLimiterRegistry = Objects.requireNonNull(rlRegistry);
        this.timeLimiter = Objects.requireNonNull(timeLimiter);

        // JDK 17 cross-compat: Thread.ofPlatform() is JEP 444 (21+).
        this.snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resilience-metrics-snapshot");
            t.setDaemon(true);
            return t;
        });
        this.snapshotScheduler.scheduleAtFixedRate(
                this::collectSnapshot, 10, 10, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------
    //  Chain registration
    // -------------------------------------------------------------------

    /**
     * Registers a resilience chain's metrics for aggregation.
     */
    public void registerChain(String name, ResilienceChain.ChainMetrics metrics) {
        chainMetrics.put(name, metrics);
    }

    // -------------------------------------------------------------------
    //  Latency histogram recording
    // -------------------------------------------------------------------

    /**
     * Records a call latency into the histogram for the given operation.
     */
    public void recordLatency(String operationName, Duration latency) {
        long ms = latency.toMillis();
        long[] buckets = latencyHistograms.computeIfAbsent(
                operationName, k -> new long[HISTOGRAM_BUCKETS_MS.length + 1]);
        int idx = findBucket(ms);
        synchronized (buckets) {
            buckets[idx]++;
        }
    }

    private static int findBucket(long ms) {
        for (int i = 0; i < HISTOGRAM_BUCKETS_MS.length; i++) {
            if (ms <= HISTOGRAM_BUCKETS_MS[i]) return i;
        }
        return HISTOGRAM_BUCKETS_MS.length; // overflow bucket
    }

    // -------------------------------------------------------------------
    //  Aggregate snapshot
    // -------------------------------------------------------------------

    /**
     * Point-in-time snapshot of all resilience metrics across every
     * registered component.
     */
    public record AggregateSnapshot(
            Instant collectedAt,
            Map<String, CircuitBreakerSnapshot> circuitBreakers,
            Map<String, BulkheadSnapshot> bulkheads,
            Map<String, RateLimiterSnapshot> rateLimiters,
            Map<String, TimeLimiterSnapshot> timeLimiters,
            Map<String, ChainSnapshot> chains,
            Map<String, HistogramSnapshot> latencyHistograms
    ) {
        public AggregateSnapshot {
            Objects.requireNonNull(collectedAt);
            circuitBreakers = Map.copyOf(circuitBreakers);
            bulkheads = Map.copyOf(bulkheads);
            rateLimiters = Map.copyOf(rateLimiters);
            timeLimiters = Map.copyOf(timeLimiters);
            chains = Map.copyOf(chains);
            latencyHistograms = Map.copyOf(latencyHistograms);
        }
    }

    public record CircuitBreakerSnapshot(
            String state,
            long totalCalls,
            long failures,
            long slowCalls,
            long rejections,
            double failureRatePercent
    ) {}

    public record BulkheadSnapshot(
            long acquired,
            long rejected,
            long active,
            double utilisationPercent,
            Duration averageWaitTime
    ) {}

    public record RateLimiterSnapshot(
            long allowed,
            long rejected,
            double rejectionRatePercent
    ) {}

    public record TimeLimiterSnapshot(
            long totalCalls,
            long successes,
            long timeouts,
            long errors,
            double timeoutRatePercent,
            Duration averageLatency
    ) {}

    public record ChainSnapshot(
            long totalCalls,
            long successes,
            long failures,
            long fallbackInvocations,
            long rateLimitRejections,
            long circuitBreakerRejections,
            long bulkheadRejections,
            long timeouts
    ) {}

    public record HistogramSnapshot(
            long[] bucketBoundariesMs,
            long[] bucketCounts,
            long totalCount
    ) {
        /** Returns the pXX percentile in milliseconds (approximate). */
        public long percentile(double p) {
            long target = (long) (totalCount * p / 100.0);
            long cumulative = 0;
            for (int i = 0; i < bucketCounts.length; i++) {
                cumulative += bucketCounts[i];
                if (cumulative >= target) {
                    return i < bucketBoundariesMs.length ? bucketBoundariesMs[i] : Long.MAX_VALUE;
                }
            }
            return Long.MAX_VALUE;
        }
    }

    // -------------------------------------------------------------------
    //  Snapshot collection
    // -------------------------------------------------------------------

    /**
     * Collects a snapshot from all registered components. Called
     * periodically by the background scheduler and on-demand.
     */
    public AggregateSnapshot collectSnapshot() {
        var cbSnapshots = new LinkedHashMap<String, CircuitBreakerSnapshot>();
        for (String name : circuitBreakerRegistry.registeredNames()) {
            var cb = circuitBreakerRegistry.<Object>get(name);
            if (cb != null) {
                var m = cb.currentMetrics();
                cbSnapshots.put(name, new CircuitBreakerSnapshot(
                        cb.currentState(), m.totalCalls(), m.failures(),
                        m.slowCalls(), m.rejections(), m.failureRatePercent()));
            }
        }

        var bhSnapshots = new LinkedHashMap<String, BulkheadSnapshot>();
        for (String name : bulkheadRegistry.registeredNames()) {
            var bh = bulkheadRegistry.get(name);
            if (bh != null) {
                var m = bh.metrics();
                bhSnapshots.put(name, new BulkheadSnapshot(
                        m.acquired(), m.rejected(), m.active(),
                        m.utilisation(bh.config().maxConcurrentCalls()), m.averageWaitTime()));
            }
        }

        var rlSnapshots = new LinkedHashMap<String, RateLimiterSnapshot>();
        for (String name : rateLimiterRegistry.registeredNames()) {
            var rl = rateLimiterRegistry.get(name);
            if (rl != null) {
                var m = rl.metrics();
                rlSnapshots.put(name, new RateLimiterSnapshot(
                        m.allowed(), m.rejected(), m.rejectionRate()));
            }
        }

        var tlSnapshots = new LinkedHashMap<String, TimeLimiterSnapshot>();
        for (var entry : timeLimiter.allMetrics().entrySet()) {
            var m = entry.getValue();
            tlSnapshots.put(entry.getKey(), new TimeLimiterSnapshot(
                    m.totalCalls(), m.successes(), m.timeouts(), m.errors(),
                    m.timeoutRate(), m.averageLatency()));
        }

        var chainSnapshots = new LinkedHashMap<String, ChainSnapshot>();
        for (var entry : chainMetrics.entrySet()) {
            var m = entry.getValue();
            chainSnapshots.put(entry.getKey(), new ChainSnapshot(
                    m.totalCalls(), m.successes(), m.failures(),
                    m.fallbackInvocations(), m.rateLimitRejections(),
                    m.circuitBreakerRejections(), m.bulkheadRejections(), m.timeouts()));
        }

        var histSnapshots = new LinkedHashMap<String, HistogramSnapshot>();
        for (var entry : latencyHistograms.entrySet()) {
            long[] counts;
            synchronized (entry.getValue()) {
                counts = entry.getValue().clone();
            }
            long total = 0;
            for (long c : counts) total += c;
            histSnapshots.put(entry.getKey(), new HistogramSnapshot(
                    HISTOGRAM_BUCKETS_MS.clone(), counts, total));
        }

        var snapshot = new AggregateSnapshot(
                Instant.now(), cbSnapshots, bhSnapshots, rlSnapshots,
                tlSnapshots, chainSnapshots, histSnapshots);
        this.latestSnapshot = snapshot;

        log.debug("Collected resilience metrics snapshot: {} CBs, {} BHs, {} RLs, {} TLs, {} chains",
                  cbSnapshots.size(), bhSnapshots.size(), rlSnapshots.size(),
                  tlSnapshots.size(), chainSnapshots.size());
        return snapshot;
    }

    /** Returns the most recently collected snapshot (may be null on first call). */
    public AggregateSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    // -------------------------------------------------------------------
    //  Health summary
    // -------------------------------------------------------------------

    /**
     * Quick health summary: returns "HEALTHY" if all circuit breakers are
     * CLOSED, "DEGRADED" if any are HALF_OPEN, or "UNHEALTHY" if any are
     * OPEN.
     */
    public String overallHealthStatus() {
        boolean anyOpen = false;
        boolean anyHalfOpen = false;

        for (String name : circuitBreakerRegistry.registeredNames()) {
            var cb = circuitBreakerRegistry.<Object>get(name);
            if (cb != null) {
                switch (cb.currentState()) {
                    case "OPEN"      -> anyOpen = true;
                    case "HALF_OPEN" -> anyHalfOpen = true;
                    default -> { /* CLOSED, all good */ }
                }
            }
        }

        if (anyOpen) return "UNHEALTHY";
        if (anyHalfOpen) return "DEGRADED";
        return "HEALTHY";
    }

    // -------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------

    public void shutdown() {
        snapshotScheduler.shutdown();
        try {
            if (!snapshotScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                snapshotScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            snapshotScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
