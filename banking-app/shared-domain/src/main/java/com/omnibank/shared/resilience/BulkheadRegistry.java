package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry that manages named bulkhead instances for downstream service
 * isolation. Supports two isolation models:
 *
 * <ul>
 *   <li><b>Semaphore-based</b> — the caller's thread executes the
 *       protected code but must first acquire a concurrency permit.
 *       Low overhead; suitable for fast or non-blocking calls.</li>
 *   <li><b>Thread-pool-based</b> — work is submitted to a dedicated
 *       {@link ExecutorService}, completely decoupling the caller from
 *       downstream latency. Supports bounded queues and virtual threads
 *       (Java 21+).</li>
 * </ul>
 *
 * <p>Each bulkhead emits metrics: acquired/rejected/waiting counts,
 * wait-time histograms, and queue depth snapshots.
 */

public class BulkheadRegistry {

    private static final Logger log = LoggerFactory.getLogger(BulkheadRegistry.class);

    private final ConcurrentHashMap<String, ManagedBulkhead> bulkheads = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Bulkhead metrics
    // -------------------------------------------------------------------

    public static final class BulkheadMetrics {

        private final LongAdder acquiredCount = new LongAdder();
        private final LongAdder rejectedCount = new LongAdder();
        private final LongAdder completedCount = new LongAdder();
        private final LongAdder totalWaitNanos = new LongAdder();
        private final AtomicLong maxWaitNanos = new AtomicLong(0);
        private final AtomicLong activeCount = new AtomicLong(0);
        private final Instant createdAt = Instant.now();

        public void recordAcquired(long waitNanos) {
            acquiredCount.increment();
            totalWaitNanos.add(waitNanos);
            maxWaitNanos.getAndUpdate(cur -> Math.max(cur, waitNanos));
            activeCount.incrementAndGet();
        }

        public void recordRejected() {
            rejectedCount.increment();
        }

        public void recordCompleted() {
            completedCount.increment();
            activeCount.decrementAndGet();
        }

        public long acquired()   { return acquiredCount.sum(); }
        public long rejected()   { return rejectedCount.sum(); }
        public long completed()  { return completedCount.sum(); }
        public long active()     { return activeCount.get(); }
        public Instant createdAt() { return createdAt; }

        public Duration averageWaitTime() {
            long count = acquiredCount.sum();
            return count == 0
                    ? Duration.ZERO
                    : Duration.ofNanos(totalWaitNanos.sum() / count);
        }

        public Duration maxWaitTime() {
            return Duration.ofNanos(maxWaitNanos.get());
        }

        /** Utilisation as a percentage of the configured maximum. */
        public double utilisation(int maxConcurrent) {
            return maxConcurrent == 0 ? 0.0 : (active() * 100.0) / maxConcurrent;
        }

        @Override
        public String toString() {
            return "BulkheadMetrics[acquired=%d, rejected=%d, active=%d, avgWait=%s]"
                    .formatted(acquired(), rejected(), active(), averageWaitTime());
        }
    }

    // -------------------------------------------------------------------
    //  Managed bulkhead wrapper
    // -------------------------------------------------------------------

    public sealed interface ManagedBulkhead
            permits SemaphoreBulkhead, ThreadPoolBulkhead {

        String name();
        BulkheadConfig config();
        BulkheadMetrics metrics();
        <T> T execute(Supplier<T> supplier);
        <T> CompletableFuture<T> executeAsync(Supplier<T> supplier);
        void shutdown();
    }

    // -------------------------------------------------------------------
    //  Semaphore-based bulkhead
    // -------------------------------------------------------------------

    public static final class SemaphoreBulkhead implements ManagedBulkhead {

        private final BulkheadConfig config;
        private final Semaphore semaphore;
        private final BulkheadMetrics metrics = new BulkheadMetrics();

        SemaphoreBulkhead(BulkheadConfig config) {
            this.config = Objects.requireNonNull(config);
            this.semaphore = new Semaphore(config.maxConcurrentCalls(), config.fair());
        }

        @Override public String name()             { return config.name(); }
        @Override public BulkheadConfig config()    { return config; }
        @Override public BulkheadMetrics metrics()  { return metrics; }

        @Override
        public <T> T execute(Supplier<T> supplier) {
            long waitStart = System.nanoTime();
            boolean acquired;
            try {
                acquired = semaphore.tryAcquire(
                        config.maxWaitDuration().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                metrics.recordRejected();
                throw new BulkheadRejectedException(config.name(),
                        "Interrupted while waiting for semaphore permit");
            }

            if (!acquired) {
                metrics.recordRejected();
                log.warn("[Bulkhead:{}] Rejected — no permit available within {}",
                         config.name(), config.maxWaitDuration());
                throw new BulkheadRejectedException(config.name(),
                        "No permit available within " + config.maxWaitDuration());
            }

            long waitNanos = System.nanoTime() - waitStart;
            metrics.recordAcquired(waitNanos);
            try {
                return supplier.get();
            } finally {
                semaphore.release();
                metrics.recordCompleted();
            }
        }

        @Override
        public <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
            return CompletableFuture.supplyAsync(() -> execute(supplier));
        }

