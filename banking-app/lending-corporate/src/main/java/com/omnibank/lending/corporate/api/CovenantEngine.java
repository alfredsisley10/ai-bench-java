package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluates financial and behavioral covenants attached to a corporate loan.
 * Financial covenants test measurable ratios (debt-to-equity, interest coverage,
 * current ratio, DSCR). Behavioral covenants verify compliance with non-numeric
 * obligations (insurance maintenance, reporting deadlines, negative pledges).
 *
 * <p>Breach detection includes cure-period logic: a covenant is not formally in
 * default until the cure window expires without remediation. The engine tracks
 * both <em>technical breaches</em> (curable) and <em>hard defaults</em>.
 */
public final class CovenantEngine {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private CovenantEngine() {}

    // ── Financial data input ──────────────────────────────────────────────

    /**
     * Snapshot of a borrower's financials used for covenant testing.
     * All monetary amounts must be in the same currency.
     */
    public record FinancialData(
            Money totalDebt,
            Money totalEquity,
            Money ebitda,
            Money interestExpense,
            Money currentAssets,
            Money currentLiabilities,
            Money netOperatingIncome,
            Money totalDebtService,
            LocalDate asOfDate
    ) {
        public FinancialData {
            Objects.requireNonNull(totalDebt, "totalDebt");
            Objects.requireNonNull(totalEquity, "totalEquity");
            Objects.requireNonNull(ebitda, "ebitda");
            Objects.requireNonNull(interestExpense, "interestExpense");
            Objects.requireNonNull(currentAssets, "currentAssets");
            Objects.requireNonNull(currentLiabilities, "currentLiabilities");
            Objects.requireNonNull(netOperatingIncome, "netOperatingIncome");
            Objects.requireNonNull(totalDebtService, "totalDebtService");
            Objects.requireNonNull(asOfDate, "asOfDate");
        }
    }

    // ── Breach result ─────────────────────────────────────────────────────

    public enum BreachSeverity { NONE, TECHNICAL, HARD_DEFAULT }

    public record CovenantTestResult(
            String covenantId,
            boolean passed,
            BreachSeverity severity,
            BigDecimal actualValue,
            BigDecimal thresholdValue,
            LocalDate testDate,
            Optional<LocalDate> cureDeadline,
            String narrative
    ) {
        public CovenantTestResult {
            Objects.requireNonNull(covenantId, "covenantId");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(testDate, "testDate");
            Objects.requireNonNull(cureDeadline, "cureDeadline");
            Objects.requireNonNull(narrative, "narrative");
        }
    }

    public record BehavioralTestResult(
            String covenantId,
            boolean compliant,
            BreachSeverity severity,
            LocalDate testDate,
            Optional<LocalDate> cureDeadline,
            String narrative
    ) {
        public BehavioralTestResult {
            Objects.requireNonNull(covenantId, "covenantId");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(testDate, "testDate");
            Objects.requireNonNull(cureDeadline, "cureDeadline");
            Objects.requireNonNull(narrative, "narrative");
        }
    }

    // ── Cure-period configuration ─────────────────────────────────────────

    public record CurePeriod(long days) {
        public static final CurePeriod STANDARD = new CurePeriod(30);
        public static final CurePeriod EXTENDED = new CurePeriod(60);
        public static final CurePeriod NONE = new CurePeriod(0);

        public CurePeriod {
            if (days < 0) throw new IllegalArgumentException("Cure period cannot be negative");
        }

        public LocalDate deadlineFrom(LocalDate breachDate) {
            return breachDate.plusDays(days);
        }
    }

    // ── Financial covenant evaluation ─────────────────────────────────────

    public static BigDecimal computeDebtToEquity(FinancialData data) {
        if (data.totalEquity().isZero()) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        return data.totalDebt().amount()
                .divide(data.totalEquity().amount(), MC);
    }

    public static BigDecimal computeInterestCoverage(FinancialData data) {
        if (data.interestExpense().isZero()) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        return data.ebitda().amount()
                .divide(data.interestExpense().amount(), MC);
    }

    public static BigDecimal computeCurrentRatio(FinancialData data) {
        if (data.currentLiabilities().isZero()) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        return data.currentAssets().amount()
                .divide(data.currentLiabilities().amount(), MC);
    }

    public static BigDecimal computeDSCR(FinancialData data) {
        if (data.totalDebtService().isZero()) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
        return data.netOperatingIncome().amount()
                .divide(data.totalDebtService().amount(), MC);
    }

