package com.omnibank.adminconsole;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rate limiter tailored for the admin console API. Differs from the
 * customer-facing {@code RateLimiter} in several important ways:
 *
 * <ul>
 *   <li><b>IP allowlisting</b> — requests from allowlisted CIDR ranges or
 *       specific IPs bypass rate limiting entirely (e.g. internal monitoring
 *       probes, load-balancer health checks).</li>
 *   <li><b>Service-account elevation</b> — authenticated service accounts
 *       receive significantly higher limits than interactive admin users.</li>
 *   <li><b>Separate thresholds</b> — admin operations are lower-volume but
 *       higher-privilege, so limits are tighter by default with explicit
 *       escalation paths.</li>
 *   <li><b>Audit integration</b> — rate-limit violations for admin endpoints
 *       are logged at WARN level for security review.</li>
 * </ul>
 *
 * <p>Uses the same token-bucket algorithm as the customer portal rate limiter
 * but with independent configuration.
 */
@Component
public class AdminRateLimiter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminRateLimiter.class);

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * Token-bucket parameters.
     *
     * @param maxTokens   maximum tokens (burst capacity)
     * @param refillRate  tokens added per second
     */
    public record BucketConfig(long maxTokens, double refillRate) {
        public BucketConfig {
            if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be > 0");
            if (refillRate <= 0) throw new IllegalArgumentException("refillRate must be > 0");
        }

        /** Default for interactive admin users: 60 req/min. */
        public static BucketConfig adminUserDefault() {
            return new BucketConfig(80, 60.0 / 60.0);
        }

        /** Elevated limit for service accounts: 600 req/min. */
        public static BucketConfig serviceAccountDefault() {
            return new BucketConfig(800, 600.0 / 60.0);
        }

        /** Tight limit for sensitive operations (user mgmt, config changes): 10 req/min. */
        public static BucketConfig sensitiveOperationDefault() {
            return new BucketConfig(15, 10.0 / 60.0);
        }
    }

    /**
     * Principal classification for rate-limit tier assignment.
     */
    public enum PrincipalType {
        /** Human admin user authenticated via SSO/MFA. */
        ADMIN_USER,
        /** Automated service account (CI/CD, monitoring, batch jobs). */
        SERVICE_ACCOUNT,
        /** Unrecognised or unauthenticated — treated most restrictively. */
        UNKNOWN
    }

    // -------------------------------------------------------------------
    //  Token bucket (same algorithm, separate implementation for independence)
    // -------------------------------------------------------------------

    static final class Bucket {

        private record State(double tokens, Instant lastRefill) {}

        private final BucketConfig config;
        private final Clock clock;
        private final AtomicReference<State> state;

        Bucket(BucketConfig config, Clock clock) {
            this.config = config;
            this.clock = clock;
            this.state = new AtomicReference<>(
                    new State(config.maxTokens(), clock.instant()));
        }

        /**
         * Tries to consume one token.
         *
         * @return remaining tokens if allowed, or -1 if denied (with retryAfter
         *         in the second element)
         */
        ConsumeOutcome tryConsume() {
            while (true) {
                State current = state.get();
                Instant now = clock.instant();
                double elapsedSec = Duration.between(current.lastRefill(), now).toMillis() / 1000.0;
                double refilled = Math.min(config.maxTokens(),
                        current.tokens() + elapsedSec * config.refillRate());

                if (refilled < 1.0) {
                    double deficit = 1.0 - refilled;
                    long retryMs = (long) Math.ceil(deficit / config.refillRate() * 1000);
                    return new ConsumeOutcome(false, 0, config.maxTokens(), Duration.ofMillis(retryMs));
                }

                State next = new State(refilled - 1.0, now);
                if (state.compareAndSet(current, next)) {
                    return new ConsumeOutcome(true, (long) (refilled - 1.0),
                            config.maxTokens(), Duration.ZERO);
                }
            }
        }
    }

    record ConsumeOutcome(boolean allowed, long remaining, long limit, Duration retryAfter) {}

    // -------------------------------------------------------------------
    //  State
    // -------------------------------------------------------------------

    private final Clock clock;
    private final BucketConfig adminUserConfig;
    private final BucketConfig serviceAccountConfig;
    private final BucketConfig sensitiveConfig;
    private final Set<String> allowlistedIps;
    private final Set<String> allowlistedCidrs;
    private final Set<String> sensitivePathPrefixes;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public AdminRateLimiter() {
        this(Clock.systemUTC(),
             BucketConfig.adminUserDefault(),
             BucketConfig.serviceAccountDefault(),
             BucketConfig.sensitiveOperationDefault(),
             Set.of("127.0.0.1", "::1"),
             Set.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"),
             Set.of("/admin/api/v1/users", "/admin/api/v1/config",
                    "/admin/api/v1/security", "/admin/api/v1/compliance"));
    }

    public AdminRateLimiter(Clock clock,
                            BucketConfig adminUserConfig,
                            BucketConfig serviceAccountConfig,
                            BucketConfig sensitiveConfig,
                            Set<String> allowlistedIps,
                            Set<String> allowlistedCidrs,
                            Set<String> sensitivePathPrefixes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.adminUserConfig = Objects.requireNonNull(adminUserConfig, "adminUserConfig");
        this.serviceAccountConfig = Objects.requireNonNull(serviceAccountConfig, "serviceAccountConfig");
        this.sensitiveConfig = Objects.requireNonNull(sensitiveConfig, "sensitiveConfig");
        this.allowlistedIps = Set.copyOf(allowlistedIps);
        this.allowlistedCidrs = Set.copyOf(allowlistedCidrs);
        this.sensitivePathPrefixes = Set.copyOf(sensitivePathPrefixes);
    }

    // -------------------------------------------------------------------
    //  Filter logic
    // -------------------------------------------------------------------

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientIp = resolveClientIp(request);

        // Allowlisted IPs bypass rate limiting
        if (isAllowlisted(clientIp)) {
            log.trace("Allowlisted IP {} — bypassing rate limit", clientIp);
            chain.doFilter(request, response);
            return;
        }

        PrincipalType principalType = classifyPrincipal(request);
        String path = request.getRequestURI();
        BucketConfig config = selectConfig(principalType, path);

        String bucketKey = buildBucketKey(clientIp, principalType, path);
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> new Bucket(config, clock));
        ConsumeOutcome outcome = bucket.tryConsume();

        // Standard rate-limit headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(outcome.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, outcome.remaining())));

        if (!outcome.allowed()) {
            log.warn("Admin rate limit exceeded: ip={} principal={} path={} retryAfter={}ms",
                    clientIp, principalType, path, outcome.retryAfter().toMillis());

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(outcome.retryAfter().toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {
                      "error": "ADMIN_RATE_LIMIT_EXCEEDED",
                      "message": "Administrative API rate limit exceeded. Retry after %d seconds.",
                      "retryAfterSeconds": %d,
                      "principalType": "%s"
                    }
                    """.formatted(outcome.retryAfter().toSeconds(),
                    outcome.retryAfter().toSeconds(),
                    principalType.name()));
            return;
        }

        chain.doFilter(request, response);
    }

    // -------------------------------------------------------------------
    //  Principal classification
    // -------------------------------------------------------------------

    /**
     * Classifies the requester based on authentication headers. In production
     * this would inspect the JWT claims or Spring Security context.
     */
    private PrincipalType classifyPrincipal(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            return PrincipalType.UNKNOWN;
        }

        // Service accounts use a specific header or JWT claim
        String serviceAccountHeader = request.getHeader("X-Service-Account");
        if (serviceAccountHeader != null && !serviceAccountHeader.isBlank()) {
            return PrincipalType.SERVICE_ACCOUNT;
        }

        return PrincipalType.ADMIN_USER;
    }

    /**
     * Selects the bucket configuration based on principal type and request
     * path sensitivity.
     */
    private BucketConfig selectConfig(PrincipalType principalType, String path) {
        // Sensitive paths always use the tighter config
        if (isSensitivePath(path)) {
            return sensitiveConfig;
        }

        return switch (principalType) {
            case SERVICE_ACCOUNT -> serviceAccountConfig;
            case ADMIN_USER -> adminUserConfig;
            case UNKNOWN -> adminUserConfig; // most restrictive non-sensitive
        };
    }

    // -------------------------------------------------------------------
    //  IP allowlisting
    // -------------------------------------------------------------------

    /**
     * Checks if the given IP is allowlisted (exact match or CIDR range).
     */
    private boolean isAllowlisted(String ip) {
        if (allowlistedIps.contains(ip)) {
            return true;
        }
        for (String cidr : allowlistedCidrs) {
            if (ipMatchesCidr(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simple CIDR matching for IPv4. For production, use a dedicated library
     * like commons-net or Guava InetAddresses.
     */
    static boolean ipMatchesCidr(String ip, String cidr) {
        String[] cidrParts = cidr.split("/");
        if (cidrParts.length != 2) return false;

        long ipLong = ipToLong(ip);
        long cidrIpLong = ipToLong(cidrParts[0]);
        if (ipLong == -1 || cidrIpLong == -1) return false;

        int prefixLength = Integer.parseInt(cidrParts[1]);
        long mask = prefixLength == 0 ? 0 : -(1L << (32 - prefixLength));
        return (ipLong & mask) == (cidrIpLong & mask);
    }

    private static long ipToLong(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) return -1;
        try {
            long result = 0;
            for (String octet : octets) {
                int val = Integer.parseInt(octet);
                if (val < 0 || val > 255) return -1;
                result = (result << 8) | val;
            }
            return result;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // -------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------

    private boolean isSensitivePath(String path) {
        for (String prefix : sensitivePathPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String buildBucketKey(String ip, PrincipalType type, String path) {
        String normalizedPath = path.replaceAll("/[0-9a-f-]{36}", "/*")
                                    .replaceAll("/\\d+", "/*");
        return ip + ":" + type.name() + ":" + normalizedPath;
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Visible-for-testing. */
    int activeBucketCount() {
        return buckets.size();
    }

    /** Visible-for-testing. */
    void reset() {
        buckets.clear();
    }
}
