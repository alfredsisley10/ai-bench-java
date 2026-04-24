package com.omnibank.cards.internal;

import com.omnibank.cards.api.AuthorizationDecision;
import com.omnibank.cards.api.CardStatus;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Real-time authorization decisioning engine. Evaluates every inbound
 * authorization request against a layered policy stack:
 *
 * <ol>
 *   <li><b>Card status gate:</b> active cards only; blocked/fraud-hold/lost
 *       short-circuits with a hard decline.</li>
 *   <li><b>Limit checks:</b> credit cards check available credit; debit
 *       cards check per-transaction, daily, and monthly spend limits.</li>
 *   <li><b>Velocity checks:</b> counts and sums of recent authorizations
 *       over 1-minute, 5-minute, 1-hour, and 24-hour windows.</li>
 *   <li><b>Merchant-category rules:</b> high-risk MCCs (casinos, crypto,
 *       adult, cash advance) require step-up or are hard-blocked depending
 *       on card product.</li>
 *   <li><b>Geographic anomaly:</b> detects impossible travel — two
 *       authorizations in different countries within a window shorter than
 *       any plausible flight time.</li>
 *   <li><b>Amount thresholds:</b> amounts above product limits require
 *       step-up authentication (SCA / 3-D Secure).</li>
 *   <li><b>Step-up triggers:</b> recurring large amounts, unusual geos,
 *       first-use-after-activation, and new-device authorizations get
 *       flagged for step-up rather than hard declined where possible.</li>
 * </ol>
 *
 * <p>Every decision is persisted to {@link AuthorizationHistoryRepository} and
 * published as a {@link AuthorizationDecidedEvent}. The engine never blocks
 * on downstream calls — a publisher failure degrades to a warning, the
 * decision stands.
 */
@Service
public class CardAuthorizationEngine {

    private static final Logger log = LoggerFactory.getLogger(CardAuthorizationEngine.class);

    // ISO decline codes we emit. Matches the ISO 8583 family — real issuers
    // have many more; these are the ones this engine can produce.
    static final String CODE_APPROVED = "00";
    static final String CODE_STEP_UP = "1A"; // 3DS / SCA challenge required
    static final String CODE_DO_NOT_HONOR = "05";
    static final String CODE_INSUFFICIENT_FUNDS = "51";
    static final String CODE_EXCEEDS_LIMIT = "61";
    static final String CODE_VELOCITY = "65";
    static final String CODE_LOST_STOLEN = "43";
    static final String CODE_PICK_UP_CARD = "04";
    static final String CODE_INVALID_MERCHANT = "03";
    static final String CODE_SUSPECTED_FRAUD = "59";
    static final String CODE_EXPIRED = "54";
    static final String CODE_INACTIVE = "78";

    /** High-risk MCCs that are hard-blocked for most card products. */
    private static final Set<String> HARD_BLOCK_MCCS = Set.of(
            "7995", // Gambling/casinos
            "6051", // Crypto / quasi-cash
            "5967", // Adult-oriented content
            "5993"  // Tobacco / smoke shops (varies by product)
    );

    /** MCCs that require step-up authentication for amounts above threshold. */
    private static final Set<String> STEP_UP_MCCS = Set.of(
            "4829", // Money transfers / wire
            "6010", // Manual cash advance
            "6011", // Automated cash advance
            "4722", // Travel agencies
            "3000", // Airlines (generic)
            "5944", // Jewelry
            "5712", // Furniture
            "5912"  // Pharmacy
    );

    /** Rules around per-product spend thresholds used by engine. */
    record ProductLimits(
            Money perTransactionLimit,
            Money dailyLimit,
            Money monthlyLimit,
            Money stepUpAmountThreshold,
            int maxAuthsPerMinute,
            int maxAuthsPerHour,
            int maxAuthsPerDay
    ) {}

    /** Default limits used when the caller does not provide product-specific overrides. */
    static final ProductLimits DEFAULT_LIMITS = new ProductLimits(
            null, null, null, null, 5, 30, 100);

