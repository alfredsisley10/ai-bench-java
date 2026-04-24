package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal credit rating model for corporate loans. Produces a composite score
 * from weighted scoring factors covering financial performance, industry risk,
 * management quality, and collateral strength. Maps the composite score to an
 * internal rating grade which feeds into PD (Probability of Default) and LGD
 * (Loss Given Default) estimation for regulatory capital calculations.
 *
 * <p>Rating migration tracking records changes over time to detect credit
 * deterioration and trigger early warning systems.
 */
public final class LoanRatingModel {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private LoanRatingModel() {}

    // ── Rating grades ─────────────────────────────────────────────────────

    public enum RatingGrade {
        AAA(1, "Prime", "0.01"),
        AA(2, "High Quality", "0.02"),
        A(3, "Upper Medium", "0.05"),
        BBB(4, "Medium", "0.18"),
        BB(5, "Speculative", "1.06"),
        B(6, "Highly Speculative", "4.53"),
        CCC(7, "Substantial Risk", "13.46"),
        CC(8, "Extremely Speculative", "26.57"),
        C(9, "Near Default", "45.00"),
        D(10, "Default", "100.00");

        private final int numericGrade;
        private final String description;
        private final BigDecimal baselinePdPercent;

        RatingGrade(int numericGrade, String description, String baselinePdPercent) {
            this.numericGrade = numericGrade;
            this.description = description;
            this.baselinePdPercent = new BigDecimal(baselinePdPercent);
        }

        public int numericGrade() { return numericGrade; }
        public String description() { return description; }
        public BigDecimal baselinePdPercent() { return baselinePdPercent; }

        /**
         * Looks up the grade corresponding to a composite score (0-100 scale).
         * Higher scores indicate better credit quality.
         */
        public static RatingGrade fromScore(BigDecimal score) {
            if (score.compareTo(BigDecimal.valueOf(95)) >= 0) return AAA;
            if (score.compareTo(BigDecimal.valueOf(88)) >= 0) return AA;
            if (score.compareTo(BigDecimal.valueOf(80)) >= 0) return A;
            if (score.compareTo(BigDecimal.valueOf(70)) >= 0) return BBB;
            if (score.compareTo(BigDecimal.valueOf(58)) >= 0) return BB;
            if (score.compareTo(BigDecimal.valueOf(45)) >= 0) return B;
            if (score.compareTo(BigDecimal.valueOf(32)) >= 0) return CCC;
            if (score.compareTo(BigDecimal.valueOf(20)) >= 0) return CC;
            if (score.compareTo(BigDecimal.valueOf(10)) >= 0) return C;
            return D;
        }
    }

    // ── Scoring factors ───────────────────────────────────────────────────

    public enum ScoringCategory { FINANCIAL, INDUSTRY, MANAGEMENT, COLLATERAL }

