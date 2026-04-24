package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps {@link CompletableFuture} and synchronous calls with configurable
 * timeouts. Supports cancel-on-timeout behaviour, fallback values, and
 * per-operation-name metrics.
 *
 * <p>In a large banking platform, downstream services occasionally become
 * unresponsive. The time limiter ensures that no caller thread is blocked
 * indefinitely, freeing resources for healthy traffic.
 *
 * <p>Usage:
 * <pre>{@code
 *   TimeLimiter limiter = new TimeLimiter();
 *
 *   // Async
 *   CompletableFuture<Balance> future = externalService.fetchBalanceAsync(acctId);
 *   Balance bal = limiter.decorateFuture("balance-fetch", Duration.ofSeconds(3), future);
 *
 *   // Sync
 *   Balance bal = limiter.executeSynchronous("balance-fetch",
 *       Duration.ofSeconds(3), () -> externalService.fetchBalance(acctId));
 * }</pre>
 */

public class TimeLimiter {

    private static final Logger log = LoggerFactory.getLogger(TimeLimiter.class);

    /** Shared scheduler for timeout cancellation tasks. */
    private final ScheduledExecutorService scheduler;

    /** Whether futures are cancelled when a timeout fires. */
    private final boolean cancelOnTimeout;

    /** Per-operation metrics keyed by operation name. */
    private final ConcurrentHashMap<String, OperationMetrics> metricsMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Configuration record
    // -------------------------------------------------------------------

    /**
     * Per-operation time limiter configuration.
     *
     * @param operationName   logical name for metrics and logging
     * @param timeout         maximum duration to wait for the result
     * @param cancelOnTimeout if {@code true}, the underlying future is cancelled on timeout
     */
    public record Config(
            String operationName,
            Duration timeout,
            boolean cancelOnTimeout
    ) {
        public Config {
            Objects.requireNonNull(operationName, "operationName");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Timeout must be positive, got " + timeout);
            }
        }