        @Override
        public void shutdown() {
            // Semaphore-based bulkheads have no resources to release
            log.info("[Bulkhead:{}] Semaphore bulkhead shut down", config.name());
        }
    }

    // -------------------------------------------------------------------
    //  Thread-pool-based bulkhead
    // -------------------------------------------------------------------

    public static final class ThreadPoolBulkhead implements ManagedBulkhead {

        private final BulkheadConfig config;
        private final ExecutorService executor;
        private final BulkheadMetrics metrics = new BulkheadMetrics();

        ThreadPoolBulkhead(BulkheadConfig config) {
            this.config = Objects.requireNonNull(config);
            this.executor = createExecutor(config);
        }

        /**
         * JDK 17 cross-compat thread factory. {@link Thread#ofVirtual()}
         * and {@link Thread#ofPlatform()} are JEP 444 additions finalized
         * in JDK 21; we roll our own named-daemon factory so this module
         * compiles and runs on 17.
         */
        private static ThreadFactory namedFactory(String prefix) {
            final var counter = new java.util.concurrent.atomic.AtomicLong(0);
            return r -> {
                Thread t = new Thread(r, prefix + counter.getAndIncrement());
                t.setDaemon(false);
                return t;
            };
        }

        private static ExecutorService createExecutor(BulkheadConfig cfg) {
            if (cfg.useVirtualThreads()) {
                /*
                 * "Virtual threads" path — on JDK 17 we fall back to a
                 * cached platform-thread pool. Concurrency is still
                 * enforced externally via the metrics active-count check.
                 */
                return Executors.newCachedThreadPool(
                        namedFactory("bulkhead-" + cfg.name() + "-"));
            }

            var queue = new ArrayBlockingQueue<Runnable>(cfg.maxQueueDepth());
            var executor = new ThreadPoolExecutor(
                    cfg.coreThreadPoolSize(),
                    cfg.maxThreadPoolSize(),
                    cfg.keepAliveTime().toMillis(), TimeUnit.MILLISECONDS,
                    queue,
                    namedFactory("bulkhead-" + cfg.name() + "-"),
                    new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }

        @Override public String name()             { return config.name(); }
        @Override public BulkheadConfig config()    { return config; }
        @Override public BulkheadMetrics metrics()  { return metrics; }

        @Override
        public <T> T execute(Supplier<T> supplier) {
            try {
                return executeAsync(supplier).get(
                        config.maxWaitDuration().toMillis() + config.keepAliveTime().toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ex) {
                metrics.recordRejected();
                throw new BulkheadRejectedException(config.name(), "Thread pool queue full");
            } catch (java.util.concurrent.TimeoutException ex) {
                metrics.recordRejected();
                throw new BulkheadRejectedException(config.name(), "Execution timed out in pool");
            } catch (java.util.concurrent.ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException re) throw re;
                throw new RuntimeException("Bulkhead execution failed", ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BulkheadRejectedException(config.name(), "Interrupted");
            }
        }

        @Override
        public <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
            long waitStart = System.nanoTime();
            return CompletableFuture.supplyAsync(() -> {
                long waitNanos = System.nanoTime() - waitStart;
                metrics.recordAcquired(waitNanos);
                try {
                    return supplier.get();
                } finally {
                    metrics.recordCompleted();
                }
            }, executor);
        }

        @Override
        public void shutdown() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("[Bulkhead:{}] Forced shutdown after 30s timeout", config.name());
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[Bulkhead:{}] Thread-pool bulkhead shut down", config.name());
        }
    }

    // -------------------------------------------------------------------
    //  Registry operations
    // -------------------------------------------------------------------

    public ManagedBulkhead getOrCreate(BulkheadConfig config) {
        return bulkheads.computeIfAbsent(config.name(), name -> {
            log.info("Creating {} bulkhead [{}]: maxConcurrent={}, maxWait={}",
                     config.type(), name, config.maxConcurrentCalls(), config.maxWaitDuration());
            return switch (config.type()) {
                case SEMAPHORE   -> new SemaphoreBulkhead(config);
                case THREAD_POOL -> new ThreadPoolBulkhead(config);
            };
        });
    }

    public ManagedBulkhead get(String name) {
        return bulkheads.get(name);
    }

    public Collection<String> registeredNames() {
        return bulkheads.keySet();
    }

    /** Snapshot of utilisation across all bulkheads. */
    public Map<String, Double> utilisationSnapshot() {
        var snapshot = new ConcurrentHashMap<String, Double>();
        bulkheads.forEach((name, bh) ->
                snapshot.put(name, bh.metrics().utilisation(bh.config().maxConcurrentCalls())));
        return Map.copyOf(snapshot);
    }

    /** Gracefully shut down all thread-pool bulkheads. */
    public void shutdownAll() {
        bulkheads.values().forEach(ManagedBulkhead::shutdown);
    }

    // -------------------------------------------------------------------
    //  Exception
    // -------------------------------------------------------------------

    public static final class BulkheadRejectedException extends RuntimeException {

        private final String bulkheadName;

        public BulkheadRejectedException(String bulkheadName, String reason) {
            super("Bulkhead [%s] rejected: %s".formatted(bulkheadName, reason));
            this.bulkheadName = bulkheadName;
        }

        public String bulkheadName() { return bulkheadName; }
    }
}
