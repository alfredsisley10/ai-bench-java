package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry that manages named circuit breakers across the application.
 * Each breaker is independently configurable with failure thresholds,
 * slow-call detection, reset timeouts, and success thresholds for
 * the half-open probing state.
 *
 * <p>Thread-safety is guaranteed by using {@link AtomicReference} for
 * state transitions and {@link ConcurrentHashMap} for the registry
 * itself. No external synchronisation is required.
 *
 * <p>Typical usage in a Spring service:
 * <pre>{@code
 *    CircuitBreakerRegistry registry;
 *
 *   var cb = registry.getOrCreate("payment-gateway",
 *       CircuitBreakerRegistry.Config.defaults()
 *           .withFailureThreshold(5)
 *           .withResetTimeout(Duration.ofSeconds(30)));
 *
 *   var result = cb.execute(() -> paymentClient.submitPayment(req));
 * }</pre>
 */

public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    private final ConcurrentHashMap<String, ManagedCircuitBreaker<?>> breakers = new ConcurrentHashMap<>();
    private final Consumer<CircuitBreakerState.TransitionEvent> globalTransitionListener;

    public CircuitBreakerRegistry() {
        this(event -> log.info("Circuit breaker [{}] transition: {} -> {} at {}",
                               event.breakerName(), event.from(), event.to(), event.transitionAt()));
    }

    public CircuitBreakerRegistry(Consumer<CircuitBreakerState.TransitionEvent> globalTransitionListener) {
        this.globalTransitionListener = Objects.requireNonNull(globalTransitionListener);
    }

    // -------------------------------------------------------------------
    //  Configuration record
    // -------------------------------------------------------------------

    /**
     * Per-breaker configuration. Immutable with copy-on-write setters.
     *
     * @param failureThreshold        number of calls in the ring before rate is evaluated
     * @param successThresholdHalfOpen successes needed in HALF_OPEN to reset
     * @param resetTimeout            how long OPEN state waits before transitioning to HALF_OPEN
     * @param slowCallDurationThreshold any call exceeding this is considered slow
     * @param slowCallRateThreshold   percentage [0..100] of slow calls that triggers OPEN
     * @param ringBufferSize          size of the sliding-window ring for failure rate calc
     * @param permittedProbesInHalfOpen max concurrent probe calls in HALF_OPEN
     */
    public record Config(
            int failureThreshold,
            int successThresholdHalfOpen,
            Duration resetTimeout,
            Duration slowCallDurationThreshold,
            double slowCallRateThreshold,
            int ringBufferSize,
            int permittedProbesInHalfOpen
    ) {
        public Config {
            if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold must be > 0");
            if (successThresholdHalfOpen <= 0) throw new IllegalArgumentException("successThresholdHalfOpen must be > 0");
            Objects.requireNonNull(resetTimeout, "resetTimeout");
            Objects.requireNonNull(slowCallDurationThreshold, "slowCallDurationThreshold");
            if (slowCallRateThreshold < 0 || slowCallRateThreshold > 100) {
                throw new IllegalArgumentException("slowCallRateThreshold must be in [0, 100]");
            }
            if (ringBufferSize <= 0) throw new IllegalArgumentException("ringBufferSize must be > 0");
            if (permittedProbesInHalfOpen <= 0) throw new IllegalArgumentException("permittedProbesInHalfOpen must be > 0");
        }

        /** Sensible banking-grade defaults. */
        public static Config defaults() {
            return new Config(5, 3, Duration.ofSeconds(30),
                              Duration.ofSeconds(2), 80.0, 100, 3);
        }

        public Config withFailureThreshold(int t) {
            return new Config(t, successThresholdHalfOpen, resetTimeout,
                    slowCallDurationThreshold, slowCallRateThreshold, ringBufferSize, permittedProbesInHalfOpen);
        }

        public Config withSuccessThresholdHalfOpen(int t) {
            return new Config(failureThreshold, t, resetTimeout,
                    slowCallDurationThreshold, slowCallRateThreshold, ringBufferSize, permittedProbesInHalfOpen);
        }

        public Config withResetTimeout(Duration d) {
            return new Config(failureThreshold, successThresholdHalfOpen, d,
                    slowCallDurationThreshold, slowCallRateThreshold, ringBufferSize, permittedProbesInHalfOpen);
        }

        public Config withSlowCallDurationThreshold(Duration d) {
            return new Config(failureThreshold, successThresholdHalfOpen, resetTimeout,
                    d, slowCallRateThreshold, ringBufferSize, permittedProbesInHalfOpen);
        }

        public Config withSlowCallRateThreshold(double rate) {
            return new Config(failureThreshold, successThresholdHalfOpen, resetTimeout,
                    slowCallDurationThreshold, rate, ringBufferSize, permittedProbesInHalfOpen);
        }

        public Config withRingBufferSize(int size) {
            return new Config(failureThreshold, successThresholdHalfOpen, resetTimeout,
                    slowCallDurationThreshold, slowCallRateThreshold, size, permittedProbesInHalfOpen);
        }
    }

    // -------------------------------------------------------------------
    //  Managed circuit breaker wrapper
    // -------------------------------------------------------------------

    /**
     * A single circuit breaker instance with an embedded concurrent state
     * machine. The state is held in an {@link AtomicReference} so
     * transitions are lock-free CAS operations.
     */
    public final class ManagedCircuitBreaker<T> {

        private final String name;
        private final Config config;
        private final AtomicReference<CircuitBreakerState> stateRef;

        ManagedCircuitBreaker(String name, Config config) {
            this.name = Objects.requireNonNull(name);
            this.config = Objects.requireNonNull(config);
            this.stateRef = new AtomicReference<>(newClosedState());
        }

        public String name()   { return name; }
        public Config config() { return config; }

        /** Current state label (CLOSED / OPEN / HALF_OPEN). */
        public String currentState() {
            return stateRef.get().label();
        }

        /** Metrics from the current state epoch. */
        public CircuitBreakerState.StateMetrics currentMetrics() {
            return stateRef.get().metrics();
        }

        /**
         * Executes the given supplier through the circuit breaker.
         *
         * @throws CircuitBreakerOpenException if the breaker is OPEN and the
         *         reset timeout has not yet elapsed
         */
        public T execute(Supplier<T> supplier) {
            return executeWithFallback(supplier, null);
        }

        /**
         * Executes with an optional fallback that is invoked when the breaker
         * is OPEN or the call fails and no retries remain.
         */
        public T executeWithFallback(Supplier<T> supplier,
                                     FallbackStrategy<T> fallback) {
            CircuitBreakerState current = stateRef.get();

            /* --- Possibly transition OPEN -> HALF_OPEN --- */
            if (current instanceof CircuitBreakerState.Open open && open.isResetTimeoutElapsed()) {
                var halfOpen = newHalfOpenState();
                if (stateRef.compareAndSet(current, halfOpen)) {
                    open.emitTransition("HALF_OPEN");
                    current = halfOpen;
                } else {
                    current = stateRef.get(); // another thread beat us
                }
            }

            /* --- Check if call is allowed --- */
            if (!current.allowsRequest()) {
                current.metrics().recordRejection();
                if (fallback != null) {
                    return fallback.execute(name, new CircuitBreakerOpenException(name));
                }
                throw new CircuitBreakerOpenException(name);
            }

            /* --- For HALF_OPEN, try to acquire a probe slot --- */
            if (current instanceof CircuitBreakerState.HalfOpen halfOpen
                    && !halfOpen.tryAcquireProbe()) {
                current.metrics().recordRejection();
                if (fallback != null) {
                    return fallback.execute(name, new CircuitBreakerOpenException(name));
                }
                throw new CircuitBreakerOpenException(name);
            }

            /* --- Execute the supplier and record the outcome --- */
            long startNanos = System.nanoTime();
            try {
                T result = supplier.get();
                long durationNanos = System.nanoTime() - startNanos;
                recordSuccess(current, durationNanos);
                return result;
            } catch (Exception ex) {
                long durationNanos = System.nanoTime() - startNanos;
                recordFailure(current, durationNanos);
                if (fallback != null) {
                    return fallback.execute(name, ex);
                }
                throw ex;
            }
        }

        // ---------------------------------------------------------------
        //  Outcome recording + state transitions
        // ---------------------------------------------------------------

        private void recordSuccess(CircuitBreakerState state, long durationNanos) {
            state.metrics().recordSuccess(durationNanos);
            checkSlowCall(state, durationNanos);

            switch (state) {
                case CircuitBreakerState.Closed closed -> {
                    if (closed.shouldTrip()) {
                        transitionToOpen(closed);
                    }
                }
                case CircuitBreakerState.HalfOpen halfOpen -> {
                    if (halfOpen.shouldReset()) {
                        var newClosed = newClosedState();
                        if (stateRef.compareAndSet(halfOpen, newClosed)) {
                            halfOpen.emitTransition("CLOSED");
                        }
                    }
                }
                case CircuitBreakerState.Open ignored -> { /* shouldn't happen */ }
            }
        }

        private void recordFailure(CircuitBreakerState state, long durationNanos) {
            state.metrics().recordFailure(durationNanos);
            checkSlowCall(state, durationNanos);

            switch (state) {
                case CircuitBreakerState.Closed closed -> {
                    if (closed.shouldTrip()) {
                        transitionToOpen(closed);
                    }
                }
                case CircuitBreakerState.HalfOpen halfOpen -> {
                    if (halfOpen.shouldReTrip()) {
                        transitionToOpen(halfOpen);
                    }
                }
                case CircuitBreakerState.Open ignored -> { /* shouldn't happen */ }
            }
        }

        private void checkSlowCall(CircuitBreakerState state, long durationNanos) {
            long thresholdNanos = config.slowCallDurationThreshold().toNanos();
            if (durationNanos >= thresholdNanos) {
                state.metrics().recordSlowCall();
            }
        }

        private void transitionToOpen(CircuitBreakerState from) {
            var open = newOpenState();
            if (stateRef.compareAndSet(from, open)) {
                if (from instanceof CircuitBreakerState.Closed c) c.emitTransition("OPEN");
                if (from instanceof CircuitBreakerState.HalfOpen h) h.emitTransition("OPEN");
            }
        }

        // ---------------------------------------------------------------
        //  State factory helpers
        // ---------------------------------------------------------------

        private CircuitBreakerState.Closed newClosedState() {
            return new CircuitBreakerState.Closed(
                    name, config.ringBufferSize(), config.failureThreshold(),
                    config.slowCallRateThreshold(), globalTransitionListener);
        }

        private CircuitBreakerState.Open newOpenState() {
            return new CircuitBreakerState.Open(name, config.resetTimeout(), globalTransitionListener);
        }

        private CircuitBreakerState.HalfOpen newHalfOpenState() {
            return new CircuitBreakerState.HalfOpen(
                    name, config.permittedProbesInHalfOpen(),
                    config.successThresholdHalfOpen(), globalTransitionListener);
        }
    }

    // -------------------------------------------------------------------
    //  Registry operations
    // -------------------------------------------------------------------

    /**
     * Retrieves an existing breaker or creates one with the given config.
     * Thread-safe: only one breaker per name will ever be created.
     */
    @SuppressWarnings("unchecked")
    public <T> ManagedCircuitBreaker<T> getOrCreate(String name, Config config) {
        return (ManagedCircuitBreaker<T>) breakers.computeIfAbsent(
                name, n -> new ManagedCircuitBreaker<>(n, config));
    }

    /** Retrieves a breaker by name, or {@code null} if not registered. */
    @SuppressWarnings("unchecked")
    public <T> ManagedCircuitBreaker<T> get(String name) {
        return (ManagedCircuitBreaker<T>) breakers.get(name);
    }

    /** All registered breaker names. */
    public Collection<String> registeredNames() {
        return breakers.keySet();
    }

    /** Snapshot of every breaker's current state. */
    public Map<String, String> stateSnapshot() {
        var snapshot = new ConcurrentHashMap<String, String>();
        breakers.forEach((name, cb) -> snapshot.put(name, cb.currentState()));
        return Map.copyOf(snapshot);
    }

    /** Force a specific breaker to OPEN (e.g. via admin endpoint). */
    public void forceOpen(String name) {
        var cb = breakers.get(name);
        if (cb != null) {
            var open = new CircuitBreakerState.Open(name, cb.config.resetTimeout(), globalTransitionListener);
            cb.stateRef.set(open);
            log.warn("Circuit breaker [{}] force-opened by operator", name);
        }
    }

    /** Force a specific breaker to CLOSED (e.g. after manual remediation). */
    public void forceClose(String name) {
        var cb = breakers.get(name);
        if (cb != null) {
            cb.stateRef.set(cb.newClosedState());
            log.warn("Circuit breaker [{}] force-closed by operator", name);
        }
    }

    /** Removes all breakers. Intended for testing. */
    public void clear() {
        breakers.clear();
    }

    // -------------------------------------------------------------------
    //  Exception
    // -------------------------------------------------------------------

    public static final class CircuitBreakerOpenException extends RuntimeException {
        private final String breakerName;

        public CircuitBreakerOpenException(String breakerName) {
            super("Circuit breaker [%s] is OPEN — call rejected".formatted(breakerName));
            this.breakerName = breakerName;
        }

        public String breakerName() { return breakerName; }
    }
}
