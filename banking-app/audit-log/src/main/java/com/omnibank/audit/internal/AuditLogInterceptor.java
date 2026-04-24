package com.omnibank.audit.internal;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring AOP interceptor that automatically captures audit entries for methods
 * annotated with {@link Audited}.
 *
 * <p>Captures:
 * <ul>
 *   <li>Method arguments (with parameter names when compiled with {@code -parameters})</li>
 *   <li>Return value (or exception if the method fails)</li>
 *   <li>Execution timing</li>
 *   <li>MDC correlation ID and actor context</li>
 * </ul>
 *
 * <p>The interceptor runs at {@link Order} 100, meaning it sits outside
 * transaction boundaries (typically at Order 200) so that the audit record
 * is created even if the transaction rolls back.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Audited(action = "TRANSFER_FUNDS", category = AuditDocument.Category.FINANCIAL,
 *          resourceType = "Payment")
 * public PaymentResult transfer(TransferRequest request) { ... }
 * }</pre>
 */
@Aspect
@Order(100)
public class AuditLogInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditLogInterceptor.class);

    // MDC keys expected to be populated by the authentication layer
    private static final String MDC_PRINCIPAL_ID = "principalId";
    private static final String MDC_PRINCIPAL_TYPE = "principalType";
    private static final String MDC_DISPLAY_NAME = "displayName";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_SESSION_ID = "sessionId";
    private static final String MDC_AUTH_METHOD = "authMethod";
    private static final String MDC_CHANNEL = "channel";
    private static final String MDC_CLIENT_IP = "clientIp";
    private static final String MDC_USER_AGENT = "userAgent";

    // Maximum length for serialised argument values to prevent enormous audit records
    private static final int MAX_ARG_VALUE_LENGTH = 2048;

    // -------------------------------------------------------------------
    //  Annotation
    // -------------------------------------------------------------------

    /**
     * Marks a method for automatic audit logging.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Audited {

        /** The business action name (e.g. "OPEN_ACCOUNT", "APPROVE_LOAN"). */
        String action();

        /** Audit category for retention policy mapping. */
        AuditDocument.Category category() default AuditDocument.Category.OPERATIONAL;

        /** The type of resource being acted upon (e.g. "Account", "Payment"). */
        String resourceType() default "";

        /**
         * SpEL expression that resolves the resource ID from method arguments.
         * For example: {@code "#request.accountNumber"}.
         * If empty, the interceptor attempts to extract it from the first argument.
         */
        String resourceIdExpression() default "";

        /** Whether to include argument values in the audit record. */
        boolean captureArgs() default true;

        /** Whether to include the return value in the audit record. */
        boolean captureResult() default true;

        /**
         * Target collection. Defaults to TRANSACTIONS.
         */
        AuditEventStore.CollectionName collection() default AuditEventStore.CollectionName.TRANSACTIONS;
    }

    // -------------------------------------------------------------------
    //  Dependencies
    // -------------------------------------------------------------------

    private final AuditEventStore store;
    private final Clock clock;

    public AuditLogInterceptor(AuditEventStore store) {
        this(store, Clock.systemUTC());
    }

    public AuditLogInterceptor(AuditEventStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // -------------------------------------------------------------------
    //  AOP advice
    // -------------------------------------------------------------------

    @Around("@annotation(com.omnibank.audit.internal.AuditLogInterceptor.Audited)")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Audited audited = method.getAnnotation(Audited.class);

        if (audited == null) {
            return pjp.proceed();
        }

        Instant start = clock.instant();
        Object result = null;
        Throwable thrown = null;

        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            try {
                recordAudit(audited, method, pjp.getArgs(), result, thrown, start);
            } catch (Exception e) {
                // Never let audit recording failure propagate to the caller
                log.error("Failed to record audit entry for {}.{}: {}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(), e.getMessage(), e);
            }
        }
    }

    // -------------------------------------------------------------------
    //  Audit document construction
    // -------------------------------------------------------------------

    private void recordAudit(Audited audited,
                             Method method,
                             Object[] args,
                             Object result,
                             Throwable thrown,
                             Instant start) {
        Duration elapsed = Duration.between(start, clock.instant());

        AuditDocument.Actor actor = buildActor();
        AuditDocument.Resource resource = buildResource(audited, method, args);
        AuditDocument.SessionInfo session = buildSessionInfo();
        AuditDocument.DeviceInfo device = buildDeviceInfo();
        AuditDocument.Outcome outcome = thrown != null
                ? AuditDocument.Outcome.FAILURE
                : AuditDocument.Outcome.SUCCESS;

        AuditDocument.Builder builder = AuditDocument.builder()
                .id(UUID.randomUUID())
                .timestamp(start)
                .action(audited.action())
                .category(audited.category())
                .outcome(outcome)
                .actor(actor)
                .resource(resource)
                .sessionInfo(session)
                .deviceInfo(device)
                .durationMs(elapsed.toMillis());

        if (thrown != null) {
            builder.errorMessage(truncate(thrown.getMessage(), MAX_ARG_VALUE_LENGTH));
        }

        // Capture method arguments as metadata
        if (audited.captureArgs() && args != null && args.length > 0) {
            Map<String, String> argMap = captureArguments(method, args);
            argMap.forEach(builder::metadata);
        }

        // Capture return value
        if (audited.captureResult() && result != null && thrown == null) {
            builder.metadata("returnValue", truncate(result.toString(), MAX_ARG_VALUE_LENGTH));
        }

        builder.metadata("method", method.getDeclaringClass().getSimpleName() + "." + method.getName());
        builder.metadata("durationMs", String.valueOf(elapsed.toMillis()));

        AuditDocument doc = builder.build();

        // Fire-and-forget async store to avoid adding latency to the business operation
        store.storeAsync(audited.collection(), doc)
                .exceptionally(ex -> {
                    log.error("Async audit store failed for {}: {}",
                            audited.action(), ex.getMessage());
                    return null;
                });
    }

    // -------------------------------------------------------------------
    //  Context extraction from MDC
    // -------------------------------------------------------------------

    private AuditDocument.Actor buildActor() {
        String principalId = mdcOrDefault(MDC_PRINCIPAL_ID, "anonymous");
        String principalType = mdcOrDefault(MDC_PRINCIPAL_TYPE, "UNKNOWN");
        String displayName = MDC.get(MDC_DISPLAY_NAME);
        return new AuditDocument.Actor(principalId, principalType, displayName, null);
    }

    private AuditDocument.Resource buildResource(Audited audited, Method method, Object[] args) {
        String resourceType = audited.resourceType().isEmpty()
                ? method.getDeclaringClass().getSimpleName()
                : audited.resourceType();
        String resourceId = extractResourceId(audited, args);
        return new AuditDocument.Resource(resourceType, resourceId, null);
    }

    private AuditDocument.SessionInfo buildSessionInfo() {
        String sessionId = MDC.get(MDC_SESSION_ID);
        String correlationId = mdcOrDefault(MDC_CORRELATION_ID, UUID.randomUUID().toString());
        String authMethod = MDC.get(MDC_AUTH_METHOD);
        String channel = MDC.get(MDC_CHANNEL);
        return new AuditDocument.SessionInfo(sessionId, correlationId, authMethod, channel);
    }

    private AuditDocument.DeviceInfo buildDeviceInfo() {
        String ip = MDC.get(MDC_CLIENT_IP);
        String ua = MDC.get(MDC_USER_AGENT);
        if (ip == null && ua == null) return null;
        return new AuditDocument.DeviceInfo(ip, ua, null, null, null, null, null);
    }

    // -------------------------------------------------------------------
    //  Argument capture
    // -------------------------------------------------------------------

    private Map<String, String> captureArguments(Method method, Object[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        Parameter[] params = method.getParameters();
        for (int i = 0; i < Math.min(params.length, args.length); i++) {
            String name = params[i].isNamePresent()
                    ? params[i].getName()
                    : "arg" + i;
            String value = args[i] != null
                    ? truncate(args[i].toString(), MAX_ARG_VALUE_LENGTH)
                    : "null";
            result.put("arg." + name, value);
        }
        return result;
    }

    private String extractResourceId(Audited audited, Object[] args) {
        // Simple extraction: use the first argument's toString if no expression is given
        if (!audited.resourceIdExpression().isEmpty()) {
            // In a full implementation this would use Spring's SpEL evaluator.
            // For now, return the expression itself as a placeholder.
            return audited.resourceIdExpression();
        }
        if (args != null && args.length > 0 && args[0] != null) {
            return truncate(args[0].toString(), 256);
        }
        return "unknown";
    }

    // -------------------------------------------------------------------
    //  Utilities
    // -------------------------------------------------------------------

    private static String mdcOrDefault(String key, String defaultValue) {
        String val = MDC.get(key);
        return val != null ? val : defaultValue;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
