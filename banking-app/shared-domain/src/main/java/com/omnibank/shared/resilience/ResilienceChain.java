package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes multiple resilience components — circuit breaker, bulkhead, retry,
 * rate limiter, and time limiter — into a single execution chain using the
 * decorator pattern. Each component wraps the next, forming an onion-style
 * call stack.
 *
 * <p>The default ordering (outermost to innermost) mirrors what large-scale
 * financial platforms use:
 * <ol>
 *   <li><b>Rate Limiter</b> — shed excess load before doing any work</li>
 *   <li><b>Time Limiter</b> — cap total wall-clock time</li>
 *   <li><b>Circuit Breaker</b> — fail fast if downstream is unhealthy</li>
 *   <li><b>Bulkhead</b> — constrain concurrency to prevent cascade</li>
 *   <li><b>Retry</b> — retry transient failures within the bulkhead slot</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 *   ResilienceChain<PaymentResponse> chain = ResilienceChain.<PaymentResponse>builder("payment-submit")
 *       .circuitBreaker(registry.getOrCreate("payment-gw", Config.defaults()))
 *       .bulkhead(bulkheadRegistry.getOrCreate(BulkheadConfig.semaphore("payment-gw", 20)))
 *       .retryPolicy(RetryPolicy.builder("payment-gw").maxAttempts(3).build())
 *       .rateLimiter(rateLimiterRegistry.getOrCreate(tokenBucketConfig))
 *       .timeLimiter(timeLimiter, Duration.ofSeconds(5))
 *       .fallback(new FallbackStrategy.FailFastFallback<>("payment-submit"))
 *       .build();
 *
 *   PaymentResponse result = chain.execute(() -> gateway.submit(request));
 * }</pre>
 */
public final class ResilienceChain<T> {

    private static final Logger log = LoggerFactory.getLogger(ResilienceChain.class);

    private final String name;
    private final CircuitBreakerRegistry.ManagedCircuitBreaker<T> circuitBreaker;
    private final BulkheadRegistry.ManagedBulkhead bulkhead;
    private final RetryPolicy retryPolicy;
    private final RateLimiterRegistry.ManagedRateLimiter rateLimiter;
    private final TimeLimiter timeLimiter;
    private final Duration timeout;
    private final FallbackStrategy<T> fallback;
    private final ChainMetrics metrics;

    private ResilienceChain(Builder<T> builder) {
        this.name = builder.name;
        this.circuitBreaker = builder.circuitBreaker;
        this.bulkhead = builder.bulkhead;
        this.retryPolicy = builder.retryPolicy;
        this.rateLimiter = builder.rateLimiter;
        this.timeLimiter = builder.timeLimiter;
        this.timeout = builder.timeout;
        this.fallback = builder.fallback;
        this.metrics = new ChainMetrics();
    }

    // -------------------------------------------------------------------
    //  Chain-level metrics
    // -------------------------------------------------------------------

    public static final class ChainMetrics {

        private final LongAdder totalCalls = new LongAdder();
        private final LongAdder successes = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder fallbackInvocations = new LongAdder();
        private final LongAdder rateLimitRejections = new LongAdder();
        private final LongAdder circuitBreakerRejections = new LongAdder();
        private final LongAdder bulkheadRejections = new LongAdder();
        private final LongAdder timeouts = new LongAdder();

        void recordSuccess()               { totalCalls.increment(); successes.increment(); }
        void recordFailure()               { totalCalls.increment(); failures.increment(); }
        void recordFallback()              { fallbackInvocations.increment(); }
        void recordRateLimitRejection()    { rateLimitRejections.increment(); }
        void recordCircuitBreakerReject()  { circuitBreakerRejections.increment(); }
        void recordBulkheadRejection()     { bulkheadRejections.increment(); }
        void recordTimeout()               { timeouts.increment(); }

        public long totalCalls()               { return totalCalls.sum(); }
        public long successes()                { return successes.sum(); }
        public long failures()                 { return failures.sum(); }
        public long fallbackInvocations()      { return fallbackInvocations.sum(); }
        public long rateLimitRejections()      { return rateLimitRejections.sum(); }
        public long circuitBreakerRejections() { return circuitBreakerRejections.sum(); }
        public long bulkheadRejections()       { return bulkheadRejections.sum(); }
        public long timeouts()                 { return timeouts.sum(); }

        @Override
        public String toString() {
            return ("ChainMetrics[total=%d, success=%d, fail=%d, fallback=%d, " +
                    "rateLimitReject=%d, cbReject=%d, bhReject=%d, timeout=%d]")
                    .formatted(totalCalls(), successes(), failures(), fallbackInvocations(),
                               rateLimitRejections(), circuitBreakerRejections(),
                               bulkheadRejections(), timeouts());
        }
    }

    // -------------------------------------------------------------------
    //  Execution
    // -------------------------------------------------------------------

    public String name()           { return name; }
    public ChainMetrics metrics()  { return metrics; }

