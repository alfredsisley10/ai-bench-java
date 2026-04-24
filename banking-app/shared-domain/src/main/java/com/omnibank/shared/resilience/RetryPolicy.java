package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configurable retry policy with pluggable backoff strategies, jitter,
 * retryable-exception predicates, and event listeners. Designed for
 * enterprise banking workloads where transient failures (network blips,
 * optimistic-lock collisions, rate-limit responses) are expected.
 *
 * <p>Usage:
 * <pre>{@code
 *   RetryPolicy policy = RetryPolicy.builder("ach-submission")
 *       .maxAttempts(4)
 *       .backoff(RetryPolicy.exponential(Duration.ofMillis(200), Duration.ofSeconds(5), 2.0))
 *       .jitter(0.25)
 *       .retryOn(ex -> ex instanceof java.net.SocketTimeoutException)
 *       .onRetry(evt -> log.warn("Retry #{} for {}", evt.attemptNumber(), evt.policyName()))
 *       .build();
 *
 *   String result = policy.execute(() -> achClient.submitBatch(file));
 * }</pre>
 */
public final class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final String name;
    private final int maxAttempts;
    private final BackoffStrategy backoff;
    private final double jitterFactor;
    private final Predicate<Throwable> retryPredicate;
    private final List<Consumer<RetryEvent>> retryListeners;
    private final RetryMetrics metrics;

    private RetryPolicy(Builder builder) {
        this.name = builder.name;
        this.maxAttempts = builder.maxAttempts;
        this.backoff = builder.backoff;
        this.jitterFactor = builder.jitterFactor;
        this.retryPredicate = builder.retryPredicate;
        this.retryListeners = List.copyOf(builder.retryListeners);
        this.metrics = new RetryMetrics();
    }

    // -------------------------------------------------------------------
    //  Backoff strategies
    // -------------------------------------------------------------------

    /**
     * Strategy that computes the delay before the next retry attempt.
     */
    @FunctionalInterface
    public interface BackoffStrategy {
        Duration delay(int attemptNumber);
    }

    /** Fixed delay between every attempt. */
    public static BackoffStrategy fixed(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        return attempt -> delay;
    }

    /**
     * Exponential backoff: {@code initialDelay * multiplier^(attempt - 1)},
     * capped at {@code maxDelay}.
     */
    public static BackoffStrategy exponential(Duration initialDelay,
                                              Duration maxDelay,
                                              double multiplier) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (multiplier <= 0) throw new IllegalArgumentException("multiplier must be > 0");
        return attempt -> {
            double delayMillis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1);
            long capped = Math.min((long) delayMillis, maxDelay.toMillis());
            return Duration.ofMillis(capped);
        };
    }

    /**
     * Decorrelated jitter backoff (AWS-style): {@code min(maxDelay, random_between(baseDelay, prevDelay * 3))}.
     * Reduces thundering-herd effects better than simple exponential + jitter.
     */
    public static BackoffStrategy decorrelatedJitter(Duration baseDelay, Duration maxDelay) {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        return new BackoffStrategy() {
            private volatile long previousDelayMs = baseDelay.toMillis();

            @Override
            public Duration delay(int attemptNumber) {
                long next = ThreadLocalRandom.current().nextLong(
                        baseDelay.toMillis(), previousDelayMs * 3 + 1);
                next = Math.min(next, maxDelay.toMillis());
                previousDelayMs = next;
                return Duration.ofMillis(next);
            }
        };
    }

    // -------------------------------------------------------------------
    //  Retry event
    // -------------------------------------------------------------------

    /**
     * Emitted on each retry attempt for observability.
     *
     * @param policyName     logical name of the retry policy
     * @param attemptNumber  1-based attempt number (the retry, not the initial call)
     * @param delay          how long we will wait before the next attempt
     * @param cause          the exception that triggered the retry
     * @param timestamp      when the event was created
     */
    public record RetryEvent(
            String policyName,
            int attemptNumber,
            Duration delay,
            Throwable cause,
            Instant timestamp
    ) {
        public RetryEvent {
            Objects.requireNonNull(policyName);
            Objects.requireNonNull(delay);
            Objects.requireNonNull(cause);
            Objects.requireNonNull(timestamp);
        }
    }

    // -------------------------------------------------------------------
    //  Metrics
    // -------------------------------------------------------------------

    public static final class RetryMetrics {

        private final LongAdder totalCalls = new LongAdder();
        private final LongAdder successWithoutRetry = new LongAdder();
        private final LongAdder successAfterRetry = new LongAdder();
        private final LongAdder exhaustedRetries = new LongAdder();
        private final LongAdder totalRetryAttempts = new LongAdder();

        public void recordSuccessNoRetry()   { totalCalls.increment(); successWithoutRetry.increment(); }
        public void recordSuccessWithRetry()  { totalCalls.increment(); successAfterRetry.increment(); }
        public void recordExhausted()         { totalCalls.increment(); exhaustedRetries.increment(); }
        public void recordRetryAttempt()      { totalRetryAttempts.increment(); }

        public long totalCalls()           { return totalCalls.sum(); }
        public long successWithoutRetry()  { return successWithoutRetry.sum(); }
        public long successAfterRetry()    { return successAfterRetry.sum(); }
        public long exhaustedRetries()     { return exhaustedRetries.sum(); }
        public long totalRetryAttempts()   { return totalRetryAttempts.sum(); }

        /** Retry rate: fraction of calls that required at least one retry. */
        public double retryRate() {
            long total = totalCalls.sum();
            return total == 0 ? 0.0 : (double) successAfterRetry.sum() / total;
        }

        @Override
        public String toString() {
            return "RetryMetrics[calls=%d, successNoRetry=%d, successWithRetry=%d, exhausted=%d, retryAttempts=%d]"
                    .formatted(totalCalls(), successWithoutRetry(), successAfterRetry(),
                               exhaustedRetries(), totalRetryAttempts());
        }
    }

    // -------------------------------------------------------------------
    //  Execution
    // -------------------------------------------------------------------

    public String name()          { return name; }
    public int maxAttempts()      { return maxAttempts; }
    public RetryMetrics metrics() { return metrics; }

    /**
     * Executes the supplier with retry according to the configured policy.
     *
     * @throws RetriesExhaustedException if all attempts fail
     */
    public <T> T execute(Supplier<T> supplier) {
        Throwable lastException = null;
        int retryCount = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = supplier.get();
                if (attempt == 1) {
                    metrics.recordSuccessNoRetry();
                } else {
                    metrics.recordSuccessWithRetry();
                }
                return result;
            } catch (Throwable ex) {
                lastException = ex;

                if (attempt == maxAttempts || !retryPredicate.test(ex)) {
                    break;
                }

                retryCount++;
                Duration rawDelay = backoff.delay(retryCount);
                Duration jitteredDelay = applyJitter(rawDelay);

                metrics.recordRetryAttempt();

                var event = new RetryEvent(name, retryCount, jitteredDelay, ex, Instant.now());
                retryListeners.forEach(listener -> listener.accept(event));

                log.debug("[Retry:{}] Attempt {} failed ({}), retrying in {}",
                          name, attempt, ex.getClass().getSimpleName(), jitteredDelay);

                sleep(jitteredDelay);
            }
        }

        metrics.recordExhausted();
        log.warn("[Retry:{}] All {} attempts exhausted. Last error: {}",
                 name, maxAttempts, lastException != null ? lastException.getMessage() : "unknown");
        throw new RetriesExhaustedException(name, maxAttempts, lastException);
    }

    /**
     * Executes a void operation (e.g. fire-and-forget notification).
     */
    public void executeRunnable(Runnable runnable) {
        execute(() -> { runnable.run(); return null; });
    }

    private Duration applyJitter(Duration base) {
        if (jitterFactor <= 0) return base;
        double jitter = base.toMillis() * jitterFactor;
        long delta = (long) (ThreadLocalRandom.current().nextDouble() * jitter);
        boolean subtract = ThreadLocalRandom.current().nextBoolean();
        long adjusted = subtract
                ? Math.max(0, base.toMillis() - delta)
                : base.toMillis() + delta;
        return Duration.ofMillis(adjusted);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry sleep interrupted for policy " + name, e);
        }
    }

    // -------------------------------------------------------------------
    //  Builder
    // -------------------------------------------------------------------

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private int maxAttempts = 3;
        private BackoffStrategy backoff = fixed(Duration.ofMillis(500));
        private double jitterFactor = 0.15;
        private Predicate<Throwable> retryPredicate = ex -> true;
        private final List<Consumer<RetryEvent>> retryListeners = new ArrayList<>();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder maxAttempts(int max) {
            if (max < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            this.maxAttempts = max;
            return this;
        }

        public Builder backoff(BackoffStrategy strategy) {
            this.backoff = Objects.requireNonNull(strategy);
            return this;
        }

        public Builder jitter(double factor) {
            if (factor < 0 || factor > 1) throw new IllegalArgumentException("jitter factor must be in [0, 1]");
            this.jitterFactor = factor;
            return this;
        }

        public Builder retryOn(Predicate<Throwable> predicate) {
            this.retryPredicate = Objects.requireNonNull(predicate);
            return this;
        }

        /** Adds multiple exception types as retryable via instanceof check. */
        @SafeVarargs
        public final Builder retryOnExceptions(Class<? extends Throwable>... types) {
            this.retryPredicate = ex -> {
                for (var type : types) {
                    if (type.isInstance(ex)) return true;
                }
                return false;
            };
            return this;
        }

        public Builder onRetry(Consumer<RetryEvent> listener) {
            this.retryListeners.add(Objects.requireNonNull(listener));
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }

    // -------------------------------------------------------------------
    //  Exception
    // -------------------------------------------------------------------

    public static final class RetriesExhaustedException extends RuntimeException {

        private final String policyName;
        private final int attemptsExhausted;

        public RetriesExhaustedException(String policyName, int attempts, Throwable lastCause) {
            super("Retry policy [%s] exhausted after %d attempts".formatted(policyName, attempts),
                  lastCause);
            this.policyName = policyName;
            this.attemptsExhausted = attempts;
        }

        public String policyName()      { return policyName; }
        public int attemptsExhausted()   { return attemptsExhausted; }
    }
}
