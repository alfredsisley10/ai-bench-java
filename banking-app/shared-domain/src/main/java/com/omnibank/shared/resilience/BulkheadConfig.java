package com.omnibank.shared.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a single bulkhead instance. Supports both
 * semaphore-based (in-thread) and thread-pool-based (offloaded) isolation.
 *
 * <p>Use the {@link Builder} to create instances with validation:
 * <pre>{@code
 *   BulkheadConfig config = BulkheadConfig.builder("payment-gateway")
 *       .type(BulkheadType.SEMAPHORE)
 *       .maxConcurrentCalls(25)
 *       .maxWaitDuration(Duration.ofMillis(500))
 *       .fairness(true)
 *       .metricsCollectionInterval(Duration.ofSeconds(10))
 *       .build();
 * }</pre>
 *
 * @param name                      logical name for metrics tags and logging
 * @param type                      isolation strategy
 * @param maxConcurrentCalls        maximum number of concurrent executions
 * @param maxWaitDuration           how long a caller may block waiting for a permit
 * @param fair                      if {@code true}, permits are granted in FIFO order
 * @param maxQueueDepth             (thread-pool only) max tasks queued before rejection
 * @param coreThreadPoolSize        (thread-pool only) minimum threads kept alive
 * @param maxThreadPoolSize         (thread-pool only) ceiling for the pool
 * @param keepAliveTime             (thread-pool only) idle thread keep-alive
 * @param metricsCollectionInterval interval between metrics snapshots
 * @param useVirtualThreads         if {@code true}, the thread-pool uses virtual threads
 */
public record BulkheadConfig(
        String name,
        BulkheadType type,
        int maxConcurrentCalls,
        Duration maxWaitDuration,
        boolean fair,
        int maxQueueDepth,
        int coreThreadPoolSize,
        int maxThreadPoolSize,
        Duration keepAliveTime,
        Duration metricsCollectionInterval,
        boolean useVirtualThreads
) {
    /**
     * Isolation strategy determines whether the bulkhead constrains
     * concurrency via a semaphore (caller's thread) or via a dedicated
     * thread pool.
     */
    public enum BulkheadType {
        /**
         * Semaphore-based: the calling thread executes the protected code
         * but must first acquire a permit. Lower overhead, suitable for
         * CPU-bound or fast I/O operations.
         */
        SEMAPHORE,

        /**
         * Thread-pool-based: work is submitted to a dedicated pool,
         * completely isolating the caller's thread from downstream latency.
         * Better for long-running or blocking I/O but incurs context-switch
         * overhead.
         */
        THREAD_POOL
    }

    /* Compact constructor — validation */
    public BulkheadConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(maxWaitDuration, "maxWaitDuration");
        Objects.requireNonNull(keepAliveTime, "keepAliveTime");
        Objects.requireNonNull(metricsCollectionInterval, "metricsCollectionInterval");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Bulkhead name must not be blank");
        }
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException("maxConcurrentCalls must be > 0, got " + maxConcurrentCalls);
        }
        if (maxWaitDuration.isNegative()) {
            throw new IllegalArgumentException("maxWaitDuration must not be negative");
        }
        if (type == BulkheadType.THREAD_POOL) {
            if (maxQueueDepth < 0) {
                throw new IllegalArgumentException("maxQueueDepth must be >= 0 for THREAD_POOL bulkhead");
            }
            if (coreThreadPoolSize <= 0) {
                throw new IllegalArgumentException("coreThreadPoolSize must be > 0 for THREAD_POOL bulkhead");
            }
            if (maxThreadPoolSize < coreThreadPoolSize) {
                throw new IllegalArgumentException(
                        "maxThreadPoolSize (%d) must be >= coreThreadPoolSize (%d)"
                                .formatted(maxThreadPoolSize, coreThreadPoolSize));
            }
        }
    }

    // -------------------------------------------------------------------
    //  Defaults
    // -------------------------------------------------------------------

    private static final int DEFAULT_MAX_CONCURRENT = 25;
    private static final Duration DEFAULT_MAX_WAIT = Duration.ofMillis(500);
    private static final int DEFAULT_QUEUE_DEPTH = 100;
    private static final int DEFAULT_CORE_POOL = 10;
    private static final int DEFAULT_MAX_POOL = 25;
    private static final Duration DEFAULT_KEEP_ALIVE = Duration.ofSeconds(60);
    private static final Duration DEFAULT_METRICS_INTERVAL = Duration.ofSeconds(10);

    // -------------------------------------------------------------------
    //  Factory method
    // -------------------------------------------------------------------

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Convenience factory for a simple semaphore bulkhead with sensible
     * banking-grade defaults.
     */
    public static BulkheadConfig semaphore(String name, int maxConcurrent) {
        return builder(name)
                .type(BulkheadType.SEMAPHORE)
                .maxConcurrentCalls(maxConcurrent)
                .build();
    }

    /**
     * Convenience factory for a thread-pool bulkhead sized for a specific
     * downstream service.
     */
    public static BulkheadConfig threadPool(String name, int coreSize, int maxSize, int queueDepth) {
        return builder(name)
                .type(BulkheadType.THREAD_POOL)
                .coreThreadPoolSize(coreSize)
                .maxThreadPoolSize(maxSize)
                .maxQueueDepth(queueDepth)
                .maxConcurrentCalls(maxSize)
                .build();
    }

    // -------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------

    public static final class Builder {

        private final String name;
        private BulkheadType type = BulkheadType.SEMAPHORE;
        private int maxConcurrentCalls = DEFAULT_MAX_CONCURRENT;
        private Duration maxWaitDuration = DEFAULT_MAX_WAIT;
        private boolean fair = true;
        private int maxQueueDepth = DEFAULT_QUEUE_DEPTH;
        private int coreThreadPoolSize = DEFAULT_CORE_POOL;
        private int maxThreadPoolSize = DEFAULT_MAX_POOL;
        private Duration keepAliveTime = DEFAULT_KEEP_ALIVE;
        private Duration metricsCollectionInterval = DEFAULT_METRICS_INTERVAL;
        private boolean useVirtualThreads = false;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder type(BulkheadType type) {
            this.type = type;
            return this;
        }

        public Builder maxConcurrentCalls(int max) {
            this.maxConcurrentCalls = max;
            return this;
        }

        public Builder maxWaitDuration(Duration duration) {
            this.maxWaitDuration = duration;
            return this;
        }

        public Builder fairness(boolean fair) {
            this.fair = fair;
            return this;
        }

        public Builder maxQueueDepth(int depth) {
            this.maxQueueDepth = depth;
            return this;
        }

        public Builder coreThreadPoolSize(int size) {
            this.coreThreadPoolSize = size;
            return this;
        }

        public Builder maxThreadPoolSize(int size) {
            this.maxThreadPoolSize = size;
            return this;
        }

        public Builder keepAliveTime(Duration time) {
            this.keepAliveTime = time;
            return this;
        }

        public Builder metricsCollectionInterval(Duration interval) {
            this.metricsCollectionInterval = interval;
            return this;
        }

        public Builder useVirtualThreads(boolean use) {
            this.useVirtualThreads = use;
            return this;
        }

        public BulkheadConfig build() {
            return new BulkheadConfig(
                    name, type, maxConcurrentCalls, maxWaitDuration, fair,
                    maxQueueDepth, coreThreadPoolSize, maxThreadPoolSize,
                    keepAliveTime, metricsCollectionInterval, useVirtualThreads);
        }
    }
}