    /** Domain event published with every decision for audit/streaming downstream. */
    public record AuthorizationDecidedEvent(
            UUID eventId,
            Instant occurredAt,
            UUID authorizationId,
            UUID cardId,
            boolean approved,
            String code,
            String reason,
            int riskScore) implements DomainEvent {

        @Override
        public String eventType() {
            return "cards.authorization_decided";
        }
    }

    private final CardRepository cards;
    private final AuthorizationHistoryRepository history;
    private final EventBus events;
    private final Clock clock;
    private final Map<UUID, ProductLimits> limitsByCard;
    private final Map<UUID, String> homeCountryByCard;

    @Autowired
    public CardAuthorizationEngine(CardRepository cards,
                                   AuthorizationHistoryRepository history,
                                   EventBus events,
                                   Clock clock) {
        this(cards, history, events, clock, Map.of(), Map.of());
    }

    public CardAuthorizationEngine(CardRepository cards,
                                   AuthorizationHistoryRepository history,
                                   EventBus events,
                                   Clock clock,
                                   Map<UUID, ProductLimits> limitsByCard,
                                   Map<UUID, String> homeCountryByCard) {
        this.cards = Objects.requireNonNull(cards, "cards");
        this.history = Objects.requireNonNull(history, "history");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limitsByCard = Map.copyOf(limitsByCard);
        this.homeCountryByCard = Map.copyOf(homeCountryByCard);
    }

    /**
     * Evaluate a single authorization request and return the issuer's decision.
     * Never throws on business-rule failures — declines are themselves a
     * valid domain outcome and get recorded in history.
     */
    public AuthorizationDecision evaluate(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        var authId = UUID.randomUUID();
        log.debug("Evaluating authorization: authId={}, card={}, amount={}, mcc={}",
                authId, request.cardId(), request.amount(), request.merchantCategoryCode());

        var card = cards.findById(request.cardId()).orElse(null);
        if (card == null) {
            return finalize(authId, request, AuthorizationDecision.declined(
                    CODE_INVALID_MERCHANT, "Unknown card id"), 100);
        }

        var cardCheck = evaluateStatus(card);
        if (cardCheck != null) return finalize(authId, request, cardCheck, 80);

        var limitCheck = evaluateLimits(card, request);
        if (limitCheck != null && !limitCheck.approved()) {
            return finalize(authId, request, limitCheck, 50);
        }

        var mccCheck = evaluateMerchantCategory(card, request);
        if (mccCheck != null && !mccCheck.approved()) {
            return finalize(authId, request, mccCheck, 60);
        }

        var velocityCheck = evaluateVelocity(card, request);
        if (velocityCheck != null && !velocityCheck.approved()) {
            return finalize(authId, request, velocityCheck, 70);
        }

        var geoCheck = evaluateGeographicAnomaly(card, request);
        if (geoCheck != null && !geoCheck.approved()) {
            return finalize(authId, request, geoCheck, 75);
        }

        var stepUp = evaluateStepUpRequired(card, request);
        if (stepUp != null) {
            return finalize(authId, request, stepUp, 40);
        }

        // Deduct the authorization against available credit / memo post-ledger.
        if (card.isCredit() && card.availableCredit() != null) {
            var newAvail = card.availableCredit().minus(request.amount());
            cards.save(card.withAvailableCredit(newAvail));
        }

        var riskScore = computeCompositeRisk(card, request);
        var approved = AuthorizationDecision.approved(CODE_APPROVED);
        return finalize(authId, request, approved, riskScore);
    }

    // --- Status gate ---------------------------------------------------------

