package com.omnibank.fraud.internal;

import com.omnibank.fraud.api.FraudDecision;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scores transactions using weighted risk factors to produce a composite risk
 * score (0-1000) with per-factor explanations. The score drives the fraud
 * decision: PASS, REVIEW, or BLOCK.
 *
 * <p>Risk factors evaluated:
 * <ul>
 *   <li><b>Amount:</b> High-value transactions relative to account history</li>
 *   <li><b>Velocity:</b> Transaction frequency in recent time windows</li>
 *   <li><b>Geography:</b> Transaction origin country vs. customer's home country</li>
 *   <li><b>Device:</b> Known vs. unknown device, device trust score</li>
 *   <li><b>Merchant category:</b> High-risk MCC codes (gambling, crypto, wire services)</li>
 *   <li><b>Time of day:</b> Transactions outside customer's normal activity pattern</li>
 *   <li><b>Channel:</b> Risk varies by channel (card-present vs. card-not-present)</li>
 * </ul>
 *
 * <p>Weights are configurable per-product and per-customer segment. The engine
 * is designed to be called synchronously in the authorization path with sub-10ms
 * latency targets.
 */
public class TransactionRiskScorer {

    private static final Logger log = LoggerFactory.getLogger(TransactionRiskScorer.class);
    private static final MathContext MC = new MathContext(6, RoundingMode.HALF_EVEN);

    /** Score thresholds for verdict determination. */
    private static final int REVIEW_THRESHOLD = 400;
    private static final int BLOCK_THRESHOLD = 700;
    private static final int MAX_SCORE = 1000;

    /** Risk factor weights (must sum to 1.0). */
    private static final Map<RiskFactor, BigDecimal> DEFAULT_WEIGHTS = Map.of(
            RiskFactor.AMOUNT, new BigDecimal("0.20"),
            RiskFactor.VELOCITY, new BigDecimal("0.20"),
            RiskFactor.GEOGRAPHY, new BigDecimal("0.15"),
            RiskFactor.DEVICE, new BigDecimal("0.15"),
            RiskFactor.MERCHANT_CATEGORY, new BigDecimal("0.10"),
            RiskFactor.TIME_OF_DAY, new BigDecimal("0.10"),
            RiskFactor.CHANNEL, new BigDecimal("0.10")
    );

    /** High-risk merchant category codes (MCC). */
    private static final Set<String> HIGH_RISK_MCCS = Set.of(
            "7995", // Gambling
            "6051", // Crypto / quasi-cash
            "4829", // Wire transfer / money orders
            "5933", // Pawn shops
            "5944", // Jewelry stores (fencing risk)
            "6012", // Financial institutions — manual cash
            "5999"  // Miscellaneous retail (common in fraud)
    );

    /** Medium-risk MCCs. */
    private static final Set<String> MEDIUM_RISK_MCCS = Set.of(
            "5912", // Drug stores (high value potential)
            "5411", // Grocery stores (cash-back exploit)
            "5732", // Electronics
            "5311"  // Department stores
    );

    /** Countries with elevated fraud risk. */
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of(
            "NG", "RO", "UA", "BY", "VN", "PH", "ID", "BR", "IN"
    );

    enum RiskFactor {
        AMOUNT, VELOCITY, GEOGRAPHY, DEVICE, MERCHANT_CATEGORY, TIME_OF_DAY, CHANNEL
    }

    record RiskFactorScore(RiskFactor factor, int rawScore, BigDecimal weight,
                           int weightedScore, String explanation) {}

    public record TransactionContext(
            AccountNumber account,
            Money amount,
            String merchantMcc,
            String merchantCountry,
            String customerHomeCountry,
            String deviceFingerprint,
            boolean deviceTrusted,
            int deviceTrustScore,
            String channel,
            Instant transactionTime,
            int recentTxnCount1Hr,
            int recentTxnCount24Hr,
            Money averageTransactionAmount
    ) {
        public TransactionContext {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(amount, "amount");
        }
    }

    record ScoringResult(int compositeScore, FraudDecision.Verdict verdict,
                          List<RiskFactorScore> factorScores,
                          List<String> signals) {}

