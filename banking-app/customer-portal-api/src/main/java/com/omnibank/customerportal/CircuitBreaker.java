package com.omnibank.customerportal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit breaker for downstream service calls, implementing the standard
 * three-state model:
 *
 * <pre>
 *   CLOSED  --[failure threshold]--> OPEN
 *   OPEN    --[reset timeout]------> HALF_OPEN
 *   HALF_OPEN --[probe succeeds]---> CLOSED
 *   HALF_OPEN --[probe fails]------> OPEN
 * </pre>
 *
 * <p>Designed for use around HTTP client calls, gRPC stubs, or any supplier
 * that can fail transiently. Supports configurable failure thresholds,
 * reset timeouts, and fallback strategies.
 *
 * <p>Thread-safe. Uses atomic references for state transitions.
 *
 * @param <T> the return type of the protected operation
 */
public final class CircuitBreaker<T> {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    // -------------------------------------------------------------------
    //  States (sealed for exhaustive matching)
    // -------------------------------------------------------------------

    /**
     * Circuit breaker states.
     */
    public enum State {
        /** Normal operation — all requests pass through. */
        CLOSED,
        /** Failure threshold exceeded — requests are short-circuited. */
        OPEN,
        /** Probing — a single request is allowed through to test recovery. */
        HALF_OPEN
    }

    // -------------------------------------------------------------------
    //  Configuration
    // -------------------------------------------------------------------

    /**
     * Circuit breaker configuration.
     *
     * @param name                  human-readable name for logging/metrics
     * @param failureThreshold      consecutive failures before opening
     * @param successThresholdInHalfOpen consecutive successes in HALF_OPEN to close
     * @param resetTimeout          how long OPEN stays open before transitioning to HALF_OPEN
     * @param slowCallThreshold     duration beyond which a call is considered slow
     * @param slowCallRateThreshold percentage of slow calls that triggers opening (0.0 - 1.0)
     */
    public record Config(
            String name,
            int failureThreshold,
            int successThresholdInHalfOpen,
            Duration resetTimeout,
            Duration slowCallThreshold,
            double slowCallRateThreshold
    ) {
        public Config {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(resetTimeout, "resetTimeout");
            Objects.requireNonNull(slowCallThreshold, "slowCallThreshold");
            if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
            if (successThresholdInHalfOpen < 1) throw new IllegalArgumentException("successThresholdInHalfOpen must be >= 1");
            if (slowCallRateThreshold < 0 || slowCallRateThreshold > 1) {
                throw new IllegalArgumentException("slowCallRateThreshold must be in [0.0, 1.0]");
            }
        }

        /** Reasonable defaults for inter-service calls within a banking platform. */
        public static Config defaults(String name) {
            return new Config(
                    name,
                    5,        // open after 5 consecutive failures
                    2,        // close after 2 consecutive successes in half-open
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(5),
                    0.5
            );
        }
    }

    // -------------------------------------------------------------------
    //  Fallback strategies
    // -------------------------------------------------------------------

    /**
     * Strategy invoked when the circuit is OPEN and a request is rejected.
     */
    @FunctionalInterface
    public interface FallbackStrategy<T> {
        /**
         * @param name          circuit breaker name
         * @param lastFailure   the most recent exception that caused the circuit to open
         * @return              a fallback value
         */
        T execute(String name, Throwable lastFailure);
    }

    // -------------------------------------------------------------------
    //  Internal mutable state
    // -------------------------------------------------------------------

    private record InternalState(
            State state,
            int consecutiveFailures,
            int consecutiveSuccessesInHalfOpen,
            Instant openedAt,
            Throwable lastFailure,
            long totalRequests,
            long totalFailures,
            long totalSlowCalls
    ) {
        InternalState withState(State newState) {
            return new InternalState(newState, consecutiveFailures,
                    consecutiveSuccessesInHalfOpen, openedAt, lastFailure,
                    totalRequests, totalFailures, totalSlowCalls);
        }

        InternalState recordFailure(Throwable t) {
            return new InternalState(state, consecutiveFailures + 1, 0,
                    openedAt, t, totalRequests + 1, totalFailures + 1, totalSlowCalls);
        }

        InternalState recordSuccess() {
            return new InternalState(state, 0,
                    consecutiveSuccessesInHalfOpen + 1, openedAt, lastFailure,
                    totalRequests + 1, totalFailures, totalSlowCalls);
        }

        InternalState recordSlowCall() {
            return new InternalState(state, consecutiveFailures,
                    consecutiveSuccessesInHalfOpen, openedAt, lastFailure,
                    totalRequests, totalFailures, totalSlowCalls + 1);
        }

        InternalState markOpened(Instant now) {
            return new InternalState(State.OPEN, consecutiveFailures,
                    0, now, lastFailure,
                    totalRequests, totalFailures, totalSlowCalls);
        }
    }

    // -------------------------------------------------------------------
    //  Instance fields
    // -------------------------------------------------------------------

    private final Config config;
    private final Clock clock;
    private final FallbackStrategy<T> fallback;
    private final AtomicReference<InternalState> internalState;
    private final AtomicInteger halfOpenProbes = new AtomicInteger(0);

    // -------------------------------------------------------------------
    //  Construction
    // -------------------------------------------------------------------

    public CircuitBreaker(Config config, FallbackStrategy<T> fallback) {
        this(config, fallback, Clock.systemUTC());
    }

    public CircuitBreaker(Config config, FallbackStrategy<T> fallback, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.internalState = new AtomicReference<>(new InternalState(
                State.CLOSED, 0, 0, Instant.MIN, null, 0, 0, 0));
    }

