package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of named rate limiters supporting token-bucket and sliding-window
 * algorithms. Each limiter can enforce per-client, per-endpoint, and global
 * rate limits with configurable burst allowance.
 *
 * <p>Rate limit decisions include standard HTTP header values so that API
 * gateways can propagate them downstream:
 * <ul>
 *   <li>{@code X-RateLimit-Limit} — the rate limit ceiling</li>
 *   <li>{@code X-RateLimit-Remaining} — tokens remaining in the current window</li>
 *   <li>{@code X-RateLimit-Reset} — epoch second when the window resets</li>
 * </ul>
 *
 * <p>A {@link DistributedRateLimiterBackend} interface is provided for
 * multi-instance deployments that need coordinated rate limiting via
 * Redis or a similar shared store.
 */

public class RateLimiterRegistry {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterRegistry.class);

    private final ConcurrentHashMap<String, ManagedRateLimiter> limiters = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * @param name              logical limiter name (e.g. "ach-submission", "client:acme-corp")
     * @param algorithm         TOKEN_BUCKET or SLIDING_WINDOW
     * @param limitPerWindow    maximum allowed calls per window
     * @param windowDuration    time window length
     * @param burstCapacity     extra tokens beyond the steady-state limit
     * @param refillRate        tokens added per second (token-bucket only)
     */
    public record Config(
            String name,
            Algorithm algorithm,
            long limitPerWindow,
            Duration windowDuration,
            long burstCapacity,
            double refillRate
    ) {
        public Config {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(windowDuration, "windowDuration");
            if (limitPerWindow <= 0) throw new IllegalArgumentException("limitPerWindow must be > 0");
            if (burstCapacity < 0) throw new IllegalArgumentException("burstCapacity must be >= 0");
            if (refillRate < 0) throw new IllegalArgumentException("refillRate must be >= 0");
        }

        public static Config tokenBucket(String name, long limit, Duration window,
                                          long burst, double refillPerSec) {
            return new Config(name, Algorithm.TOKEN_BUCKET, limit, window, burst, refillPerSec);
        }

        public static Config slidingWindow(String name, long limit, Duration window) {
            return new Config(name, Algorithm.SLIDING_WINDOW, limit, window, 0, 0);
        }
    }

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW }

    // -------------------------------------------------------------------
    //  Rate limit decision
    // -------------------------------------------------------------------

    /**
     * Result of a rate-limit check, including standard HTTP headers.
     *
     * @param allowed           whether the request is permitted
     * @param limitHeader       X-RateLimit-Limit
     * @param remainingHeader   X-RateLimit-Remaining
     * @param resetEpochSecond  X-RateLimit-Reset (epoch seconds)
     * @param retryAfter        suggested Retry-After duration (only if not allowed)
     */
    public record RateLimitDecision(
            boolean allowed,
            long limitHeader,
            long remainingHeader,
            long resetEpochSecond,
            Duration retryAfter
    ) {
        public Map<String, String> toHeaders() {
            var headers = new java.util.LinkedHashMap<String, String>();
            headers.put("X-RateLimit-Limit", String.valueOf(limitHeader));
            headers.put("X-RateLimit-Remaining", String.valueOf(remainingHeader));
            headers.put("X-RateLimit-Reset", String.valueOf(resetEpochSecond));
            if (!allowed && retryAfter != null) {
                headers.put("Retry-After", String.valueOf(retryAfter.getSeconds()));
            }
            return Map.copyOf(headers);
        }
    }

    // -------------------------------------------------------------------
    //  Distributed backend interface
    // -------------------------------------------------------------------

    /**
     * SPI for coordinated rate limiting across multiple application instances.
     * Implementations typically use Redis INCR + EXPIRE or a Lua script.
     */
    public interface DistributedRateLimiterBackend {

        /**
         * Atomically attempts to consume a token for the given key.
         *
         * @param key       the rate-limit key (composite of client + endpoint + etc.)
         * @param limit     max tokens per window
         * @param window    window duration
         * @return the decision including remaining tokens
         */
        RateLimitDecision tryConsume(String key, long limit, Duration window);

        /** Health check for the distributed store. */
        boolean isHealthy();
    }

    // -------------------------------------------------------------------
    //  Metrics
    // -------------------------------------------------------------------

    public static final class RateLimiterMetrics {

        private final LongAdder allowed = new LongAdder();
        private final LongAdder rejected = new LongAdder();

        void recordAllowed()  { allowed.increment(); }
        void recordRejected() { rejected.increment(); }

        public long allowed()  { return allowed.sum(); }
        public long rejected() { return rejected.sum(); }

        public double rejectionRate() {
            long total = allowed.sum() + rejected.sum();
            return total == 0 ? 0.0 : (rejected.sum() * 100.0) / total;
        }

        @Override
        public String toString() {
            return "RateLimiterMetrics[allowed=%d, rejected=%d, rejectionRate=%.1f%%]"
                    .formatted(allowed(), rejected(), rejectionRate());
        }
    }

    // -------------------------------------------------------------------
    //  Token-bucket implementation
    // -------------------------------------------------------------------

    private static final class TokenBucketLimiter implements ManagedRateLimiter {

        private final Config config;
        private final RateLimiterMetrics metrics = new RateLimiterMetrics();
        private final AtomicLong tokens;
        private final AtomicLong lastRefillNanos;

        TokenBucketLimiter(Config config) {
            this.config = config;
            this.tokens = new AtomicLong(config.limitPerWindow() + config.burstCapacity());
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
        }

        @Override public String name()               { return config.name(); }
        @Override public Config config()              { return config; }
        @Override public RateLimiterMetrics metrics() { return metrics; }

        @Override
        public RateLimitDecision tryAcquire() {
            return tryAcquire(1);
        }

        @Override
        public RateLimitDecision tryAcquire(int permits) {
            refill();
            long currentTokens = tokens.get();
            if (currentTokens >= permits) {
                if (tokens.compareAndSet(currentTokens, currentTokens - permits)) {
                    metrics.recordAllowed();
                    return new RateLimitDecision(
                            true,
                            config.limitPerWindow(),
                            currentTokens - permits,
                            computeResetEpoch(),
                            Duration.ZERO);
                }
            }
            /* Contention or insufficient tokens: retry once after refill */
            refill();
            currentTokens = tokens.get();
            if (currentTokens >= permits && tokens.compareAndSet(currentTokens, currentTokens - permits)) {
                metrics.recordAllowed();
                return new RateLimitDecision(
                        true, config.limitPerWindow(),
                        currentTokens - permits, computeResetEpoch(), Duration.ZERO);
            }

            metrics.recordRejected();
            Duration retryAfter = Duration.ofMillis(
                    (long) (permits / Math.max(config.refillRate(), 0.001) * 1000));
            return new RateLimitDecision(
                    false, config.limitPerWindow(),
                    Math.max(0, tokens.get()), computeResetEpoch(), retryAfter);
        }

        private void refill() {
            long now = System.nanoTime();
            long last = lastRefillNanos.get();
            double elapsed = (now - last) / 1_000_000_000.0;
            long newTokens = (long) (elapsed * config.refillRate());
            if (newTokens > 0 && lastRefillNanos.compareAndSet(last, now)) {
                long maxTokens = config.limitPerWindow() + config.burstCapacity();
                tokens.getAndUpdate(cur -> Math.min(maxTokens, cur + newTokens));
            }
        }

        private long computeResetEpoch() {
            return Instant.now().plus(config.windowDuration()).getEpochSecond();
        }
    }

    // -------------------------------------------------------------------
    //  Sliding-window implementation
    // -------------------------------------------------------------------

    private static final class SlidingWindowLimiter implements ManagedRateLimiter {

        private final Config config;
        private final RateLimiterMetrics metrics = new RateLimiterMetrics();
        private final AtomicLong windowStart;
        private final AtomicLong counter;

        SlidingWindowLimiter(Config config) {
            this.config = config;
            this.windowStart = new AtomicLong(System.nanoTime());
            this.counter = new AtomicLong(0);
        }

        @Override public String name()               { return config.name(); }
        @Override public Config config()              { return config; }
        @Override public RateLimiterMetrics metrics() { return metrics; }

        @Override
        public RateLimitDecision tryAcquire() {
            return tryAcquire(1);
        }

        @Override
        public RateLimitDecision tryAcquire(int permits) {
            maybeResetWindow();
            long current = counter.get();
            if (current + permits <= config.limitPerWindow()) {
                if (counter.compareAndSet(current, current + permits)) {
                    metrics.recordAllowed();
                    long remaining = config.limitPerWindow() - current - permits;
                    return new RateLimitDecision(
                            true, config.limitPerWindow(), remaining,
                            computeResetEpoch(), Duration.ZERO);
                }
                /* Contention: retry once */
                current = counter.get();
                if (current + permits <= config.limitPerWindow()
                        && counter.compareAndSet(current, current + permits)) {
                    metrics.recordAllowed();
                    return new RateLimitDecision(
                            true, config.limitPerWindow(),
                            config.limitPerWindow() - current - permits,
                            computeResetEpoch(), Duration.ZERO);
                }
            }

            metrics.recordRejected();
            long windowElapsedNanos = System.nanoTime() - windowStart.get();
            long windowRemainingMs = config.windowDuration().toMillis()
                    - TimeUnit.NANOSECONDS.toMillis(windowElapsedNanos);
            return new RateLimitDecision(
                    false, config.limitPerWindow(), 0,
                    computeResetEpoch(), Duration.ofMillis(Math.max(0, windowRemainingMs)));
        }

        private void maybeResetWindow() {
            long now = System.nanoTime();
            long start = windowStart.get();
            if (now - start >= config.windowDuration().toNanos()) {
                if (windowStart.compareAndSet(start, now)) {
                    counter.set(0);
                }
            }
        }

        private long computeResetEpoch() {
            long elapsedNanos = System.nanoTime() - windowStart.get();
            long remainingNanos = config.windowDuration().toNanos() - elapsedNanos;
            return Instant.now().plusNanos(Math.max(0, remainingNanos)).getEpochSecond();
        }

        private static final java.util.concurrent.TimeUnit TimeUnit = java.util.concurrent.TimeUnit.NANOSECONDS;
    }

    // -------------------------------------------------------------------
    //  Managed limiter interface
    // -------------------------------------------------------------------

    public sealed interface ManagedRateLimiter
            permits TokenBucketLimiter, SlidingWindowLimiter {

        String name();
        Config config();
        RateLimiterMetrics metrics();
        RateLimitDecision tryAcquire();
        RateLimitDecision tryAcquire(int permits);
    }

    // -------------------------------------------------------------------
    //  Registry operations
    // -------------------------------------------------------------------

    public ManagedRateLimiter getOrCreate(Config config) {
        return limiters.computeIfAbsent(config.name(), name -> {
            log.info("Creating {} rate limiter [{}]: limit={}/{}",
                     config.algorithm(), name, config.limitPerWindow(), config.windowDuration());
            return switch (config.algorithm()) {
                case TOKEN_BUCKET    -> new TokenBucketLimiter(config);
                case SLIDING_WINDOW  -> new SlidingWindowLimiter(config);
            };
        });
    }

    public ManagedRateLimiter get(String name) {
        return limiters.get(name);
    }

    public Collection<String> registeredNames() {
        return limiters.keySet();
    }

    /** Creates a composite key for per-client, per-endpoint limiting. */
    public static String compositeKey(String clientId, String endpoint) {
        return clientId + "::" + endpoint;
    }

    // -------------------------------------------------------------------
    //  Exception
    // -------------------------------------------------------------------

    public static final class RateLimitExceededException extends RuntimeException {

        private final String limiterName;
        private final RateLimitDecision decision;

        public RateLimitExceededException(String limiterName, RateLimitDecision decision) {
            super("Rate limit exceeded for [%s]. Retry after %s"
                          .formatted(limiterName, decision.retryAfter()));
            this.limiterName = limiterName;
            this.decision = decision;
        }

        public String limiterName()        { return limiterName; }
        public RateLimitDecision decision() { return decision; }
    }
}