    private final Clock clock;

    public TransactionRiskScorer(Clock clock) {
        this.clock = clock;
    }

    /**
     * Score a transaction and produce a fraud decision with full explanation.
     */
    public FraudDecision score(TransactionContext context) {
        ScoringResult result = computeScore(context);

        log.debug("Risk score for account {} amount {}: {} ({})",
                context.account(), context.amount(), result.compositeScore(), result.verdict());

        return new FraudDecision(result.verdict(), result.compositeScore(), result.signals());
    }

    /**
     * Compute the detailed scoring result with per-factor breakdown.
     */
    ScoringResult computeScore(TransactionContext context) {
        List<RiskFactorScore> factorScores = new ArrayList<>();
        List<String> signals = new ArrayList<>();

        factorScores.add(scoreAmount(context, signals));
        factorScores.add(scoreVelocity(context, signals));
        factorScores.add(scoreGeography(context, signals));
        factorScores.add(scoreDevice(context, signals));
        factorScores.add(scoreMerchantCategory(context, signals));
        factorScores.add(scoreTimeOfDay(context, signals));
        factorScores.add(scoreChannel(context, signals));

        int compositeScore = factorScores.stream()
                .mapToInt(RiskFactorScore::weightedScore)
                .sum();
        compositeScore = Math.min(compositeScore, MAX_SCORE);

        FraudDecision.Verdict verdict = determineVerdict(compositeScore);
        return new ScoringResult(compositeScore, verdict, List.copyOf(factorScores),
                List.copyOf(signals));
    }

    private RiskFactorScore scoreAmount(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;
        Money amount = ctx.amount();

        if (ctx.averageTransactionAmount() != null && ctx.averageTransactionAmount().isPositive()) {
            BigDecimal ratio = amount.amount().divide(
                    ctx.averageTransactionAmount().amount(), MC);

            if (ratio.compareTo(BigDecimal.TEN) > 0) {
                rawScore = 900;
                signals.add("Amount is 10x+ customer average");
            } else if (ratio.compareTo(BigDecimal.valueOf(5)) > 0) {
                rawScore = 700;
                signals.add("Amount is 5x-10x customer average");
            } else if (ratio.compareTo(BigDecimal.valueOf(3)) > 0) {
                rawScore = 400;
                signals.add("Amount is 3x-5x customer average");
            } else if (ratio.compareTo(BigDecimal.TWO) > 0) {
                rawScore = 200;
            }
        }

        // Absolute thresholds
        if (amount.compareTo(Money.of("10000.00", CurrencyCode.USD)) >= 0) {
            rawScore = Math.max(rawScore, 500);
            signals.add("Transaction >= $10,000 (CTR threshold)");
        } else if (amount.compareTo(Money.of("5000.00", CurrencyCode.USD)) >= 0) {
            rawScore = Math.max(rawScore, 300);
        }

        return weighted(RiskFactor.AMOUNT, rawScore, signals.isEmpty() ? "Normal amount" : null);
    }

    private RiskFactorScore scoreVelocity(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;

        if (ctx.recentTxnCount1Hr() > 10) {
            rawScore = 900;
            signals.add("Extreme velocity: " + ctx.recentTxnCount1Hr() + " txns in 1hr");
        } else if (ctx.recentTxnCount1Hr() > 5) {
            rawScore = 600;
            signals.add("High velocity: " + ctx.recentTxnCount1Hr() + " txns in 1hr");
        } else if (ctx.recentTxnCount1Hr() > 3) {
            rawScore = 300;
        }

        if (ctx.recentTxnCount24Hr() > 30) {
            rawScore = Math.max(rawScore, 700);
            signals.add("Daily count elevated: " + ctx.recentTxnCount24Hr() + " txns in 24hr");
        } else if (ctx.recentTxnCount24Hr() > 15) {
            rawScore = Math.max(rawScore, 400);
        }

        return weighted(RiskFactor.VELOCITY, rawScore, null);
    }