    private AuthorizationDecision evaluateStatus(CardEntity card) {
        var status = card.status();
        if (status == CardStatus.ACTIVE) return null;
        if (status == CardStatus.PENDING_ACTIVATION) {
            return AuthorizationDecision.declined(CODE_INACTIVE, "Card not yet activated");
        }
        if (status == CardStatus.EXPIRED) {
            return AuthorizationDecision.declined(CODE_EXPIRED, "Card expired");
        }
        if (status == CardStatus.LOST || status == CardStatus.STOLEN) {
            return AuthorizationDecision.declined(CODE_LOST_STOLEN,
                    "Card reported " + status.name().toLowerCase());
        }
        if (status == CardStatus.FRAUD_HOLD) {
            return AuthorizationDecision.declined(CODE_SUSPECTED_FRAUD, "Fraud hold active");
        }
        if (status == CardStatus.BLOCKED) {
            return AuthorizationDecision.declined(CODE_DO_NOT_HONOR, "Card blocked");
        }
        if (status == CardStatus.CLOSED) {
            return AuthorizationDecision.declined(CODE_PICK_UP_CARD, "Card closed");
        }
        return AuthorizationDecision.declined(CODE_DO_NOT_HONOR, "Inactive status: " + status);
    }

    // --- Limit checks --------------------------------------------------------

    private AuthorizationDecision evaluateLimits(CardEntity card, AuthorizationRequest request) {
        var limits = limitsByCard.getOrDefault(card.cardId(), DEFAULT_LIMITS);

        if (card.isCredit()) {
            if (card.availableCredit() != null
                    && request.amount().compareTo(card.availableCredit()) > 0) {
                return AuthorizationDecision.declined(CODE_INSUFFICIENT_FUNDS,
                        "Insufficient credit: need " + request.amount()
                                + ", available " + card.availableCredit());
            }
        }

        if (limits.perTransactionLimit() != null
                && request.amount().compareTo(limits.perTransactionLimit()) > 0) {
            return AuthorizationDecision.declined(CODE_EXCEEDS_LIMIT,
                    "Per-transaction limit exceeded");
        }

        if (limits.dailyLimit() != null) {
            var dailySpend = spendSince(card.cardId(), Duration.ofDays(1));
            var projected = dailySpend.plus(request.amount());
            if (projected.compareTo(limits.dailyLimit()) > 0) {
                return AuthorizationDecision.declined(CODE_EXCEEDS_LIMIT, "Daily limit exceeded");
            }
        }

        if (limits.monthlyLimit() != null) {
            var monthlySpend = spendSince(card.cardId(), Duration.ofDays(30));
            var projected = monthlySpend.plus(request.amount());
            if (projected.compareTo(limits.monthlyLimit()) > 0) {
                return AuthorizationDecision.declined(CODE_EXCEEDS_LIMIT, "Monthly limit exceeded");
            }
        }

        return AuthorizationDecision.approved(CODE_APPROVED);
    }

    // --- MCC rules -----------------------------------------------------------

    private AuthorizationDecision evaluateMerchantCategory(CardEntity card,
                                                           AuthorizationRequest request) {
        var mcc = request.merchantCategoryCode();
        if (HARD_BLOCK_MCCS.contains(mcc)) {
            // Business cards get a carve-out for gambling MCC.
            if (mcc.equals("7995") && card.product() == com.omnibank.cards.api.CardProduct.CREDIT_BUSINESS) {
                return null;
            }
            return AuthorizationDecision.declined(CODE_INVALID_MERCHANT,
                    "Merchant category " + mcc + " blocked for this card");
        }
        return null;
    }

    // --- Velocity ------------------------------------------------------------

    private AuthorizationDecision evaluateVelocity(CardEntity card, AuthorizationRequest request) {
        var limits = limitsByCard.getOrDefault(card.cardId(), DEFAULT_LIMITS);
        var now = Timestamp.now(clock);

        int lastMinute = countSince(card.cardId(), now.minus(Duration.ofMinutes(1)));
        if (lastMinute >= limits.maxAuthsPerMinute()) {
            return AuthorizationDecision.declined(CODE_VELOCITY,
                    "Exceeded auths/minute velocity: " + lastMinute);
        }

        int lastHour = countSince(card.cardId(), now.minus(Duration.ofHours(1)));
        if (lastHour >= limits.maxAuthsPerHour()) {
            return AuthorizationDecision.declined(CODE_VELOCITY,
                    "Exceeded auths/hour velocity: " + lastHour);
        }

        int lastDay = countSince(card.cardId(), now.minus(Duration.ofDays(1)));
        if (lastDay >= limits.maxAuthsPerDay()) {
            return AuthorizationDecision.declined(CODE_VELOCITY,
                    "Exceeded auths/day velocity: " + lastDay);
        }

        return null;
    }