    /**
     * Tests a single financial covenant against the provided financial data.
     * Returns a detailed result including the actual vs. threshold comparison,
     * pass/fail determination, and cure-period information if breached.
     */
    public static CovenantTestResult testFinancialCovenant(
            Covenant.Financial covenant,
            FinancialData data,
            CurePeriod curePeriod,
            Optional<LocalDate> priorBreachDate
    ) {
        BigDecimal actual = switch (covenant.metric()) {
            case DEBT_SERVICE_COVERAGE -> computeDSCR(data);
            case LEVERAGE_RATIO -> computeDebtToEquity(data);
            case FIXED_CHARGE_COVERAGE -> computeInterestCoverage(data);
            case MIN_TANGIBLE_NET_WORTH -> data.totalEquity().amount();
            case CURRENT_RATIO -> computeCurrentRatio(data);
        };

        boolean passed = switch (covenant.operator()) {
            case GTE -> actual.compareTo(covenant.threshold()) >= 0;
            case LTE -> actual.compareTo(covenant.threshold()) <= 0;
            case GT -> actual.compareTo(covenant.threshold()) > 0;
            case LT -> actual.compareTo(covenant.threshold()) < 0;
        };

        if (passed) {
            return new CovenantTestResult(
                    covenant.id(), true, BreachSeverity.NONE,
                    actual, covenant.threshold(), data.asOfDate(),
                    Optional.empty(),
                    "Covenant %s met: actual=%s, threshold=%s %s".formatted(
                            covenant.metric(), actual.toPlainString(),
                            covenant.operator(), covenant.threshold().toPlainString())
            );
        }

        BreachSeverity severity = determineSeverity(
                data.asOfDate(), curePeriod, priorBreachDate);
        Optional<LocalDate> cureDeadline = curePeriod.days() > 0
                ? Optional.of(curePeriod.deadlineFrom(
                        priorBreachDate.orElse(data.asOfDate())))
                : Optional.empty();

        return new CovenantTestResult(
                covenant.id(), false, severity,
                actual, covenant.threshold(), data.asOfDate(), cureDeadline,
                "Covenant %s BREACHED: actual=%s vs threshold %s %s. Severity: %s".formatted(
                        covenant.metric(), actual.toPlainString(),
                        covenant.operator(), covenant.threshold().toPlainString(), severity)
        );
    }

    /**
     * Evaluates all financial covenants for a loan in a single pass.
     */
    public static List<CovenantTestResult> testAllFinancialCovenants(
            List<Covenant.Financial> covenants,
            FinancialData data,
            CurePeriod curePeriod
    ) {
        List<CovenantTestResult> results = new ArrayList<>();
        for (var covenant : covenants) {
            results.add(testFinancialCovenant(covenant, data, curePeriod, Optional.empty()));
        }
        return Collections.unmodifiableList(results);
    }

    // ── Behavioral covenant evaluation ────────────────────────────────────

    /**
     * Tests a behavioral covenant. Since behavioral covenants are binary
     * (compliant / non-compliant), the caller must supply the compliance flag
     * obtained from external verification (e.g., insurance certificate on file,
     * financial statements received on time).
     */
    public static BehavioralTestResult testBehavioralCovenant(
            Covenant.Behavioral covenant,
            boolean isCompliant,
            LocalDate testDate,
            CurePeriod curePeriod,
            Optional<LocalDate> priorBreachDate
    ) {
        if (isCompliant) {
            return new BehavioralTestResult(
                    covenant.id(), true, BreachSeverity.NONE,
                    testDate, Optional.empty(),
                    "Behavioral covenant '%s' satisfied as of %s".formatted(
                            covenant.description(), testDate)
            );
        }

        BreachSeverity severity = determineSeverity(testDate, curePeriod, priorBreachDate);
        Optional<LocalDate> cureDeadline = curePeriod.days() > 0
                ? Optional.of(curePeriod.deadlineFrom(
                        priorBreachDate.orElse(testDate)))
                : Optional.empty();

        return new BehavioralTestResult(
                covenant.id(), false, severity,
                testDate, cureDeadline,
                "Behavioral covenant '%s' BREACHED as of %s. Severity: %s".formatted(
                        covenant.description(), testDate, severity)
        );
    }

    // ── Aggregate compliance summary ──────────────────────────────────────

    public record ComplianceSummary(
            LoanId loanId,
            LocalDate testDate,
            int totalCovenants,
            int passed,
            int technicalBreaches,
            int hardDefaults,
            boolean inCompliance
    ) {
        public boolean hasAnyBreach() {
            return technicalBreaches > 0 || hardDefaults > 0;
        }
    }

    public static ComplianceSummary summarize(
            LoanId loanId,
            List<CovenantTestResult> financialResults,
            List<BehavioralTestResult> behavioralResults,
            LocalDate testDate
    ) {
        int total = financialResults.size() + behavioralResults.size();
        int passCount = 0;
        int technicalCount = 0;
        int hardCount = 0;

        for (var r : financialResults) {
            switch (r.severity()) {
                case NONE -> passCount++;
                case TECHNICAL -> technicalCount++;
                case HARD_DEFAULT -> hardCount++;
            }
        }
        for (var r : behavioralResults) {
            switch (r.severity()) {
                case NONE -> passCount++;
                case TECHNICAL -> technicalCount++;
                case HARD_DEFAULT -> hardCount++;
            }
        }

        return new ComplianceSummary(
                loanId, testDate, total, passCount,
                technicalCount, hardCount, hardCount == 0 && technicalCount == 0
        );
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static BreachSeverity determineSeverity(
            LocalDate currentDate,
            CurePeriod curePeriod,
            Optional<LocalDate> priorBreachDate
    ) {
        if (curePeriod.days() == 0) {
            return BreachSeverity.HARD_DEFAULT;
        }
        if (priorBreachDate.isPresent()) {
            long daysSinceBreach = ChronoUnit.DAYS.between(priorBreachDate.get(), currentDate);
            return daysSinceBreach > curePeriod.days()
                    ? BreachSeverity.HARD_DEFAULT
                    : BreachSeverity.TECHNICAL;
        }
        return BreachSeverity.TECHNICAL;
    }
}
