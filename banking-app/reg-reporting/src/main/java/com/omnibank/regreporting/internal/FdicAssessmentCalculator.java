package com.omnibank.regreporting.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Calculates the FDIC quarterly Deposit Insurance Fund (DIF) assessment
 * invoice for a large institution under the risk-based scorecard methodology
 * described in 12 CFR 327. The scorecard replaces the flat per-$100 premium
 * used for smaller banks with a multi-factor model weighted by CAMELS-adjacent
 * proxies.
 *
 * <p>For institutions with assets ≥ $10 billion, the scorecard combines two
 * sub-scores:
 * <ol>
 *   <li><b>Performance score (70% weight):</b> Weighted CAMELS-component
 *       proxies — capital adequacy, asset quality, earnings, liquidity, and
 *       risk profile. Each component is translated into points on a 0-100
 *       scale via piecewise benchmarks. Points inside 0-30 are reported as
 *       "favorable"; 30-70 as "neutral"; 70-100 as "unfavorable".</li>
 *   <li><b>Loss severity score (30% weight):</b> Derived from loss given
 *       failure (LGF) assumptions under a stress scenario; reflects how much
 *       the DIF would lose if the bank failed.</li>
 * </ol>
 *
 * <p>The total score is mapped to an initial base assessment rate (4.0 bps to
 * 30.0 bps annually). Adjustments for unsecured-debt holdings and brokered
 * deposits are applied before generating a final rate, then multiplied by the
 * quarter's assessment base (quarterly-averaged consolidated total assets minus
 * average tangible equity, per Dodd-Frank).
 */
public class FdicAssessmentCalculator {

    private static final Logger log = LoggerFactory.getLogger(FdicAssessmentCalculator.class);

    /** Assessment rates are quoted in basis points on an annual basis. */
    private static final BigDecimal BPS_PER_UNIT = new BigDecimal("10000");

    /** Quarterly invoice = annual rate ÷ 4. */
    private static final BigDecimal QUARTERLY_DIVISOR = new BigDecimal("4");

    /** Minimum initial base rate under 2023 schedule. */
    public static final BigDecimal INITIAL_MIN_BPS = new BigDecimal("2.5");
    /** Maximum initial base rate under 2023 schedule. */
    public static final BigDecimal INITIAL_MAX_BPS = new BigDecimal("32.0");

    /** Performance sub-score weight. */
    private static final BigDecimal PERFORMANCE_WEIGHT = new BigDecimal("0.70");
    /** Loss-severity sub-score weight. */
    private static final BigDecimal LOSS_SEVERITY_WEIGHT = new BigDecimal("0.30");

    /** Maximum adjustment for brokered deposits. */
    public static final BigDecimal BROKERED_DEPOSIT_CAP_BPS = new BigDecimal("10.0");

    public enum CamelsComponent { CAPITAL, ASSET_QUALITY, EARNINGS, LIQUIDITY, RISK_PROFILE }

    /**
     * Performance score inputs — every component is a fraction (0.0 to 1.0)
     * where 0 is best-in-class and 1 is worst.
     */
    public record PerformanceInputs(
            BigDecimal leverageRatio,            // CET1 / avg total assets (higher is better)
            BigDecimal nonPerformingLoanRatio,   // NPL / total loans (lower is better)
            BigDecimal netIncomeToAssetsRatio,   // ROA annualised
            BigDecimal liquidCoreDepositRatio,   // core deposits / total liabilities
            BigDecimal concentrationRisk          // 0.0-1.0 normalized by supervisor
    ) {
        public PerformanceInputs {
            Objects.requireNonNull(leverageRatio, "leverageRatio");
            Objects.requireNonNull(nonPerformingLoanRatio, "nonPerformingLoanRatio");
            Objects.requireNonNull(netIncomeToAssetsRatio, "netIncomeToAssetsRatio");
            Objects.requireNonNull(liquidCoreDepositRatio, "liquidCoreDepositRatio");
            Objects.requireNonNull(concentrationRisk, "concentrationRisk");
        }
    }

