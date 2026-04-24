package com.omnibank.payments.internal;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the full payment lifecycle as a state machine.
 *
 * <p>Payment state transitions:
 * <pre>
 *   RECEIVED -> VALIDATED -> ROUTED -> SUBMITTED -> SETTLED -> COMPLETED
 *                  |            |          |           |
 *                  +-> REJECTED |          +-> REJECTED|
 *                  |            |          |           +-> RETURNED
 *                  +-> CANCELED +-> CANCELED          |
 *                                                     +-> CANCELED (conditional)
 * </pre>
 *
 * <p>State transition rules:
 * <ul>
 *   <li>Each transition is validated against the allowed-transitions map</li>
 *   <li>Every transition publishes a domain event for downstream consumers</li>
 *   <li>Transitions are idempotent: applying the same state is a no-op</li>
 *   <li>Terminal states (COMPLETED, REJECTED, RETURNED) cannot transition further</li>
 * </ul>
 *
 * <p>Extended states beyond the base PaymentStatus are tracked here to capture
 * the ROUTED and COMPLETED phases that the base enum does not model.
 */
public class PaymentLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleManager.class);

    /**
     * Extended lifecycle states that provide finer granularity than PaymentStatus.
     */
    public enum LifecycleState {
        INITIATED,
        VALIDATED,
        SCREENING_IN_PROGRESS,
        SCREENING_CLEARED,
        SCREENING_HELD,
        ROUTED,
        SUBMITTED,
        ACKNOWLEDGED,
        SETTLED,
        COMPLETED,
        REJECTED,
        RETURNED,
        RETURN_REQUESTED,
        CANCELED,
        FAILED
    }

    private static final Map<LifecycleState, Set<LifecycleState>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(LifecycleState.class);

        ALLOWED_TRANSITIONS.put(LifecycleState.INITIATED,
                EnumSet.of(LifecycleState.VALIDATED, LifecycleState.REJECTED, LifecycleState.CANCELED));

        ALLOWED_TRANSITIONS.put(LifecycleState.VALIDATED,
                EnumSet.of(LifecycleState.SCREENING_IN_PROGRESS, LifecycleState.ROUTED,
                        LifecycleState.REJECTED, LifecycleState.CANCELED));

        ALLOWED_TRANSITIONS.put(LifecycleState.SCREENING_IN_PROGRESS,
                EnumSet.of(LifecycleState.SCREENING_CLEARED, LifecycleState.SCREENING_HELD,
                        LifecycleState.REJECTED));

        ALLOWED_TRANSITIONS.put(LifecycleState.SCREENING_CLEARED,
                EnumSet.of(LifecycleState.ROUTED, LifecycleState.CANCELED));

        ALLOWED_TRANSITIONS.put(LifecycleState.SCREENING_HELD,
                EnumSet.of(LifecycleState.SCREENING_CLEARED, LifecycleState.REJECTED, LifecycleState.CANCELED));

        ALLOWED_TRANSITIONS.put(LifecycleState.ROUTED,
                EnumSet.of(LifecycleState.SUBMITTED, LifecycleState.REJECTED, LifecycleState.CANCELED));

        ALLOWED_TRANSITIONS.put(LifecycleState.SUBMITTED,
                EnumSet.of(LifecycleState.ACKNOWLEDGED, LifecycleState.SETTLED,
                        LifecycleState.REJECTED, LifecycleState.FAILED));

        ALLOWED_TRANSITIONS.put(LifecycleState.ACKNOWLEDGED,
                EnumSet.of(LifecycleState.SETTLED, LifecycleState.REJECTED, LifecycleState.FAILED));

        ALLOWED_TRANSITIONS.put(LifecycleState.SETTLED,
                EnumSet.of(LifecycleState.COMPLETED, LifecycleState.RETURNED, LifecycleState.RETURN_REQUESTED));

        ALLOWED_TRANSITIONS.put(LifecycleState.RETURN_REQUESTED,
                EnumSet.of(LifecycleState.RETURNED, LifecycleState.COMPLETED));

        // Terminal states — no transitions allowed
        ALLOWED_TRANSITIONS.put(LifecycleState.COMPLETED, EnumSet.noneOf(LifecycleState.class));
        ALLOWED_TRANSITIONS.put(LifecycleState.REJECTED, EnumSet.noneOf(LifecycleState.class));
        ALLOWED_TRANSITIONS.put(LifecycleState.RETURNED, EnumSet.noneOf(LifecycleState.class));
        ALLOWED_TRANSITIONS.put(LifecycleState.CANCELED, EnumSet.noneOf(LifecycleState.class));
        ALLOWED_TRANSITIONS.put(LifecycleState.FAILED, EnumSet.noneOf(LifecycleState.class));
    }

    /**
     * Domain event published on every lifecycle state change.
     */
    public record PaymentLifecycleEvent(
            String eventId,
            PaymentId paymentId,
            LifecycleState previousState,
            LifecycleState newState,
            String actor,
            String reason,
            PaymentRail rail,
            Instant occurredAt
    ) {}

    /**
     * Tracks the current lifecycle state for a payment, along with metadata.
     */
    public record PaymentLifecycle(
            PaymentId paymentId,
            LifecycleState currentState,
            PaymentRail rail,
            Instant createdAt,
            Instant lastTransitionAt,
            int transitionCount,
            String lastActor,
            String lastReason
    ) {}

    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<UUID, PaymentLifecycle> lifecycles = new ConcurrentHashMap<>();

    public PaymentLifecycleManager(Clock clock, ApplicationEventPublisher eventPublisher) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * Initiates a new payment lifecycle in the INITIATED state.
     */
    public PaymentLifecycle initiate(PaymentId paymentId, PaymentRail rail) {
        var now = Timestamp.now(clock);
        var lifecycle = new PaymentLifecycle(
                paymentId, LifecycleState.INITIATED, rail, now, now, 0, "SYSTEM", "Payment initiated");

        var existing = lifecycles.putIfAbsent(paymentId.value(), lifecycle);
        if (existing != null) {
            log.warn("Lifecycle already exists for payment: {}, current state: {}",
                    paymentId, existing.currentState());
            return existing;
        }

        log.info("Payment lifecycle initiated: paymentId={}, rail={}", paymentId, rail);
        publishEvent(paymentId, null, LifecycleState.INITIATED, "SYSTEM", "Payment initiated", rail);
        return lifecycle;
    }

    /**
     * Transitions a payment to a new lifecycle state.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public PaymentLifecycle transition(PaymentId paymentId, LifecycleState newState,
                                        String actor, String reason) {
        var current = lifecycles.get(paymentId.value());
        if (current == null) {
            throw new IllegalArgumentException("No lifecycle found for payment: " + paymentId);
        }

        // Idempotency: same state is a no-op
        if (current.currentState() == newState) {
            log.debug("Idempotent state transition ignored: paymentId={}, state={}", paymentId, newState);
            return current;
        }

        // Validate transition
        var allowed = ALLOWED_TRANSITIONS.getOrDefault(current.currentState(), EnumSet.noneOf(LifecycleState.class));
        if (!allowed.contains(newState)) {
            throw new IllegalStateException(
                    "Invalid state transition: %s -> %s for payment %s. Allowed: %s"
                            .formatted(current.currentState(), newState, paymentId, allowed));
        }

        var now = Timestamp.now(clock);
        var previousState = current.currentState();
        var updated = new PaymentLifecycle(
                paymentId, newState, current.rail(), current.createdAt(),
                now, current.transitionCount() + 1, actor, reason);

        lifecycles.put(paymentId.value(), updated);

        log.info("Payment state transition: paymentId={}, {} -> {}, actor={}, reason={}",
                paymentId, previousState, newState, actor, reason);

        publishEvent(paymentId, previousState, newState, actor, reason, current.rail());
        return updated;
    }

    /**
     * Convenience method to transition through multiple states in sequence.
     */
    public PaymentLifecycle transitionThrough(PaymentId paymentId, List<LifecycleState> states,
                                               String actor, String reason) {
        PaymentLifecycle current = null;
        for (var state : states) {
            current = transition(paymentId, state, actor, reason);
        }
        return current;
    }

    /**
     * Returns the current lifecycle state for a payment.
     */
    public LifecycleState currentState(PaymentId paymentId) {
        var lifecycle = lifecycles.get(paymentId.value());
        if (lifecycle == null) {
            throw new IllegalArgumentException("No lifecycle found for payment: " + paymentId);
        }
        return lifecycle.currentState();
    }

    /**
     * Returns the full lifecycle record for a payment.
     */
    public PaymentLifecycle getLifecycle(PaymentId paymentId) {
        var lifecycle = lifecycles.get(paymentId.value());
        if (lifecycle == null) {
            throw new IllegalArgumentException("No lifecycle found for payment: " + paymentId);
        }
        return lifecycle;
    }

    /**
     * Checks if a transition from the current state to the target state is allowed.
     */
    public boolean canTransition(PaymentId paymentId, LifecycleState targetState) {
        var lifecycle = lifecycles.get(paymentId.value());
        if (lifecycle == null) return false;

        var allowed = ALLOWED_TRANSITIONS.getOrDefault(lifecycle.currentState(), EnumSet.noneOf(LifecycleState.class));
        return allowed.contains(targetState);
    }

    /**
     * Returns all allowed transitions from the current state.
     */
    public Set<LifecycleState> allowedTransitions(PaymentId paymentId) {
        var lifecycle = lifecycles.get(paymentId.value());
        if (lifecycle == null) return EnumSet.noneOf(LifecycleState.class);
        return ALLOWED_TRANSITIONS.getOrDefault(lifecycle.currentState(), EnumSet.noneOf(LifecycleState.class));
    }

    /**
     * Checks if the payment is in a terminal state.
     */
    public boolean isTerminal(PaymentId paymentId) {
        var state = currentState(paymentId);
        return ALLOWED_TRANSITIONS.getOrDefault(state, EnumSet.noneOf(LifecycleState.class)).isEmpty();
    }

    /**
     * Maps the extended lifecycle state back to the base PaymentStatus for API compatibility.
     */
    public PaymentStatus toPaymentStatus(LifecycleState state) {
        return switch (state) {
            case INITIATED -> PaymentStatus.RECEIVED;
            case VALIDATED, SCREENING_IN_PROGRESS, SCREENING_CLEARED, SCREENING_HELD -> PaymentStatus.VALIDATED;
            case ROUTED, SUBMITTED, ACKNOWLEDGED -> PaymentStatus.SUBMITTED;
            case SETTLED, COMPLETED -> PaymentStatus.SETTLED;
            case REJECTED, FAILED -> PaymentStatus.REJECTED;
            case RETURNED, RETURN_REQUESTED -> PaymentStatus.RETURNED;
            case CANCELED -> PaymentStatus.CANCELED;
        };
    }

    private void publishEvent(PaymentId paymentId, LifecycleState previous,
                               LifecycleState newState, String actor,
                               String reason, PaymentRail rail) {
        var event = new PaymentLifecycleEvent(
                UUID.randomUUID().toString(), paymentId, previous, newState,
                actor, reason, rail, Timestamp.now(clock));

        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Failed to publish lifecycle event: paymentId={}, state={}", paymentId, newState, e);
        }
    }
}
