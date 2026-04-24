package com.omnibank.shared.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Sealed state hierarchy for circuit breakers. Each state carries its own
 * metrics ring buffer and encapsulates the transition logic to the next
 * allowable state. Thread-safe — all counters use atomic operations.
 *
 * <p>State transitions follow the standard finite-state machine:
 * <pre>
 *   CLOSED ──(failure threshold exceeded)──► OPEN
 *   OPEN   ──(reset timeout elapsed)──────► HALF_OPEN
 *   HALF_OPEN ──(success threshold met)───► CLOSED
 *   HALF_OPEN ──(any failure)─────────────► OPEN
 * </pre>
 */
public sealed interface CircuitBreakerState
        permits CircuitBreakerState.Closed,
                CircuitBreakerState.Open,
                CircuitBreakerState.HalfOpen {

    /** Human-readable state label for metrics tags and logging. */
    String label();

    /** Snapshot of metrics accumulated while the breaker was in this state. */
    StateMetrics metrics();

    /** Whether the state allows a call attempt through. */
    boolean allowsRequest();

    // -----------------------------------------------------------------------
    //  Shared metrics model
    // -----------------------------------------------------------------------

    /**
     * Mutable, thread-safe accumulator for call outcomes within a state epoch.
     * A new instance is created on each state transition so that per-state
     * accounting is clean.
     */
    final class StateMetrics {

        private final LongAdder totalCalls = new LongAdder();
        private final LongAdder successCount = new LongAdder();
        private final LongAdder failureCount = new LongAdder();
        private final LongAdder slowCallCount = new LongAdder();
        private final LongAdder rejectedCount = new LongAdder();
        private final AtomicLong maxLatencyNanos = new AtomicLong(0);
        private final Instant createdAt = Instant.now();

        /* Ring buffer of recent call durations (fixed-size, lock-free). */
        private final long[] latencyRingNanos;
        private final AtomicInteger ringIndex = new AtomicInteger(0);

        public StateMetrics(int ringSize) {
            this.latencyRingNanos = new long[Math.max(ringSize, 16)];
        }

        public void recordSuccess(long durationNanos) {
            totalCalls.increment();
            successCount.increment();
            recordLatency(durationNanos);
        }

        public void recordFailure(long durationNanos) {
            totalCalls.increment();
            failureCount.increment();
            recordLatency(durationNanos);
        }

        public void recordSlowCall() {
            slowCallCount.increment();
        }

        public void recordRejection() {
            rejectedCount.increment();
        }

        /** Failure rate as a percentage [0..100]. Returns -1 if no calls yet. */
        public double failureRatePercent() {
            long total = totalCalls.sum();
            return total == 0 ? -1.0 : (failureCount.sum() * 100.0) / total;
        }

        /** Slow-call rate as a percentage [0..100]. Returns -1 if no calls. */
        public double slowCallRatePercent() {
            long total = totalCalls.sum();
            return total == 0 ? -1.0 : (slowCallCount.sum() * 100.0) / total;
        }

        public long totalCalls()   { return totalCalls.sum(); }
        public long successes()    { return successCount.sum(); }
        public long failures()     { return failureCount.sum(); }
        public long slowCalls()    { return slowCallCount.sum(); }
        public long rejections()   { return rejectedCount.sum(); }
        public Instant createdAt() { return createdAt; }

        public long maxLatencyNanos() { return maxLatencyNanos.get(); }

        private void recordLatency(long durationNanos) {
            int idx = ringIndex.getAndUpdate(i -> (i + 1) % latencyRingNanos.length);
            latencyRingNanos[idx] = durationNanos;
            maxLatencyNanos.getAndUpdate(cur -> Math.max(cur, durationNanos));
        }

        @Override
        public String toString() {
            return "StateMetrics[total=%d, success=%d, failure=%d, slow=%d, rejected=%d, failureRate=%.1f%%]"
                    .formatted(totalCalls(), successes(), failures(), slowCalls(), rejections(),
                               failureRatePercent());
        }
    }

    // -----------------------------------------------------------------------
    //  State transition event
    // -----------------------------------------------------------------------

    /**
     * Emitted on every state transition for observability.
     *
     * @param breakerName  logical name of the circuit breaker
     * @param from         state we are leaving
     * @param to           state we are entering
     * @param transitionAt wall-clock time of the transition
     * @param metricsAtTransition snapshot of metrics from the departing state
     */
    record TransitionEvent(
            String breakerName,
            String from,
            String to,
            Instant transitionAt,
            StateMetrics metricsAtTransition
    ) {
        public TransitionEvent {
            Objects.requireNonNull(breakerName, "breakerName");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(transitionAt, "transitionAt");
        }
    }

    // -----------------------------------------------------------------------
    //  Concrete states
    // -----------------------------------------------------------------------

    /**
     * CLOSED — normal operation. All calls are permitted. The failure rate is
     * monitored against a sliding-window ring buffer; once the rate crosses
     * the configured threshold the breaker transitions to OPEN.
     */
    final class Closed implements CircuitBreakerState {

        private final StateMetrics metrics;
        private final int failureThreshold;
        private final double slowCallRateThreshold;
        private final Consumer<TransitionEvent> onTransition;
        private final String breakerName;

        public Closed(String breakerName, int ringSize, int failureThreshold,
                      double slowCallRateThreshold,
                      Consumer<TransitionEvent> onTransition) {
            this.breakerName = Objects.requireNonNull(breakerName);
            this.metrics = new StateMetrics(ringSize);
            this.failureThreshold = failureThreshold;
            this.slowCallRateThreshold = slowCallRateThreshold;
            this.onTransition = Objects.requireNonNull(onTransition);
        }

        @Override public String label()          { return "CLOSED"; }
        @Override public StateMetrics metrics()   { return metrics; }
        @Override public boolean allowsRequest()  { return true; }

        /**
         * Evaluates whether the breaker should trip to OPEN based on current
         * metrics. Callers invoke this after recording a call outcome.
         *
         * @return {@code true} if the breaker should transition to OPEN
         */
        public boolean shouldTrip() {
            if (metrics.totalCalls() < failureThreshold) {
                return false; // not enough data yet
            }
            boolean failureRateExceeded = metrics.failureRatePercent() >= 50.0;
            boolean slowRateExceeded = slowCallRateThreshold > 0
                    && metrics.slowCallRatePercent() >= slowCallRateThreshold;
            return failureRateExceeded || slowRateExceeded;
        }

        public void emitTransition(String toState) {
            onTransition.accept(new TransitionEvent(
                    breakerName, "CLOSED", toState, Instant.now(), metrics));
        }
    }

    /**
     * OPEN — circuit is tripped. All calls are rejected immediately. After
     * the configured reset timeout elapses the breaker transitions to
     * HALF_OPEN to probe for recovery.
     */
    final class Open implements CircuitBreakerState {

        private final StateMetrics metrics;
        private final Instant openedAt;
        private final Duration resetTimeout;
        private final Consumer<TransitionEvent> onTransition;
        private final String breakerName;

        public Open(String breakerName, Duration resetTimeout,
                    Consumer<TransitionEvent> onTransition) {
            this.breakerName = Objects.requireNonNull(breakerName);
            this.metrics = new StateMetrics(16);
            this.openedAt = Instant.now();
            this.resetTimeout = Objects.requireNonNull(resetTimeout);
            this.onTransition = Objects.requireNonNull(onTransition);
        }

        @Override public String label()          { return "OPEN"; }
        @Override public StateMetrics metrics()   { return metrics; }
        @Override public boolean allowsRequest()  { return false; }

        public Instant openedAt()           { return openedAt; }
        public Duration resetTimeout()      { return resetTimeout; }

        /**
         * @return {@code true} if enough time has elapsed to attempt a probe
         */
        public boolean isResetTimeoutElapsed() {
            return Instant.now().isAfter(openedAt.plus(resetTimeout));
        }

        public void emitTransition(String toState) {
            onTransition.accept(new TransitionEvent(
                    breakerName, "OPEN", toState, Instant.now(), metrics));
        }
    }

    /**
     * HALF_OPEN — probing for recovery. A limited number of trial calls are
     * permitted. If enough succeed the breaker resets to CLOSED; any failure
     * immediately trips it back to OPEN.
     */
    final class HalfOpen implements CircuitBreakerState {

        private final StateMetrics metrics;
        private final int permittedProbes;
        private final AtomicInteger probeCount = new AtomicInteger(0);
        private final int successThreshold;
        private final Consumer<TransitionEvent> onTransition;
        private final String breakerName;

        public HalfOpen(String breakerName, int permittedProbes, int successThreshold,
                        Consumer<TransitionEvent> onTransition) {
            this.breakerName = Objects.requireNonNull(breakerName);
            this.metrics = new StateMetrics(permittedProbes);
            this.permittedProbes = permittedProbes;
            this.successThreshold = successThreshold;
            this.onTransition = Objects.requireNonNull(onTransition);
        }

        @Override public String label()         { return "HALF_OPEN"; }
        @Override public StateMetrics metrics()  { return metrics; }

        @Override
        public boolean allowsRequest() {
            return probeCount.get() < permittedProbes;
        }

        /**
         * Attempts to acquire a probe slot.
         *
         * @return {@code true} if this call may proceed as a probe
         */
        public boolean tryAcquireProbe() {
            return probeCount.getAndIncrement() < permittedProbes;
        }

        /**
         * @return {@code true} if enough probes have succeeded to reset to CLOSED
         */
        public boolean shouldReset() {
            return metrics.successes() >= successThreshold;
        }

        /**
         * @return {@code true} if any probe has failed, requiring re-trip to OPEN
         */
        public boolean shouldReTrip() {
            return metrics.failures() > 0;
        }

        public void emitTransition(String toState) {
            onTransition.accept(new TransitionEvent(
                    breakerName, "HALF_OPEN", toState, Instant.now(), metrics));
        }
    }
}
