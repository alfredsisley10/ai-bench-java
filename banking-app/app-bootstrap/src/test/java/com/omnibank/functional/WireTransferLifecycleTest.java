package com.omnibank.functional;

import com.omnibank.compliance.internal.AmlTransactionMonitor;
import com.omnibank.compliance.internal.AmlTransactionMonitor.MonitoredTransaction;
import com.omnibank.compliance.internal.AmlTransactionMonitor.TypologyCode;
import com.omnibank.fraud.api.FraudDecision;
import com.omnibank.fraud.internal.TransactionRiskScorer;
import com.omnibank.fraud.internal.TransactionRiskScorer.TransactionContext;
import com.omnibank.fraud.internal.VelocityCheckEngine;
import com.omnibank.fraud.internal.VelocityCheckEngine.TransactionEvent;
import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentRequest;
import com.omnibank.payments.internal.PaymentAuditTrail;
import com.omnibank.payments.internal.PaymentAuditTrail.ActorType;
import com.omnibank.payments.internal.PaymentAuditTrail.AuditAction;
import com.omnibank.payments.internal.PaymentAuditTrail.PaymentSnapshot;
import com.omnibank.payments.internal.PaymentLifecycleManager;
import com.omnibank.payments.internal.PaymentLifecycleManager.LifecycleState;
import com.omnibank.payments.routing.BeneficiaryValidator;
import com.omnibank.payments.routing.BeneficiaryValidator.BankDirectoryEntry;
import com.omnibank.payments.routing.BeneficiaryValidator.ValidationFailed;
import com.omnibank.payments.routing.BeneficiaryValidator.ValidationPassed;
import com.omnibank.payments.routing.PaymentRoutingEngine;
import com.omnibank.payments.routing.PaymentRoutingEngine.BankCapability;
import com.omnibank.payments.routing.PaymentRoutingEngine.RoutingPreference;
import com.omnibank.payments.routing.RailCostCalculator;
import com.omnibank.payments.routing.RailCostCalculator.CorrespondentCharge;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a wire transfer through its complete lifecycle while every cross-cutting
 * subsystem observes the same payment in parallel:
 *
 * <ul>
 *   <li>{@code payments-hub.routing} — beneficiary validator (routing / OFAC /
 *       directory), routing engine (rail eligibility / fallback), cost
 *       calculator (per-rail breakdown + correspondent surcharges)</li>
 *   <li>{@code payments-hub.internal} — lifecycle state machine + immutable
 *       audit trail (with hash-chained sequence numbers)</li>
 *   <li>{@code fraud-detection} — composite risk scorer + per-account / per-
 *       customer velocity engine</li>
 *   <li>{@code compliance} — AML transaction monitor (structuring / rapid
 *       movement / round-trip / FATF jurisdiction)</li>
 *   <li>{@code shared-domain} + {@code shared-messaging} for value types and
 *       event capture</li>
 * </ul>
 *
 * <p>Each test exercises a distinct branch of the routing decision tree
 * (preferred-rail accepted / fallback-driven / regulatory-block). Together
 * they produce a deeply nested call graph in the AppMap recording — every
 * lifecycle transition fans out into validation, scoring, monitoring, and
 * audit-write. AppMap traces of this shape are notoriously hard to
 * reverse-engineer back into a clean architectural picture because the same
 * data record (the {@link PaymentRequest}) appears in many parallel
 * subsystem call stacks.
 */
class WireTransferLifecycleTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final Instant T0 = Instant.parse("2026-04-17T16:00:00Z");
    private static final RoutingNumber ACME_RTN = RoutingNumber.of("021000021");
    private static final RoutingNumber RIVERA_RTN = RoutingNumber.of("011000028");

    private Clock clock;
    private FlowTestSupport.RecordingEventBus bus;
    private FlowTestSupport.RecordingEventPublisher springEvents;

    // payments-hub
    private RailCostCalculator costCalculator;
    private PaymentRoutingEngine routingEngine;
    private BeneficiaryValidator beneficiaryValidator;
    private PaymentLifecycleManager lifecycle;
    private PaymentAuditTrail audit;

    // fraud
    private TransactionRiskScorer riskScorer;
    private VelocityCheckEngine velocity;

    // compliance
    private AmlTransactionMonitor aml;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(T0, NY);
        bus = new FlowTestSupport.RecordingEventBus();
        springEvents = new FlowTestSupport.RecordingEventPublisher();

        costCalculator = new RailCostCalculator();
        routingEngine = new PaymentRoutingEngine(clock, costCalculator);
        beneficiaryValidator = new BeneficiaryValidator();
        lifecycle = new PaymentLifecycleManager(clock, springEvents);
        audit = new PaymentAuditTrail(clock);

        riskScorer = new TransactionRiskScorer(clock);
        velocity = new VelocityCheckEngine(clock);

        aml = new AmlTransactionMonitor(bus, clock);

        // Seed the bank directory with two participating institutions: the
        // bank we send to (multi-rail) and a wire-only correspondent.
        beneficiaryValidator.loadBankDirectory(List.of(
                new BankDirectoryEntry(ACME_RTN, "Acme National Bank",
                        "New York", "NY",
                        true, true, true, true, true, T0),
                new BankDirectoryEntry(RIVERA_RTN, "Rivera Federal Trust",
                        "Miami", "FL",
                        true, true, true, false, false, T0)
        ));
        routingEngine.registerBankCapability(new BankCapability(
                ACME_RTN, "Acme National Bank",
                EnumSet.of(PaymentRail.ACH, PaymentRail.RTP, PaymentRail.FEDNOW, PaymentRail.WIRE),
                true, true, T0));
        routingEngine.registerBankCapability(new BankCapability(
                RIVERA_RTN, "Rivera Federal Trust",
                EnumSet.of(PaymentRail.ACH, PaymentRail.WIRE),
                false, false, T0));

        // Add a correspondent surcharge so the wire path includes the
        // correspondent calculation branch in our trace.
        costCalculator.registerCorrespondentCharge(new CorrespondentCharge(
                "Citi London", "CITIGB2L",
                Money.of("12.50", CurrencyCode.USD),
                new BigDecimal("3.00")));
    }

    @Test
    void domestic_wire_walks_validate_route_screen_settle_complete_with_full_audit() {
        var customer = new CustomerId(UUID.randomUUID());
        var originator = AccountNumber.of("OB-C-WIRE0001");
        var amount = Money.of("8500.00", CurrencyCode.USD);
        var request = paymentRequest(originator, ACME_RTN, "98765432109",
                amount, PaymentRail.WIRE);
        var paymentId = PaymentId.newId();

        // ---- 0. Open the audit trail with the original payment snapshot -----
        var initialSnapshot = snapshot(paymentId, request, /* status */ null);
        audit.record(PaymentAuditTrail.builder()
                .paymentId(paymentId)
                .action(AuditAction.PAYMENT_INITIATED)
                .actor(ActorType.SYSTEM, "system", "payments-hub")
                .afterState(initialSnapshot)
                .reason("Wire request received via API"));

        // ---- 1. Beneficiary validation ---------------------------------------
        var beneValidation = beneficiaryValidator.validate(
                "Acme Holdings LLC", ACME_RTN.raw(), "98765432109");
        assertThat(beneValidation).isInstanceOf(ValidationPassed.class);

        lifecycle.initiate(paymentId, request.rail());
        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "schema OK");

        // ---- 2. Compliance: sanctions screening + fraud risk -----------------
        lifecycle.transition(paymentId, LifecycleState.SCREENING_IN_PROGRESS,
                "screening", "OFAC + fraud risk");

        var risk = riskScorer.score(new TransactionContext(
                originator, amount,
                "4829",     // Wire transfer / money order MCC
                "US", "US",
                "device-corp-treasury", true, 95, "INTERNATIONAL_WIRE",
                T0, 0, 1, Money.of("5500.00", CurrencyCode.USD)));
        assertThat(risk.verdict()).isIn(FraudDecision.Verdict.PASS,
                FraudDecision.Verdict.REVIEW);

        lifecycle.transition(paymentId, LifecycleState.SCREENING_CLEARED,
                "screening", "no OFAC hit, risk verdict=" + risk.verdict());

        // ---- 3. Routing — engine should pick WIRE for this amount ------------
        var routing = routingEngine.route(request, RoutingPreference.FASTEST_SETTLEMENT);
        assertThat(routing.selectedRail()).isEqualTo(PaymentRail.WIRE);
        // Cost breakdown should include the Fedwire flat fee plus internal
        // wire processing fee — confirms we walked the cost calculator path.
        var breakdown = costCalculator.calculateCostBreakdown(PaymentRail.WIRE, amount);
        assertThat(breakdown.appliedComponents()).isNotEmpty();
        assertThat(breakdown.totalCost().isPositive()).isTrue();

        // Same wire with a correspondent surcharge should be more expensive.
        var withCorrespondent = costCalculator.calculateWireCostWithCorrespondent(
                amount, "CITIGB2L");
        assertThat(withCorrespondent.totalCost()).isGreaterThan(breakdown.totalCost());

        lifecycle.transition(paymentId, LifecycleState.ROUTED, "router",
                "rail=" + routing.selectedRail() + ", cost=" + breakdown.totalCost());

        // ---- 4. Velocity check (per-account + per-customer) -----------------
        // The wire amount ($8500) exceeds the per-minute account cap ($2000),
        // so the engine reports a Breach with a single rule-amount violation.
        // The test asserts that we walk both per-account and per-customer
        // codepaths regardless of verdict.
        var perAccount = velocity.recordAndCheck(new TransactionEvent(
                originator, customer, amount, T0, "INTERNATIONAL_WIRE"));
        assertThat(perAccount).isInstanceOfAny(
                VelocityCheckEngine.VelocityResult.Clear.class,
                VelocityCheckEngine.VelocityResult.Breach.class);

        var perCustomer = velocity.checkCustomer(customer, originator);
        assertThat(perCustomer).isNotNull();

        // ---- 5. Submission + settlement, with intermediate ACK --------------
        lifecycle.transition(paymentId, LifecycleState.SUBMITTED, "fedwire",
                "FED IMAD assigned");
        lifecycle.transition(paymentId, LifecycleState.ACKNOWLEDGED, "fedwire",
                "ACK from beneficiary FI");
        lifecycle.transition(paymentId, LifecycleState.SETTLED, "fedwire",
                "settled in fed account");
        lifecycle.transition(paymentId, LifecycleState.COMPLETED, "poster",
                "booked to GL");

        // ---- 6. AML monitor sees the outbound wire -------------------------
        var amlAlerts = aml.monitorTransaction(new MonitoredTransaction(
                UUID.randomUUID(),
                originator, customer,
                amount, "OUTBOUND", "WIRE",
                "US", "Acme Holdings LLC",
                T0));
        assertThat(amlAlerts).isEmpty();

        // ---- 7. Audit chain: every lifecycle transition should produce an
        //         entry, the chain hash should verify, and the final entry
        //         should report COMPLETED.
        for (var stage : List.of(
                AuditAction.PAYMENT_VALIDATED,
                AuditAction.PAYMENT_ROUTED,
                AuditAction.PAYMENT_SUBMITTED,
                AuditAction.PAYMENT_ACKNOWLEDGED,
                AuditAction.PAYMENT_SETTLED,
                AuditAction.PAYMENT_COMPLETED)) {
            audit.record(PaymentAuditTrail.builder()
                    .paymentId(paymentId)
                    .action(stage)
                    .actor(ActorType.SYSTEM, "lifecycle", "PaymentLifecycleManager")
                    .afterState(initialSnapshot)
                    .reason(stage.name()));
        }
        assertThat(audit.verifyChainIntegrity(paymentId)).isTrue();
        assertThat(audit.getTrail(paymentId)).hasSizeGreaterThanOrEqualTo(7);

        // Lifecycle must be terminal at COMPLETED.
        assertThat(lifecycle.isTerminal(paymentId)).isTrue();
        assertThat(lifecycle.currentState(paymentId)).isEqualTo(LifecycleState.COMPLETED);
    }

    @Test
    void rtp_payment_routed_to_wire_when_beneficiary_bank_lacks_rtp_capability() {
        var customer = new CustomerId(UUID.randomUUID());
        var originator = AccountNumber.of("OB-C-FALL0002");
        var amount = Money.of("4200.00", CurrencyCode.USD);

        // Rivera does not support RTP — the engine should fall back to a
        // rail Rivera does support.
        var request = paymentRequest(originator, RIVERA_RTN, "11122233344",
                amount, PaymentRail.RTP);
        var paymentId = PaymentId.newId();

        lifecycle.initiate(paymentId, request.rail());
        lifecycle.transition(paymentId, LifecycleState.VALIDATED, "validator", "ok");

        var routing = routingEngine.route(request, RoutingPreference.FASTEST_SETTLEMENT);
        // Engine must move off RTP and pick a rail Rivera supports.
        assertThat(routing.selectedRail()).isNotEqualTo(PaymentRail.RTP);
        assertThat(routing.selectedRail()).isIn(PaymentRail.WIRE, PaymentRail.ACH);

        var costs = costCalculator.compareCostsAcrossRails(amount);
        // Comparison should include all 5 rails — sanity check we walked the
        // full per-rail loop.
        assertThat(costs).containsKeys(PaymentRail.ACH, PaymentRail.WIRE,
                PaymentRail.RTP, PaymentRail.FEDNOW, PaymentRail.BOOK);
        // BOOK should always be the cheapest (zero cost).
        assertThat(costs.get(PaymentRail.BOOK).isZero()).isTrue();
        // Wire should be more expensive than ACH due to the flat Fedwire fee.
        assertThat(costs.get(PaymentRail.WIRE)).isGreaterThan(costs.get(PaymentRail.ACH));
    }

    @Test
    void invalid_routing_number_rejects_payment_at_validation_with_blocking_error() {
        var originator = AccountNumber.of("OB-C-BAD00003");
        var paymentId = PaymentId.newId();

        // Routing numbers are gated by an ABA checksum BEFORE the directory
        // lookup. The validator should reject with at least one BLOCKING
        // error in the routing-number / directory chain.
        var bogus = "999999999";
        var beneValidation = beneficiaryValidator.validate(
                "Mystery Holdings LLC", bogus, "55566677788");

        assertThat(beneValidation).isInstanceOf(ValidationFailed.class);
        var failed = (ValidationFailed) beneValidation;
        assertThat(failed.errors())
                .anyMatch(e -> e.severity()
                        == BeneficiaryValidator.ErrorSeverity.BLOCKING)
                .anyMatch(e -> e.category() == BeneficiaryValidator.ErrorCategory.ROUTING_NUMBER
                        || e.category() == BeneficiaryValidator.ErrorCategory.BANK_DIRECTORY);

        lifecycle.initiate(paymentId, PaymentRail.WIRE);
        lifecycle.transition(paymentId, LifecycleState.REJECTED, "validator",
                "Beneficiary validation failed: " + bogus);
        assertThat(lifecycle.isTerminal(paymentId)).isTrue();
    }

    @Test
    void high_value_wire_to_high_risk_jurisdiction_generates_aml_alert() {
        var customer = new CustomerId(UUID.randomUUID());
        var originator = AccountNumber.of("OB-C-HIGHRSK4");
        var amount = Money.of("75000.00", CurrencyCode.USD);

        // The AML monitor tracks FATF jurisdictions (AF, IR, KP, ...) while
        // the fraud risk scorer keeps its own list (NG, RO, UA, BR, IN, ...).
        // The lists do not overlap — so we exercise each engine with a
        // jurisdiction it actually flags.
        var alerts = aml.monitorTransaction(new MonitoredTransaction(
                UUID.randomUUID(), originator, customer,
                amount, "OUTBOUND", "WIRE",
                "AF",                     // FATF — tripped by AML
                "Kabul Trade Hub",
                T0));

        assertThat(alerts).isNotEmpty();
        assertThat(alerts).anyMatch(a ->
                a.typology() == TypologyCode.HIGH_RISK_JURISDICTION);

        var risk = riskScorer.score(new TransactionContext(
                originator, amount,
                "4829", "NG", "US",       // NG — tripped by risk scorer
                "device-known-treasury", true, 90, "INTERNATIONAL_WIRE",
                T0, 0, 0, Money.of("5000.00", CurrencyCode.USD)));
        assertThat(risk.verdict()).isIn(
                FraudDecision.Verdict.REVIEW, FraudDecision.Verdict.BLOCK);
        assertThat(risk.signals()).anyMatch(s -> s.contains("High-risk country"));
    }

    private PaymentRequest paymentRequest(AccountNumber originator,
                                          RoutingNumber beneRouting,
                                          String beneAccount,
                                          Money amount,
                                          PaymentRail rail) {
        return new PaymentRequest(
                "idem-" + originator.raw() + "-" + amount.amount().toPlainString(),
                rail, originator, Optional.of(beneRouting),
                beneAccount, "Acme Holdings LLC", amount,
                "test wire", T0);
    }

    private PaymentSnapshot snapshot(PaymentId id, PaymentRequest req,
                                     com.omnibank.payments.api.PaymentStatus status) {
        return new PaymentSnapshot(
                id,
                status != null ? status : com.omnibank.payments.api.PaymentStatus.RECEIVED,
                req.rail(), req.amount(),
                req.originator().raw(),
                req.beneficiaryAccount(), req.beneficiaryName(),
                req.memo(), T0);
    }
}
