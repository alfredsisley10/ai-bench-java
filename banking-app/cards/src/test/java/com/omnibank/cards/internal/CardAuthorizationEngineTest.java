package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardStatus;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardAuthorizationEngineTest {

    private Clock clock;
    private InMemoryCardRepository cards;
    private AuthorizationHistoryRepository history;
    private CardsTestSupport.RecordingEventBus events;
    private CardAuthorizationEngine engine;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        cards = new InMemoryCardRepository();
        history = new AuthorizationHistoryRepository();
        events = new CardsTestSupport.RecordingEventBus();
        engine = new CardAuthorizationEngine(cards, history, events, clock);
    }

    @Test
    void approves_simple_authorization_and_publishes_event() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "50.00", "5812"));

        assertThat(decision.approved()).isTrue();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_APPROVED);
        assertThat(events.events).hasSize(1);
        assertThat(history.size()).isEqualTo(1);
    }

    @Test
    void declines_unknown_card() {
        var decision = engine.evaluate(CardsTestSupport.sampleRequest(UUID.randomUUID(), "10.00", "5812"));
        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_INVALID_MERCHANT);
    }

    @Test
    void declines_card_that_is_lost() {
        var id = UUID.randomUUID();
        var card = CardsTestSupport.activeCreditCard(id)
                .withStatus(CardStatus.LOST, Instant.now(), "reported lost");
        cards.save(card);

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "25.00", "5812"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_LOST_STOLEN);
    }

    @Test
    void declines_if_credit_insufficient() {
        var id = UUID.randomUUID();
        var base = CardsTestSupport.activeCreditCard(id);
        cards.save(base.withAvailableCredit(Money.of("5.00", CurrencyCode.USD)));

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "500.00", "5812"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_INSUFFICIENT_FUNDS);
    }

    @Test
    void decrements_available_credit_on_approve() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));

        engine.evaluate(CardsTestSupport.sampleRequest(id, "100.00", "5812"));

        var updated = cards.findById(id).orElseThrow();
        assertThat(updated.availableCredit())
                .isEqualTo(Money.of("9900.00", CurrencyCode.USD));
    }

    @Test
    void blocks_high_risk_mcc_casino() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "20.00", "7995"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_INVALID_MERCHANT);
    }

    @Test
    void step_up_on_large_ecommerce() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));

        var decision = engine.evaluate(CardsTestSupport.eCommerceRequest(id, "1200.00", "5999"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_STEP_UP);
        assertThat(decision.reason()).contains("3-D Secure");
    }

    @Test
    void velocity_trips_after_too_many_auths_per_minute() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));
        // Seed 5 rapid recent successful authorizations.
        for (int i = 0; i < 5; i++) {
            engine.evaluate(CardsTestSupport.sampleRequest(id, "1.00", "5812"));
        }

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "1.00", "5812"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_VELOCITY);
    }

    @Test
    void geographic_step_up_when_first_tx_outside_home_country() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));
        var engineWithHome = new CardAuthorizationEngine(
                cards, history, events, clock,
                Map.of(),
                Map.of(id, "US"));

        var req = new AuthorizationRequest(
                id, Money.of("50.00", CurrencyCode.USD),
                "5812", "London Restaurant", "GB",
                true, true, true, false, false, false,
                "device-999", "acquirer-1", null,
                Instant.parse("2026-04-16T10:00:00Z"));

        // Seed a prior US transaction so recentForCard returns something
        // that differs from GB; otherwise the step-up branch is evaluated
        // based on first-tx-outside-home.
        engineWithHome.evaluate(CardsTestSupport.sampleRequest(id, "1.00", "5812"));
        var decision = engineWithHome.evaluate(req);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_STEP_UP);
    }

    @Test
    void impossible_travel_flagged_as_suspected_fraud() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));
        // Prior US transaction.
        engine.evaluate(CardsTestSupport.sampleRequest(id, "10.00", "5812"));

        // Followed 10 minutes later by a JP transaction — impossible.
        var req = new AuthorizationRequest(
                id, Money.of("10.00", CurrencyCode.USD),
                "5812", "Tokyo diner", "JP",
                true, true, true, false, false, false,
                "device-jp", "acquirer-1", null,
                Instant.parse("2026-04-16T10:10:00Z"));

        var decision = engine.evaluate(req);

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_SUSPECTED_FRAUD);
    }

    @Test
    void expired_card_declined_with_expired_code() {
        var id = UUID.randomUUID();
        var card = CardsTestSupport.activeCreditCard(id)
                .withStatus(CardStatus.EXPIRED, Instant.now(), "aged out");
        cards.save(card);

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "10.00", "5812"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.code()).isEqualTo(CardAuthorizationEngine.CODE_EXPIRED);
    }

    @Test
    void event_publisher_failure_does_not_break_decision() {
        var id = UUID.randomUUID();
        cards.save(CardsTestSupport.activeCreditCard(id));
        events.throwOnNext = true;

        var decision = engine.evaluate(CardsTestSupport.sampleRequest(id, "10.00", "5812"));

        assertThat(decision.approved()).isTrue();
    }
}
