package com.omnibank.customerportal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Generates and propagates correlation IDs across the request lifecycle.
 * Integrates with SLF4J MDC so that every log statement within the request
 * scope automatically includes the correlation and trace context.
 *
 * <p>This filter must run <em>before</em> all other business-logic filters
 * (including {@link RateLimiter}) so that rate-limit log messages already
 * carry the correlation ID.
 *
 * <h3>Header propagation</h3>
 * <ul>
 *   <li>{@code X-Correlation-Id} — unique per logical business operation;
 *       generated if not present on the incoming request.</li>
 *   <li>{@code X-Request-Id} — unique per HTTP request; always generated
 *       server-side.</li>
 *   <li>{@code X-Trace-Id} — distributed tracing identifier (forwarded from
 *       upstream if present; otherwise generated).</li>
 *   <li>{@code X-Span-Id} — span within the trace; always generated.</li>
 *   <li>{@code X-Parent-Span-Id} — forwarded from upstream if present.</li>
 * </ul>
 *
 * <h3>MDC keys populated</h3>
 * {@code correlationId}, {@code requestId}, {@code traceId}, {@code spanId},
 * {@code parentSpanId}, {@code requestPath}, {@code requestMethod},
 * {@code clientIp}, {@code userAgent}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelation extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestCorrelation.class);

    // Incoming headers (case-insensitive matching handled by servlet container)
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_SPAN_ID = "X-Span-Id";
    public static final String HEADER_PARENT_SPAN_ID = "X-Parent-Span-Id";

    // MDC keys
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_PARENT_SPAN_ID = "parentSpanId";
    public static final String MDC_REQUEST_PATH = "requestPath";
    public static final String MDC_REQUEST_METHOD = "requestMethod";
    public static final String MDC_CLIENT_IP = "clientIp";
    public static final String MDC_USER_AGENT = "userAgent";
    public static final String MDC_REQUEST_START = "requestStart";

    private final Clock clock;

    public RequestCorrelation() {
        this(Clock.systemUTC());
    }

    public RequestCorrelation(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // -------------------------------------------------------------------
    //  Filter logic
    // -------------------------------------------------------------------

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Instant start = clock.instant();

        // Resolve or generate identifiers
        String correlationId = resolveOrGenerate(request, HEADER_CORRELATION_ID);
        String requestId = generateId(); // always unique per request
        String traceId = resolveOrGenerate(request, HEADER_TRACE_ID);
        String spanId = generateSpanId();
        String parentSpanId = request.getHeader(HEADER_PARENT_SPAN_ID);

        // Populate MDC
        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        if (parentSpanId != null) {
            MDC.put(MDC_PARENT_SPAN_ID, parentSpanId);
        }
        MDC.put(MDC_REQUEST_PATH, request.getRequestURI());
        MDC.put(MDC_REQUEST_METHOD, request.getMethod());
        MDC.put(MDC_CLIENT_IP, resolveClientIp(request));
        MDC.put(MDC_USER_AGENT, request.getHeader("User-Agent"));
        MDC.put(MDC_REQUEST_START, start.toString());

        // Set response headers so downstream services and clients can correlate
        response.setHeader(HEADER_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_REQUEST_ID, requestId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        response.setHeader(HEADER_SPAN_ID, spanId);
        if (parentSpanId != null) {
            response.setHeader(HEADER_PARENT_SPAN_ID, parentSpanId);
        }

        try {
            log.debug("Request started: {} {} correlationId={} traceId={}",
                    request.getMethod(), request.getRequestURI(), correlationId, traceId);

            chain.doFilter(request, response);
        } finally {
            Duration elapsed = Duration.between(start, clock.instant());
            log.info("Request completed: {} {} status={} duration={}ms correlationId={}",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), elapsed.toMillis(), correlationId);

            // Clean up MDC to prevent leaking into other requests on the same thread
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
            MDC.remove(MDC_PARENT_SPAN_ID);
            MDC.remove(MDC_REQUEST_PATH);
            MDC.remove(MDC_REQUEST_METHOD);
            MDC.remove(MDC_CLIENT_IP);
            MDC.remove(MDC_USER_AGENT);
            MDC.remove(MDC_REQUEST_START);
        }
    }

    // -------------------------------------------------------------------
    //  Trace context holder (for programmatic access)
    // -------------------------------------------------------------------

    /**
     * Immutable snapshot of the current request's trace context. Useful for
     * propagating correlation IDs to async tasks or outgoing HTTP calls.
     *
     * @param correlationId the business-level correlation ID
     * @param requestId     the per-request unique ID
     * @param traceId       distributed trace ID
     * @param spanId        current span ID
     * @param parentSpanId  parent span ID (may be null)
     */
    public record TraceContext(
            String correlationId,
            String requestId,
            String traceId,
            String spanId,
            String parentSpanId
    ) {
        /**
         * Captures the current trace context from MDC. Returns null if no
         * context is active (e.g. called outside a request scope).
         */
        public static TraceContext current() {
            String cid = MDC.get(MDC_CORRELATION_ID);
            if (cid == null) return null;
            return new TraceContext(
                    cid,
                    MDC.get(MDC_REQUEST_ID),
                    MDC.get(MDC_TRACE_ID),
                    MDC.get(MDC_SPAN_ID),
                    MDC.get(MDC_PARENT_SPAN_ID)
            );
        }

        /**
         * Restores this context into MDC. Call this when executing an async
         * task that was spawned from a request thread.
         */
        public void restoreToMdc() {
            MDC.put(MDC_CORRELATION_ID, correlationId);
            MDC.put(MDC_REQUEST_ID, requestId);
            MDC.put(MDC_TRACE_ID, traceId);
            MDC.put(MDC_SPAN_ID, spanId);
            if (parentSpanId != null) {
                MDC.put(MDC_PARENT_SPAN_ID, parentSpanId);
            }
        }

        /**
         * Clears this context from MDC. Call after the async task completes.
         */
        public void clearFromMdc() {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
            MDC.remove(MDC_PARENT_SPAN_ID);
        }
    }

    /**
     * Wraps a {@link Runnable} so that the current trace context is propagated
     * to the thread executing the task.
     */
    public static Runnable propagate(Runnable task) {
        TraceContext ctx = TraceContext.current();
        if (ctx == null) return task;
        return () -> {
            ctx.restoreToMdc();
            try {
                task.run();
            } finally {
                ctx.clearFromMdc();
            }
        };
    }

    /**
     * Wraps a {@link java.util.concurrent.Callable} so that the current trace
     * context is propagated to the thread executing the task.
     */
    public static <V> java.util.concurrent.Callable<V> propagate(java.util.concurrent.Callable<V> task) {
        TraceContext ctx = TraceContext.current();
        if (ctx == null) return task;
        return () -> {
            ctx.restoreToMdc();
            try {
                return task.call();
            } finally {
                ctx.clearFromMdc();
            }
        };
    }

    // -------------------------------------------------------------------
    //  Internals
    // -------------------------------------------------------------------

    private String resolveOrGenerate(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank() && isValidId(value)) {
            return value.trim();
        }
        return generateId();
    }

    private static String generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generates a shorter span ID (16 hex characters) to keep trace payloads
     * compact while still providing sufficient entropy.
     */
    private static String generateSpanId() {
        return Long.toHexString(UUID.randomUUID().getMostSignificantBits());
    }

    /**
     * Validates that an incoming ID looks reasonable (not an injection attempt).
     */
    private static boolean isValidId(String id) {
        if (id.length() > 128) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
