package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardProduct;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Category-based rewards engine.
 *
 * <ul>
 *   <li><b>Accrual tables</b> map MCCs to category multipliers (3x travel,
 *       2x dining, 1x everything else).</li>
 *   <li><b>Tiered bonuses</b> stack on top of base accrual (e.g. 10%
 *       lifestyle kicker on dining for Platinum members).</li>
 *   <li><b>Redemption</b> supports cash-back, travel at boosted value, and
 *       gift cards at a discount to cash.</li>
 *   <li><b>Expiration</b> follows a rolling-24-month rule; we bucket
 *       earnings by accrual month so expiry can sweep oldest first.</li>
 * </ul>
 */
@Service
public class CardRewardsCalculator {

    private static final Logger log = LoggerFactory.getLogger(CardRewardsCalculator.class);

    /** Category multipliers by MCC range. */
    public enum RewardsCategory {
        TRAVEL(3),
        DINING(2),
        GROCERIES(2),
        FUEL(2),
        ENTERTAINMENT(2),
        ONLINE_SHOPPING(2),
        DRUG_STORES(2),
        EVERYTHING_ELSE(1);

        private final int multiplier;
        RewardsCategory(int m) { this.multiplier = m; }
        public int multiplier() { return multiplier; }
    }

    /** Mapping of MCC ranges into reward categories — abbreviated. */
    private static final Map<String, RewardsCategory> MCC_CATEGORY = Map.ofEntries(
            Map.entry("3000", RewardsCategory.TRAVEL),  // Airlines
            Map.entry("3001", RewardsCategory.TRAVEL),
            Map.entry("3005", RewardsCategory.TRAVEL),
            Map.entry("3058", RewardsCategory.TRAVEL),  // Delta
            Map.entry("4111", RewardsCategory.TRAVEL),  // Commuter transport
            Map.entry("4511", RewardsCategory.TRAVEL),  // Airlines generic
            Map.entry("4722", RewardsCategory.TRAVEL),  // Travel agencies
            Map.entry("7011", RewardsCategory.TRAVEL),  // Hotels/motels
            Map.entry("5812", RewardsCategory.DINING),
            Map.entry("5813", RewardsCategory.DINING),
            Map.entry("5814", RewardsCategory.DINING),
            Map.entry("5411", RewardsCategory.GROCERIES),
            Map.entry("5499", RewardsCategory.GROCERIES),
            Map.entry("5541", RewardsCategory.FUEL),
            Map.entry("5542", RewardsCategory.FUEL),
            Map.entry("7832", RewardsCategory.ENTERTAINMENT),
            Map.entry("7922", RewardsCategory.ENTERTAINMENT),
            Map.entry("5912", RewardsCategory.DRUG_STORES),
            Map.entry("5999", RewardsCategory.ONLINE_SHOPPING)
    );

    /** Program tiers determine bonus kickers — expressed in basis points. */
    public enum RewardsTier {
        STANDARD(0),
        GOLD(500),     // 5%
        PLATINUM(1000),// 10%
        RESERVE(2500); // 25%

        private final int bonusBps;
        RewardsTier(int bonusBps) { this.bonusBps = bonusBps; }
        public int bonusBps() { return bonusBps; }
    }

    /**
     * Redemption channels with different cash-out ratios. Rate is
     * expressed as "cents per point" (so 1.00 = 1 cent / point = $0.01
     * per point = $1.00 per 100 points).
     */
    public enum RedemptionChannel {
        CASH_BACK(new BigDecimal("1.00")),
        STATEMENT_CREDIT(new BigDecimal("1.00")),
        TRAVEL(new BigDecimal("1.25")),
        GIFT_CARDS(new BigDecimal("1.10")),
        MERCHANDISE(new BigDecimal("0.80"));

        private final BigDecimal centsPerPoint;
        RedemptionChannel(BigDecimal centsPerPoint) { this.centsPerPoint = centsPerPoint; }
        public BigDecimal centsPerPoint() { return centsPerPoint; }
    }

    /** A single accrual — immutable ledger-style record. */
    public record RewardsAccrual(
            UUID accrualId,
            UUID cardId,
            UUID authorizationId,
            long points,
            RewardsCategory category,
            Instant earnedAt,
            LocalDate expiresOn,
            boolean redeemed) {}

    /** Rollup of the customer's rewards ledger. */
    public record RewardsBalance(
            UUID cardId,
            long outstandingPoints,
            long lifetimeEarned,
            long lifetimeRedeemed,
            long expiredPoints) {}

    /** Event published on accrual and redemption — downstream notifications. */
    public record RewardsEvent(
            UUID eventId,
            Instant occurredAt,
            UUID cardId,
            String kind,
            long points,
            String detail) implements DomainEvent {

        @Override
        public String eventType() {
            return "cards.rewards_" + kind.toLowerCase();
        }
    }

