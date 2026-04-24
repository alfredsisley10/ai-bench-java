package com.omnibank.cards.internal;

import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule-based fraud evaluator. Runs 10 rules against an inbound authorization
 * request plus the card's behavioral profile and returns a composite score.
 * Intended as a complement to the live authorization engine — the auth
 * engine uses this for risk scoring, and the back-office fraud desk uses
 * it post-hoc to triage alerts.
 *
 * <p>Each rule returns 0 when it does not fire and a positive integer when
 * it does. The total score is the sum across all rules, capped at 100.
 * Rules can also "trigger" without contributing to score (used for
 * deterministic bright-lines like chip-absence on a chip-mandatory BIN).
 */
@Service
public class CardFraudRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CardFraudRuleEvaluator.class);

    /** Behavioral profile computed from a card's history. */
    public record CardBehaviorProfile(
            UUID cardId,
            Money averageTicketSize,
            Money largestTransactionEver,
            int typicalAuthsPerDay,
            Set<String> frequentCountries,
            Set<String> knownDevices,
            Set<String> frequentMccs,
            Instant lastActivityAt) {}

    /** Fire decision for a single rule. */
    public record RuleHit(String ruleCode, int score, String reason) {}

    /** Composite output. */
    public record FraudAssessment(
            UUID cardId,
            int compositeScore,
            List<RuleHit> hits,
            boolean recommendHardBlock,
            boolean recommendStepUp) {}

    /** Thresholds that turn a composite score into a recommendation. */
    public record Thresholds(int stepUpMin, int hardBlockMin) {
        public Thresholds {
            if (stepUpMin <= 0 || hardBlockMin <= stepUpMin) {
                throw new IllegalArgumentException(
                        "stepUpMin must be >0 and < hardBlockMin");
            }
        }
    }

    /** Canonical known-risk MCC table — lines up with the authorization engine. */
    private static final Set<String> HIGH_RISK_MCCS = Set.of(
            "7995", "6051", "5967", "5993", "6010", "6011", "4829");

    /** MCCs that imply the cardholder is likely traveling. */
    private static final Set<String> TRAVEL_MCCS = Set.of(
            "3000", "3001", "3005", "3058", "4111", "4511", "4722", "7011");

    private final AuthorizationHistoryRepository history;
    private final Clock clock;
    private final Thresholds thresholds;
    private final Map<UUID, CardBehaviorProfile> profiles = new ConcurrentHashMap<>();

    @Autowired
    public CardFraudRuleEvaluator(AuthorizationHistoryRepository history, Clock clock) {
        this(history, clock, new Thresholds(40, 75));
    }

    public CardFraudRuleEvaluator(AuthorizationHistoryRepository history,
                                  Clock clock,
                                  Thresholds thresholds) {
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** Attach a profile for a card. Tests and seed pipelines call this directly. */
    public void attachProfile(CardBehaviorProfile profile) {
        profiles.put(profile.cardId(), profile);
    }

    /**
     * Evaluate the request. Safe to call without an attached profile — the
     * evaluator degrades to bright-line rules only.
     */
    public FraudAssessment evaluate(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        var profile = profiles.get(request.cardId());
        var hits = new ArrayList<RuleHit>();

        applyRule(hits, velocityRule(request));
        applyRule(hits, geolocationRule(request, profile));
        applyRule(hits, mccRiskRule(request));
        applyRule(hits, amountVsProfileRule(request, profile));
        applyRule(hits, chipAbsenceRule(request));
        applyRule(hits, newDeviceRule(request, profile));
        applyRule(hits, eCommerceAnomalyRule(request, profile));
        applyRule(hits, nightOwlRule(request, profile));
        applyRule(hits, cardTestingRule(request));
        applyRule(hits, dormantCardRule(request, profile));

        int score = 0;
        for (var h : hits) score += h.score();
        if (score > 100) score = 100;

        boolean hardBlock = score >= thresholds.hardBlockMin();
        boolean stepUp = score >= thresholds.stepUpMin() && !hardBlock;

        var assessment = new FraudAssessment(
                request.cardId(), score, List.copyOf(hits), hardBlock, stepUp);
        log.debug("Fraud assessment: card={}, score={}, hits={}, hardBlock={}, stepUp={}",
                request.cardId(), score, hits.size(), hardBlock, stepUp);
        return assessment;
    }

    private static void applyRule(List<RuleHit> hits, RuleHit candidate) {
        if (candidate != null && candidate.score() > 0) {
            hits.add(candidate);
        }
    }

    // --- Individual rules ---------------------------------------------------

    /** R01: transaction count in the last 10 minutes. */
    RuleHit velocityRule(AuthorizationRequest request) {
        var since = Timestamp.now(clock).minus(Duration.ofMinutes(10));
        int recent = history.recentForCard(request.cardId(), since).size();
        if (recent >= 5) {
            return new RuleHit("R01_VELOCITY", 25,
                    "Recent velocity: " + recent + " auths in 10m");
        }
        if (recent >= 3) {
            return new RuleHit("R01_VELOCITY", 15,
                    "Elevated velocity: " + recent + " auths in 10m");
        }
        return null;
    }

    /** R02: merchant country outside the card's typical set. */
    RuleHit geolocationRule(AuthorizationRequest request, CardBehaviorProfile profile) {
        if (profile == null) return null;
        if (request.merchantCountry() == null || request.merchantCountry().isBlank()) return null;
        if (profile.frequentCountries().contains(request.merchantCountry())) return null;
        boolean travelLike = TRAVEL_MCCS.contains(request.merchantCategoryCode());
        int score = travelLike ? 10 : 20;
        return new RuleHit("R02_GEOLOCATION", score,
                "Unusual country " + request.merchantCountry());
    }

    /** R03: MCC is on the high-risk list. */
    RuleHit mccRiskRule(AuthorizationRequest request) {
        if (HIGH_RISK_MCCS.contains(request.merchantCategoryCode())) {
            return new RuleHit("R03_MCC_RISK", 20,
                    "High-risk MCC: " + request.merchantCategoryCode());
        }
        return null;
    }

    /** R04: transaction amount is a large multiple of the cardholder's average ticket. */
    RuleHit amountVsProfileRule(AuthorizationRequest request, CardBehaviorProfile profile) {
        if (profile == null || profile.averageTicketSize() == null) return null;
        if (profile.averageTicketSize().isZero()) return null;
        var ratio = request.amount().amount()
                .divide(profile.averageTicketSize().amount(), 4, java.math.RoundingMode.HALF_EVEN);
        if (ratio.compareTo(new BigDecimal("10")) > 0) {
            return new RuleHit("R04_AMOUNT_DEVIATION", 25,
                    "Amount " + request.amount() + " is " + ratio.setScale(1, java.math.RoundingMode.HALF_EVEN) + "x average");
        }
        if (ratio.compareTo(new BigDecimal("5")) > 0) {
            return new RuleHit("R04_AMOUNT_DEVIATION", 12,
                    "Amount elevated vs average");
        }
        return null;
    }

    /** R05: in-person authorization with no chip and no PIN — mag stripe. */
    RuleHit chipAbsenceRule(AuthorizationRequest request) {
        if (!request.cardPresent()) return null;
        if (request.chipUsed() || request.pinEntered() || request.contactless()) return null;
        return new RuleHit("R05_CHIP_ABSENCE", 15,
                "Card-present mag-stripe (no chip / PIN / NFC)");
    }

    /** R06: device fingerprint not in the known-device list. */
    RuleHit newDeviceRule(AuthorizationRequest request, CardBehaviorProfile profile) {
        if (profile == null) return null;
        if (request.deviceFingerprint() == null) return null;
        if (profile.knownDevices().contains(request.deviceFingerprint())) return null;
        return new RuleHit("R06_NEW_DEVICE", 12,
                "Unrecognized device fingerprint");
    }

    /** R07: ecommerce authorization in unusually high amount range. */
    RuleHit eCommerceAnomalyRule(AuthorizationRequest request, CardBehaviorProfile profile) {
        if (!request.ecommerce()) return null;
        if (request.amount().amount().compareTo(new BigDecimal("1000.00")) <= 0) return null;
        if (profile != null && profile.largestTransactionEver() != null
                && request.amount().compareTo(profile.largestTransactionEver()) <= 0) {
            return new RuleHit("R07_ECOM_ANOMALY", 8,
                    "Large ecom but within prior max");
        }
        return new RuleHit("R07_ECOM_ANOMALY", 18,
                "Large ecom above prior max");
    }

    /** R08: late-night activity for a daytime-spender. */
    RuleHit nightOwlRule(AuthorizationRequest request, CardBehaviorProfile profile) {
        var hour = request.timestamp().atZone(Timestamp.BANK_ZONE).getHour();
        if (hour >= 7 && hour <= 23) return null;
        if (profile == null) {
            return new RuleHit("R08_NIGHT_OWL", 5, "Off-hours activity (profile absent)");
        }
        return new RuleHit("R08_NIGHT_OWL", 10,
                "Off-hours transaction at " + hour + ":00");
    }

    /** R09: many small-dollar authorizations in a row — classic card-testing pattern. */
    RuleHit cardTestingRule(AuthorizationRequest request) {
        var since = Timestamp.now(clock).minus(Duration.ofMinutes(30));
        var recent = history.recentForCard(request.cardId(), since);
        int smallCount = 0;
        for (var r : recent) {
            if (r.amount().amount().compareTo(new BigDecimal("5.00")) < 0) {
                smallCount++;
            }
        }
        if (smallCount >= 3
                && request.amount().amount().compareTo(new BigDecimal("5.00")) < 0) {
            return new RuleHit("R09_CARD_TESTING", 30,
                    "Card testing pattern: " + (smallCount + 1) + " small auths in 30m");
        }
        return null;
    }

    /** R10: card dormant for >90 days and comes alive with a large transaction. */
    RuleHit dormantCardRule(AuthorizationRequest request, CardBehaviorProfile profile) {
        if (profile == null || profile.lastActivityAt() == null) return null;
        var daysSince = Duration.between(profile.lastActivityAt(), request.timestamp()).toDays();
        if (daysSince < 90) return null;
        if (request.amount().amount().compareTo(new BigDecimal("500.00")) < 0) return null;
        return new RuleHit("R10_DORMANT_REVIVAL", 20,
                "Dormant card (" + daysSince + " days) with large amount");
    }

    /** List of known rule codes — handy for dashboards. */
    public List<String> ruleCatalog() {
        return List.of(
                "R01_VELOCITY",
                "R02_GEOLOCATION",
                "R03_MCC_RISK",
                "R04_AMOUNT_DEVIATION",
                "R05_CHIP_ABSENCE",
                "R06_NEW_DEVICE",
                "R07_ECOM_ANOMALY",
                "R08_NIGHT_OWL",
                "R09_CARD_TESTING",
                "R10_DORMANT_REVIVAL"
        );
    }

    /** Build a default profile shell — callers fill in fields. */
    public static CardBehaviorProfile emptyProfile(UUID cardId) {
        return new CardBehaviorProfile(
                cardId, null, null, 0,
                Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), null);
    }
}