    /**
     * Loss severity inputs — per the Scorecard Notice, these are derived from
     * the supervisory loss-given-failure study; here we accept them as inputs
     * because they are produced upstream by the capital-planning group.
     */
    public record LossSeverityInputs(
            BigDecimal expectedLossRate,         // fraction of insured deposits
            BigDecimal uninsuredDepositsRatio,   // uninsured / total deposits
            BigDecimal secureBorrowingsRatio     // FHLB + repo / total liab (higher = more loss-giving)
    ) {
        public LossSeverityInputs {
            Objects.requireNonNull(expectedLossRate, "expectedLossRate");
            Objects.requireNonNull(uninsuredDepositsRatio, "uninsuredDepositsRatio");
            Objects.requireNonNull(secureBorrowingsRatio, "secureBorrowingsRatio");
        }
    }

    /** Adjustments applied after the base rate is computed. */
    public record RateAdjustments(
            BigDecimal unsecuredDebtAdjustmentBps,  // signed; see 12 CFR 327
            BigDecimal brokeredDepositAdjustmentBps,// non-negative; capped at BROKERED_DEPOSIT_CAP_BPS
            BigDecimal depositoryDebtAdjustmentBps  // new under 2023 — negative for holding senior debt
    ) {
        public RateAdjustments {
            Objects.requireNonNull(unsecuredDebtAdjustmentBps, "unsecuredDebtAdjustmentBps");
            Objects.requireNonNull(brokeredDepositAdjustmentBps, "brokeredDepositAdjustmentBps");
            Objects.requireNonNull(depositoryDebtAdjustmentBps, "depositoryDebtAdjustmentBps");
        }
    }

    public record AssessmentInput(
            String rssdId,
            LocalDate quarterEnd,
            PerformanceInputs performance,
            LossSeverityInputs lossSeverity,
            RateAdjustments adjustments,
            Money averageTotalAssets,            // from RC-K
            Money averageTangibleEquity,         // tier 1 capital avg
            boolean isHighlyComplex
    ) {
        public AssessmentInput {
            Objects.requireNonNull(rssdId, "rssdId");
            Objects.requireNonNull(quarterEnd, "quarterEnd");
            Objects.requireNonNull(performance, "performance");
            Objects.requireNonNull(lossSeverity, "lossSeverity");
            Objects.requireNonNull(adjustments, "adjustments");
            Objects.requireNonNull(averageTotalAssets, "averageTotalAssets");
            Objects.requireNonNull(averageTangibleEquity, "averageTangibleEquity");
        }
    }

    public record ComponentScore(CamelsComponent component, BigDecimal weight,
                                  BigDecimal rawInput, BigDecimal points) {
        public ComponentScore {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(weight, "weight");
            Objects.requireNonNull(points, "points");
        }
    }

    public record AssessmentInvoice(
            UUID invoiceId,
            String rssdId,
            LocalDate quarterEnd,
            Map<CamelsComponent, ComponentScore> performanceComponents,
            BigDecimal performanceScore,
            BigDecimal lossSeverityScore,
            BigDecimal totalScore,
            BigDecimal initialBaseRateBps,
            BigDecimal totalBaseRateBps,
            Money assessmentBase,
            Money quarterlyAssessment
    ) {
        public AssessmentInvoice {
            Objects.requireNonNull(invoiceId, "invoiceId");
            Objects.requireNonNull(rssdId, "rssdId");
            Objects.requireNonNull(quarterEnd, "quarterEnd");
            performanceComponents = Map.copyOf(performanceComponents);
            Objects.requireNonNull(performanceScore, "performanceScore");
            Objects.requireNonNull(lossSeverityScore, "lossSeverityScore");
            Objects.requireNonNull(totalScore, "totalScore");
            Objects.requireNonNull(initialBaseRateBps, "initialBaseRateBps");
            Objects.requireNonNull(totalBaseRateBps, "totalBaseRateBps");
            Objects.requireNonNull(assessmentBase, "assessmentBase");
            Objects.requireNonNull(quarterlyAssessment, "quarterlyAssessment");
        }

        public Percent annualRate() {
            return Percent.ofBps(totalBaseRateBps.setScale(0, RoundingMode.HALF_EVEN).longValueExact());
        }
    }