    private RiskFactorScore scoreGeography(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;
        String merchantCountry = ctx.merchantCountry();
        String homeCountry = ctx.customerHomeCountry();

        if (merchantCountry != null && homeCountry != null) {
            if (!merchantCountry.equalsIgnoreCase(homeCountry)) {
                rawScore = 300;
                signals.add("Cross-border transaction: " + merchantCountry + " vs home " + homeCountry);

                if (HIGH_RISK_COUNTRIES.contains(merchantCountry.toUpperCase())) {
                    rawScore = 800;
                    signals.add("High-risk country: " + merchantCountry);
                }
            }
        }

        return weighted(RiskFactor.GEOGRAPHY, rawScore, null);
    }

    private RiskFactorScore scoreDevice(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;

        if (ctx.deviceFingerprint() == null || ctx.deviceFingerprint().isBlank()) {
            rawScore = 500;
            signals.add("No device fingerprint provided");
        } else if (!ctx.deviceTrusted()) {
            rawScore = 600;
            signals.add("Unrecognized device");
        } else if (ctx.deviceTrustScore() < 30) {
            rawScore = 400;
            signals.add("Low device trust score: " + ctx.deviceTrustScore());
        } else if (ctx.deviceTrustScore() < 60) {
            rawScore = 200;
        }

        return weighted(RiskFactor.DEVICE, rawScore, null);
    }

    private RiskFactorScore scoreMerchantCategory(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;
        String mcc = ctx.merchantMcc();

        if (mcc != null) {
            if (HIGH_RISK_MCCS.contains(mcc)) {
                rawScore = 700;
                signals.add("High-risk MCC: " + mcc);
            } else if (MEDIUM_RISK_MCCS.contains(mcc)) {
                rawScore = 300;
                signals.add("Medium-risk MCC: " + mcc);
            }
        }

        return weighted(RiskFactor.MERCHANT_CATEGORY, rawScore, null);
    }

    private RiskFactorScore scoreTimeOfDay(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;
        Instant txnTime = ctx.transactionTime() != null ? ctx.transactionTime() : Timestamp.now(clock);
        ZonedDateTime zdt = txnTime.atZone(ZoneId.of("America/New_York"));
        int hour = zdt.getHour();

        // Transactions between 1am and 5am are unusual for most retail customers
        if (hour >= 1 && hour < 5) {
            rawScore = 400;
            signals.add("Unusual time of day: " + hour + ":00 ET");
        } else if (hour >= 23 || hour < 1) {
            rawScore = 200;
        }

        return weighted(RiskFactor.TIME_OF_DAY, rawScore, null);
    }

    private RiskFactorScore scoreChannel(TransactionContext ctx, List<String> signals) {
        int rawScore = 0;
        String channel = ctx.channel();

        if (channel != null) {
            rawScore = switch (channel.toUpperCase()) {
                case "CNP", "CARD_NOT_PRESENT", "ONLINE" -> {
                    signals.add("Card-not-present channel");
                    yield 400;
                }
                case "INTERNATIONAL_WIRE" -> {
                    signals.add("International wire channel");
                    yield 500;
                }
                case "MOBILE" -> 200;
                case "ATM" -> 100;
                case "BRANCH", "CARD_PRESENT" -> 50;
                default -> 150;
            };
        }

        return weighted(RiskFactor.CHANNEL, rawScore, null);
    }

    private RiskFactorScore weighted(RiskFactor factor, int rawScore, String explanation) {
        BigDecimal weight = DEFAULT_WEIGHTS.getOrDefault(factor, BigDecimal.ZERO);
        int weightedScore = new BigDecimal(rawScore).multiply(weight, MC).intValue();
        return new RiskFactorScore(factor, rawScore, weight, weightedScore,
                explanation != null ? explanation : factor.name() + " score: " + rawScore);
    }

    private FraudDecision.Verdict determineVerdict(int compositeScore) {
        if (compositeScore >= BLOCK_THRESHOLD) return FraudDecision.Verdict.BLOCK;
        if (compositeScore >= REVIEW_THRESHOLD) return FraudDecision.Verdict.REVIEW;
        return FraudDecision.Verdict.PASS;
    }
}
