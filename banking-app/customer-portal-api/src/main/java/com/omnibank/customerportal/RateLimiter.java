package com.omnibank.customerportal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token-bucket rate limiter implemented as a Spring servlet filter. Each
 * client (identified by API key or IP address) gets an independent bucket
 * with configurable capacity and refill rate.
 *
 * <p>Features:
 * <ul>
 *   <li>Per-endpoint limit overrides</li>
 *   <li>Burst allowance via initial bucket capacity</li>
 *   <li>Sliding-window token refill</li>
 *   <li>RFC 6585 {@code 429 Too Many Requests} response with
 *       {@code Retry-After} header</li>
 *   <li>Standard rate-limit headers (X-RateLimit-*)</li>
 * </ul>
 */
@Component
public class RateLimiter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * Rate limit configuration for a single endpoint or the default tier.
     *
     * @param maxTokens     bucket capacity (burst limit)
     * @param refillRate    tokens added per second
     * @param refillAmount  tokens added per refill tick
     */
    public record LimitConfig(
            long maxTokens,
            double refillRate,
            long refillAmount
    ) {
        public LimitConfig {
            if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
            if (refillRate <= 0) throw new IllegalArgumentException("refillRate must be > 0");
            if (refillAmount <= 0) throw new IllegalArgumentException("refillAmount must be > 0");
        }

        /** Standard customer-portal rate: 100 req/min with burst to 150. */
        public static LimitConfig customerDefault() {
            return new LimitConfig(150, 100.0 / 60.0, 1);
        }

        /** Tighter limit for payment endpoints: 20 req/min. */
        public static LimitConfig paymentEndpoint() {
            return new LimitConfig(30, 20.0 / 60.0, 1);
        }

        /** Generous limit for read-only balance checks: 300 req/min. */
        public static LimitConfig balanceEndpoint() {
            return new LimitConfig(350, 300.0 / 60.0, 1);
        }
    }

    // -------------------------------------------------------------------
    //  Token bucket
    // -------------------------------------------------------------------

    /**
     * Thread-safe token bucket using atomic CAS updates.
     */
    static final class TokenBucket {

        private record State(double tokens, Instant lastRefill) {}

        private final LimitConfig config;
        private final Clock clock;
        private final AtomicReference<State> state;

        TokenBucket(LimitConfig config, Clock clock) {
            this.config = config;
            this.clock = clock;
            this.state = new AtomicReference<>(
                    new State(config.maxTokens(), clock.instant()));
        }

        /**
         * Attempts to consume one token.
         *
         * @return a {@link ConsumeResult} indicating success or the wait time
         */
        ConsumeResult tryConsume() {
            while (true) {
                State current = state.get();
                Instant now = clock.instant();
                double elapsed = Duration.between(current.lastRefill(), now).toMillis() / 1000.0;
                double refilled = Math.min(
                        config.maxTokens(),
                        current.tokens() + elapsed * config.refillRate());

                if (refilled < 1.0) {
                    // Not enough tokens — calculate retry-after
                    double deficit = 1.0 - refilled;
                    long retryAfterMs = (long) Math.ceil(deficit / config.refillRate() * 1000);
                    return new ConsumeResult(false, (long) refilled, config.maxTokens(),
                            Duration.ofMillis(retryAfterMs));
                }

                State next = new State(refilled - 1.0, now);
                if (state.compareAndSet(current, next)) {
                    return new ConsumeResult(true, (long) (refilled - 1.0),
                            config.maxTokens(), Duration.ZERO);
                }
                // CAS failed — another thread modified the state; retry
            }
        }

        /** Returns the current token count without consuming. */
        long remainingTokens() {
            State current = state.get();
            double elapsed = Duration.between(current.lastRefill(), clock.instant()).toMillis() / 1000.0;
            return (long) Math.min(config.maxTokens(),
                    current.tokens() + elapsed * config.refillRate());
        }
    }

    /**
     * Result of a token consumption attempt.
     *
     * @param allowed      whether the request is permitted
     * @param remaining    tokens remaining after this request
     * @param limit        total bucket capacity
     * @param retryAfter   suggested wait time if denied
     */
    public record ConsumeResult(
            boolean allowed,
            long remaining,
            long limit,
            Duration retryAfter
    ) {}

    // -------------------------------------------------------------------
    //  State
    // -------------------------------------------------------------------

    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, LimitConfig> endpointOverrides;
    private final LimitConfig defaultConfig;

    public RateLimiter() {
        this(Clock.systemUTC(), LimitConfig.customerDefault(), Map.of(
                "/api/v1/payments", LimitConfig.paymentEndpoint(),
                "/api/v2/payments", LimitConfig.paymentEndpoint(),
                "/api/v1/accounts/*/balance", LimitConfig.balanceEndpoint(),
                "/api/v2/accounts/*/balance", LimitConfig.balanceEndpoint()
        ));
    }

    public RateLimiter(Clock clock, LimitConfig defaultConfig,
                       Map<String, LimitConfig> endpointOverrides) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig");
        this.endpointOverrides = Map.copyOf(endpointOverrides);
    }

    // -------------------------------------------------------------------
    //  Filter logic
    // -------------------------------------------------------------------

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientId = resolveClientId(request);
        String path = request.getRequestURI();
        LimitConfig config = resolveConfig(path);

        String bucketKey = clientId + ":" + normalizePath(path);
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey,
                k -> new TokenBucket(config, clock));

        ConsumeResult result = bucket.tryConsume();

        // Always set rate-limit headers (draft-ietf-httpapi-ratelimit-headers)
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, result.remaining())));
        response.setHeader("X-RateLimit-Reset",
                String.valueOf(Instant.now().plusSeconds(60).getEpochSecond()));

        if (!result.allowed()) {
            log.warn("Rate limit exceeded for client={} path={} retryAfter={}ms",
                    clientId, path, result.retryAfter().toMillis());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After",
                    String.valueOf(result.retryAfter().toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {
                      "error": "RATE_LIMIT_EXCEEDED",
                      "message": "Too many requests. Please retry after %d seconds.",
                      "retryAfterSeconds": %d
                    }
                    """.formatted(result.retryAfter().toSeconds(), result.retryAfter().toSeconds()));
            return;
        }

        chain.doFilter(request, response);
    }

    // -------------------------------------------------------------------
    //  Client identification
    // -------------------------------------------------------------------

    /**
     * Resolves the client identifier. Prefers the API key header, then
     * falls back to the originating IP.
     */
    private String resolveClientId(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /**
     * Matches the request path against configured overrides.
     */
    private LimitConfig resolveConfig(String path) {
        for (Map.Entry<String, LimitConfig> entry : endpointOverrides.entrySet()) {
            if (pathMatches(path, entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultConfig;
    }

    /**
     * Simple path matching with wildcard (*) support.
     */
    private static boolean pathMatches(String path, String pattern) {
        if (!pattern.contains("*")) {
            return path.startsWith(pattern);
        }
        String regex = pattern.replace("*", "[^/]+");
        return path.matches(regex);
    }

    // Normalizes variable path segments so bucket keys group correctly.
    private static String normalizePath(String path) {
        return path.replaceAll("/\\d+", "/*")
                   .replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/*");
    }

    /** Visible-for-testing: number of active buckets. */
    int activeBucketCount() {
        return buckets.size();
    }

    /** Visible-for-testing: clear all buckets (e.g. between tests). */
    void reset() {
        buckets.clear();
    }
}