        public static Config of(String name, Duration timeout) {
            return new Config(name, timeout, true);
        }
    }

    // -------------------------------------------------------------------
    //  Per-operation metrics
    // -------------------------------------------------------------------

    public static final class OperationMetrics {

        private final String operationName;
        private final LongAdder totalCalls = new LongAdder();
        private final LongAdder successCount = new LongAdder();
        private final LongAdder timeoutCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LongAdder cancelledCount = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();

        public OperationMetrics(String operationName) {
            this.operationName = Objects.requireNonNull(operationName);
        }

        void recordSuccess(long latencyNanos) {
            totalCalls.increment();
            successCount.increment();
            totalLatencyNanos.add(latencyNanos);
        }

        void recordTimeout() {
            totalCalls.increment();
            timeoutCount.increment();
        }

        void recordError() {
            totalCalls.increment();
            errorCount.increment();
        }

        void recordCancelled() {
            cancelledCount.increment();
        }

        public String operationName() { return operationName; }
        public long totalCalls()      { return totalCalls.sum(); }
        public long successes()       { return successCount.sum(); }
        public long timeouts()        { return timeoutCount.sum(); }
        public long errors()          { return errorCount.sum(); }
        public long cancellations()   { return cancelledCount.sum(); }

        public Duration averageLatency() {
            long count = successCount.sum();
            return count == 0 ? Duration.ZERO : Duration.ofNanos(totalLatencyNanos.sum() / count);
        }

        /** Timeout rate as a percentage [0..100]. */
        public double timeoutRate() {
            long total = totalCalls.sum();
            return total == 0 ? 0.0 : (timeoutCount.sum() * 100.0) / total;
        }

        @Override
        public String toString() {
            return "OperationMetrics[%s: total=%d, success=%d, timeout=%d, error=%d, avgLatency=%s]"
                    .formatted(operationName, totalCalls(), successes(), timeouts(), errors(),
                               averageLatency());
        }
    }

    // -------------------------------------------------------------------
    //  Constructors
    // -------------------------------------------------------------------

    public TimeLimiter() {
        this(true);
    }

    public TimeLimiter(boolean cancelOnTimeout) {
        this.cancelOnTimeout = cancelOnTimeout;
        // JDK 17 cross-compat: Thread.ofPlatform() is JEP 444 (21+).
        final var tlCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "time-limiter-" + tlCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------
    //  Async future decoration
    // -------------------------------------------------------------------

    /**
     * Decorates a {@link CompletableFuture} with a timeout. If the future
     * does not complete within the specified duration, a
     * {@link TimeLimitExceededException} is raised and (optionally) the
     * future is cancelled.
     */
    public <T> T decorateFuture(String operationName, Duration timeout,
                                CompletableFuture<T> future) {
        return decorateFuture(operationName, timeout, future, null);
    }

    /**
     * Decorates with a fallback value returned when the timeout fires.
     */
    public <T> T decorateFuture(String operationName, Duration timeout,
                                CompletableFuture<T> future,
                                Supplier<T> fallback) {
        Objects.requireNonNull(operationName);
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(future);

        OperationMetrics opMetrics = metricsFor(operationName);
        long startNanos = System.nanoTime();

        /* Schedule a cancellation if cancelOnTimeout is enabled */
        Future<?> cancellationTask = null;
        if (cancelOnTimeout) {
            cancellationTask = scheduler.schedule(
                    () -> {
                        if (!future.isDone()) {
                            future.cancel(true);
                            opMetrics.recordCancelled();
                            log.warn("[TimeLimiter:{}] Cancelled future after timeout {}",
                                     operationName, timeout);
                        }
                    },
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        try {
            T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = System.nanoTime() - startNanos;
            opMetrics.recordSuccess(elapsed);
            return result;
        } catch (TimeoutException e) {
            opMetrics.recordTimeout();
            log.warn("[TimeLimiter:{}] Timed out after {}", operationName, timeout);
            if (fallback != null) {
                return fallback.get();
            }
            throw new TimeLimitExceededException(operationName, timeout);
        } catch (CancellationException e) {
            opMetrics.recordTimeout();
            if (fallback != null) {
                return fallback.get();
            }
            throw new TimeLimitExceededException(operationName, timeout);
        } catch (ExecutionException e) {
            opMetrics.recordError();
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("TimeLimiter execution failed for " + operationName, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            opMetrics.recordError();
            throw new RuntimeException("TimeLimiter interrupted for " + operationName, e);
        } finally {
            if (cancellationTask != null && !cancellationTask.isDone()) {
                cancellationTask.cancel(false);
            }
        }
    }

    // -------------------------------------------------------------------
    //  Synchronous execution with timeout
    // -------------------------------------------------------------------

    /**
     * Executes a synchronous supplier on a virtual thread with a timeout.
     * This is the preferred method for blocking I/O calls that do not
     * already return a {@link CompletableFuture}.
     */
    public <T> T executeSynchronous(String operationName, Duration timeout,
                                    Supplier<T> supplier) {
        return executeSynchronous(operationName, timeout, supplier, null);
    }

    public <T> T executeSynchronous(String operationName, Duration timeout,
                                    Supplier<T> supplier, Supplier<T> fallback) {
        // JDK 17 cross-compat: cached platform threads. Virtual threads
        // (JEP 444) would be ideal here but weren't finalized until 21.
        CompletableFuture<T> future = CompletableFuture.supplyAsync(supplier,
                Executors.newCachedThreadPool());
        return decorateFuture(operationName, timeout, future, fallback);
    }

    // -------------------------------------------------------------------
    //  Metrics access
    // -------------------------------------------------------------------

    public OperationMetrics metricsFor(String operationName) {
        return metricsMap.computeIfAbsent(operationName, OperationMetrics::new);
    }

    public ConcurrentHashMap<String, OperationMetrics> allMetrics() {
        return metricsMap;
    }

    // -------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------

    /** Graceful shutdown of the internal scheduler. */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------
    //  Exception
    // -------------------------------------------------------------------

    public static final class TimeLimitExceededException extends RuntimeException {

        private final String operationName;
        private final Duration timeout;

        public TimeLimitExceededException(String operationName, Duration timeout) {
            super("Time limit exceeded for [%s] after %s".formatted(operationName, timeout));
            this.operationName = operationName;
            this.timeout = timeout;
        }

        public String operationName() { return operationName; }
        public Duration timeout()     { return timeout; }
    }
}
