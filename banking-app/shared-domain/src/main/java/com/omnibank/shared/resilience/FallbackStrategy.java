package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sealed hierarchy of fallback behaviours that can be attached to resilience
 * components (circuit breakers, bulkheads, time limiters). Every strategy
 * records invocation metrics and emits structured log events so that
 * operations teams can track how often the system is degraded.
 *
 * <p>Usage:
 * <pre>{@code
 *   FallbackStrategy<AccountBalance> fallback = new CachedFallback<>(
 *           "balance-cache", Duration.ofMinutes(5));
 *   fallback.updateCache("acct-123", latestBalance);
 *   // ... later, when the primary call fails:
 *   AccountBalance result = fallback.execute("acct-123", originalException);
 * }</pre>
 *
 * @param <T> the type of the value the fallback produces
 */
public sealed interface FallbackStrategy<T>
        permits FallbackStrategy.CachedFallback,
                FallbackStrategy.DefaultValueFallback,
                FallbackStrategy.DegradedServiceFallback,
                FallbackStrategy.FailFastFallback {

    /**
     * Execute the fallback logic for the given context key.
     *
     * @param contextKey  identifies the resource (account, customer, endpoint)
     * @param cause       the original exception that triggered the fallback
     * @return the fallback value
     * @throws RuntimeException if the fallback itself cannot produce a value
     */
    T execute(String contextKey, Throwable cause);

    /** Human-readable strategy name for metrics and logging. */
    String strategyName();

    /** Snapshot of how many times this fallback has been invoked. */
    FallbackMetrics metrics();

    // -------------------------------------------------------------------
    //  Shared metrics
    // -------------------------------------------------------------------

    final class FallbackMetrics {

        private final LongAdder invocations = new LongAdder();
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();

        public void recordHit()  { invocations.increment(); hits.increment(); }
        public void recordMiss() { invocations.increment(); misses.increment(); }

        public long invocations() { return invocations.sum(); }
        public long hits()        { return hits.sum(); }
        public long misses()      { return misses.sum(); }

        @Override
        public String toString() {
            return "FallbackMetrics[invocations=%d, hits=%d, misses=%d]"
                    .formatted(invocations(), hits(), misses());
        }
    }

    // -------------------------------------------------------------------
    //  CachedFallback — return last known good value
    // -------------------------------------------------------------------

    /**
     * Returns the most recently cached value for the given key. The cache
     * entries have a configurable staleness TTL; if the cached value is
     * older than the TTL the fallback reports a miss and throws.
     */
    final class CachedFallback<T> implements FallbackStrategy<T> {

        private static final Logger log = LoggerFactory.getLogger(CachedFallback.class);

        private record CacheEntry<V>(V value, Instant storedAt) {}

        private final String name;
        private final Duration maxStaleness;
        private final ConcurrentHashMap<String, CacheEntry<T>> cache = new ConcurrentHashMap<>();
        private final FallbackMetrics metrics = new FallbackMetrics();

        public CachedFallback(String name, Duration maxStaleness) {
            this.name = Objects.requireNonNull(name, "name");
            this.maxStaleness = Objects.requireNonNull(maxStaleness, "maxStaleness");
        }

        /**
         * Stores or updates the cached value for a context key. Callers
         * should invoke this on every successful primary call so that the
         * cache stays reasonably fresh.
         */
        public void updateCache(String contextKey, T value) {
            cache.put(contextKey, new CacheEntry<>(value, Instant.now()));
        }

        @Override
        public T execute(String contextKey, Throwable cause) {
            CacheEntry<T> entry = cache.get(contextKey);
            if (entry == null) {
                metrics.recordMiss();
                log.warn("[CachedFallback:{}] No cached value for key={}, cause={}",
                         name, contextKey, cause.getMessage());
                throw new FallbackExhaustedException(
                        "No cached value available for " + contextKey, cause);
            }
            Duration age = Duration.between(entry.storedAt(), Instant.now());
            if (age.compareTo(maxStaleness) > 0) {
                metrics.recordMiss();
                log.warn("[CachedFallback:{}] Cached value for key={} is stale (age={}), " +
                         "maxStaleness={}", name, contextKey, age, maxStaleness);
                throw new FallbackExhaustedException(
                        "Cached value for %s is stale (age=%s)".formatted(contextKey, age), cause);
            }
            metrics.recordHit();
            log.info("[CachedFallback:{}] Returning cached value for key={} (age={})",
                     name, contextKey, age);
            return entry.value();
        }

        @Override public String strategyName()    { return "CachedFallback:" + name; }
        @Override public FallbackMetrics metrics() { return metrics; }
    }

    // -------------------------------------------------------------------
    //  DefaultValueFallback — return a static default
    // -------------------------------------------------------------------

    /**
     * Always returns a pre-configured static default value. Suitable for
     * read-only endpoints where a neutral/empty result is acceptable
     * during outages (e.g. empty list, zero balance placeholder).
     */
    final class DefaultValueFallback<T> implements FallbackStrategy<T> {

        private static final Logger log = LoggerFactory.getLogger(DefaultValueFallback.class);

        private final String name;
        private final T defaultValue;
        private final FallbackMetrics metrics = new FallbackMetrics();

        public DefaultValueFallback(String name, T defaultValue) {
            this.name = Objects.requireNonNull(name, "name");
            this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        }

        @Override
        public T execute(String contextKey, Throwable cause) {
            metrics.recordHit();
            log.info("[DefaultValueFallback:{}] Returning default for key={}, cause={}",
                     name, contextKey, cause.getClass().getSimpleName());
            return defaultValue;
        }

        @Override public String strategyName()    { return "DefaultValueFallback:" + name; }
        @Override public FallbackMetrics metrics() { return metrics; }
    }

    // -------------------------------------------------------------------
    //  DegradedServiceFallback — return a partial result
    // -------------------------------------------------------------------

    /**
     * Computes a degraded / partial response. The supplier receives the
     * context key and is expected to assemble whatever partial data is
     * available (e.g. from a local cache, a secondary data source, or
     * hard-coded defaults for non-critical fields).
     */
    final class DegradedServiceFallback<T> implements FallbackStrategy<T> {

        private static final Logger log = LoggerFactory.getLogger(DegradedServiceFallback.class);

        @FunctionalInterface
        public interface DegradedSupplier<T> {
            Optional<T> supply(String contextKey, Throwable cause);
        }

        private final String name;
        private final DegradedSupplier<T> degradedSupplier;
        private final FallbackMetrics metrics = new FallbackMetrics();

        public DegradedServiceFallback(String name, DegradedSupplier<T> degradedSupplier) {
            this.name = Objects.requireNonNull(name, "name");
            this.degradedSupplier = Objects.requireNonNull(degradedSupplier, "degradedSupplier");
        }

        @Override
        public T execute(String contextKey, Throwable cause) {
            Optional<T> partial = degradedSupplier.supply(contextKey, cause);
            if (partial.isPresent()) {
                metrics.recordHit();
                log.info("[DegradedServiceFallback:{}] Returning partial result for key={}",
                         name, contextKey);
                return partial.get();
            }
            metrics.recordMiss();
            log.warn("[DegradedServiceFallback:{}] Unable to produce degraded result for key={}",
                     name, contextKey);
            throw new FallbackExhaustedException(
                    "Degraded supplier produced no result for " + contextKey, cause);
        }

        @Override public String strategyName()    { return "DegradedServiceFallback:" + name; }
        @Override public FallbackMetrics metrics() { return metrics; }
    }

    // -------------------------------------------------------------------
    //  FailFastFallback — throw immediately
    // -------------------------------------------------------------------

    /**
     * No fallback. Re-throws immediately, wrapping the cause in a
     * {@link FallbackExhaustedException}. Use this when degraded behaviour
     * is unacceptable (e.g. funds-transfer authorisation must never
     * return a stale result).
     */
    final class FailFastFallback<T> implements FallbackStrategy<T> {

        private static final Logger log = LoggerFactory.getLogger(FailFastFallback.class);

        private final String name;
        private final FallbackMetrics metrics = new FallbackMetrics();

        public FailFastFallback(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        @Override
        public T execute(String contextKey, Throwable cause) {
            metrics.recordMiss();
            log.error("[FailFastFallback:{}] Failing fast for key={}, cause={}",
                      name, contextKey, cause.getMessage());
            throw new FallbackExhaustedException(
                    "FailFast: no fallback for " + contextKey, cause);
        }

        @Override public String strategyName()    { return "FailFastFallback:" + name; }
        @Override public FallbackMetrics metrics() { return metrics; }
    }

    // -------------------------------------------------------------------
    //  Exception
    // -------------------------------------------------------------------

    /**
     * Thrown when no fallback strategy is able to produce a result.
     */
    final class FallbackExhaustedException extends RuntimeException {

        public FallbackExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