    private final Map<UUID, List<RewardsAccrual>> accrualsByCard = new ConcurrentHashMap<>();
    private final Map<UUID, RewardsTier> tierByCard;
    private final EventBus events;
    private final Clock clock;

    @Autowired
    public CardRewardsCalculator(EventBus events, Clock clock) {
        this(events, clock, Map.of());
    }

    public CardRewardsCalculator(EventBus events, Clock clock, Map<UUID, RewardsTier> tierByCard) {
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tierByCard = new ConcurrentHashMap<>(Map.copyOf(tierByCard));
    }

    /** Update a card's tier — e.g. after annual review. */
    public void setTier(UUID cardId, RewardsTier tier) {
        tierByCard.put(cardId, tier);
    }

    /**
     * Compute accrual for a purchase. Products that don't participate
     * (debit cards, prepaid) yield zero points. Amount determines base
     * points (1 pt per dollar), category multiplier and tier bonus stack.
     */
    public RewardsAccrual accrue(UUID cardId,
                                 UUID authorizationId,
                                 CardProduct product,
                                 Money purchaseAmount,
                                 String merchantCategoryCode) {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(purchaseAmount, "purchaseAmount");

        if (!isEligibleForPoints(product)) {
            log.debug("Skipping rewards accrual for non-eligible product: {}", product);
            return null;
        }
        if (!purchaseAmount.isPositive()) {
            return null;
        }

        var category = resolveCategory(merchantCategoryCode);
        long basePoints = purchaseAmount.amount()
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
        long categoryPoints = basePoints * category.multiplier();

        var tier = tierByCard.getOrDefault(cardId, RewardsTier.STANDARD);
        long bonusPoints = (categoryPoints * tier.bonusBps()) / 10_000L;
        long totalPoints = categoryPoints + bonusPoints;

        var now = Timestamp.now(clock);
        var expires = now.atZone(Timestamp.BANK_ZONE).toLocalDate().plusMonths(24);
        var accrual = new RewardsAccrual(
                UUID.randomUUID(), cardId, authorizationId, totalPoints,
                category, now, expires, false);

        accrualsByCard.computeIfAbsent(cardId, k -> new ArrayList<>()).add(accrual);
        publish(cardId, "ACCRUED", totalPoints,
                "category=" + category + ",tier=" + tier + ",mcc=" + merchantCategoryCode);
        log.debug("Accrued {} pts: card={}, category={}, tier={}",
                totalPoints, cardId, category, tier);
        return accrual;
    }

    /**
     * Redeem points — converts points to a monetary equivalent based on
     * channel. Oldest-first FIFO over the non-redeemed ledger.
     */
    public Money redeem(UUID cardId, long requestedPoints, RedemptionChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (requestedPoints <= 0) {
            throw new IllegalArgumentException("requestedPoints must be positive");
        }
        var accruals = accrualsByCard.getOrDefault(cardId, new ArrayList<>());
        long outstanding = outstandingPoints(accruals);
        if (requestedPoints > outstanding) {
            throw new IllegalStateException(
                    "Insufficient points: requested " + requestedPoints + ", have " + outstanding);
        }

        long remaining = requestedPoints;
        var updated = new ArrayList<RewardsAccrual>(accruals.size());
        for (var a : accruals) {
            if (remaining <= 0 || a.redeemed()) {
                updated.add(a);
                continue;
            }
            if (a.points() <= remaining) {
                remaining -= a.points();
                updated.add(new RewardsAccrual(
                        a.accrualId(), a.cardId(), a.authorizationId(), a.points(),
                        a.category(), a.earnedAt(), a.expiresOn(), true));
            } else {
                // Partial redemption — split the accrual.
                updated.add(new RewardsAccrual(
                        a.accrualId(), a.cardId(), a.authorizationId(),
                        remaining, a.category(), a.earnedAt(), a.expiresOn(), true));
                updated.add(new RewardsAccrual(
                        UUID.randomUUID(), a.cardId(), a.authorizationId(),
                        a.points() - remaining, a.category(),
                        a.earnedAt(), a.expiresOn(), false));
                remaining = 0;
            }
        }
        accrualsByCard.put(cardId, updated);

        var cash = convertToCash(requestedPoints, channel);
        publish(cardId, "REDEEMED", requestedPoints,
                "channel=" + channel + ",cash=" + cash);
        log.info("Redeemed {} pts via {}: card={}, cash={}", requestedPoints, channel, cardId, cash);
        return cash;
    }

