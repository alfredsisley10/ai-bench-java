package com.omnibank.cards.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardFraudRuleEvaluatorTest {

    private Clock clock;
    private AuthorizationHistoryRepository history;
    private CardFraudRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        history = new AuthorizationHistoryRepository();
        evaluator = new CardFraudRuleEvaluator(history, clock);
    }

    @Test
    void benign_request_scores_zero_and_recommends_nothing() {
        var cardId = UUID.randomUUID();
        var profile = new CardFraudRuleEvaluator.CardBehaviorProfile(
                cardId,
                Money.of("100.00", CurrencyCode.USD),
                Money.of("500.00", CurrencyCode.USD),
                5,
                Set.of("US"),
                Set.of("device-123"),
                Set.of("5812"),
                Instant.parse("2026-04-15T10:00:00Z"));
        evaluator.attachProfile(profile);

        var req = CardsTestSupport.sampleRequest(cardId, "100.00", "5812");
        var assessment = evaluator.evaluate(req);

        assertThat(assessment.compositeScore()).isZero();
        assertThat(assessment.recommendHardBlock()).isFalse();
        assertThat(assessment.recommendStepUp()).isFalse();
    }

    @Test
    void high_risk_mcc_triggers_mcc_rule() {
        var cardId = UUID.randomUUID();
        var req = CardsTestSupport.sampleRequest(cardId, "100.00", "7995");
        var assessment = evaluator.evaluate(req);

        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R03_MCC_RISK"))).isTrue();
    }

    @Test
    void amount_10x_average_triggers_deviation_rule() {
        var cardId = UUID.randomUUID();
        evaluator.attachProfile(new CardFraudRuleEvaluator.CardBehaviorProfile(
                cardId,
                Money.of("10.00", CurrencyCode.USD),
                Money.of("100.00", CurrencyCode.USD),
                5, Set.of("US"), Set.of(), Set.of(), null));

        var req = CardsTestSupport.sampleRequest(cardId, "500.00", "5812");
        var assessment = evaluator.evaluate(req);

        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R04_AMOUNT_DEVIATION"))).isTrue();
    }

    @Test
    void card_present_magstripe_triggers_chip_absence_rule() {
        var req = new AuthorizationRequest(
                UUID.randomUUID(),
                Money.of("20.00", CurrencyCode.USD),
                "5812", "Diner", "US",
                true, false, false, false, false, false,
                null, null, null,
                Instant.parse("2026-04-16T10:00:00Z"));

        var assessment = evaluator.evaluate(req);
        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R05_CHIP_ABSENCE"))).isTrue();
    }

    @Test
    void velocity_rule_fires_after_many_recent_authorizations() {
        var cardId = UUID.randomUUID();
        var now = Instant.parse("2026-04-16T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            history.record(new AuthorizationRecord(
                    UUID.randomUUID(), cardId,
                    Money.of("1.00", CurrencyCode.USD),
                    "5812", "US",
                    com.omnibank.cards.api.AuthorizationDecision.approved("00"),
                    now.minusSeconds(60L * i), 0));
        }

        var assessment = evaluator.evaluate(
                CardsTestSupport.sampleRequest(cardId, "10.00", "5812"));

        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R01_VELOCITY"))).isTrue();
    }

    @Test
    void new_device_rule_fires_when_fingerprint_unknown() {
        var cardId = UUID.randomUUID();
        evaluator.attachProfile(new CardFraudRuleEvaluator.CardBehaviorProfile(
                cardId, Money.of("10.00", CurrencyCode.USD),
                Money.of("100.00", CurrencyCode.USD),
                5, Set.of("US"), Set.of("known-device"), Set.of(), null));

        var req = CardsTestSupport.sampleRequest(cardId, "10.00", "5812");
        var assessment = evaluator.evaluate(req);

        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R06_NEW_DEVICE"))).isTrue();
    }

    @Test
    void card_testing_pattern_is_detected() {
        var cardId = UUID.randomUUID();
        var now = Instant.parse("2026-04-16T10:00:00Z");
        for (int i = 0; i < 3; i++) {
            history.record(new AuthorizationRecord(
                    UUID.randomUUID(), cardId,
                    Money.of("1.00", CurrencyCode.USD),
                    "5812", "US",
                    com.omnibank.cards.api.AuthorizationDecision.approved("00"),
                    now.minusSeconds(60L * i), 0));
        }

        var req = new AuthorizationRequest(
                cardId, Money.of("1.00", CurrencyCode.USD),
                "5812", "Vendor", "US",
                false, false, false, false, true, false,
                null, null, null, now);

        var assessment = evaluator.evaluate(req);
        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R09_CARD_TESTING"))).isTrue();
    }

    @Test
    void dormant_card_revival_with_large_amount_triggers_rule() {
        var cardId = UUID.randomUUID();
        var lastActive = Instant.parse("2025-01-01T10:00:00Z");
        evaluator.attachProfile(new CardFraudRuleEvaluator.CardBehaviorProfile(
                cardId, Money.of("50.00", CurrencyCode.USD),
                Money.of("100.00", CurrencyCode.USD),
                5, Set.of("US"), Set.of(), Set.of(), lastActive));

        var req = CardsTestSupport.sampleRequest(cardId, "900.00", "5812");
        var assessment = evaluator.evaluate(req);

        assertThat(assessment.hits().stream()
                .anyMatch(h -> h.ruleCode().equals("R10_DORMANT_REVIVAL"))).isTrue();
    }

    @Test
    void composite_score_capped_at_100() {
        var cardId = UUID.randomUUID();
        var lastActive = Instant.parse("2025-01-01T10:00:00Z");
        evaluator.attachProfile(new CardFraudRuleEvaluator.CardBehaviorProfile(
                cardId, Money.of("10.00", CurrencyCode.USD),
                Money.of("20.00", CurrencyCode.USD),
                1, Set.of("US"), Set.of(), Set.of(), lastActive));

        // Request that fires many rules at once.
        var req = new AuthorizationRequest(
                cardId, Money.of("5000.00", CurrencyCode.USD),
                "7995", "Casino Royale", "MX",
                true, false, false, false, true, false,
                "unknown-device", null, null,
                Instant.parse("2026-04-16T03:30:00Z"));
        var assessment = evaluator.evaluate(req);

        assertThat(assessment.compositeScore()).isLessThanOrEqualTo(100);
        assertThat(assessment.compositeScore()).isPositive();
    }

    @Test
    void thresholds_validate_arguments() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new CardFraudRuleEvaluator.Thresholds(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new CardFraudRuleEvaluator.Thresholds(20, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_profile_factory_produces_empty_sets() {
        var p = CardFraudRuleEvaluator.emptyProfile(UUID.randomUUID());
        assertThat(p.frequentCountries()).isEmpty();
        assertThat(p.knownDevices()).isEmpty();
    }

    @Test
    void rule_catalog_lists_all_ten_rules() {
        assertThat(evaluator.ruleCatalog()).hasSize(10);
    }
}
