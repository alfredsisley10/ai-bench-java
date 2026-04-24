package com.omnibank.functional;

import com.omnibank.cards.api.CardProduct;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.cards.internal.AuthorizationHistoryRepository;
import com.omnibank.cards.internal.AuthorizationRequest;
import com.omnibank.cards.internal.CardAuthorizationEngine;
import com.omnibank.cards.internal.CardEntity;
import com.omnibank.cards.internal.CardFraudRuleEvaluator;
import com.omnibank.cards.internal.CardLifecycleManager;
import com.omnibank.cards.internal.CardTokenizationService;
import com.omnibank.cards.internal.InMemoryCardRepository;
import com.omnibank.cards.internal.InterchangeCalculator;
import com.omnibank.cards.internal.InterchangeCalculator.TransactionType;
import com.omnibank.cards.internal.CardRewardsCalculator;
import com.omnibank.cards.internal.CardRewardsCalculator.RedemptionChannel;
import com.omnibank.cards.internal.CardRewardsCalculator.RewardsTier;
import com.omnibank.compliance.internal.AmlTransactionMonitor;
import com.omnibank.compliance.internal.AmlTransactionMonitor.MonitoredTransaction;
import com.omnibank.compliance.internal.KycWorkflowEngine;
import com.omnibank.compliance.internal.KycWorkflowEngine.BeneficialOwner;
import com.omnibank.compliance.internal.KycWorkflowEngine.DocumentSubmission;
import com.omnibank.compliance.internal.KycWorkflowEngine.IdentityData;
import com.omnibank.fraud.internal.TransactionRiskScorer;
import com.omnibank.fraud.internal.TransactionRiskScorer.TransactionContext;
import com.omnibank.fraud.internal.VelocityCheckEngine;
import com.omnibank.fraud.internal.VelocityCheckEngine.TransactionEvent;
import com.omnibank.payments.routing.BeneficiaryValidator;
import com.omnibank.payments.routing.BeneficiaryValidator.BankDirectoryEntry;
import com.omnibank.payments.routing.BeneficiaryValidator.ValidationPassed;
import com.omnibank.payments.routing.BeneficiaryValidator.ValidationResult;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end customer onboarding flow that exercises eight modules in a
 * single test path:
 *
 * <ul>
 *   <li>{@code shared-domain} — money, identifiers, customer ids</li>
 *   <li>{@code shared-messaging} — DomainEvent + EventBus</li>
 *   <li>{@code compliance} — KYC workflow (identity / docs / beneficial
 *       ownership / PEP / sanctions / risk tier) and AML monitoring</li>
 *   <li>{@code fraud-detection} — composite risk scorer + velocity engine</li>
 *   <li>{@code cards} — token vault, lifecycle issue/activate, authorization
 *       engine with status / limit / MCC / velocity / geo / step-up rules,
 *       fraud rule evaluator, interchange + rewards calculators</li>
 *   <li>{@code payments-hub} — beneficiary validator (routing / OFAC /
 *       directory)</li>
 * </ul>
 *
 * <p>The test is deliberately wide: one assertion per phase, and each phase
 * touches a different subsystem. The resulting AppMap trace spans all of the
 * above modules in a single sequence diagram, with hundreds of distinct
 * {@code (class, method)} pairs in the flame graph — the kind of trace shape
 * that makes architecture inference from a single capture genuinely hard.
 */
class CustomerOnboardingFlowTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final Instant T0 = Instant.parse("2026-04-17T14:00:00Z");

    private Clock clock;
    private FlowTestSupport.RecordingEventBus bus;

    // Compliance
    private KycWorkflowEngine kyc;
    private AmlTransactionMonitor aml;

    // Fraud
    private TransactionRiskScorer riskScorer;
    private VelocityCheckEngine velocity;

    // Cards
    private CardTokenizationService tokens;
    private InMemoryCardRepository cardRepo;
    private AuthorizationHistoryRepository authHistory;
    private CardLifecycleManager cardLifecycle;
    private CardAuthorizationEngine authEngine;
    private CardFraudRuleEvaluator fraudRules;
    private InterchangeCalculator interchange;
    private CardRewardsCalculator rewards;

    // Payments
    private BeneficiaryValidator beneficiaryValidator;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(T0, NY);
        bus = new FlowTestSupport.RecordingEventBus();

        kyc = new KycWorkflowEngine(bus, clock);
        aml = new AmlTransactionMonitor(bus, clock);

        riskScorer = new TransactionRiskScorer(clock);
        velocity = new VelocityCheckEngine(clock);

        tokens = new CardTokenizationService(clock);
        cardRepo = new InMemoryCardRepository();
        authHistory = new AuthorizationHistoryRepository();
        cardLifecycle = new CardLifecycleManager(cardRepo, tokens, bus, clock);
        authEngine = new CardAuthorizationEngine(cardRepo, authHistory, bus, clock);
        fraudRules = new CardFraudRuleEvaluator(authHistory, clock);
        interchange = new InterchangeCalculator();
        rewards = new CardRewardsCalculator(bus, clock);

        beneficiaryValidator = new BeneficiaryValidator();
        beneficiaryValidator.loadBankDirectory(List.of(
                new BankDirectoryEntry(
                        RoutingNumber.of("021000021"),
                        "Acme National Bank", "New York", "NY",
                        true, true, true, true, true, T0)
        ));
    }

    @Test
    void new_consumer_completes_kyc_gets_a_card_and_makes_a_first_authorized_purchase() {
        // ---- 1. Customer enters the bank: KYC kickoff ------------------------
        var customer = new CustomerId(UUID.randomUUID());
        var identity = new IdentityData(
                "Casey", "Rivera", LocalDate.of(1990, 4, 17),
                "123-45-6789", "742 Evergreen Terrace",
                "Brooklyn", "NY", "11201", "US");

        var workflow1 = kyc.initiateKyc(customer, identity);
        assertThat(workflow1.stageResults())
                .hasSizeGreaterThanOrEqualTo(1);

        // ---- 2. Documents arrive (passport + proof of address) ---------------
        var passport = new DocumentSubmission(
                "PASSPORT", "P1234567",
                "US", LocalDate.of(2024, 1, 1), LocalDate.of(2034, 1, 1),
                new byte[]{1, 2, 3, 4});
        var utility = new DocumentSubmission(
                "UTILITY_BILL", "U9988776",
                "US", LocalDate.of(2026, 1, 5), null,
                new byte[]{5, 6, 7, 8});
        kyc.submitDocuments(customer, List.of(passport, utility));

        // Beneficial-ownership step is required for all consumer accounts that
        // pass through the entity workflow path. We submit a self-as-100% owner
        // record to exercise that branch even for an individual.
        var selfOwner = new BeneficialOwner(
                "Casey Rivera", LocalDate.of(1990, 4, 17),
                "123-45-6789", 100.0, "INDIVIDUAL");
        kyc.submitBeneficialOwnership(customer, List.of(selfOwner));

        // ---- 3. Run PEP, adverse media, and sanctions screenings -------------
        var workflow2 = kyc.runScreenings(customer, "Casey Rivera", "US");
        assertThat(workflow2.overallStatus().name()).isEqualTo("PASSED");
        assertThat(workflow2.riskTier()).isNotNull();

        // ---- 4. Issue a card --------------------------------------------------
        var linkedAccount = AccountNumber.of("OB-C-RIVE9001");
        var newCard = cardLifecycle.issue(
                customer, linkedAccount, CardProduct.CREDIT_REWARDS, /* virtual */ false);
        assertThat(newCard.status()).isEqualTo(CardStatus.PENDING_ACTIVATION);

        // Activate the card (the cardholder taps "activate" in the app).
        var activated = cardLifecycle.activate(newCard.cardId());
        assertThat(activated.status()).isEqualTo(CardStatus.ACTIVE);

        // Tag the rewards tier so accruals stack the platinum bonus path.
        rewards.setTier(activated.cardId(), RewardsTier.PLATINUM);

        // ---- 5. Validate the linked beneficiary on file ----------------------
        ValidationResult vr = beneficiaryValidator.validate(
                "Acme National Bank", "021000021", "98765432109");
        assertThat(vr).isInstanceOf(ValidationPassed.class);

        // ---- 6. First card-present purchase at a restaurant ($87.50 / MCC 5812).
        // Drives both the authorization engine and the fraud rule evaluator,
        // and we score a parallel transaction risk profile through the
        // payment-side risk scorer.
        var diningRequest = new AuthorizationRequest(
                activated.cardId(),
                Money.of("87.50", CurrencyCode.USD),
                "5812", "Bistro 21", "US",
                /* cardPresent */ true,
                /* chipUsed   */ true,
                /* pinEntered */ true,
                /* contactless */ false,
                /* ecommerce  */ false,
                /* recurring  */ false,
                "device-known-iphone",
                "acquirer-1",
                "auth-001",
                T0);

        var diningDecision = authEngine.evaluate(diningRequest);
        assertThat(diningDecision.approved()).isTrue();

        var diningFraudAssessment = fraudRules.evaluate(diningRequest);
        assertThat(diningFraudAssessment.recommendHardBlock()).isFalse();
        assertThat(diningFraudAssessment.recommendStepUp()).isFalse();

        var diningTxnRisk = riskScorer.score(new TransactionContext(
                linkedAccount,
                Money.of("87.50", CurrencyCode.USD),
                "5812", "US", "US",
                "device-known-iphone", true, 95, "CARD_PRESENT",
                T0, 1, 2,
                Money.of("75.00", CurrencyCode.USD)));
        assertThat(diningTxnRisk.verdict().name()).isEqualTo("PASS");

        // ---- 7. Settlement-time interchange + rewards accrual ----------------
        var fee = interchange.compute(
                activated.network(),
                TransactionType.CREDIT_CARD_PRESENT,
                "5812",
                Money.of("87.50", CurrencyCode.USD));
        assertThat(fee.isPositive()).isTrue();

        var accrual = rewards.accrue(
                activated.cardId(), UUID.randomUUID(),
                CardProduct.CREDIT_REWARDS,
                Money.of("87.50", CurrencyCode.USD), "5812");
        assertThat(accrual).isNotNull();
        assertThat(accrual.points()).isGreaterThan(87L);

        // ---- 8. Velocity engine sees the same purchase from the deposit-side -
        var velocityResult = velocity.recordAndCheck(new TransactionEvent(
                linkedAccount, customer,
                Money.of("87.50", CurrencyCode.USD), T0, "CARD_PRESENT"));
        assertThat(velocityResult).isNotNull();

        // ---- 9. AML monitor sees the deposit funding the card payment --------
        var amlAlerts = aml.monitorTransaction(new MonitoredTransaction(
                UUID.randomUUID(),
                linkedAccount,
                customer,
                Money.of("87.50", CurrencyCode.USD),
                "OUTBOUND", "INTERNAL", "US", "Bistro 21",
                T0));
        // A normal, single dining authorization should not trip any AML
        // typology, but the call still walks the full detector chain
        // (structuring, rapid movement, high-risk jurisdiction, round-trip).
        assertThat(amlAlerts).isEmpty();

        // ---- 10. Cardholder spends rewards via statement credit --------------
        var redeemed = rewards.redeem(activated.cardId(),
                accrual.points(), RedemptionChannel.STATEMENT_CREDIT);
        assertThat(redeemed.isPositive()).isTrue();

        // ---- 11. Bus must have captured at least: KYC stages, card events,
        //          authorization decision, rewards accrual + redemption.
        assertThat(bus.events).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void higher_risk_signals_flow_into_a_step_up_rather_than_hard_block() {
        // Mint a customer + card, but immediately drive a transaction that
        // tickles several step-up triggers: e-commerce, no chip, large amount,
        // unknown device.
        var customer = new CustomerId(UUID.randomUUID());
        kyc.initiateKyc(customer, new IdentityData(
                "Devon", "Patel", LocalDate.of(1985, 7, 7),
                "987-65-4321", "1 Main St",
                "San Francisco", "CA", "94102", "US"));
        kyc.submitDocuments(customer, List.of(
                new DocumentSubmission("PASSPORT", "P9999999",
                        "US", LocalDate.of(2024, 6, 6), LocalDate.of(2034, 6, 6),
                        new byte[]{0, 0, 0, 0})
        ));
        kyc.submitBeneficialOwnership(customer, List.of(
                new BeneficialOwner("Devon Patel",
                        LocalDate.of(1985, 7, 7), "987-65-4321",
                        100.0, "INDIVIDUAL")));
        kyc.runScreenings(customer, "Devon Patel", "US");

        var card = cardLifecycle.issue(
                customer, AccountNumber.of("OB-C-PATE2001"),
                CardProduct.CREDIT_REWARDS, /* virtual */ true);
        cardLifecycle.activate(card.cardId());

        var ecomLargeAmount = new AuthorizationRequest(
                card.cardId(),
                Money.of("750.00", CurrencyCode.USD),
                "5732", "Generic Online Inc", "US",
                false, false, false, false, true, false,
                "device-fresh-from-the-internet",
                "acquirer-online",
                null, T0);

        var decision = authEngine.evaluate(ecomLargeAmount);
        // Large amounts on first-use ecom should hit the step-up branch,
        // returning a non-approved decision with the step-up reason code.
        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo("1A");

        // Fraud rule evaluator runs all 10 rules, even with no behavior
        // profile attached — score is bounded [0, 100].
        var assessment = fraudRules.evaluate(ecomLargeAmount);
        assertThat(assessment.compositeScore()).isBetween(0, 100);
    }

    @Test
    void high_risk_jurisdiction_triggers_aml_alert_during_onboarding_followups() {
        var customer = new CustomerId(UUID.randomUUID());
        kyc.initiateKyc(customer, new IdentityData(
                "Sam", "Thomas", LocalDate.of(1979, 1, 1),
                "111-22-3333", "Plaza Two",
                "Miami", "FL", "33101", "US"));
        kyc.submitDocuments(customer, List.of(
                new DocumentSubmission("PASSPORT", "P0001111",
                        "US", LocalDate.of(2025, 1, 1), LocalDate.of(2035, 1, 1),
                        new byte[]{9, 9, 9, 9})
        ));
        kyc.submitBeneficialOwnership(customer, List.of(
                new BeneficialOwner("Sam Thomas",
                        LocalDate.of(1979, 1, 1), "111-22-3333",
                        100.0, "INDIVIDUAL")));
        kyc.runScreenings(customer, "Sam Thomas", "US");

        var account = AccountNumber.of("OB-C-THOM5005");
        var inbound = new MonitoredTransaction(
                UUID.randomUUID(), account, customer,
                Money.of("12500.00", CurrencyCode.USD),
                "INBOUND", "WIRE",
                "IR",                     // FATF high-risk jurisdiction
                "Tehran Trading LLC",
                T0);

        var alerts = aml.monitorTransaction(inbound);
        assertThat(alerts).isNotEmpty();
        assertThat(alerts).anyMatch(a ->
                a.typology() == AmlTransactionMonitor.TypologyCode.HIGH_RISK_JURISDICTION);
    }
}