    /** Default weights used when the supervisor has not assigned custom ones. */
    private static final Map<CamelsComponent, BigDecimal> DEFAULT_WEIGHTS = new EnumMap<>(Map.of(
            CamelsComponent.CAPITAL, new BigDecimal("0.25"),
            CamelsComponent.ASSET_QUALITY, new BigDecimal("0.25"),
            CamelsComponent.EARNINGS, new BigDecimal("0.15"),
            CamelsComponent.LIQUIDITY, new BigDecimal("0.20"),
            CamelsComponent.RISK_PROFILE, new BigDecimal("0.15")
    ));

    public AssessmentInvoice calculate(AssessmentInput input) {
        Objects.requireNonNull(input, "input");

        Map<CamelsComponent, ComponentScore> components = scoreComponents(input.performance());
        BigDecimal perfScore = components.values().stream()
                .map(c -> c.weight().multiply(c.points()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_EVEN);

        BigDecimal lossScore = scoreLossSeverity(input.lossSeverity());

        BigDecimal total = perfScore.multiply(PERFORMANCE_WEIGHT)
                .add(lossScore.multiply(LOSS_SEVERITY_WEIGHT))
                .setScale(4, RoundingMode.HALF_EVEN);

        BigDecimal initialRate = mapScoreToRate(total);

        // Cap brokered-deposit adjustment per regulation
        BigDecimal brokeredBps = input.adjustments().brokeredDepositAdjustmentBps()
                .min(BROKERED_DEPOSIT_CAP_BPS).max(BigDecimal.ZERO);

        BigDecimal totalRate = initialRate
                .add(input.adjustments().unsecuredDebtAdjustmentBps())
                .add(brokeredBps)
                .add(input.adjustments().depositoryDebtAdjustmentBps())
                .max(BigDecimal.ZERO);

        Money base = assessmentBase(input.averageTotalAssets(), input.averageTangibleEquity());
        Money quarterly = base.times(totalRate.divide(BPS_PER_UNIT, 10, RoundingMode.HALF_EVEN))
                .dividedBy(QUARTERLY_DIVISOR);

        AssessmentInvoice invoice = new AssessmentInvoice(
                UUID.randomUUID(), input.rssdId(), input.quarterEnd(),
                components, perfScore, lossScore, total,
                initialRate, totalRate, base, quarterly
        );
        log.info("FDIC assessment computed: rssd={}, totalScore={}, rate={}bps, invoice={}",
                input.rssdId(), total, totalRate, quarterly);
        return invoice;
    }

    /**
     * Compute the assessment base. Per Dodd-Frank section 331, the base is
     * the average consolidated total assets less the average tangible equity.
     */
    public static Money assessmentBase(Money avgAssets, Money avgTangibleEquity) {
        return avgAssets.minus(avgTangibleEquity);
    }

    private Map<CamelsComponent, ComponentScore> scoreComponents(PerformanceInputs p) {
        Map<CamelsComponent, ComponentScore> m = new EnumMap<>(CamelsComponent.class);

        // Capital: leverage ratio thresholds 5%/8%/12%.
        BigDecimal capPts;
        BigDecimal lev = p.leverageRatio();
        if (lev.compareTo(new BigDecimal("0.12")) >= 0) capPts = BigDecimal.ZERO;
        else if (lev.compareTo(new BigDecimal("0.08")) >= 0) capPts = new BigDecimal("25");
        else if (lev.compareTo(new BigDecimal("0.05")) >= 0) capPts = new BigDecimal("60");
        else capPts = new BigDecimal("100");

        m.put(CamelsComponent.CAPITAL, new ComponentScore(CamelsComponent.CAPITAL,
                DEFAULT_WEIGHTS.get(CamelsComponent.CAPITAL), lev, capPts));

        // Asset quality: NPL ratio 1% is pristine, > 4% is severe.
        BigDecimal npl = p.nonPerformingLoanRatio();
        BigDecimal aqPts;
        if (npl.compareTo(new BigDecimal("0.01")) <= 0) aqPts = new BigDecimal("5");
        else if (npl.compareTo(new BigDecimal("0.02")) <= 0) aqPts = new BigDecimal("25");
        else if (npl.compareTo(new BigDecimal("0.04")) <= 0) aqPts = new BigDecimal("60");
        else aqPts = new BigDecimal("100");
        m.put(CamelsComponent.ASSET_QUALITY, new ComponentScore(CamelsComponent.ASSET_QUALITY,
                DEFAULT_WEIGHTS.get(CamelsComponent.ASSET_QUALITY), npl, aqPts));

        // Earnings: ROA 1%+ excellent, <0 problem.
        BigDecimal roa = p.netIncomeToAssetsRatio();
        BigDecimal eaPts;
        if (roa.compareTo(new BigDecimal("0.01")) >= 0) eaPts = new BigDecimal("5");
        else if (roa.compareTo(new BigDecimal("0.005")) >= 0) eaPts = new BigDecimal("25");
        else if (roa.signum() >= 0) eaPts = new BigDecimal("60");
        else eaPts = new BigDecimal("100");
        m.put(CamelsComponent.EARNINGS, new ComponentScore(CamelsComponent.EARNINGS,
                DEFAULT_WEIGHTS.get(CamelsComponent.EARNINGS), roa, eaPts));

        // Liquidity: core deposits > 70% is strong.
        BigDecimal liq = p.liquidCoreDepositRatio();
        BigDecimal liqPts;
        if (liq.compareTo(new BigDecimal("0.7")) >= 0) liqPts = new BigDecimal("5");
        else if (liq.compareTo(new BigDecimal("0.5")) >= 0) liqPts = new BigDecimal("25");
        else if (liq.compareTo(new BigDecimal("0.35")) >= 0) liqPts = new BigDecimal("60");
        else liqPts = new BigDecimal("100");
        m.put(CamelsComponent.LIQUIDITY, new ComponentScore(CamelsComponent.LIQUIDITY,
                DEFAULT_WEIGHTS.get(CamelsComponent.LIQUIDITY), liq, liqPts));

        // Risk profile: pre-scored concentration risk on 0..1, scaled to 0..100.
        BigDecimal conc = p.concentrationRisk();
        BigDecimal rpPts = conc.multiply(new BigDecimal("100")).max(BigDecimal.ZERO)
                .min(new BigDecimal("100"));
        m.put(CamelsComponent.RISK_PROFILE, new ComponentScore(CamelsComponent.RISK_PROFILE,
                DEFAULT_WEIGHTS.get(CamelsComponent.RISK_PROFILE), conc, rpPts));

        return m;
    }

    private BigDecimal scoreLossSeverity(LossSeverityInputs l) {
        // Weighted average — expected loss dominates, uninsured concentration
        // is 30% of the score, secured borrowings 10%.
        BigDecimal elPts = l.expectedLossRate().multiply(new BigDecimal("100"))
                .min(new BigDecimal("100")).max(BigDecimal.ZERO);
        BigDecimal unPts = l.uninsuredDepositsRatio().multiply(new BigDecimal("100"))
                .min(new BigDecimal("100")).max(BigDecimal.ZERO);
        BigDecimal sbPts = l.secureBorrowingsRatio().multiply(new BigDecimal("100"))
                .min(new BigDecimal("100")).max(BigDecimal.ZERO);
        return elPts.multiply(new BigDecimal("0.60"))
                .add(unPts.multiply(new BigDecimal("0.30")))
                .add(sbPts.multiply(new BigDecimal("0.10")))
                .setScale(4, RoundingMode.HALF_EVEN);
    }

    /**
     * Maps a total score (0-100) onto the initial base-rate grid. The mapping
     * follows the linear interpolation specified in the 2023 FDIC assessment
     * rule between 2.5 bps (score 0) and 32 bps (score 100).
     */
    static BigDecimal mapScoreToRate(BigDecimal totalScore) {
        BigDecimal clamped = totalScore.max(BigDecimal.ZERO)
                .min(new BigDecimal("100"));
        BigDecimal rateRange = INITIAL_MAX_BPS.subtract(INITIAL_MIN_BPS);
        BigDecimal offset = clamped.divide(new BigDecimal("100"), 10, RoundingMode.HALF_EVEN)
                .multiply(rateRange);
        return INITIAL_MIN_BPS.add(offset).setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Generate a printable summary of all components for the invoice. */
    public List<String> renderSummary(AssessmentInvoice invoice) {
        return List.of(
                "FDIC Assessment Invoice — %s".formatted(invoice.invoiceId()),
                "RSSD: " + invoice.rssdId(),
                "Quarter: " + invoice.quarterEnd(),
                "Total score: " + invoice.totalScore(),
                "Initial rate (bps): " + invoice.initialBaseRateBps(),
                "Adjusted rate (bps): " + invoice.totalBaseRateBps(),
                "Assessment base: " + invoice.assessmentBase(),
                "Quarterly assessment: " + invoice.quarterlyAssessment()
        );
    }

    /** Compute the annual amount that would be paid at the current rate. */
    public Money annualisedAssessment(AssessmentInvoice invoice) {
        return invoice.quarterlyAssessment().times(4L);
    }

    /** Validate rate adjustments against caps — used as a gate before invoicing. */
    public static void validateAdjustments(RateAdjustments adj) {
        if (adj.brokeredDepositAdjustmentBps().compareTo(BROKERED_DEPOSIT_CAP_BPS) > 0) {
            throw new IllegalArgumentException(
                    "Brokered-deposit adjustment %s exceeds cap of %s bps"
                            .formatted(adj.brokeredDepositAdjustmentBps(), BROKERED_DEPOSIT_CAP_BPS));
        }
        if (adj.brokeredDepositAdjustmentBps().signum() < 0) {
            throw new IllegalArgumentException(
                    "Brokered-deposit adjustment must be non-negative");
        }
        BigDecimal minDebtAdj = new BigDecimal("-5.0");
        BigDecimal maxDebtAdj = new BigDecimal("5.0");
        if (adj.unsecuredDebtAdjustmentBps().compareTo(minDebtAdj) < 0
                || adj.unsecuredDebtAdjustmentBps().compareTo(maxDebtAdj) > 0) {
            throw new IllegalArgumentException(
                    "Unsecured-debt adjustment outside allowed band [-5, +5]: "
                            + adj.unsecuredDebtAdjustmentBps());
        }
    }

    /** Convenience constructor for a zero-adjustment base case. */
    public static RateAdjustments noAdjustments() {
        return new RateAdjustments(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Helper: convert a basis-points rate to a Money amount applied to a
     * given base and period (in quarters).
     */
    public static Money applyRateBps(Money base, BigDecimal rateBps, int quarters) {
        BigDecimal frac = rateBps.divide(BPS_PER_UNIT, 10, RoundingMode.HALF_EVEN);
        return base.times(frac).times(quarters).dividedBy(new BigDecimal("4"));
    }

    /**
     * Build the smallest valid input object with sane defaults — tests and
     * ad-hoc callers use this to avoid repeating boilerplate.
     */
    public static AssessmentInput zeroInput(String rssd, LocalDate quarter,
                                              Money avgAssets, Money avgEquity) {
        PerformanceInputs pi = new PerformanceInputs(
                new BigDecimal("0.08"), new BigDecimal("0.01"),
                new BigDecimal("0.01"), new BigDecimal("0.70"),
                new BigDecimal("0.10"));
        LossSeverityInputs ls = new LossSeverityInputs(
                new BigDecimal("0.01"), new BigDecimal("0.30"), new BigDecimal("0.10"));
        return new AssessmentInput(rssd, quarter, pi, ls, noAdjustments(),
                avgAssets, avgEquity, false);
    }

    /**
     * Category label for the raw total score. Used in reports where examiners
     * want a plain-English classification alongside the numeric score.
     */
    public static String riskCategoryOf(BigDecimal totalScore) {
        if (totalScore.compareTo(new BigDecimal("30")) <= 0) return "LOW_RISK";
        if (totalScore.compareTo(new BigDecimal("60")) <= 0) return "MODERATE_RISK";
        if (totalScore.compareTo(new BigDecimal("85")) <= 0) return "HIGH_RISK";
        return "HIGHEST_RISK";
    }

    /** Currency helper for callers composing inputs from USD figures. */
    public static Money usd(String amount) {
        return Money.of(amount, CurrencyCode.USD);
    }
}
