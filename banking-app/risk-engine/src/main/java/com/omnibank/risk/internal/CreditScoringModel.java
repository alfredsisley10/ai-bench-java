package com.omnibank.risk.internal;

import com.omnibank.risk.api.CreditScore;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * FICO-style consumer credit scoring model. Produces a score in the
 * [{@link #MIN_SCORE}, {@link #MAX_SCORE}] range along with factor-level
 * contributions so adverse-action notices can cite the specific reasons for
 * the outcome (ECOA / FCRA §615).
 *
 * <p>The five canonical factors and their weights:
 * <ul>
 *   <li><b>Payment history (35%)</b> — on-time rate across all tradelines,
 *       penalised for recent 30/60/90+ day delinquencies, charge-offs, and
 *       public records (bankruptcy, foreclosure).</li>
 *   <li><b>Credit utilisation (30%)</b> — revolving balance to revolving
 *       credit-limit ratio. Flat curve through 10% then aggressive drop-off.</li>
 *   <li><b>Length of credit history (15%)</b> — average age of accounts and
 *       the age of the oldest tradeline.</li>
 *   <li><b>New credit (10%)</b> — hard inquiries within the last 12 months
 *       and freshly-opened accounts.</li>
 *   <li><b>Credit mix (10%)</b> — diversity across revolving, instalment,
 *       mortgage and retail.</li>
 * </ul>
 *
 * <p>The model is deterministic — same input yields same output — which is
 * required for both regulatory reproducibility and for challenger-model
 * A/B testing harnesses.
 */
public class CreditScoringModel {

    private static final Logger log = LoggerFactory.getLogger(CreditScoringModel.class);

    public static final int MIN_SCORE = 300;
    public static final int MAX_SCORE = 850;
    public static final int SCORE_RANGE = MAX_SCORE - MIN_SCORE;

    /** Declared factor weights. Sum is 1.00 — violating that throws at load. */
    public static final Map<Factor, BigDecimal> WEIGHTS;

    static {
        Map<Factor, BigDecimal> w = new EnumMap<>(Factor.class);
        w.put(Factor.PAYMENT_HISTORY, new BigDecimal("0.35"));
        w.put(Factor.UTILIZATION, new BigDecimal("0.30"));
        w.put(Factor.LENGTH_OF_HISTORY, new BigDecimal("0.15"));
        w.put(Factor.NEW_CREDIT, new BigDecimal("0.10"));
        w.put(Factor.CREDIT_MIX, new BigDecimal("0.10"));
        BigDecimal sum = w.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalStateException(
                    "Factor weights must sum to 1.00 but summed to " + sum);
        }
        WEIGHTS = Map.copyOf(w);
    }

    /** Canonical FICO-style factors. */
    public enum Factor {
        PAYMENT_HISTORY,
        UTILIZATION,
        LENGTH_OF_HISTORY,
        NEW_CREDIT,
        CREDIT_MIX
    }

    /** Coarse credit tiers used for downstream pricing / underwriting policy. */
    public enum ScoreBand {
        EXCELLENT(800, 850),
        VERY_GOOD(740, 799),
        GOOD(670, 739),
        FAIR(580, 669),
        POOR(300, 579);

        public final int minInclusive;
        public final int maxInclusive;

        ScoreBand(int min, int max) {
            this.minInclusive = min;
            this.maxInclusive = max;
        }

        public static ScoreBand forScore(int score) {
            for (ScoreBand b : values()) {
                if (score >= b.minInclusive && score <= b.maxInclusive) return b;
            }
            throw new IllegalArgumentException("Score out of range: " + score);
        }
    }

    /** One tradeline / account contributing to the score. */
    public record Tradeline(
            String tradelineId,
            TradelineType type,
            LocalDate openedOn,
            Optional<LocalDate> closedOn,
            BigDecimal creditLimit,
            BigDecimal currentBalance,
            int thirtyDayDelinquencies,
            int sixtyDayDelinquencies,
            int ninetyPlusDayDelinquencies,
            boolean chargedOff,
            int onTimePaymentsLast24Months,
            int totalPaymentsLast24Months
    ) {
        public Tradeline {
            Objects.requireNonNull(tradelineId, "tradelineId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(openedOn, "openedOn");
            Objects.requireNonNull(closedOn, "closedOn");
            Objects.requireNonNull(creditLimit, "creditLimit");
            Objects.requireNonNull(currentBalance, "currentBalance");
            if (totalPaymentsLast24Months < 0 || onTimePaymentsLast24Months < 0) {
                throw new IllegalArgumentException("payment counts cannot be negative");
            }
            if (onTimePaymentsLast24Months > totalPaymentsLast24Months) {
                throw new IllegalArgumentException(
                        "onTime payments cannot exceed total payments");
            }
        }
    }

    public enum TradelineType {
        REVOLVING_CARD,
        INSTALLMENT_AUTO,
        INSTALLMENT_PERSONAL,
        MORTGAGE,
        STUDENT_LOAN,
        RETAIL_CARD,
        HELOC
    }

    /** Hard inquiry against the bureau. */
    public record HardInquiry(LocalDate inquiredOn, String source) {
        public HardInquiry {
            Objects.requireNonNull(inquiredOn, "inquiredOn");
        }
    }

    /** Public record — bankruptcy, tax lien, foreclosure. */
    public record PublicRecord(LocalDate filedOn, PublicRecordType type) {
        public PublicRecord {
            Objects.requireNonNull(filedOn, "filedOn");
            Objects.requireNonNull(type, "type");
        }
    }

    public enum PublicRecordType { BANKRUPTCY_CH7, BANKRUPTCY_CH13, TAX_LIEN, FORECLOSURE, JUDGMENT }

    /** Input bundle passed in to the scoring pipeline. */
    public record CreditProfile(
            CustomerId customer,
            List<Tradeline> tradelines,
            List<HardInquiry> inquiries,
            List<PublicRecord> publicRecords,
            LocalDate asOfDate
    ) {
        public CreditProfile {
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(asOfDate, "asOfDate");
            tradelines = tradelines == null ? List.of() : List.copyOf(tradelines);
            inquiries = inquiries == null ? List.of() : List.copyOf(inquiries);
            publicRecords = publicRecords == null ? List.of() : List.copyOf(publicRecords);
        }
    }

    /** Per-factor contribution explanation. */
    public record FactorContribution(
            Factor factor,
            BigDecimal rawScore,      // 0..1
            BigDecimal weight,        // 0..1
            BigDecimal weightedScore, // rawScore * weight
            String narrative,
            List<String> adverseReasons
    ) {}

    /** Complete scoring result. */
    public record ScoringResult(
            CreditScore score,
            List<FactorContribution> contributions,
            List<String> topAdverseReasons
    ) {}

    /** Published after every successful scoring event. */
    public record CreditScoreComputedEvent(
            UUID eventId, Instant occurredAt,
            CustomerId customer, int score, CreditScore.Grade grade)
            implements DomainEvent {
        @Override public String eventType() { return "risk.credit.score_computed"; }
    }

    private final Clock clock;
    private final EventBus events;

    public CreditScoringModel(Clock clock, EventBus events) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Compute a score for the profile. Empty profiles receive a thin-file
     * default score of {@link #MIN_SCORE} with an explicit adverse reason.
     */
    public ScoringResult score(CreditProfile profile) {
        Objects.requireNonNull(profile, "profile");

        List<FactorContribution> contributions = new ArrayList<>();
        contributions.add(paymentHistoryFactor(profile));
        contributions.add(utilizationFactor(profile));
        contributions.add(lengthOfHistoryFactor(profile));
        contributions.add(newCreditFactor(profile));
        contributions.add(creditMixFactor(profile));

        BigDecimal weightedRaw = contributions.stream()
                .map(FactorContribution::weightedScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int finalScore = toScore(weightedRaw);
        CreditScore.Grade grade = gradeFor(finalScore);
        CreditScore score = new CreditScore(finalScore, grade, Timestamp.now(clock));

        List<String> adverse = rankAdverseReasons(contributions);
        ScoringResult result = new ScoringResult(score, List.copyOf(contributions), adverse);

        publish(profile.customer(), score);
        log.debug("Credit score computed: customer={} score={} grade={}",
                profile.customer(), finalScore, grade);
        return result;
    }

    /* ---------- Factor calculators ---------- */

    FactorContribution paymentHistoryFactor(CreditProfile profile) {
        List<Tradeline> lines = profile.tradelines();
        if (lines.isEmpty()) {
            return new FactorContribution(Factor.PAYMENT_HISTORY,
                    new BigDecimal("0.20"), WEIGHTS.get(Factor.PAYMENT_HISTORY),
                    weighted("0.20", Factor.PAYMENT_HISTORY),
                    "Thin file: no tradelines observed",
                    List.of("Insufficient payment history"));
        }

        long totalPayments = 0, onTimePayments = 0;
        int d30 = 0, d60 = 0, d90 = 0, chargeOffs = 0;
        for (Tradeline t : lines) {
            totalPayments += t.totalPaymentsLast24Months();
            onTimePayments += t.onTimePaymentsLast24Months();
            d30 += t.thirtyDayDelinquencies();
            d60 += t.sixtyDayDelinquencies();
            d90 += t.ninetyPlusDayDelinquencies();
            if (t.chargedOff()) chargeOffs++;
        }

        BigDecimal onTimeRate = totalPayments == 0
                ? new BigDecimal("0.95")
                : BigDecimal.valueOf(onTimePayments)
                        .divide(BigDecimal.valueOf(totalPayments), 4, RoundingMode.HALF_EVEN);

        BigDecimal raw = onTimeRate;
        raw = raw.subtract(new BigDecimal("0.05").multiply(BigDecimal.valueOf(d30)));
        raw = raw.subtract(new BigDecimal("0.10").multiply(BigDecimal.valueOf(d60)));
        raw = raw.subtract(new BigDecimal("0.18").multiply(BigDecimal.valueOf(d90)));
        raw = raw.subtract(new BigDecimal("0.25").multiply(BigDecimal.valueOf(chargeOffs)));

        // Public records are a sledgehammer on payment history.
        for (PublicRecord pr : profile.publicRecords()) {
            int yearsOld = Math.max(0,
                    Period.between(pr.filedOn(), profile.asOfDate()).getYears());
            BigDecimal penalty = switch (pr.type()) {
                case BANKRUPTCY_CH7 -> new BigDecimal("0.40");
                case BANKRUPTCY_CH13 -> new BigDecimal("0.30");
                case FORECLOSURE -> new BigDecimal("0.35");
                case TAX_LIEN -> new BigDecimal("0.20");
                case JUDGMENT -> new BigDecimal("0.15");
            };
            BigDecimal decay = BigDecimal.valueOf(Math.max(0.2, 1.0 - (yearsOld * 0.1)));
            raw = raw.subtract(penalty.multiply(decay));
        }

        raw = clamp01(raw);

        List<String> reasons = new ArrayList<>();
        if (d90 > 0 || chargeOffs > 0) reasons.add("Serious delinquency or charge-off on file");
        else if (d60 > 0) reasons.add("Recent 60-day delinquency");
        else if (d30 > 0) reasons.add("Recent 30-day delinquency");
        if (!profile.publicRecords().isEmpty()) reasons.add("Public record on file");

        String narrative = "On-time rate %s%% across %d tradeline(s); %d late 30d, %d late 60d, %d late 90+, %d charge-off(s)"
                .formatted(onTimeRate.multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_EVEN), lines.size(), d30, d60, d90, chargeOffs);

        return new FactorContribution(Factor.PAYMENT_HISTORY, raw,
                WEIGHTS.get(Factor.PAYMENT_HISTORY),
                raw.multiply(WEIGHTS.get(Factor.PAYMENT_HISTORY)),
                narrative, reasons);
    }

    FactorContribution utilizationFactor(CreditProfile profile) {
        List<Tradeline> revolvers = profile.tradelines().stream()
                .filter(t -> t.type() == TradelineType.REVOLVING_CARD
                        || t.type() == TradelineType.RETAIL_CARD
                        || t.type() == TradelineType.HELOC)
                .toList();

        if (revolvers.isEmpty()) {
            return new FactorContribution(Factor.UTILIZATION,
                    new BigDecimal("0.65"), WEIGHTS.get(Factor.UTILIZATION),
                    weighted("0.65", Factor.UTILIZATION),
                    "No revolving accounts — utilisation neutral",
                    List.of());
        }

        BigDecimal limit = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        for (Tradeline t : revolvers) {
            limit = limit.add(t.creditLimit());
            balance = balance.add(t.currentBalance());
        }

        BigDecimal util = limit.signum() == 0
                ? BigDecimal.ONE
                : balance.divide(limit, 4, RoundingMode.HALF_EVEN);

        BigDecimal raw;
        if (util.compareTo(new BigDecimal("0.10")) <= 0) {
            raw = new BigDecimal("1.00");
        } else if (util.compareTo(new BigDecimal("0.30")) <= 0) {
            raw = new BigDecimal("0.80");
        } else if (util.compareTo(new BigDecimal("0.50")) <= 0) {
            raw = new BigDecimal("0.60");
        } else if (util.compareTo(new BigDecimal("0.75")) <= 0) {
            raw = new BigDecimal("0.35");
        } else if (util.compareTo(new BigDecimal("0.90")) <= 0) {
            raw = new BigDecimal("0.20");
        } else {
            raw = new BigDecimal("0.05");
        }

        List<String> reasons = new ArrayList<>();
        if (util.compareTo(new BigDecimal("0.50")) > 0)
            reasons.add("High revolving utilisation relative to limit");

        return new FactorContribution(Factor.UTILIZATION, raw,
                WEIGHTS.get(Factor.UTILIZATION),
                raw.multiply(WEIGHTS.get(Factor.UTILIZATION)),
                "Utilisation %s%% (%s / %s)".formatted(
                        util.multiply(BigDecimal.valueOf(100))
                                .setScale(1, RoundingMode.HALF_EVEN),
                        balance, limit),
                reasons);
    }

    FactorContribution lengthOfHistoryFactor(CreditProfile profile) {
        List<Tradeline> lines = profile.tradelines();
        if (lines.isEmpty()) {
            return new FactorContribution(Factor.LENGTH_OF_HISTORY,
                    BigDecimal.ZERO, WEIGHTS.get(Factor.LENGTH_OF_HISTORY),
                    BigDecimal.ZERO, "No credit history", List.of("No credit history"));
        }

        int totalMonths = 0;
        int oldestMonths = 0;
        for (Tradeline t : lines) {
            int months = (int) Math.max(0, monthsBetween(t.openedOn(), profile.asOfDate()));
            totalMonths += months;
            if (months > oldestMonths) oldestMonths = months;
        }
        double avgYears = (totalMonths / (double) lines.size()) / 12.0;
        double oldestYears = oldestMonths / 12.0;

        // Logistic-ish response: age reaches full credit by ~15 years.
        double avgComponent = Math.min(1.0, avgYears / 15.0);
        double oldestComponent = Math.min(1.0, oldestYears / 20.0);
        BigDecimal raw = BigDecimal.valueOf(0.6 * avgComponent + 0.4 * oldestComponent)
                .setScale(4, RoundingMode.HALF_EVEN);

        List<String> reasons = new ArrayList<>();
        if (avgYears < 2) reasons.add("Short average credit-history length");
        if (oldestYears < 3) reasons.add("No long-standing tradelines");

        return new FactorContribution(Factor.LENGTH_OF_HISTORY, raw,
                WEIGHTS.get(Factor.LENGTH_OF_HISTORY),
                raw.multiply(WEIGHTS.get(Factor.LENGTH_OF_HISTORY)),
                "Avg age %.1fy / oldest %.1fy across %d tradelines"
                        .formatted(avgYears, oldestYears, lines.size()),
                reasons);
    }

    FactorContribution newCreditFactor(CreditProfile profile) {
        LocalDate cutoff12m = profile.asOfDate().minusMonths(12);
        LocalDate cutoff6m = profile.asOfDate().minusMonths(6);

        long inquiriesLast12 = profile.inquiries().stream()
                .filter(q -> !q.inquiredOn().isBefore(cutoff12m)).count();
        long inquiriesLast6 = profile.inquiries().stream()
                .filter(q -> !q.inquiredOn().isBefore(cutoff6m)).count();
        long newAccountsLast12 = profile.tradelines().stream()
                .filter(t -> !t.openedOn().isBefore(cutoff12m)).count();

        // Baseline 1.0 — penalise per recent event.
        double raw = 1.0
                - 0.07 * inquiriesLast12
                - 0.05 * inquiriesLast6
                - 0.09 * newAccountsLast12;
        raw = Math.max(0.0, Math.min(1.0, raw));

        List<String> reasons = new ArrayList<>();
        if (inquiriesLast12 >= 4) reasons.add("Multiple recent credit inquiries");
        if (newAccountsLast12 >= 3) reasons.add("Multiple newly opened accounts");

        BigDecimal rawBd = BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_EVEN);
        return new FactorContribution(Factor.NEW_CREDIT, rawBd,
                WEIGHTS.get(Factor.NEW_CREDIT),
                rawBd.multiply(WEIGHTS.get(Factor.NEW_CREDIT)),
                "%d inquiries / %d new accounts in last 12m"
                        .formatted(inquiriesLast12, newAccountsLast12),
                reasons);
    }

    FactorContribution creditMixFactor(CreditProfile profile) {
        List<Tradeline> lines = profile.tradelines();
        if (lines.isEmpty()) {
            return new FactorContribution(Factor.CREDIT_MIX,
                    BigDecimal.ZERO, WEIGHTS.get(Factor.CREDIT_MIX),
                    BigDecimal.ZERO, "No accounts to assess diversity",
                    List.of("Insufficient diversity of credit"));
        }

        long distinctTypes = lines.stream()
                .map(Tradeline::type).distinct().count();
        // 1 type = 0.25, 2 = 0.50, 3 = 0.75, 4+ = 1.00
        double raw = Math.min(1.0, distinctTypes * 0.25);

        List<String> reasons = new ArrayList<>();
        if (distinctTypes < 2) reasons.add("Limited credit mix — only one account type");

        BigDecimal rawBd = BigDecimal.valueOf(raw).setScale(4, RoundingMode.HALF_EVEN);
        return new FactorContribution(Factor.CREDIT_MIX, rawBd,
                WEIGHTS.get(Factor.CREDIT_MIX),
                rawBd.multiply(WEIGHTS.get(Factor.CREDIT_MIX)),
                "%d distinct account types".formatted(distinctTypes),
                reasons);
    }

    /* ---------- Helpers ---------- */

    private int toScore(BigDecimal weightedRaw) {
        BigDecimal clamped = clamp01(weightedRaw);
        int delta = clamped.multiply(BigDecimal.valueOf(SCORE_RANGE))
                .setScale(0, RoundingMode.HALF_EVEN).intValue();
        return MIN_SCORE + delta;
    }

    private static BigDecimal clamp01(BigDecimal v) {
        if (v.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
        if (v.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return v;
    }

    private static BigDecimal weighted(String raw, Factor factor) {
        return new BigDecimal(raw).multiply(WEIGHTS.get(factor));
    }

    private static long monthsBetween(LocalDate a, LocalDate b) {
        Period p = Period.between(a, b);
        return p.getYears() * 12L + p.getMonths();
    }

    static CreditScore.Grade gradeFor(int score) {
        if (score >= 820) return CreditScore.Grade.AAA;
        if (score >= 780) return CreditScore.Grade.AA;
        if (score >= 740) return CreditScore.Grade.A;
        if (score >= 700) return CreditScore.Grade.BBB;
        if (score >= 660) return CreditScore.Grade.BB;
        if (score >= 620) return CreditScore.Grade.B;
        if (score >= 580) return CreditScore.Grade.CCC;
        if (score >= 540) return CreditScore.Grade.CC;
        if (score >= 500) return CreditScore.Grade.C;
        return CreditScore.Grade.D;
    }

    private static List<String> rankAdverseReasons(Collection<FactorContribution> contributions) {
        // Rank by lowest raw score (largest shortfall from the max) × weight —
        // factors that cost the most weighted points come first.
        return contributions.stream()
                .sorted((a, b) -> {
                    BigDecimal lossA = BigDecimal.ONE.subtract(a.rawScore())
                            .multiply(a.weight());
                    BigDecimal lossB = BigDecimal.ONE.subtract(b.rawScore())
                            .multiply(b.weight());
                    return lossB.compareTo(lossA);
                })
                .flatMap(c -> c.adverseReasons().stream())
                .distinct()
                .limit(4)
                .toList();
    }

    private void publish(CustomerId customer, CreditScore score) {
        try {
            events.publish(new CreditScoreComputedEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    customer, score.fico(), score.grade()));
        } catch (Exception e) {
            log.warn("Failed to publish credit score event for {}: {}",
                    customer, e.getMessage());
        }
    }

    /** Helper used by tests to evaluate scoring without emitting events. */
    static LocalDate defaultAsOfDate(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