    /**
     * Expire all accruals whose expiry date has passed. Returns the total
     * points written off.
     */
    public long expireOldPoints(UUID cardId) {
        var today = LocalDate.now(clock);
        var accruals = accrualsByCard.getOrDefault(cardId, new ArrayList<>());
        long expired = 0;
        var updated = new ArrayList<RewardsAccrual>(accruals.size());
        for (var a : accruals) {
            if (!a.redeemed() && a.expiresOn().isBefore(today)) {
                expired += a.points();
                // Marking redeemed acts as tombstoned — the point balance
                // no longer counts toward outstanding and we track it in
                // the lifetime view.
                updated.add(new RewardsAccrual(
                        a.accrualId(), a.cardId(), a.authorizationId(),
                        a.points(), a.category(), a.earnedAt(),
                        a.expiresOn(), true));
            } else {
                updated.add(a);
            }
        }
        accrualsByCard.put(cardId, updated);
        if (expired > 0) {
            publish(cardId, "EXPIRED", expired, "rolling 24m");
            log.info("Expired {} pts on card {}", expired, cardId);
        }
        return expired;
    }

    /** Return the full accrual ledger for a card (copy). */
    public List<RewardsAccrual> historyFor(UUID cardId) {
        return List.copyOf(accrualsByCard.getOrDefault(cardId, new ArrayList<>()));
    }

    /** Roll the ledger up into the balance summary shown in statements. */
    public RewardsBalance balanceFor(UUID cardId) {
        var accruals = accrualsByCard.getOrDefault(cardId, new ArrayList<>());
        long outstanding = 0, lifetimeEarn = 0, lifetimeRedeem = 0, expired = 0;
        var today = LocalDate.now(clock);
        for (var a : accruals) {
            lifetimeEarn += a.points();
            if (a.redeemed()) {
                lifetimeRedeem += a.points();
                if (a.expiresOn().isBefore(today)) {
                    expired += a.points();
                }
            } else {
                outstanding += a.points();
            }
        }
        // lifetimeRedeem includes expired, so strip it for the "used" view.
        return new RewardsBalance(cardId, outstanding, lifetimeEarn,
                lifetimeRedeem - expired, expired);
    }

    private boolean isEligibleForPoints(CardProduct product) {
        return product == CardProduct.CREDIT_REWARDS
                || product == CardProduct.CREDIT_BUSINESS
                || product == CardProduct.CREDIT_BASIC
                || product == CardProduct.DEBIT_PREMIUM;
    }

    private static long outstandingPoints(List<RewardsAccrual> list) {
        long total = 0;
        for (var a : list) {
            if (!a.redeemed()) total += a.points();
        }
        return total;
    }

    static RewardsCategory resolveCategory(String mcc) {
        if (mcc == null) return RewardsCategory.EVERYTHING_ELSE;
        var exact = MCC_CATEGORY.get(mcc);
        if (exact != null) return exact;
        // Fallback: range-based classification
        int code;
        try {
            code = Integer.parseInt(mcc);
        } catch (NumberFormatException e) {
            return RewardsCategory.EVERYTHING_ELSE;
        }
        if (code >= 3000 && code <= 3999) return RewardsCategory.TRAVEL;
        if (code >= 4100 && code <= 4199) return RewardsCategory.TRAVEL;
        if (code >= 4500 && code <= 4599) return RewardsCategory.TRAVEL;
        if (code >= 5810 && code <= 5815) return RewardsCategory.DINING;
        if (code >= 5400 && code <= 5499) return RewardsCategory.GROCERIES;
        if (code >= 5540 && code <= 5549) return RewardsCategory.FUEL;
        if (code >= 7000 && code <= 7099) return RewardsCategory.TRAVEL;
        if (code >= 7800 && code <= 7999) return RewardsCategory.ENTERTAINMENT;
        return RewardsCategory.EVERYTHING_ELSE;
    }

    /** 100 pts at 1 cpp (CASH_BACK) yields $1.00. */
    private Money convertToCash(long points, RedemptionChannel channel) {
        // points * cents-per-point = total cents; divide by 100 for dollars.
        var cents = channel.centsPerPoint()
                .multiply(BigDecimal.valueOf(points));
        var dollars = cents.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
        return Money.of(dollars, CurrencyCode.USD);
    }

    private void publish(UUID cardId, String kind, long points, String detail) {
        try {
            events.publish(new RewardsEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    cardId, kind, points, detail));
        } catch (Exception e) {
            log.warn("Failed to publish rewards event kind={}: {}", kind, e.getMessage());
        }
    }

    /** Enumerates the supported redemption channels — helper for admin UI. */
    public Set<RedemptionChannel> availableChannels() {
        return Set.of(RedemptionChannel.values());
    }
}