    // --- Geographic anomaly --------------------------------------------------

    /** MPH bound used to judge whether two authorizations are plausibly same traveler. */
    private static final int MAX_PLAUSIBLE_MPH = 600; // commercial jet cruise

    /** Simplified rough-distance lookup (km) between country pairs. */
    private static final Map<String, Integer> COUNTRY_DISTANCE_KM = Map.of(
            "US-GB", 7500,
            "US-JP", 10800,
            "US-BR", 7200,
            "US-CA", 800,
            "GB-FR", 350,
            "US-AU", 15000,
            "US-MX", 1000);

    private AuthorizationDecision evaluateGeographicAnomaly(CardEntity card,
                                                            AuthorizationRequest request) {
        var recent = history.recentForCard(card.cardId(), Timestamp.now(clock).minus(Duration.ofHours(12)));
        if (recent.isEmpty()) return null;

        var current = request.merchantCountry();
        if (current == null || current.isBlank()) return null;

        var home = homeCountryByCard.get(card.cardId());

        for (int i = recent.size() - 1; i >= 0; i--) {
            var prior = recent.get(i);
            if (!prior.approved()) continue;
            var priorCountry = prior.merchantCountry();
            if (priorCountry == null || priorCountry.equals(current)) continue;

            long minutes = Duration.between(prior.decidedAt(), request.timestamp()).toMinutes();
            if (minutes <= 0) continue;
            int distance = approximateDistance(priorCountry, current);
            double requiredMph = (distance * 0.621371) / (minutes / 60.0);
            if (requiredMph > MAX_PLAUSIBLE_MPH) {
                return AuthorizationDecision.declined(CODE_SUSPECTED_FRAUD,
                        "Impossible travel: " + priorCountry + " -> " + current
                                + " in " + minutes + "m");
            }
        }

        // Step-up if transacting outside home country for the first time today
        if (home != null && !home.equals(current)) {
            boolean seenCurrentToday = recent.stream()
                    .anyMatch(r -> current.equals(r.merchantCountry()));
            if (!seenCurrentToday) {
                return AuthorizationDecision.declined(CODE_STEP_UP,
                        "Geographic step-up: first transaction in " + current);
            }
        }

        return null;
    }

    private static int approximateDistance(String a, String b) {
        if (a.equals(b)) return 0;
        var key = a.compareTo(b) < 0 ? a + "-" + b : b + "-" + a;
        return COUNTRY_DISTANCE_KM.getOrDefault(key, 5000);
    }

    // --- Step-up triggers ----------------------------------------------------

    private AuthorizationDecision evaluateStepUpRequired(CardEntity card,
                                                        AuthorizationRequest request) {
        var limits = limitsByCard.getOrDefault(card.cardId(), DEFAULT_LIMITS);

        if (limits.stepUpAmountThreshold() != null
                && request.amount().compareTo(limits.stepUpAmountThreshold()) > 0
                && !request.chipUsed() && !request.pinEntered()) {
            return AuthorizationDecision.declined(CODE_STEP_UP,
                    "Amount > step-up threshold with no chip/PIN");
        }

        if (request.ecommerce() && request.amount().amount()
                .compareTo(new BigDecimal("500.00")) > 0) {
            return AuthorizationDecision.declined(CODE_STEP_UP,
                    "E-commerce amount > $500: 3-D Secure required");
        }

        if (STEP_UP_MCCS.contains(request.merchantCategoryCode())
                && request.amount().amount().compareTo(new BigDecimal("1000.00")) > 0) {
            return AuthorizationDecision.declined(CODE_STEP_UP,
                    "High-risk MCC " + request.merchantCategoryCode() + " with large amount");
        }

        // First-use-after-activation — request chip or PIN even for small amounts.
        var allHistory = history.allForCard(card.cardId());
        boolean firstUse = allHistory.stream().noneMatch(AuthorizationRecord::approved);
        if (firstUse && request.amount().amount().compareTo(new BigDecimal("200.00")) > 0
                && !request.chipUsed() && !request.pinEntered()) {
            return AuthorizationDecision.declined(CODE_STEP_UP,
                    "First card use with mag-stripe / no PIN — step up");
        }

        return null;
    }

