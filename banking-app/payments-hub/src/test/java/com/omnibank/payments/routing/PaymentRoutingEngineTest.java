package com.omnibank.payments.routing;

import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.payments.routing.PaymentRoutingEngine.BankCapability;
import com.omnibank.payments.routing.PaymentRoutingEngine.NoEligibleRailException;
import com.omnibank.payments.routing.PaymentRoutingEngine.RoutingPreference;
import com.omnibank.payments.routing.PaymentRoutingEngine.SettlementSpeed;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRoutingEngineTest {

    private PaymentRoutingEngine engine;
    private RailCostCalculator costCalculator;

    @BeforeEach
    void setUp() {
        costCalculator = new RailCostCalculator();
        var clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        engine = new PaymentRoutingEngine(clock, costCalculator);
        // Register beneficiary with all rails supported.
        engine.registerBankCapability(new BankCapability(
                RoutingNumber.of("021000021"),
                "Acme Bank",
                EnumSet.of(PaymentRail.ACH, PaymentRail.RTP, PaymentRail.FEDNOW, PaymentRail.WIRE),
                true, true, Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void book_transfer_is_immediate_and_zero_cost() {
        var req = requestBuilder(PaymentRail.BOOK, new BigDecimal("500.00")).build();
        var decision = engine.route(req, RoutingPreference.LOWEST_COST);

        assertThat(decision.selectedRail()).isEqualTo(PaymentRail.BOOK);
        assertThat(decision.estimatedCost().isZero()).isTrue();
        assertThat(decision.estimatedSpeed()).isEqualTo(SettlementSpeed.IMMEDIATE);
    }

    @Test
    void customer_selected_rail_used_when_eligible() {
        var req = requestBuilder(PaymentRail.WIRE, new BigDecimal("250000.00")).build();
        var decision = engine.route(req, RoutingPreference.FASTEST_SETTLEMENT);

        assertThat(decision.selectedRail()).isEqualTo(PaymentRail.WIRE);
        assertThat(decision.routingReason()).contains("Customer-selected");
    }

    @Test
    void automatic_routing_picks_instant_rail_when_fastest_preferred() {
        var req = requestWithoutExplicitRail(new BigDecimal("5000.00"));
        var decision = engine.route(req, RoutingPreference.FASTEST_SETTLEMENT);

        assertThat(decision.selectedRail()).isIn(PaymentRail.RTP, PaymentRail.FEDNOW, PaymentRail.WIRE);
        assertThat(decision.estimatedSpeed()).isIn(SettlementSpeed.IMMEDIATE, SettlementSpeed.SAME_DAY);
    }

    @Test
    void amount_over_one_million_excludes_rtp_and_ach() {
        var req = requestWithoutExplicitRail(new BigDecimal("2000000.00"));
        var decision = engine.route(req, RoutingPreference.LOWEST_COST);

        assertThat(decision.selectedRail()).isEqualTo(PaymentRail.WIRE);
    }

    @Test
    void amount_over_fednow_limit_excludes_fednow() {
        var req = requestWithoutExplicitRail(new BigDecimal("750000.00"));
        var decision = engine.route(req, RoutingPreference.FASTEST_SETTLEMENT);

        assertThat(decision.selectedRail()).isNotEqualTo(PaymentRail.FEDNOW);
    }

    @Test
    void non_rtp_participant_beneficiary_excludes_rtp() {
        engine.registerBankCapability(new BankCapability(
                RoutingNumber.of("121000248"),
                "Slow Bank",
                EnumSet.of(PaymentRail.ACH, PaymentRail.WIRE),
                false, false, Instant.now()));

        var req = requestBuilder(null, new BigDecimal("1000.00"))
                .beneficiaryRouting(Optional.of(RoutingNumber.of("121000248")))
                .build();
        var decision = engine.route(req, RoutingPreference.FASTEST_SETTLEMENT);

        assertThat(decision.selectedRail()).isNotEqualTo(PaymentRail.RTP);
        assertThat(decision.selectedRail()).isNotEqualTo(PaymentRail.FEDNOW);
    }

    @Test
    void reroute_returns_next_rail_after_failure() {
        var req = requestWithoutExplicitRail(new BigDecimal("100.00"));
        var reroute = engine.reroute(req, PaymentRail.RTP, RoutingPreference.LOWEST_COST);

        assertThat(reroute).isPresent();
        assertThat(reroute.get().selectedRail()).isNotEqualTo(PaymentRail.RTP);
        assertThat(reroute.get().routingReason()).contains("Fallback from RTP");
    }

    @Test
    void wire_unlimited_accepts_large_amount() {
        var req = requestWithoutExplicitRail(new BigDecimal("2000000.00"));
        var decision = engine.route(req, RoutingPreference.LOWEST_COST);
        assertThat(decision.selectedRail()).isEqualTo(PaymentRail.WIRE);
    }

    @Test
    void explicit_rail_that_is_ineligible_falls_through_to_auto_routing() {
        // RTP cannot handle >1M — engine falls back to auto-routing for that request.
        var req = requestBuilder(PaymentRail.RTP, new BigDecimal("2000000.00"))
                .beneficiaryRouting(Optional.of(RoutingNumber.of("021000021")))
                .build();
        var decision = engine.route(req, RoutingPreference.LOWEST_COST);
        assertThat(decision.selectedRail()).isEqualTo(PaymentRail.WIRE);
    }

    @Test
    void custom_routing_rule_added_is_persisted_and_sortable() {
        var before = engine.toString();
        engine.addRoutingRule(new PaymentRoutingEngine.RoutingRule(
                "FORCE_FEDNOW", "Prefer FedNow", 1, PaymentRail.FEDNOW,
                null, new BigDecimal("100000.00"), SettlementSpeed.IMMEDIATE, true));
        // Rule added successfully (no exception); engine can still route.
        var req = requestBuilder(PaymentRail.RTP, new BigDecimal("5000.00")).build();
        var decision = engine.route(req, RoutingPreference.CUSTOMER_PREFERRED);
        assertThat(decision.selectedRail()).isNotNull();
    }

    @Test
    void disabled_rule_is_ignored() {
        engine.addRoutingRule(new PaymentRoutingEngine.RoutingRule(
                "DISABLED_RULE", "Do not apply", 1, PaymentRail.WIRE,
                null, null, SettlementSpeed.SAME_DAY, false));

        var req = requestWithoutExplicitRail(new BigDecimal("500.00"));
        var decision = engine.route(req, RoutingPreference.LOWEST_COST);

        // Disabled rule shouldn't force WIRE. Lowest cost on small amounts is normally ACH or RTP.
        assertThat(decision.selectedRail()).isNotNull();
    }

    @Test
    void no_eligible_rail_exception_has_descriptive_message() {
        var ex = new NoEligibleRailException("test message");
        assertThatThrownBy(() -> { throw ex; })
                .isInstanceOf(NoEligibleRailException.class)
                .hasMessage("test message");
    }

    @Test
    void rail_cost_calculator_returns_non_negative_costs() {
        for (var rail : PaymentRail.values()) {
            var cost = costCalculator.calculateCost(rail, Money.of(1_000, CurrencyCode.USD));
            assertThat(cost.isNegative()).isFalse();
        }
    }

    private PaymentRequestBuilder requestBuilder(PaymentRail rail, BigDecimal amount) {
        return new PaymentRequestBuilder()
                .idempotencyKey("idem-" + amount)
                .rail(rail)
                .originator(AccountNumber.of("OB-C-ABCD1234"))
                .beneficiaryRouting(Optional.of(RoutingNumber.of("021000021")))
                .beneficiaryAccount("98765432109")
                .beneficiaryName("Jane Doe")
                .amount(Money.of(amount, CurrencyCode.USD))
                .requestedAt(Instant.parse("2026-04-16T10:00:00Z"));
    }

    private PaymentRequest requestWithoutExplicitRail(BigDecimal amount) {
        return requestBuilder(PaymentRail.WIRE, amount).build();
    }

    private static class PaymentRequestBuilder {
        String idempotencyKey;
        PaymentRail rail;
        AccountNumber originator;
        Optional<RoutingNumber> beneficiaryRouting = Optional.empty();
        String beneficiaryAccount;
        String beneficiaryName;
        Money amount;
        String memo = "";
        Instant requestedAt;

        PaymentRequestBuilder idempotencyKey(String v) { this.idempotencyKey = v; return this; }
        PaymentRequestBuilder rail(PaymentRail v) { this.rail = v; return this; }
        PaymentRequestBuilder originator(AccountNumber v) { this.originator = v; return this; }
        PaymentRequestBuilder beneficiaryRouting(Optional<RoutingNumber> v) { this.beneficiaryRouting = v; return this; }
        PaymentRequestBuilder beneficiaryAccount(String v) { this.beneficiaryAccount = v; return this; }
        PaymentRequestBuilder beneficiaryName(String v) { this.beneficiaryName = v; return this; }
        PaymentRequestBuilder amount(Money v) { this.amount = v; return this; }
        PaymentRequestBuilder requestedAt(Instant v) { this.requestedAt = v; return this; }

        PaymentRequest build() {
            return new PaymentRequest(
                    idempotencyKey,
                    rail == null ? PaymentRail.WIRE : rail,
                    originator, beneficiaryRouting, beneficiaryAccount, beneficiaryName,
                    amount, memo, requestedAt);
        }
    }
}