    // -------------------------------------------------------------------
    //  Core execution
    // -------------------------------------------------------------------

    /**
     * Executes the given supplier through the circuit breaker.
     *
     * <p>In CLOSED state, the call passes through. In OPEN state, the
     * fallback is invoked immediately. In HALF_OPEN, a single probe is
     * allowed through.
     *
     * @param supplier the operation to protect
     * @return the result from the supplier or the fallback
     */
    public T execute(Supplier<T> supplier) {
        InternalState current = internalState.get();
        return switch (current.state()) {
            case CLOSED -> executeClosed(supplier);
            case OPEN -> executeOpen(supplier, current);
            case HALF_OPEN -> executeHalfOpen(supplier);
        };
    }

    private T executeClosed(Supplier<T> supplier) {
        Instant callStart = clock.instant();
        try {
            T result = supplier.get();
            onSuccess(callStart);
            return result;
        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    private T executeOpen(Supplier<T> supplier, InternalState current) {
        // Check if reset timeout has elapsed -> transition to HALF_OPEN
        if (Duration.between(current.openedAt(), clock.instant())
                .compareTo(config.resetTimeout()) >= 0) {
            log.info("[{}] Reset timeout elapsed, transitioning OPEN -> HALF_OPEN", config.name());
            transitionTo(State.HALF_OPEN);
            return executeHalfOpen(supplier);
        }

        log.debug("[{}] Circuit is OPEN, invoking fallback", config.name());
        return fallback.execute(config.name(), current.lastFailure());
    }

    private T executeHalfOpen(Supplier<T> supplier) {
        // Only allow one probe at a time
        if (!halfOpenProbes.compareAndSet(0, 1)) {
            log.debug("[{}] HALF_OPEN probe already in progress, invoking fallback", config.name());
            return fallback.execute(config.name(), internalState.get().lastFailure());
        }

        try {
            Instant callStart = clock.instant();
            T result = supplier.get();
            onHalfOpenSuccess(callStart);
            return result;
        } catch (Exception e) {
            onHalfOpenFailure(e);
            throw e;
        } finally {
            halfOpenProbes.set(0);
        }
    }

    // -------------------------------------------------------------------
    //  State transitions
    // -------------------------------------------------------------------

    private void onSuccess(Instant callStart) {
        checkSlowCall(callStart);
        internalState.updateAndGet(InternalState::recordSuccess);
    }

    private void onFailure(Throwable t) {
        InternalState updated = internalState.updateAndGet(s -> s.recordFailure(t));
        if (updated.consecutiveFailures() >= config.failureThreshold()) {
            log.warn("[{}] Failure threshold ({}) reached, opening circuit",
                    config.name(), config.failureThreshold());
            openCircuit();
        }
    }

    private void onHalfOpenSuccess(Instant callStart) {
        checkSlowCall(callStart);
        InternalState updated = internalState.updateAndGet(InternalState::recordSuccess);
        if (updated.consecutiveSuccessesInHalfOpen() >= config.successThresholdInHalfOpen()) {
            log.info("[{}] Success threshold in HALF_OPEN reached, closing circuit", config.name());
            transitionTo(State.CLOSED);
        }
    }

    private void onHalfOpenFailure(Throwable t) {
        log.warn("[{}] Probe failed in HALF_OPEN, re-opening circuit: {}", config.name(), t.getMessage());
        internalState.updateAndGet(s -> s.recordFailure(t));
        openCircuit();
    }

    private void openCircuit() {
        Instant now = clock.instant();
        internalState.updateAndGet(s -> s.markOpened(now));
    }

    private void transitionTo(State newState) {
        internalState.updateAndGet(s -> s.withState(newState));
    }

    private void checkSlowCall(Instant callStart) {
        Duration elapsed = Duration.between(callStart, clock.instant());
        if (elapsed.compareTo(config.slowCallThreshold()) > 0) {
            internalState.updateAndGet(InternalState::recordSlowCall);
            log.debug("[{}] Slow call detected: {}ms", config.name(), elapsed.toMillis());
        }
    }

    // -------------------------------------------------------------------
    //  Observability
    // -------------------------------------------------------------------

    /**
     * Point-in-time snapshot of circuit breaker metrics.
     *
     * @param name            circuit breaker name
     * @param state           current state
     * @param totalRequests   lifetime request count
     * @param totalFailures   lifetime failure count
     * @param totalSlowCalls  lifetime slow call count
     * @param failureRate     failure / total (0.0 - 1.0)
     * @param consecutiveFailures current consecutive failure streak
     */
    public record Snapshot(
            String name,
            State state,
            long totalRequests,
            long totalFailures,
            long totalSlowCalls,
            double failureRate,
            int consecutiveFailures
    ) {}

    /**
     * Returns a point-in-time snapshot of this circuit breaker's state
     * and metrics.
     */
    public Snapshot snapshot() {
        InternalState s = internalState.get();
        double failureRate = s.totalRequests() == 0 ? 0.0
                : (double) s.totalFailures() / s.totalRequests();
        return new Snapshot(
                config.name(), s.state(), s.totalRequests(), s.totalFailures(),
                s.totalSlowCalls(), failureRate, s.consecutiveFailures());
    }

    /** Returns the current circuit state. */
    public State currentState() {
        return internalState.get().state();
    }

    /** Manually resets the circuit to CLOSED. Use with caution. */
    public void reset() {
        internalState.set(new InternalState(
                State.CLOSED, 0, 0, Instant.MIN, null, 0, 0, 0));
        log.info("[{}] Circuit breaker manually reset to CLOSED", config.name());
    }
}