    public record ScoringFactor(
            String factorId,
            String name,
            ScoringCategory category,
            BigDecimal weight,
            BigDecimal minScore,
            BigDecimal maxScore
    ) {
        public ScoringFactor {
            Objects.requireNonNull(factorId, "factorId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(weight, "weight");
            if (weight.signum() <= 0 || weight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Weight must be in (0, 1]: " + weight);
            }
        }
    }

    public record FactorScore(
            String factorId,
            BigDecimal rawScore,
            BigDecimal weightedScore,
            String rationale
    ) {
        public FactorScore {
            Objects.requireNonNull(factorId, "factorId");
            Objects.requireNonNull(rawScore, "rawScore");
            Objects.requireNonNull(weightedScore, "weightedScore");
        }
    }

    // ── Standard factor definitions ───────────────────────────────────────

    private static final List<ScoringFactor> STANDARD_FACTORS = List.of(
            new ScoringFactor("FIN_LEVERAGE", "Leverage Ratio", ScoringCategory.FINANCIAL,
                    new BigDecimal("0.15"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("FIN_COVERAGE", "Interest Coverage", ScoringCategory.FINANCIAL,
                    new BigDecimal("0.15"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("FIN_PROFITABILITY", "Profitability", ScoringCategory.FINANCIAL,
                    new BigDecimal("0.10"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("FIN_LIQUIDITY", "Liquidity", ScoringCategory.FINANCIAL,
                    new BigDecimal("0.10"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("IND_SECTOR", "Industry Sector Risk", ScoringCategory.INDUSTRY,
                    new BigDecimal("0.10"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("IND_POSITION", "Market Position", ScoringCategory.INDUSTRY,
                    new BigDecimal("0.05"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("IND_CYCLE", "Business Cycle Sensitivity", ScoringCategory.INDUSTRY,
                    new BigDecimal("0.05"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("MGT_EXPERIENCE", "Management Experience", ScoringCategory.MANAGEMENT,
                    new BigDecimal("0.08"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("MGT_GOVERNANCE", "Corporate Governance", ScoringCategory.MANAGEMENT,
                    new BigDecimal("0.07"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("COL_TYPE", "Collateral Type", ScoringCategory.COLLATERAL,
                    new BigDecimal("0.05"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("COL_COVERAGE", "Collateral Coverage", ScoringCategory.COLLATERAL,
                    new BigDecimal("0.05"), BigDecimal.ZERO, BigDecimal.valueOf(100)),
            new ScoringFactor("COL_LIQUIDITY", "Collateral Liquidity", ScoringCategory.COLLATERAL,
                    new BigDecimal("0.05"), BigDecimal.ZERO, BigDecimal.valueOf(100))
    );

    public static List<ScoringFactor> standardFactors() {
        return STANDARD_FACTORS;
    }

    // ── Rating result ─────────────────────────────────────────────────────

    public record RatingResult(
            LoanId loanId,
            BigDecimal compositeScore,
            RatingGrade grade,
            BigDecimal probabilityOfDefault,
            BigDecimal lossGivenDefault,
            BigDecimal expectedLoss,
            List<FactorScore> factorScores,
            LocalDate ratingDate,
            String analystId,
            Optional<String> overrideReason
    ) {
        public RatingResult {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(compositeScore, "compositeScore");
            Objects.requireNonNull(grade, "grade");
            Objects.requireNonNull(probabilityOfDefault, "probabilityOfDefault");
            Objects.requireNonNull(lossGivenDefault, "lossGivenDefault");
            Objects.requireNonNull(expectedLoss, "expectedLoss");
            factorScores = List.copyOf(factorScores);
            Objects.requireNonNull(ratingDate, "ratingDate");
            Objects.requireNonNull(overrideReason, "overrideReason");
        }
    }

    // ── Rating migration ──────────────────────────────────────────────────

    public record RatingMigration(
            LoanId loanId,
            RatingGrade previousGrade,
            RatingGrade newGrade,
            LocalDate migrationDate,
            String trigger,
            int notchesChanged
    ) {
        public RatingMigration {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(previousGrade, "previousGrade");
            Objects.requireNonNull(newGrade, "newGrade");
            Objects.requireNonNull(migrationDate, "migrationDate");
            Objects.requireNonNull(trigger, "trigger");
        }

        public boolean isUpgrade() { return newGrade.numericGrade() < previousGrade.numericGrade(); }
        public boolean isDowngrade() { return newGrade.numericGrade() > previousGrade.numericGrade(); }
    }

    // ── Core scoring logic ────────────────────────────────────────────────

    /**
     * Computes a composite credit score from individual factor scores.
     * Each factor is weighted according to its defined weight; the composite
     * is the sum of weighted scores capped at 100.
     */
    public static BigDecimal computeCompositeScore(List<FactorScore> factorScores) {
        BigDecimal composite = factorScores.stream()
                .map(FactorScore::weightedScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return composite.min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO);
    }

    /**
     * Scores a single factor, applying the weight to produce the weighted score.
     */
    public static FactorScore scoreFactor(
            ScoringFactor factor,
            BigDecimal rawScore,
            String rationale
    ) {
        BigDecimal clamped = rawScore
                .max(factor.minScore())
                .min(factor.maxScore());
        BigDecimal weighted = clamped.multiply(factor.weight(), MC);
        return new FactorScore(factor.factorId(), clamped, weighted, rationale);
    }

    /**
     * Estimates Probability of Default (PD) based on the rating grade.
     * Uses the baseline PD for the grade, adjusted by a through-the-cycle
     * calibration factor.
     */
    public static BigDecimal estimatePD(RatingGrade grade, BigDecimal calibrationFactor) {
        return grade.baselinePdPercent()
                .multiply(calibrationFactor, MC)
                .divide(BigDecimal.valueOf(100), MC);
    }

    /**
     * Estimates Loss Given Default (LGD) based on collateral coverage and
     * seniority. Secured senior debt typically has LGD of 25-45%; unsecured
     * subordinated debt can exceed 75%.
     */
    public static BigDecimal estimateLGD(
            BigDecimal collateralCoverageRatio,
            boolean isSenior,
            boolean isSecured
    ) {
        BigDecimal baseLgd;
        if (isSecured && isSenior) {
            baseLgd = new BigDecimal("0.35");
        } else if (isSecured) {
            baseLgd = new BigDecimal("0.50");
        } else if (isSenior) {
            baseLgd = new BigDecimal("0.55");
        } else {
            baseLgd = new BigDecimal("0.75");
        }

        // Adjust for collateral coverage: higher coverage reduces LGD
        if (collateralCoverageRatio.compareTo(BigDecimal.ONE) > 0) {
            BigDecimal adjustment = collateralCoverageRatio.subtract(BigDecimal.ONE)
                    .multiply(new BigDecimal("0.15"), MC);
            baseLgd = baseLgd.subtract(adjustment).max(new BigDecimal("0.10"));
        }

        return baseLgd;
    }

    /**
     * Calculates Expected Loss (EL) = PD * LGD * EAD (Exposure at Default).
     */
    public static Money expectedLoss(BigDecimal pd, BigDecimal lgd, Money exposureAtDefault) {
        BigDecimal elFraction = pd.multiply(lgd, MC);
        return exposureAtDefault.times(elFraction);
    }

    /**
     * Performs a full rating assessment for a loan.
     */
    public static RatingResult rateLoan(
            LoanId loanId,
            List<FactorScore> factorScores,
            BigDecimal collateralCoverageRatio,
            boolean isSenior,
            boolean isSecured,
            Money exposureAtDefault,
            LocalDate ratingDate,
            String analystId,
            BigDecimal calibrationFactor
    ) {
        BigDecimal composite = computeCompositeScore(factorScores);
        RatingGrade grade = RatingGrade.fromScore(composite);
        BigDecimal pd = estimatePD(grade, calibrationFactor);
        BigDecimal lgd = estimateLGD(collateralCoverageRatio, isSenior, isSecured);
        Money el = expectedLoss(pd, lgd, exposureAtDefault);

        return new RatingResult(
                loanId, composite, grade, pd, lgd,
                el.amount(), factorScores, ratingDate,
                analystId, Optional.empty()
        );
    }

    /**
     * Detects rating migration between two rating results and returns
     * a migration record if the grade changed.
     */
    public static Optional<RatingMigration> detectMigration(
            LoanId loanId,
            RatingGrade previousGrade,
            RatingGrade currentGrade,
            LocalDate migrationDate,
            String trigger
    ) {
        if (previousGrade == currentGrade) {
            return Optional.empty();
        }
        int notches = currentGrade.numericGrade() - previousGrade.numericGrade();
        return Optional.of(new RatingMigration(
                loanId, previousGrade, currentGrade, migrationDate, trigger, notches
        ));
    }

    /**
     * Identifies loans on the watchlist based on rating deterioration thresholds.
     * A loan is watchlisted if it has been downgraded by two or more notches
     * within the lookback period or has a grade of CCC or below.
     */
    public static boolean isWatchlistCandidate(
            RatingGrade currentGrade,
            List<RatingMigration> recentMigrations,
            int notchThreshold
    ) {
        if (currentGrade.numericGrade() >= RatingGrade.CCC.numericGrade()) {
            return true;
        }
        int totalDowngrade = recentMigrations.stream()
                .filter(RatingMigration::isDowngrade)
                .mapToInt(m -> Math.abs(m.notchesChanged()))
                .sum();
        return totalDowngrade >= notchThreshold;
    }
}