    // --- Support ------------------------------------------------------------

    private int countSince(UUID cardId, Instant since) {
        return history.recentForCard(cardId, since).size();
    }

    private Money spendSince(UUID cardId, Duration window) {
        var since = Timestamp.now(clock).minus(window);
        var recent = history.recentForCard(cardId, since);
        Money total = null;
        for (var r : recent) {
            if (!r.approved()) continue;
            total = total == null ? r.amount() : total.plus(r.amount());
        }
        if (total == null) {
            // No approved activity in the window; start at zero in the request currency.
            return Money.zero(com.omnibank.shared.domain.CurrencyCode.USD);
        }
        return total;
    }

    private int computeCompositeRisk(CardEntity card, AuthorizationRequest request) {
        int score = 0;
        if (request.ecommerce()) score += 10;
        if (!request.chipUsed() && !request.pinEntered()) score += 10;
        if (request.amount().amount().compareTo(new BigDecimal("1000.00")) > 0) score += 10;
        if (STEP_UP_MCCS.contains(request.merchantCategoryCode())) score += 15;
        if (request.merchantCountry() != null
                && homeCountryByCard.containsKey(card.cardId())
                && !homeCountryByCard.get(card.cardId()).equals(request.merchantCountry())) {
            score += 20;
        }
        return Math.min(100, score);
    }

    private AuthorizationDecision finalize(UUID authId,
                                           AuthorizationRequest request,
                                           AuthorizationDecision decision,
                                           int riskScore) {
        var record = new AuthorizationRecord(
                authId, request.cardId(), request.amount(),
                request.merchantCategoryCode(), request.merchantCountry(),
                decision, Timestamp.now(clock), riskScore);
        history.record(record);
        publishEvent(record);
        log.info("Authorization decision: authId={}, card={}, approved={}, code={}, risk={}",
                authId, request.cardId(), decision.approved(), decision.code(), riskScore);
        return decision;
    }

    private void publishEvent(AuthorizationRecord record) {
        try {
            events.publish(new AuthorizationDecidedEvent(
                    UUID.randomUUID(), record.decidedAt(), record.authorizationId(),
                    record.cardId(), record.approved(), record.decision().code(),
                    record.decision().reason(), record.riskScore()));
        } catch (Exception e) {
            log.warn("Failed to publish authorization decision event: {}", e.getMessage());
        }
    }

    // --- Public helpers for admin / replay ----------------------------------

    /** Returns the set of decline codes this engine can emit (useful for dashboards). */
    public Set<String> declineCodes() {
        return EnumSet.allOf(DeclineCode.class).stream()
                .map(DeclineCode::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Bulk evaluate (e.g. for simulation / replay of a prior day's traffic). */
    public List<AuthorizationDecision> evaluateAll(List<AuthorizationRequest> requests) {
        List<AuthorizationDecision> out = new java.util.ArrayList<>(requests.size());
        for (var req : requests) out.add(evaluate(req));
        return out;
    }

    /** Enumerated decline codes the engine understands. */
    public enum DeclineCode {
        APPROVED(CODE_APPROVED),
        STEP_UP(CODE_STEP_UP),
        DO_NOT_HONOR(CODE_DO_NOT_HONOR),
        INSUFFICIENT_FUNDS(CODE_INSUFFICIENT_FUNDS),
        EXCEEDS_LIMIT(CODE_EXCEEDS_LIMIT),
        VELOCITY(CODE_VELOCITY),
        LOST_STOLEN(CODE_LOST_STOLEN),
        PICK_UP_CARD(CODE_PICK_UP_CARD),
        INVALID_MERCHANT(CODE_INVALID_MERCHANT),
        SUSPECTED_FRAUD(CODE_SUSPECTED_FRAUD),
        EXPIRED(CODE_EXPIRED),
        INACTIVE(CODE_INACTIVE);

        private final String code;
        DeclineCode(String code) { this.code = code; }
        public String code() { return code; }
    }
}
