package com.omnibank.functional;

import com.omnibank.fraud.api.FraudDecision;
import com.omnibank.fraud.internal.TransactionRiskScorer;
import com.omnibank.fraud.internal.TransactionRiskScorer.TransactionContext;
import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.payments.internal.PaymentLifecycleManager;
import com.omnibank.payments.internal.PaymentLifecycleManager.LifecycleState;
import com.omnibank.payments.routing.PaymentRoutingEngine;
import com.omnibank.payments.routing.PaymentRoutingEngine.BankCapability;
import com.omnibank.payments.routing.PaymentRoutingEngine.RoutingPreference;
import com.omnibank.payments.routing.RailCostCalculator;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-module functional test that traverses fraud scoring, lifecycle state
 * machine, routing, and cost calculation in a single flow. This exercises the
 * kind of end-to-end path that real banking transactions hit and makes AppMap
 * traces meaningful — the generated AppMap spans all four modules in a single
 * call sequence.
 */
class PaymentProcessingFlowTest {

    private Clock clock;
    private RecordingPublisher publisher;
    private TransactionRiskScorer riskScorer;
    private RailCostCalculator costCalculator;
    private PaymentRoutingEngine routingEngine;
    private PaymentLifecycleManager lifecycle;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T15:00:00Z"), ZoneId.of("UTC"));
        publisher = new RecordingPublisher();
        riskScorer = new TransactionRiskScorer(clock);
        costCalculator = new RailCostCalculator();
        routingEngine = new PaymentRoutingEngine(clock, costCalculator);
        lifecycle = new PaymentLifecycleManager(clock, publisher);

        routingEngine.registerBankCapability(new BankCapability(
                RoutingNumber.of("021000021"), "Acme Bank",
                EnumSet.of(PaymentRail.ACH, PaymentRail.RTP, PaymentRail.FEDNOW, PaymentRail.WIRE),
                true, true, Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void clean_payment_walks_full_happy_path() {
        var request = paymentRequest(Money.of("1500.00", CurrencyCode.USD), PaymentRail.WIRE);
        var paymentId = PaymentId.newId();

        lifecycle.initiate(paymentId, request.rail());

        var fraud = scoreFraud(request, lowRiskFraudContext(request));
        assertThat(fraud.verdict()).isEqualTo(FraudDecision.Verdict.PASS);

        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "schema ok");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_IN_PROGRESS, "sanctions", "screening");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_CLEARED, "sanctions", "no hits");

        var routing = routingEngine.route(request, RoutingPreference.FASTEST_SETTLEMENT);
        assertThat(routing.selectedRail()).isEqualTo(PaymentRail.WIRE);

        lifecycle.transition(paymentId, LifecycleState.ROUTED, "router", "rail=" + routing.selectedRail());
        lifecycle.transition(paymentId, LifecycleState.SUBMITTED, "fedwire", "submitted");
        lifecycle.transition(paymentId, LifecycleState.SETTLED, "fedwire", "ack");
        lifecycle.transition(paymentId, LifecycleState.COMPLETED, "poster", "booked to GL");

        assertThat(lifecycle.isTerminal(paymentId)).isTrue();
        assertThat(lifecycle.currentState(paymentId)).isEqualTo(LifecycleState.COMPLETED);

        // Publisher captured at least one event per transition (plus initiate).
        assertThat(publisher.events).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void high_risk_payment_is_rejected_before_routing() {
        var request = paymentRequest(Money.of("25000.00", CurrencyCode.USD), PaymentRail.WIRE);
        var paymentId = PaymentId.newId();

        lifecycle.initiate(paymentId, request.rail());

        var fraud = scoreFraud(request, highRiskFraudContext(request));
        assertThat(fraud.verdict()).isNotEqualTo(FraudDecision.Verdict.PASS);

        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "schema ok");
        lifecycle.transition(paymentId, LifecycleState.REJECTED, "fraud", "blocked by risk scorer");

