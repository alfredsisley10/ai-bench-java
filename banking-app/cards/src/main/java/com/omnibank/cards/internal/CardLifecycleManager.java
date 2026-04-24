package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.cards.api.CardProduct;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Card lifecycle engine. Drives cards from issuance through closure.
 *
 * <p>Covers:
 * <ul>
 *   <li>Physical and virtual issuance with appropriate defaults (limits,
 *       expiry, network selection).</li>
 *   <li>Activation (converts a PENDING_ACTIVATION card to ACTIVE).</li>
 *   <li>Blocking flows — generic, fraud, lost, stolen — each with the
 *       correct downstream status.</li>
 *   <li>Replacement issuance that retains the PAN's token id via a
 *       rotation (so history keeps joining cleanly).</li>
 *   <li>Expiry reissue runs automatically at a cron-controlled sweep — the
 *       scheduler hooks {@link #reissueExpiringCards(int)}.</li>
 * </ul>
 *
 * <p>All transitions go through {@link CardStatus#canTransitionTo(CardStatus)}.
 * Illegal moves throw {@link IllegalStateException}.
 */
@Service
public class CardLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(CardLifecycleManager.class);

    /** Default credit limits per product. */
    private static final Map<CardProduct, Money> DEFAULT_CREDIT_LIMIT = Map.of(
            CardProduct.CREDIT_BASIC, Money.of("5000.00", CurrencyCode.USD),
            CardProduct.CREDIT_REWARDS, Money.of("15000.00", CurrencyCode.USD),
            CardProduct.CREDIT_BUSINESS, Money.of("50000.00", CurrencyCode.USD)
    );

    /** Default expiration years for a newly-issued card. */
    private static final int DEFAULT_EXPIRATION_YEARS = 4;

    public record CardIssued(
            UUID eventId,
            Instant occurredAt,
            UUID cardId,
            CustomerId holder,
            CardProduct product,
            CardNetwork network,
            boolean virtual) implements DomainEvent {
        @Override public String eventType() { return "cards.issued"; }
    }

    public record CardStatusChanged(
            UUID eventId,
            Instant occurredAt,
            UUID cardId,
            CardStatus previousStatus,
            CardStatus newStatus,
            String reason) implements DomainEvent {
        @Override public String eventType() { return "cards.status_changed"; }
    }

    /** Result of a bulk reissue sweep — for ops dashboards. */
    public record ReissueSummary(int scanned, int reissued, int skipped) {}

    private final CardRepository cards;
    private final CardTokenizationService tokenization;
    private final EventBus events;
    private final Clock clock;

    public CardLifecycleManager(CardRepository cards,
                                CardTokenizationService tokenization,
                                EventBus events,
                                Clock clock) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.tokenization = Objects.requireNonNull(tokenization, "tokenization");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Issue a new card. Picks the network by product (defaulting to Visa
     * for credit, debit rail for debit). Credit cards start with the
     * product's default limit. Virtual cards skip embossing.
     */
    @Transactional
    public CardEntity issue(CustomerId holder,
                            AccountNumber linkedAccount,
                            CardProduct product,
                            boolean virtual) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(linkedAccount, "linkedAccount");
        Objects.requireNonNull(product, "product");

        var network = defaultNetworkFor(product);
        var token = tokenization.issueToken(network);
        var now = Timestamp.now(clock);
        var creditLimit = DEFAULT_CREDIT_LIMIT.get(product);
        var expiry = LocalDate.now(clock).plusYears(DEFAULT_EXPIRATION_YEARS);

        var entity = new CardEntity(
                UUID.randomUUID(), holder, linkedAccount, product, network, token,
                CardStatus.PENDING_ACTIVATION, virtual, expiry,
                creditLimit,
                creditLimit,                       // available = limit at issue
                CurrencyCode.USD, now, now,
                virtual ? "Virtual card provisioned" : "Physical card produced",
                0);
        cards.save(entity);

        publishIssued(entity);
        log.info("Issued {} card: id={}, product={}, network={}, holder={}",
                virtual ? "virtual" : "physical",
                entity.cardId(), product, network, holder);
        return entity;
    }

    /** Activate a card — requires PENDING_ACTIVATION status. */
    @Transactional
    public CardEntity activate(UUID cardId) {
        var card = requireCard(cardId);
        if (card.status() == CardStatus.ACTIVE) {
            log.debug("Card {} already ACTIVE — activation is idempotent", cardId);
            return card;
        }
        return transitionTo(card, CardStatus.ACTIVE, "Activated by cardholder");
    }

    /** Generic block for compliance / operational reasons. */
    @Transactional
    public CardEntity block(UUID cardId, String reason) {
        Objects.requireNonNull(reason, "reason");
        var card = requireCard(cardId);
        return transitionTo(card, CardStatus.BLOCKED, reason);
    }

    /** Report lost — transitions to LOST and moves to CLOSED via {@link #replace(UUID)}. */
    @Transactional
    public CardEntity reportLost(UUID cardId) {
        var card = requireCard(cardId);
        return transitionTo(card, CardStatus.LOST, "Reported lost by cardholder");
    }

    /** Report stolen — similar to lost but triggers a different operational path. */
    @Transactional
    public CardEntity reportStolen(UUID cardId) {
        var card = requireCard(cardId);
        return transitionTo(card, CardStatus.STOLEN, "Reported stolen by cardholder");
    }

    /** Fraud team holds the card pending investigation. */
    @Transactional
    public CardEntity fraudHold(UUID cardId, String details) {
        Objects.requireNonNull(details, "details");
        var card = requireCard(cardId);
        return transitionTo(card, CardStatus.FRAUD_HOLD, details);
    }

    /** Release from fraud hold back to active — typically after investigation clears. */
    @Transactional
    public CardEntity releaseFromHold(UUID cardId) {
        var card = requireCard(cardId);
        if (card.status() != CardStatus.FRAUD_HOLD && card.status() != CardStatus.BLOCKED) {
            throw new IllegalStateException(
                    "Can only release from FRAUD_HOLD or BLOCKED; was " + card.status());
        }
        return transitionTo(card, CardStatus.ACTIVE, "Released from hold");
    }

    /**
     * Replace a lost / stolen / damaged card. Closes the old card, rotates
     * the PAN behind the token, and issues a new PENDING_ACTIVATION card
     * with the same token id so downstream history still joins.
     */
    @Transactional
    public CardEntity replace(UUID cardId) {
        var card = requireCard(cardId);
        if (card.status() != CardStatus.LOST && card.status() != CardStatus.STOLEN
                && card.status() != CardStatus.FRAUD_HOLD && card.status() != CardStatus.BLOCKED) {
            throw new IllegalStateException(
                    "Replacement requires LOST/STOLEN/BLOCKED/FRAUD_HOLD; was " + card.status());
        }
        var closed = transitionTo(card, CardStatus.CLOSED,
                "Closed for replacement");
        var newToken = tokenization.rotate(card.token());
        var now = Timestamp.now(clock);
        var expiry = LocalDate.now(clock).plusYears(DEFAULT_EXPIRATION_YEARS);
        var reissued = closed.withReissued(newToken, expiry, now);
        cards.save(reissued);
        publishIssued(reissued);
        log.info("Replaced card {}: new tokenLast4={}", cardId, newToken.last4());
        return reissued;
    }

    /** Close a card permanently — irreversible. */
    @Transactional
    public CardEntity close(UUID cardId, String reason) {
        Objects.requireNonNull(reason, "reason");
        var card = requireCard(cardId);
        return transitionTo(card, CardStatus.CLOSED, reason);
    }

    /**
     * Sweep cards that expire within the given window and reissue them.
     * Called by a daily scheduled job in production.
     */
    @Transactional
    public ReissueSummary reissueExpiringCards(int windowDays) {
        var today = LocalDate.now(clock);
        var cutoff = today.plusDays(windowDays);
        int scanned = 0, reissued = 0, skipped = 0;
        for (var card : cards.findAll()) {
            scanned++;
            if (card.status().isTerminal()) {
                skipped++;
                continue;
            }
            if (card.expirationDate().isAfter(cutoff)) {
                skipped++;
                continue;
            }
            try {
                reissueExpired(card.cardId());
                reissued++;
            } catch (Exception e) {
                log.error("Failed to reissue card {}: {}", card.cardId(), e.getMessage(), e);
                skipped++;
            }
        }
        log.info("Expiry reissue sweep: scanned={}, reissued={}, skipped={}",
                scanned, reissued, skipped);
        return new ReissueSummary(scanned, reissued, skipped);
    }

    /** Reissue a single card that is expiring or expired. */
    @Transactional
    public CardEntity reissueExpired(UUID cardId) {
        var card = requireCard(cardId);
        var now = Timestamp.now(clock);
        var today = LocalDate.now(clock);
        if (card.expirationDate().isAfter(today.plusMonths(2))) {
            throw new IllegalStateException(
                    "Card " + cardId + " is not yet within reissue window (expires "
                            + card.expirationDate() + ")");
        }
        if (card.status() == CardStatus.EXPIRED) {
            var newToken = tokenization.rotate(card.token());
            var expiry = today.plusYears(DEFAULT_EXPIRATION_YEARS);
            var reissued = card.withReissued(newToken, expiry, now);
            cards.save(reissued);
            publishIssued(reissued);
            return reissued;
        }
        // Still active: rotate PAN, push expiry out, go back to PENDING_ACTIVATION.
        var newToken = tokenization.rotate(card.token());
        var expiry = today.plusYears(DEFAULT_EXPIRATION_YEARS);
        var reissued = card.withReissued(newToken, expiry, now);
        cards.save(reissued);
        publishIssued(reissued);
        log.info("Reissued card {} due to upcoming expiry", cardId);
        return reissued;
    }

    /** Daily expiry sweep — marks cards that have passed their expiry date. */
    @Transactional
    public int markExpiredCards() {
        var today = LocalDate.now(clock);
        int marked = 0;
        for (var card : cards.findAll()) {
            if (card.status().isTerminal()) continue;
            if (card.expirationDate().isBefore(today)) {
                transitionTo(card, CardStatus.EXPIRED, "Expiration date passed");
                marked++;
            }
        }
        return marked;
    }

    // --- Helpers ------------------------------------------------------------

    private CardEntity transitionTo(CardEntity card, CardStatus target, String reason) {
        if (card.status() == target) {
            log.debug("Card {} already in state {} — idempotent", card.cardId(), target);
            return card;
        }
        if (!card.status().canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Illegal card status transition: %s -> %s for %s"
                            .formatted(card.status(), target, card.cardId()));
        }
        var previous = card.status();
        var updated = card.withStatus(target, Timestamp.now(clock), reason);
        cards.save(updated);
        publishStatusChanged(previous, updated);
        log.info("Card {} transitioned {} -> {} (reason={})",
                card.cardId(), previous, target, reason);
        return updated;
    }

    private CardEntity requireCard(UUID cardId) {
        return cards.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown card: " + cardId));
    }

    private static CardNetwork defaultNetworkFor(CardProduct product) {
        return switch (product) {
            case DEBIT_STANDARD, DEBIT_PREMIUM -> CardNetwork.VISA;
            case CREDIT_BASIC, CREDIT_REWARDS -> CardNetwork.VISA;
            case CREDIT_BUSINESS -> CardNetwork.MASTERCARD;
            case PREPAID -> CardNetwork.VISA;
        };
    }

    private void publishIssued(CardEntity card) {
        try {
            events.publish(new CardIssued(
                    UUID.randomUUID(), Timestamp.now(clock),
                    card.cardId(), card.holder(), card.product(), card.network(),
                    card.virtual()));
        } catch (Exception e) {
            log.warn("Failed to publish CardIssued for {}: {}", card.cardId(), e.getMessage());
        }
    }

    private void publishStatusChanged(CardStatus previous, CardEntity card) {
        try {
            events.publish(new CardStatusChanged(
                    UUID.randomUUID(), Timestamp.now(clock),
                    card.cardId(), previous, card.status(), card.lastStatusChangeReason()));
        } catch (Exception e) {
            log.warn("Failed to publish CardStatusChanged for {}: {}",
                    card.cardId(), e.getMessage());
        }
    }

    /** List of card products the bank issues today — used by admin UIs. */
    public List<CardProduct> supportedProducts() {
        return List.of(CardProduct.values());
    }
}