    /**
     * Executes the supplier through the full resilience chain. Components
     * that were not configured are skipped (pass-through).
     */
    public T execute(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        Supplier<T> decorated = supplier;

        /*
         * Build the decorator chain from innermost to outermost.
         * Execution order is outermost-first when the chain is invoked.
         */

        /* Layer 5 (innermost): Retry */
        if (retryPolicy != null) {
            Supplier<T> inner = decorated;
            decorated = () -> retryPolicy.execute(inner);
        }

        /* Layer 4: Bulkhead */
        if (bulkhead != null) {
            Supplier<T> inner = decorated;
            decorated = () -> {
                try {
                    return bulkhead.execute(inner);
                } catch (BulkheadRegistry.BulkheadRejectedException ex) {
                    metrics.recordBulkheadRejection();
                    throw ex;
                }
            };
        }

        /* Layer 3: Circuit Breaker */
        if (circuitBreaker != null) {
            Supplier<T> inner = decorated;
            decorated = () -> {
                try {
                    return circuitBreaker.execute(inner);
                } catch (CircuitBreakerRegistry.CircuitBreakerOpenException ex) {
                    metrics.recordCircuitBreakerReject();
                    throw ex;
                }
            };
        }

        /* Layer 2: Time Limiter */
        if (timeLimiter != null && timeout != null) {
            Supplier<T> inner = decorated;
            decorated = () -> {
                try {
                    return timeLimiter.executeSynchronous(name, timeout, inner);
                } catch (TimeLimiter.TimeLimitExceededException ex) {
                    metrics.recordTimeout();
                    throw ex;
                }
            };
        }

        /* Layer 1 (outermost): Rate Limiter */
        if (rateLimiter != null) {
            Supplier<T> inner = decorated;
            decorated = () -> {
                var decision = rateLimiter.tryAcquire();
                if (!decision.allowed()) {
                    metrics.recordRateLimitRejection();
                    throw new RateLimiterRegistry.RateLimitExceededException(
                            rateLimiter.name(), decision);
                }
                return inner.get();
            };
        }

        /* Execute the full chain */
        try {
            T result = decorated.get();
            metrics.recordSuccess();
            return result;
        } catch (Exception ex) {
            metrics.recordFailure();
            if (fallback != null) {
                metrics.recordFallback();
                log.debug("[ResilienceChain:{}] Primary path failed, invoking fallback: {}",
                          name, ex.getMessage());
                return fallback.execute(name, ex);
            }
            throw ex;
        }
    }

    /**
     * Async variant that runs the chain on a platform-thread cached pool
     * and returns a {@link CompletableFuture}. Would ideally use virtual
     * threads (JEP 444) but those require JDK 21; this library targets
     * JDK 17 for broad cross-compatibility.
     */
    public CompletableFuture<T> executeAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(
                () -> execute(supplier),
                Executors.newCachedThreadPool());
    }

    // -------------------------------------------------------------------
    //  Component accessors (for introspection / health)
    // -------------------------------------------------------------------

    public CircuitBreakerRegistry.ManagedCircuitBreaker<T> circuitBreaker() { return circuitBreaker; }
    public BulkheadRegistry.ManagedBulkhead bulkhead()                     { return bulkhead; }
    public RetryPolicy retryPolicy()                                       { return retryPolicy; }
    public RateLimiterRegistry.ManagedRateLimiter rateLimiter()            { return rateLimiter; }
    public FallbackStrategy<T> fallback()                                  { return fallback; }

    /** Lists which components are active in this chain. */
    public List<String> activeComponents() {
        var components = new ArrayList<String>();
        if (rateLimiter != null)    components.add("RateLimiter[" + rateLimiter.name() + "]");
        if (timeLimiter != null)    components.add("TimeLimiter[" + name + "/" + timeout + "]");
        if (circuitBreaker != null) components.add("CircuitBreaker[" + circuitBreaker.name() + "]");
        if (bulkhead != null)       components.add("Bulkhead[" + bulkhead.name() + "]");
        if (retryPolicy != null)    components.add("Retry[" + retryPolicy.name() + "]");
        if (fallback != null)       components.add("Fallback[" + fallback.strategyName() + "]");
        return Collections.unmodifiableList(components);
    }

    // -------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------

    public static <T> Builder<T> builder(String name) {
        return new Builder<>(name);
    }

    public static final class Builder<T> {

        private final String name;
        private CircuitBreakerRegistry.ManagedCircuitBreaker<T> circuitBreaker;
        private BulkheadRegistry.ManagedBulkhead bulkhead;
        private RetryPolicy retryPolicy;
        private RateLimiterRegistry.ManagedRateLimiter rateLimiter;
        private TimeLimiter timeLimiter;
        private Duration timeout;
        private FallbackStrategy<T> fallback;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder<T> circuitBreaker(CircuitBreakerRegistry.ManagedCircuitBreaker<T> cb) {
            this.circuitBreaker = cb;
            return this;
        }

        public Builder<T> bulkhead(BulkheadRegistry.ManagedBulkhead bh) {
            this.bulkhead = bh;
            return this;
        }

        public Builder<T> retryPolicy(RetryPolicy rp) {
            this.retryPolicy = rp;
            return this;
        }

        public Builder<T> rateLimiter(RateLimiterRegistry.ManagedRateLimiter rl) {
            this.rateLimiter = rl;
            return this;
        }

        public Builder<T> timeLimiter(TimeLimiter tl, Duration timeout) {
            this.timeLimiter = tl;
            this.timeout = timeout;
            return this;
        }

        public Builder<T> fallback(FallbackStrategy<T> fb) {
            this.fallback = fb;
            return this;
        }

        public ResilienceChain<T> build() {
            return new ResilienceChain<>(this);
        }
    }
}
