package com.omnibank.payments.internal;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.payments.internal.PaymentLifecycleManager.LifecycleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLifecycleManagerTest {

    private Clock clock;
    private RecordingPublisher publisher;
    private PaymentLifecycleManager manager;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        publisher = new RecordingPublisher();
        manager = new PaymentLifecycleManager(clock, publisher);
    }

    @Test
    void initiates_new_lifecycle_in_initiated_state() {
        var paymentId = PaymentId.newId();
        var lifecycle = manager.initiate(paymentId, PaymentRail.ACH);

        assertThat(lifecycle.currentState()).isEqualTo(LifecycleState.INITIATED);
        assertThat(lifecycle.rail()).isEqualTo(PaymentRail.ACH);
        assertThat(lifecycle.transitionCount()).isZero();
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void initiate_is_idempotent_returns_existing_lifecycle() {
        var paymentId = PaymentId.newId();
        var first = manager.initiate(paymentId, PaymentRail.WIRE);
        var second = manager.initiate(paymentId, PaymentRail.ACH);

        assertThat(second).isSameAs(first);
        assertThat(second.rail()).isEqualTo(PaymentRail.WIRE);
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void happy_path_walks_through_full_lifecycle_to_completed() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.WIRE);

        manager.transitionThrough(paymentId, List.of(
                LifecycleState.VALIDATED,
                LifecycleState.SCREENING_IN_PROGRESS,
                LifecycleState.SCREENING_CLEARED,
                LifecycleState.ROUTED,
                LifecycleState.SUBMITTED,
                LifecycleState.ACKNOWLEDGED,
                LifecycleState.SETTLED,
                LifecycleState.COMPLETED
        ), "system", "happy path");

        assertThat(manager.currentState(paymentId)).isEqualTo(LifecycleState.COMPLETED);
        assertThat(manager.isTerminal(paymentId)).isTrue();
        assertThat(manager.getLifecycle(paymentId).transitionCount()).isEqualTo(8);
    }

    @Test
    void transition_to_same_state_is_idempotent_no_event_published() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        manager.transition(paymentId, LifecycleState.VALIDATED, "ops", "validated");

        publisher.events.clear();
        var result = manager.transition(paymentId, LifecycleState.VALIDATED, "ops", "again");

        assertThat(result.currentState()).isEqualTo(LifecycleState.VALIDATED);
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void invalid_transition_throws_illegal_state() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);

        assertThatThrownBy(() -> manager.transition(paymentId, LifecycleState.SETTLED, "ops", "skip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid state transition");
    }

    @Test
    void transition_on_unknown_payment_throws_illegal_argument() {
        assertThatThrownBy(() -> manager.transition(PaymentId.newId(), LifecycleState.VALIDATED, "ops", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminal_state_blocks_further_transitions() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        manager.transition(paymentId, LifecycleState.VALIDATED, "ops", "ok");
        manager.transition(paymentId, LifecycleState.REJECTED, "ops", "bad data");

        assertThat(manager.isTerminal(paymentId)).isTrue();
        assertThatThrownBy(() -> manager.transition(paymentId, LifecycleState.VALIDATED, "ops", "retry"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void can_transition_reports_false_for_unknown_payment() {
        assertThat(manager.canTransition(PaymentId.newId(), LifecycleState.VALIDATED)).isFalse();
    }

    @Test
    void can_transition_reports_true_for_allowed_next_state() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        assertThat(manager.canTransition(paymentId, LifecycleState.VALIDATED)).isTrue();
        assertThat(manager.canTransition(paymentId, LifecycleState.SETTLED)).isFalse();
    }

    @Test
    void allowed_transitions_are_empty_for_terminal_state() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        manager.transition(paymentId, LifecycleState.CANCELED, "ops", "user canceled");

        assertThat(manager.allowedTransitions(paymentId)).isEmpty();
    }

    @Test
    void screening_held_can_clear_to_cleared_or_reject() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.WIRE);
        manager.transitionThrough(paymentId, List.of(
                LifecycleState.VALIDATED,
                LifecycleState.SCREENING_IN_PROGRESS,
                LifecycleState.SCREENING_HELD
        ), "sanctions", "hit on OFAC");

        assertThat(manager.allowedTransitions(paymentId))
                .contains(LifecycleState.SCREENING_CLEARED, LifecycleState.REJECTED, LifecycleState.CANCELED);
    }

    @Test
    void returned_from_settled_via_return_requested() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        manager.transitionThrough(paymentId, List.of(
                LifecycleState.VALIDATED,
                LifecycleState.ROUTED,
                LifecycleState.SUBMITTED,
                LifecycleState.SETTLED,
                LifecycleState.RETURN_REQUESTED,
                LifecycleState.RETURNED
        ), "ach", "R01 insufficient funds");

        assertThat(manager.currentState(paymentId)).isEqualTo(LifecycleState.RETURNED);
        assertThat(manager.isTerminal(paymentId)).isTrue();
    }

    @Test
    void toPaymentStatus_maps_all_extended_states() {
        assertThat(manager.toPaymentStatus(LifecycleState.INITIATED)).isEqualTo(PaymentStatus.RECEIVED);
        assertThat(manager.toPaymentStatus(LifecycleState.SCREENING_HELD)).isEqualTo(PaymentStatus.VALIDATED);
        assertThat(manager.toPaymentStatus(LifecycleState.ACKNOWLEDGED)).isEqualTo(PaymentStatus.SUBMITTED);
        assertThat(manager.toPaymentStatus(LifecycleState.COMPLETED)).isEqualTo(PaymentStatus.SETTLED);
        assertThat(manager.toPaymentStatus(LifecycleState.FAILED)).isEqualTo(PaymentStatus.REJECTED);
        assertThat(manager.toPaymentStatus(LifecycleState.RETURN_REQUESTED)).isEqualTo(PaymentStatus.RETURNED);
        assertThat(manager.toPaymentStatus(LifecycleState.CANCELED)).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void publisher_exception_does_not_break_transition() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        publisher.throwOnNext = true;

        var updated = manager.transition(paymentId, LifecycleState.VALIDATED, "ops", "ok");

        assertThat(updated.currentState()).isEqualTo(LifecycleState.VALIDATED);
    }

    @Test
    void transition_count_increments_per_transition() {
        var paymentId = PaymentId.newId();
        manager.initiate(paymentId, PaymentRail.ACH);
        manager.transition(paymentId, LifecycleState.VALIDATED, "ops", "a");
        manager.transition(paymentId, LifecycleState.ROUTED, "ops", "b");
        manager.transition(paymentId, LifecycleState.SUBMITTED, "ops", "c");

        assertThat(manager.getLifecycle(paymentId).transitionCount()).isEqualTo(3);
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        boolean throwOnNext = false;

        @Override
        public void publishEvent(Object event) {
            if (throwOnNext) {
                throwOnNext = false;
                throw new RuntimeException("publisher boom");
            }
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            publishEvent((Object) event);
        }
    }
}