        assertThat(lifecycle.isTerminal(paymentId)).isTrue();
        assertThat(lifecycle.currentState(paymentId)).isEqualTo(LifecycleState.REJECTED);
    }

    @Test
    void sanctions_held_payment_can_clear_and_continue() {
        var request = paymentRequest(Money.of("9500.00", CurrencyCode.USD), PaymentRail.WIRE);
        var paymentId = PaymentId.newId();

        lifecycle.initiate(paymentId, request.rail());
        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "ok");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_IN_PROGRESS, "sanctions", "ofac check");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_HELD, "sanctions", "potential hit");

        // Human analyst clears the alert
        lifecycle.transition(paymentId, LifecycleState.SCREENING_CLEARED, "analyst", "false positive");

        var routing = routingEngine.route(request, RoutingPreference.FASTEST_SETTLEMENT);
        lifecycle.transition(paymentId, LifecycleState.ROUTED, "router",
                "rail=" + routing.selectedRail());
        lifecycle.transition(paymentId, LifecycleState.SUBMITTED, "gateway", "sent");

        assertThat(lifecycle.currentState(paymentId)).isEqualTo(LifecycleState.SUBMITTED);
    }

    @Test
    void payment_fails_at_settlement_and_rerouting_to_fallback_rail_succeeds() {
        var request = paymentRequest(Money.of("500.00", CurrencyCode.USD), PaymentRail.RTP);
        var paymentId = PaymentId.newId();

        lifecycle.initiate(paymentId, request.rail());
        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "ok");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_IN_PROGRESS, "sanctions", "ofac");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_CLEARED, "sanctions", "clean");
        lifecycle.transition(paymentId, LifecycleState.ROUTED, "router", "rtp");
        lifecycle.transition(paymentId, LifecycleState.SUBMITTED, "rtp", "sent");
        lifecycle.transition(paymentId, LifecycleState.FAILED, "rtp", "network down");

        // Fallback rerouting by the routing engine
        var rerouted = routingEngine.reroute(request, PaymentRail.RTP, RoutingPreference.FASTEST_SETTLEMENT);
        assertThat(rerouted).isPresent();
        assertThat(rerouted.get().selectedRail()).isNotEqualTo(PaymentRail.RTP);
    }

    @Test
    void cross_border_cross_currency_amounts_route_to_wire() {
        var request = paymentRequest(Money.of("2500000.00", CurrencyCode.USD), PaymentRail.RTP);
        var paymentId = PaymentId.newId();

        lifecycle.initiate(paymentId, request.rail());
        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "ok");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_IN_PROGRESS, "sanctions", "ofac");
        lifecycle.transition(paymentId, LifecycleState.SCREENING_CLEARED, "sanctions", "clean");

        // Amount over RTP limit → auto-routing will pick WIRE
        var routing = routingEngine.route(request, RoutingPreference.LOWEST_COST);
        assertThat(routing.selectedRail()).isEqualTo(PaymentRail.WIRE);
        lifecycle.transition(paymentId, LifecycleState.ROUTED, "router", "wire forced");

        assertThat(lifecycle.currentState(paymentId)).isEqualTo(LifecycleState.ROUTED);
    }

    private FraudDecision scoreFraud(PaymentRequest request, TransactionContext context) {
        return riskScorer.score(context);
    }

    private TransactionContext lowRiskFraudContext(PaymentRequest request) {
        return new TransactionContext(
                request.originator(), request.amount(),
                "5812", "US", "US",
                "device-known", true, 90, "BRANCH",
                Instant.parse("2026-04-16T15:00:00Z"),
                1, 3, Money.of("1800.00", CurrencyCode.USD));
    }

    private TransactionContext highRiskFraudContext(PaymentRequest request) {
        return new TransactionContext(
                request.originator(), request.amount(),
                "7995", "NG", "US",
                null, false, 5, "ONLINE",
                Instant.parse("2026-04-16T02:30:00Z"),
                25, 60, Money.of("50.00", CurrencyCode.USD));
    }

    private PaymentRequest paymentRequest(Money amount, PaymentRail rail) {
        return new PaymentRequest(
                "idem-" + amount.amount().toPlainString(),
                rail,
                AccountNumber.of("OB-C-ABCD1234"),
                Optional.of(RoutingNumber.of("021000021")),
                "98765432109",
                "Jane Doe",
                amount,
                "test payment",
                Instant.parse("2026-04-16T15:00:00Z"));
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            publishEvent((Object) event);
        }
    }
}
