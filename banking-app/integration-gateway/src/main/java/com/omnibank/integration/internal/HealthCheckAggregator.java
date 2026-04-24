package com.omnibank.integration.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.omnibank.shared.resilience.BulkheadRegistry;
import com.omnibank.shared.resilience.CircuitBreakerRegistry;
import com.omnibank.shared.resilience.RateLimiterRegistry;
import com.omnibank.shared.resilience.ResilienceMetrics;

/**
 * Aggregates health information from all external service circuit breakers,
 * bulkheads, and rate limiters into a composite health indicator suitable
 * for Spring Boot Actuator's {@code /actuator/health} endpoint.
 *
 * <p>In a large financial platform, knowing which downstream services are
 * degraded (HALF_OPEN) or completely unavailable (OPEN) is critical for
 * incident triage. This aggregator provides:
 * <ul>
 *   <li>Per-service health status derived from circuit breaker state</li>
 *   <li>Degradation level (HEALTHY, DEGRADED, CRITICAL, DOWN)</li>
 *   <li>Recommended actions for operations teams</li>
 *   <li>Bulkhead saturation warnings</li>
 *   <li>Rate limiter rejection rate alerts</li>
 * </ul>
 *
 * <p>Implements the Spring Actuator {@code HealthIndicator} contract by
 * convention (the actual interface dependency is kept optional so that
 * shared-domain does not pull in Spring Boot Actuator).
 */
