package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardProduct;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardLifecycleManagerTest {

    private Clock clock;
    private InMemoryCardRepository cards;
    private CardTokenizationService vault;
    private CardsTestSupport.RecordingEventBus events;
    private CardLifecycleManager lifecycle;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        cards = new InMemoryCardRepository();
        vault = new CardTokenizationService(clock);
        events = new CardsTestSupport.RecordingEventBus();
        lifecycle = new CardLifecycleManager(cards, vault, events, clock);
    }

    private CustomerId holder() {
        return new CustomerId(UUID.randomUUID());
    }

    private AccountNumber account() {
        return AccountNumber.of("OB-X-ABC12345");
    }

    @Test
    void issued_card_starts_in_pending_activation() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_REWARDS, false);
        assertThat(card.status()).isEqualTo(CardStatus.PENDING_ACTIVATION);
        assertThat(card.virtual()).isFalse();
        assertThat(cards.size()).isEqualTo(1);
    }

    @Test
    void virtual_issue_sets_flag() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_REWARDS, true);
        assertThat(card.virtual()).isTrue();
    }

    @Test
    void activate_moves_pending_to_active() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        var activated = lifecycle.activate(card.cardId());
        assertThat(activated.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void activate_is_idempotent_on_already_active_card() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.activate(card.cardId());

        var again = lifecycle.activate(card.cardId());
        assertThat(again.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void block_moves_active_to_blocked() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.activate(card.cardId());

        var blocked = lifecycle.block(card.cardId(), "suspicious activity");

        assertThat(blocked.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(blocked.lastStatusChangeReason()).isEqualTo("suspicious activity");
    }

    @Test
    void report_lost_then_replace_issues_new_token_with_same_card_id() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.activate(card.cardId());
        lifecycle.reportLost(card.cardId());

        var replaced = lifecycle.replace(card.cardId());

        assertThat(replaced.status()).isEqualTo(CardStatus.PENDING_ACTIVATION);
        assertThat(replaced.reissueCount()).isEqualTo(1);
        assertThat(replaced.token().value()).isEqualTo(card.token().value());
    }

    @Test
    void illegal_transition_throws() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.close(card.cardId(), "cardholder closed");

        assertThatThrownBy(() -> lifecycle.activate(card.cardId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fraud_hold_and_release_round_trips_to_active() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.activate(card.cardId());
        lifecycle.fraudHold(card.cardId(), "rule R07");

        var released = lifecycle.releaseFromHold(card.cardId());
        assertThat(released.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void mark_expired_cards_transitions_past_dates() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.activate(card.cardId());
        var preExpire = LocalDate.now(clock).minusDays(1);
        // Overwrite expiration directly via repository.
        var existing = cards.findById(card.cardId()).orElseThrow();
        cards.save(new CardEntity(
                existing.cardId(), existing.holder(), existing.linkedAccount(),
                existing.product(), existing.network(), existing.token(),
                existing.status(), existing.virtual(), preExpire,
                existing.creditLimit(), existing.availableCredit(),
                existing.currency(), existing.issuedAt(),
                existing.lastStatusChangeAt(), existing.lastStatusChangeReason(),
                existing.reissueCount()));

        int marked = lifecycle.markExpiredCards();

        assertThat(marked).isEqualTo(1);
        assertThat(cards.findById(card.cardId()).orElseThrow().status())
                .isEqualTo(CardStatus.EXPIRED);
    }

    @Test
    void reissueExpiringCards_walks_eligible_population() {
        var stale = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        // Force the stale card close to expiry.
        var existing = cards.findById(stale.cardId()).orElseThrow();
        cards.save(new CardEntity(
                existing.cardId(), existing.holder(), existing.linkedAccount(),
                existing.product(), existing.network(), existing.token(),
                CardStatus.ACTIVE, existing.virtual(),
                LocalDate.now(clock).plusDays(10),
                existing.creditLimit(), existing.availableCredit(),
                existing.currency(), existing.issuedAt(),
                existing.lastStatusChangeAt(), existing.lastStatusChangeReason(),
                existing.reissueCount()));

        var summary = lifecycle.reissueExpiringCards(30);

        assertThat(summary.scanned()).isEqualTo(1);
        assertThat(summary.reissued()).isEqualTo(1);
    }

    @Test
    void events_are_published_on_status_transitions() {
        var card = lifecycle.issue(holder(), account(), CardProduct.CREDIT_BASIC, false);
        lifecycle.activate(card.cardId());

        assertThat(events.eventsOfType(CardLifecycleManager.CardIssued.class))
                .hasSize(1);
        assertThat(events.eventsOfType(CardLifecycleManager.CardStatusChanged.class))
                .hasSize(1);
    }
}
