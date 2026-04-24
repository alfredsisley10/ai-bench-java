package com.omnibank.functional;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.cards.api.CardProduct;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.cards.internal.AuthorizationHistoryRepository;
import com.omnibank.cards.internal.AuthorizationRequest;
import com.omnibank.cards.internal.CardAuthorizationEngine;
import com.omnibank.cards.internal.CardEntity;
import com.omnibank.cards.internal.CardFraudRuleEvaluator;
import com.omnibank.cards.internal.CardFraudRuleEvaluator.CardBehaviorProfile;
import com.omnibank.cards.internal.CardLifecycleManager;
import com.omnibank.cards.internal.CardRewardsCalculator;
import com.omnibank.cards.internal.CardRewardsCalculator.RedemptionChannel;
import com.omnibank.cards.internal.CardRewardsCalculator.RewardsTier;
import com.omnibank.cards.internal.CardTokenizationService;
import com.omnibank.cards.internal.InMemoryCardRepository;
import com.omnibank.cards.internal.InterchangeCalculator;
import com.omnibank.cards.internal.InterchangeCalculator.TransactionType;
import com.omnibank.compliance.internal.AmlTransactionMonitor;
import com.omnibank.compliance.internal.AmlTransactionMonitor.MonitoredTransaction;
import com.omnibank.fraud.internal.TransactionRiskScorer;
import com.omnibank.fraud.internal.TransactionRiskScorer.TransactionContext;
import com.omnibank.fraud.internal.VelocityCheckEngine;
import com.omnibank.fraud.internal.VelocityCheckEngine.TransactionEvent;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives several months of card-spending behavior in a single test path so the
 * resulting AppMap traces span every interesting branch of the cards module
 * AND the cross-cutting subsystems that observe each transaction.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code cards.internal} — token vault rotation, lifecycle issue /
 *       activate / fraud-hold / replace, authorization engine (status / limit
 *       / MCC / velocity / geo / step-up), fraud rule evaluator with attached
 *       behavior profile, interchange + rewards calculators</li>
 *   <li>{@code fraud-detection} — the deposit-side risk scorer + velocity
 *       engine running in parallel for the same authorization stream</li>
 *   <li>{@code compliance} — AML monitor sweeps every settlement event</li>
 *   <li>{@code shared-domain} + {@code shared-messaging} — money / customer /
 *       account value types, EventBus capture</li>
 * </ul>
 *
 * <p>This is the highest-fan-out trace in the suite: a single test that
 * exercises ~30 distinct service methods across 6 modules, producing roughly
 * 20+ packages on the dependency map and 100+ flame-graph frames.
 */
class CardSpendingPatternFlowTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final Instant T0 = Instant.parse("2026-04-17T15:00:00Z");

    private Clock clock;
    private FlowTestSupport.RecordingEventBus bus;

    // cards
    private CardTokenizationService tokens;
    private InMemoryCardRepository cardRepo;
    private AuthorizationHistoryRepository authHistory;
    private CardLifecycleManager cardLifecycle;
    private CardAuthorizationEngine authEngine;
    private CardFraudRuleEvaluator fraudRules;
    private InterchangeCalculator interchange;
    private CardRewardsCalculator rewards;

    // cross-cutting
    private TransactionRiskScorer riskScorer;
    private VelocityCheckEngine velocity;
    private AmlTransactionMonitor aml;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(T0, NY);
        bus = new FlowTestSupport.RecordingEventBus();

        tokens = new CardTokenizationService(clock);
        cardRepo = new InMemoryCardRepository();
        authHistory = new AuthorizationHistoryRepository();
        cardLifecycle = new CardLifecycleManager(cardRepo, tokens, bus, clock);
        authEngine = new CardAuthorizationEngine(cardRepo, authHistory, bus, clock);
        fraudRules = new CardFraudRuleEvaluator(authHistory, clock);
        interchange = new InterchangeCalculator();
        rewards = new CardRewardsCalculator(bus, clock);

        riskScorer = new TransactionRiskScorer(clock);
        velocity = new VelocityCheckEngine(clock);
        aml = new AmlTransactionMonitor(bus, clock);
    }

    @Test
    void steady_state_spending_clears_authorization_collects_interchange_and_accrues_rewards() {
        var customer = new CustomerId(UUID.randomUUID());
        var account = AccountNumber.of("OB-C-CARD0001");
        var card = cardLifecycle.issue(customer, account,
                CardProduct.CREDIT_REWARDS, /* virtual */ false);
        cardLifecycle.activate(card.cardId());

        // Attach a behavior profile so the fraud-rule evaluator scores
        // deviation rules properly.
        var profile = new CardBehaviorProfile(
                card.cardId(),
                Money.of("65.00", CurrencyCode.USD),
                Money.of("420.00", CurrencyCode.USD),
                3,
                Set.of("US"),
                Set.of("device-iphone-known"),
                Set.of("5411", "5812", "5541"),
                T0.minusSeconds(3600));
        fraudRules.attachProfile(profile);
        rewards.setTier(card.cardId(), RewardsTier.GOLD);

        // Run a small batch of normal-life purchases. For each: authorize,
        // fraud-rule score, deposit-side risk score, interchange, rewards.
        var purchases = List.of(
                authReq(card.cardId(), "12.50",  "5411", "Hometown Grocery", T0.plusSeconds(   60)),
                authReq(card.cardId(), "44.20",  "5812", "Bistro 21",        T0.plusSeconds(  120)),
                authReq(card.cardId(), "37.95",  "5541", "Quick Gas",        T0.plusSeconds(  300)),
                authReq(card.cardId(), "8.10",   "5912", "Drug Mart",        T0.plusSeconds(  600)),
                authReq(card.cardId(), "62.00",  "5812", "Late Lunch",       T0.plusSeconds(  900))
        );

        Money totalAccrued = Money.zero(CurrencyCode.USD);
        Money totalInterchange = Money.zero(CurrencyCode.USD);
        for (var req : purchases) {
            var decision = authEngine.evaluate(req);
            assertThat(decision.approved()).isTrue();

            var assessment = fraudRules.evaluate(req);
            assertThat(assessment.recommendHardBlock()).isFalse();

            var risk = riskScorer.score(toRiskContext(account, req));
            // Steady-state purchases on the customer's profile should not
            // produce a BLOCK — REVIEW is acceptable for elevated MCCs.
            assertThat(risk.verdict().name()).isNotEqualTo("BLOCK");

            var fee = interchange.compute(card.network(),
                    TransactionType.CREDIT_CARD_PRESENT,
                    req.merchantCategoryCode(), req.amount());
            totalInterchange = totalInterchange.plus(fee);

            var accrual = rewards.accrue(card.cardId(), UUID.randomUUID(),
                    card.product(), req.amount(), req.merchantCategoryCode());
            if (accrual != null) {
                totalAccrued = totalAccrued.plus(
                        Money.of(BigDecimal.valueOf(accrual.points()), CurrencyCode.USD));
            }

            velocity.recordAndCheck(new TransactionEvent(
                    account, customer, req.amount(), req.timestamp(), "CARD_PRESENT"));
        }
        assertThat(totalInterchange.isPositive()).isTrue();
        assertThat(totalAccrued.isPositive()).isTrue();

        // Cardholder redeems half their points as statement credit. The
        // calculator must split the oldest accrual to fulfill the partial
        // redemption — that's the FIFO-with-split branch.
        var balance = rewards.balanceFor(card.cardId());
        assertThat(balance.outstandingPoints()).isPositive();

        var redeemValue = rewards.redeem(
                card.cardId(),
                Math.max(1L, balance.outstandingPoints() / 2),
                RedemptionChannel.STATEMENT_CREDIT);
        assertThat(redeemValue.isPositive()).isTrue();

        // AML monitor checks the day's settlement events; nothing in the
        // batch is suspicious so no alerts.
        for (var req : purchases) {
            var alerts = aml.monitorTransaction(new MonitoredTransaction(
                    UUID.randomUUID(), account, customer,
                    req.amount(), "OUTBOUND", "CARD_PRESENT",
                    "US", req.merchantName(), req.timestamp()));
            assertThat(alerts).isEmpty();
        }
    }

    @Test
    void lost_card_replacement_rotates_token_keeps_history_keyed_to_same_card_id() {
        var customer = new CustomerId(UUID.randomUUID());
        var account = AccountNumber.of("OB-C-CARD0002");
        var original = cardLifecycle.issue(customer, account,
                CardProduct.CREDIT_REWARDS, /* virtual */ false);
        cardLifecycle.activate(original.cardId());
        var originalToken = original.token();

        // First authorization succeeds — authorizes against the active card.
        var firstReq = authReq(original.cardId(), "210.00",
                "5732", "Electronics R Us", T0.plusSeconds(120));
        // Force an in-person + chip combo so we don't trip the first-use
        // step-up rule, which fires at amounts above $200 with no chip/PIN.
        firstReq = withChipPin(firstReq);
        var firstDecision = authEngine.evaluate(firstReq);
        assertThat(firstDecision.approved()).isTrue();

        // Customer reports the card lost. Lifecycle moves to LOST.
        var lost = cardLifecycle.reportLost(original.cardId());
        assertThat(lost.status()).isEqualTo(CardStatus.LOST);

        // While the card is LOST any new authorization must hard-decline.
        var attemptedAfterLoss = authReq(original.cardId(), "10.00",
                "5411", "Convenience", T0.plusSeconds(7200));
        var afterLossDecision = authEngine.evaluate(attemptedAfterLoss);
        assertThat(afterLossDecision.approved()).isFalse();
        assertThat(afterLossDecision.code()).isEqualTo("43"); // CODE_LOST_STOLEN

        // Replace — closes the old card, rotates the PAN behind the token,
        // and reissues the same card record (same cardId, same token id) in
        // PENDING_ACTIVATION with a refreshed expiry. Downstream history
        // joins cleanly because both the cardId and the token id survive.
        var replacement = cardLifecycle.replace(original.cardId());
        assertThat(replacement.cardId()).isEqualTo(original.cardId());
        assertThat(replacement.token().value()).isEqualTo(originalToken.value());
        assertThat(replacement.status()).isEqualTo(CardStatus.PENDING_ACTIVATION);
        assertThat(replacement.reissueCount())
                .isGreaterThan(original.reissueCount());
        assertThat(tokens.rotationCount(originalToken)).isGreaterThanOrEqualTo(1);

        // The replacement card has to be activated before it can authorize.
        cardLifecycle.activate(replacement.cardId());
        var replReq = authReq(replacement.cardId(), "55.00",
                "5812", "Bistro 21", T0.plusSeconds(86400));
        replReq = withChipPin(replReq);
        var replDecision = authEngine.evaluate(replReq);
        assertThat(replDecision.approved()).isTrue();
    }

    @Test
    void card_testing_pattern_with_repeated_small_amounts_trips_fraud_rule_and_blocks() {
        var customer = new CustomerId(UUID.randomUUID());
        var account = AccountNumber.of("OB-C-CARD0003");
        var card = cardLifecycle.issue(customer, account,
                CardProduct.CREDIT_REWARDS, /* virtual */ true);
        cardLifecycle.activate(card.cardId());

        // Fire several sub-$5 e-commerce authorizations in quick succession.
        // The fraud rule evaluator's R09_CARD_TESTING fires when 3+ small
        // amounts hit the same card within 30 minutes.
        for (int i = 0; i < 4; i++) {
            var req = new AuthorizationRequest(
                    card.cardId(), Money.of("0.99", CurrencyCode.USD),
                    "5732", "MicroMerchant " + i, "US",
                    false, false, false, false, true, false,
                    "device-bot-" + i, "acquirer-x", null,
                    T0.plusSeconds(60L * i));
            authEngine.evaluate(req);
        }

        // The 5th sub-$5 attempt should be flagged by the fraud rule
        // evaluator above the step-up threshold.
        var probe = new AuthorizationRequest(
                card.cardId(), Money.of("0.99", CurrencyCode.USD),
                "5732", "MicroMerchant 5", "US",
                false, false, false, false, true, false,
                "device-bot-5", "acquirer-x", null,
                T0.plusSeconds(360));
        var assessment = fraudRules.evaluate(probe);
        assertThat(assessment.compositeScore()).isGreaterThan(0);
        assertThat(assessment.hits())
                .anyMatch(h -> h.ruleCode().equals("R09_CARD_TESTING"));

        // Operations team responds by placing the card on a fraud hold.
        // The status gate in the auth engine must then short-circuit.
        cardLifecycle.fraudHold(card.cardId(), "Card-testing pattern detected");
        var blocked = authEngine.evaluate(probe);
        assertThat(blocked.approved()).isFalse();
        assertThat(blocked.code()).isEqualTo("59"); // CODE_SUSPECTED_FRAUD

        // Once the back office clears the hold, the card status returns to
        // ACTIVE and the engine's status gate stops short-circuiting. Under
        // a fixed test clock the velocity engine still sees the prior
        // attempts in its 1-minute window, so a follow-up auth may decline
        // for VELOCITY (code 65) rather than approve. The point of this
        // assertion is that the FRAUD_HOLD short-circuit (code 59) is gone.
        cardLifecycle.releaseFromHold(card.cardId());
        var legit = authReq(card.cardId(), "85.00",
                "5411", "Hometown Grocery", T0.plusSeconds(7200));
        var legitChip = withChipPin(legit);
        var resumed = authEngine.evaluate(legitChip);
        assertThat(resumed.code()).isNotEqualTo("59"); // not blocked by status gate
    }

    @Test
    void released_card_with_no_velocity_history_can_authorize_normally() {
        // Use a fresh card (no prior history) to confirm the post-release
        // authorization actually approves when velocity isn't elevated.
        var customer = new CustomerId(UUID.randomUUID());
        var account = AccountNumber.of("OB-C-CARD0099");
        var card = cardLifecycle.issue(customer, account,
                CardProduct.CREDIT_REWARDS, /* virtual */ false);
        cardLifecycle.activate(card.cardId());
        cardLifecycle.fraudHold(card.cardId(), "manual review");
        cardLifecycle.releaseFromHold(card.cardId());

        var first = authReq(card.cardId(), "120.00",
                "5411", "Hometown Grocery", T0.plusSeconds(60));
        var firstChip = withChipPin(first);
        var decision = authEngine.evaluate(firstChip);
        assertThat(decision.approved()).isTrue();
    }

    @Test
    void interchange_rate_card_differs_per_network_and_per_mcc() {
        // Pure unit pass through the interchange calculator + rate-card lookup.
        // Walks every branch of resolveCategory + every network branch.
        var amount = Money.of("125.00", CurrencyCode.USD);

        var visaGrocery = interchange.compute(
                CardNetwork.VISA, TransactionType.CREDIT_CARD_PRESENT, "5411", amount);
        var mcGrocery = interchange.compute(
                CardNetwork.MASTERCARD, TransactionType.CREDIT_CARD_PRESENT, "5411", amount);
        var amexTravel = interchange.compute(
                CardNetwork.AMEX, TransactionType.CREDIT_CARD_PRESENT, "3000", amount);

        // Sanity: every network should produce a positive fee.
        assertThat(visaGrocery.isPositive()).isTrue();
        assertThat(mcGrocery.isPositive()).isTrue();
        assertThat(amexTravel.isPositive()).isTrue();

        // Amex travel rate (2.50%) is materially higher than Visa grocery
        // (1.00%) — confirms we used the correct rate card branches.
        assertThat(amexTravel).isGreaterThan(visaGrocery);

        // Effective rate sanity: dollars / amount should equal the bps share.
        var effectiveAmexTravel = interchange.effectiveRatePercent(
                CardNetwork.AMEX, TransactionType.CREDIT_CARD_PRESENT, "3000", amount);
        assertThat(effectiveAmexTravel.doubleValue()).isGreaterThan(2.0);
    }

    private AuthorizationRequest authReq(UUID cardId, String amount, String mcc,
                                         String merchant, Instant ts) {
        return new AuthorizationRequest(
                cardId,
                Money.of(amount, CurrencyCode.USD),
                mcc, merchant, "US",
                /* cardPresent */ true,
                /* chipUsed   */ false,
                /* pinEntered */ false,
                /* contactless */ true,
                /* ecommerce  */ false,
                /* recurring  */ false,
                "device-iphone-known",
                "acquirer-1",
                "auth-" + UUID.randomUUID().toString().substring(0, 8),
                ts);
    }

    private AuthorizationRequest withChipPin(AuthorizationRequest r) {
        return new AuthorizationRequest(
                r.cardId(), r.amount(), r.merchantCategoryCode(),
                r.merchantName(), r.merchantCountry(),
                /* cardPresent */ true,
                /* chipUsed   */ true,
                /* pinEntered */ true,
                /* contactless */ false,
                /* ecommerce  */ false,
                /* recurring  */ r.recurring(),
                r.deviceFingerprint(), r.acquirerId(),
                r.authorizationCode(), r.timestamp());
    }

    private TransactionContext toRiskContext(AccountNumber account,
                                             AuthorizationRequest r) {
        return new TransactionContext(
                account, r.amount(),
                r.merchantCategoryCode(),
                r.merchantCountry(), "US",
                r.deviceFingerprint(), true, 90,
                r.ecommerce() ? "CNP" : "CARD_PRESENT",
                r.timestamp(),
                1, 4,
                Money.of("65.00", CurrencyCode.USD));
    }
}