public class HealthCheckAggregator {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckAggregator.class);

    private static final double BULKHEAD_WARN_THRESHOLD = 80.0;
    private static final double RATE_LIMIT_WARN_THRESHOLD = 10.0;

    private final CircuitBreakerRegistry cbRegistry;
    private final BulkheadRegistry bhRegistry;
    private final RateLimiterRegistry rlRegistry;
    private final ResilienceMetrics resilienceMetrics;

    /** Background polling for continuous health assessment. */
    private final ScheduledExecutorService healthPoller;
    private volatile CompositeHealthReport latestReport;

    /** External health check endpoints that can be registered dynamically. */
    private final ConcurrentHashMap<String, ExternalHealthCheck> externalChecks =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------
    //  Health status model
    // -------------------------------------------------------------------

    /**
     * Overall system degradation level.
     */
    public enum DegradationLevel {
        /** All external services are fully operational. */
        HEALTHY,
        /** Some services are in HALF_OPEN state or showing elevated errors. */
        DEGRADED,
        /** Multiple critical services are unhealthy; partial outage. */
        CRITICAL,
        /** A core service (e.g. FedACH, Fedwire) is completely unavailable. */
        DOWN
    }

    /**
     * Health status of a single external service.
     *
     * @param serviceName        logical name
     * @param circuitBreakerState current CB state (CLOSED / OPEN / HALF_OPEN)
     * @param failureRate         current failure rate percentage
     * @param bulkheadUtilisation bulkhead usage percentage (0 if no BH)
     * @param rateLimitRejectionRate rate limiter rejection percentage (0 if no RL)
     * @param degradationLevel    computed degradation level
     * @param recommendations     suggested actions for operations
     * @param lastChecked         when this status was computed
     */
    public record ServiceHealthStatus(
            String serviceName,
            String circuitBreakerState,
            double failureRate,
            double bulkheadUtilisation,
            double rateLimitRejectionRate,
            DegradationLevel degradationLevel,
            List<String> recommendations,
            Instant lastChecked
    ) {
        public ServiceHealthStatus {
            Objects.requireNonNull(serviceName);
            Objects.requireNonNull(circuitBreakerState);
            Objects.requireNonNull(degradationLevel);
            recommendations = List.copyOf(recommendations);
        }
    }

    /**
     * Composite health report across all external services.
     *
     * @param overallLevel     worst degradation level across all services
     * @param serviceStatuses  per-service health details
     * @param summaryMessage   human-readable summary
     * @param reportedAt       when this report was generated
     * @param healthyCount     number of fully healthy services
     * @param degradedCount    number of degraded services
     * @param criticalCount    number of critical / down services
     */
    public record CompositeHealthReport(
            DegradationLevel overallLevel,
            List<ServiceHealthStatus> serviceStatuses,
            String summaryMessage,
            Instant reportedAt,
            int healthyCount,
            int degradedCount,
            int criticalCount
    ) {
        public CompositeHealthReport {
            Objects.requireNonNull(overallLevel);
            serviceStatuses = List.copyOf(serviceStatuses);
            Objects.requireNonNull(summaryMessage);
            Objects.requireNonNull(reportedAt);
        }

        /** Spring Actuator convention: UP, DOWN, OUT_OF_SERVICE, UNKNOWN. */
        public String actuatorStatus() {
            return switch (overallLevel) {
                case HEALTHY  -> "UP";
                case DEGRADED -> "UP"; // degraded but still serving traffic
                case CRITICAL -> "OUT_OF_SERVICE";
                case DOWN     -> "DOWN";
            };
        }

        /**
         * Returns the report as a map suitable for Spring Actuator's
         * {@code Health.Builder#withDetails(Map)}.
         */
        public Map<String, Object> toActuatorDetails() {
            var details = new LinkedHashMap<String, Object>();
            details.put("status", actuatorStatus());
            details.put("degradationLevel", overallLevel.name());
            details.put("summary", summaryMessage);
            details.put("healthy", healthyCount);
            details.put("degraded", degradedCount);
            details.put("critical", criticalCount);

            var services = new LinkedHashMap<String, Object>();
            for (var svc : serviceStatuses) {
                var svcDetail = new LinkedHashMap<String, Object>();
                svcDetail.put("circuitBreaker", svc.circuitBreakerState());
                svcDetail.put("failureRate", "%.1f%%".formatted(svc.failureRate()));
                svcDetail.put("bulkheadUtilisation", "%.1f%%".formatted(svc.bulkheadUtilisation()));
                svcDetail.put("rateLimitRejectionRate", "%.1f%%".formatted(svc.rateLimitRejectionRate()));
                svcDetail.put("level", svc.degradationLevel().name());
                svcDetail.put("recommendations", svc.recommendations());
                services.put(svc.serviceName(), svcDetail);
            }
            details.put("services", services);
            return Collections.unmodifiableMap(details);
        }
    }

    // -------------------------------------------------------------------
    //  External health check registration
    // -------------------------------------------------------------------

    /**
     * Pluggable health check for services that expose their own health
     * endpoint (e.g. a correspondent bank's connectivity ping).
     */
    @FunctionalInterface
    public interface ExternalHealthCheck {
        /**
         * @return {@code true} if the external service is reachable
         */
        boolean check();
    }

    public void registerExternalCheck(String serviceName, ExternalHealthCheck check) {
        externalChecks.put(serviceName, check);
        log.info("[HealthCheckAggregator] Registered external health check for [{}]", serviceName);
    }

    // -------------------------------------------------------------------
    //  Constructor
    // -------------------------------------------------------------------

    public HealthCheckAggregator(CircuitBreakerRegistry cbRegistry,
                                  BulkheadRegistry bhRegistry,
                                  RateLimiterRegistry rlRegistry,
                                  ResilienceMetrics resilienceMetrics) {
        this.cbRegistry = Objects.requireNonNull(cbRegistry);
        this.bhRegistry = Objects.requireNonNull(bhRegistry);
        this.rlRegistry = Objects.requireNonNull(rlRegistry);
        this.resilienceMetrics = Objects.requireNonNull(resilienceMetrics);

        // JDK 17 cross-compat: Thread.ofPlatform() is JEP 444 (21+).
        this.healthPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-aggregator");
            t.setDaemon(true);
            return t;
        });
        this.healthPoller.scheduleAtFixedRate(
                () -> { try { this.latestReport = assess(); } catch (Exception ex) {
                    log.error("[HealthCheckAggregator] Error during health assessment", ex);
                }}, 5, 15, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------
    //  Health assessment
    // -------------------------------------------------------------------

    /**
     * Performs a full health assessment across all registered resilience
     * components and returns a composite report.
     */
    public CompositeHealthReport assess() {
        Collection<String> cbNames = cbRegistry.registeredNames();
        List<ServiceHealthStatus> statuses = new ArrayList<>();

        int healthyCount = 0, degradedCount = 0, criticalCount = 0;
        DegradationLevel worst = DegradationLevel.HEALTHY;

        for (String name : cbNames) {
            var cb = cbRegistry.<Object>get(name);
            if (cb == null) continue;

            String cbState = cb.currentState();
            double failureRate = cb.currentMetrics().failureRatePercent();
            if (failureRate < 0) failureRate = 0;

            /* Check bulkhead utilisation */
            double bhUtil = 0.0;
            var bh = bhRegistry.get(name);
            if (bh != null) {
                bhUtil = bh.metrics().utilisation(bh.config().maxConcurrentCalls());
            }

            /* Check rate limiter rejection rate */
            double rlRejectRate = 0.0;
            var rl = rlRegistry.get(name);
            if (rl != null) {
                rlRejectRate = rl.metrics().rejectionRate();
            }

            /* Determine degradation level */
            var recommendations = new ArrayList<String>();
            DegradationLevel level = computeServiceLevel(
                    cbState, failureRate, bhUtil, rlRejectRate, recommendations);

            statuses.add(new ServiceHealthStatus(
                    name, cbState, failureRate, bhUtil, rlRejectRate,
                    level, recommendations, Instant.now()));

            switch (level) {
                case HEALTHY  -> healthyCount++;
                case DEGRADED -> { degradedCount++; worst = mergeWorst(worst, level); }
                case CRITICAL, DOWN -> { criticalCount++; worst = mergeWorst(worst, level); }
            }
        }

        /* Run external health checks */
        for (var entry : externalChecks.entrySet()) {
            boolean healthy;
            try {
                healthy = entry.getValue().check();
            } catch (Exception ex) {
                healthy = false;
                log.warn("[HealthCheckAggregator] External check [{}] threw exception: {}",
                         entry.getKey(), ex.getMessage());
            }
            if (!healthy) {
                var recommendations = List.of(
                        "External service [%s] health check failed — verify connectivity"
                                .formatted(entry.getKey()));
                statuses.add(new ServiceHealthStatus(
                        entry.getKey(), "UNKNOWN", 100.0, 0.0, 0.0,
                        DegradationLevel.DOWN, recommendations, Instant.now()));
                criticalCount++;
                worst = mergeWorst(worst, DegradationLevel.DOWN);
            }
        }

        String summary = buildSummary(worst, healthyCount, degradedCount, criticalCount);

        var report = new CompositeHealthReport(
                worst, statuses, summary, Instant.now(),
                healthyCount, degradedCount, criticalCount);

        if (worst != DegradationLevel.HEALTHY) {
            log.warn("[HealthCheckAggregator] {}", summary);
        }

        return report;
    }

    /** Returns the most recently computed health report (may be null at startup). */
    public CompositeHealthReport latestReport() {
        return latestReport;
    }

    // -------------------------------------------------------------------
    //  Internal helpers
    // -------------------------------------------------------------------

    private DegradationLevel computeServiceLevel(String cbState, double failureRate,
                                                  double bhUtil, double rlRejectRate,
                                                  List<String> recommendations) {
        DegradationLevel level = DegradationLevel.HEALTHY;

        switch (cbState) {
            case "OPEN" -> {
                level = DegradationLevel.DOWN;
                recommendations.add("Circuit breaker is OPEN — service is unavailable. " +
                        "Investigate downstream service and consider force-close after remediation.");
            }
            case "HALF_OPEN" -> {
                level = DegradationLevel.DEGRADED;
                recommendations.add("Circuit breaker is HALF_OPEN — service is recovering. " +
                        "Monitor probe results.");
            }
            default -> {
                if (failureRate > 50) {
                    level = DegradationLevel.CRITICAL;
                    recommendations.add("Failure rate is %.1f%% — approaching circuit breaker threshold."
                            .formatted(failureRate));
                } else if (failureRate > 20) {
                    level = mergeWorst(level, DegradationLevel.DEGRADED);
                    recommendations.add("Elevated failure rate of %.1f%% — investigate upstream."
                            .formatted(failureRate));
                }
            }
        }

        if (bhUtil > BULKHEAD_WARN_THRESHOLD) {
            level = mergeWorst(level, DegradationLevel.DEGRADED);
            recommendations.add("Bulkhead utilisation at %.1f%% — risk of rejection. " +
                    "Consider scaling concurrency limit.".formatted(bhUtil));
        }

        if (rlRejectRate > RATE_LIMIT_WARN_THRESHOLD) {
            level = mergeWorst(level, DegradationLevel.DEGRADED);
            recommendations.add("Rate limiter rejecting %.1f%% of requests — " +
                    "review rate limit configuration.".formatted(rlRejectRate));
        }

        return level;
    }

    private static DegradationLevel mergeWorst(DegradationLevel a, DegradationLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private String buildSummary(DegradationLevel level, int healthy, int degraded, int critical) {
        return switch (level) {
            case HEALTHY  -> "All %d external services are healthy.".formatted(healthy);
            case DEGRADED -> "%d service(s) degraded, %d healthy, %d critical."
                    .formatted(degraded, healthy, critical);
            case CRITICAL -> "CRITICAL: %d service(s) in critical state. %d degraded, %d healthy."
                    .formatted(critical, degraded, healthy);
            case DOWN     -> "DOWN: %d service(s) completely unavailable. Immediate investigation required."
                    .formatted(critical);
        };
    }

    // -------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------

    public void shutdown() {
        healthPoller.shutdown();
        try {
            if (!healthPoller.awaitTermination(5, TimeUnit.SECONDS)) {
                healthPoller.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthPoller.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
