package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.FdicAssessmentCalculator.AssessmentInput;
import com.omnibank.regreporting.internal.FdicAssessmentCalculator.AssessmentInvoice;
import com.omnibank.regreporting.internal.FdicAssessmentCalculator.LossSeverityInputs;
import com.omnibank.regreporting.internal.FdicAssessmentCalculator.PerformanceInputs;
import com.omnibank.regreporting.internal.FdicAssessmentCalculator.RateAdjustments;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FdicAssessmentCalculatorTest {

    private static Money usd(String v) { return Money.of(v, CurrencyCode.USD); }

    private final FdicAssessmentCalculator calc = new FdicAssessmentCalculator();

    @Test
    void low_risk_bank_gets_near_minimum_rate() {
        PerformanceInputs p = new PerformanceInputs(
                new BigDecimal("0.15"),   // leverage 15% — excellent
                new BigDecimal("0.005"),  // NPL 0.5%
                new BigDecimal("0.015"),  // ROA 1.5%
                new BigDecimal("0.80"),   // core deposits 80%
                new BigDecimal("0.05"));
        LossSeverityInputs ls = new LossSeverityInputs(
                new BigDecimal("0.002"),
                new BigDecimal("0.05"),
                new BigDecimal("0.05"));
        AssessmentInput in = new AssessmentInput("RSSD-1",
                LocalDate.of(2026, 3, 31), p, ls,
                FdicAssessmentCalculator.noAdjustments(),
                usd("100000000"), usd("10000000"), false);
        AssessmentInvoice inv = calc.calculate(in);

        assertThat(inv.totalBaseRateBps())
                .isLessThanOrEqualTo(new BigDecimal("10.0"));
        assertThat(inv.assessmentBase()).isEqualTo(usd("90000000"));
        assertThat(inv.quarterlyAssessment().isPositive()).isTrue();
    }

    @Test
    void high_risk_bank_gets_max_rate_region() {
        PerformanceInputs p = new PerformanceInputs(
                new BigDecimal("0.03"),   // leverage 3% — poor
                new BigDecimal("0.08"),   // NPL 8%
                new BigDecimal("-0.01"),  // negative ROA
                new BigDecimal("0.25"),
                new BigDecimal("0.90"));
        LossSeverityInputs ls = new LossSeverityInputs(
                new BigDecimal("0.20"),
                new BigDecimal("0.80"),
                new BigDecimal("0.50"));
        AssessmentInput in = new AssessmentInput("RSSD-2",
                LocalDate.of(2026, 3, 31), p, ls,
                FdicAssessmentCalculator.noAdjustments(),
                usd("100000000"), usd("5000000"), true);
        AssessmentInvoice inv = calc.calculate(in);

        assertThat(inv.totalBaseRateBps())
                .isGreaterThanOrEqualTo(new BigDecimal("15.0"));
        assertThat(inv.totalScore())
                .isGreaterThan(new BigDecimal("30"));
    }

    @Test
    void brokered_deposit_cap_enforced() {
        RateAdjustments too_high = new RateAdjustments(
                BigDecimal.ZERO, new BigDecimal("15.0"), BigDecimal.ZERO);
        assertThatThrownBy(() -> FdicAssessmentCalculator.validateAdjustments(too_high))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsecured_debt_adjustment_bounds_check() {
        RateAdjustments out_of_band = new RateAdjustments(
                new BigDecimal("-10.0"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> FdicAssessmentCalculator.validateAdjustments(out_of_band))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void annualised_is_four_times_quarterly() {
        PerformanceInputs p = new PerformanceInputs(
                new BigDecimal("0.08"), new BigDecimal("0.01"),
                new BigDecimal("0.01"), new BigDecimal("0.70"),
                new BigDecimal("0.10"));
        LossSeverityInputs ls = new LossSeverityInputs(
                new BigDecimal("0.01"),
                new BigDecimal("0.30"),
                new BigDecimal("0.10"));
        AssessmentInput in = new AssessmentInput("RSSD-3",
                LocalDate.of(2026, 6, 30), p, ls,
                FdicAssessmentCalculator.noAdjustments(),
                usd("50000000"), usd("5000000"), false);
        AssessmentInvoice inv = calc.calculate(in);
        assertThat(calc.annualisedAssessment(inv))
                .isEqualTo(inv.quarterlyAssessment().times(4L));
    }

    @Test
    void score_to_rate_mapping_is_monotonic() {
        BigDecimal low = FdicAssessmentCalculator.mapScoreToRate(BigDecimal.ZERO);
        BigDecimal mid = FdicAssessmentCalculator.mapScoreToRate(new BigDecimal("50"));
        BigDecimal high = FdicAssessmentCalculator.mapScoreToRate(new BigDecimal("100"));
        assertThat(low).isLessThan(mid);
        assertThat(mid).isLessThan(high);
        assertThat(low).isEqualByComparingTo(FdicAssessmentCalculator.INITIAL_MIN_BPS);
        assertThat(high).isEqualByComparingTo(FdicAssessmentCalculator.INITIAL_MAX_BPS);
    }

    @Test
    void score_clamps_into_allowed_range() {
        BigDecimal negative = FdicAssessmentCalculator.mapScoreToRate(new BigDecimal("-10"));
        BigDecimal over = FdicAssessmentCalculator.mapScoreToRate(new BigDecimal("150"));
        assertThat(negative).isEqualByComparingTo(FdicAssessmentCalculator.INITIAL_MIN_BPS);
        assertThat(over).isEqualByComparingTo(FdicAssessmentCalculator.INITIAL_MAX_BPS);
    }

    @Test
    void risk_category_buckets() {
        assertThat(FdicAssessmentCalculator.riskCategoryOf(new BigDecimal("20")))
                .isEqualTo("LOW_RISK");
        assertThat(FdicAssessmentCalculator.riskCategoryOf(new BigDecimal("45")))
                .isEqualTo("MODERATE_RISK");
        assertThat(FdicAssessmentCalculator.riskCategoryOf(new BigDecimal("75")))
                .isEqualTo("HIGH_RISK");
        assertThat(FdicAssessmentCalculator.riskCategoryOf(new BigDecimal("99")))
                .isEqualTo("HIGHEST_RISK");
    }

    @Test
    void assessment_base_is_assets_minus_equity() {
        assertThat(FdicAssessmentCalculator.assessmentBase(usd("100"), usd("20")))
                .isEqualTo(usd("80"));
    }

    @Test
    void render_summary_includes_total_score() {
        AssessmentInvoice inv = calc.calculate(FdicAssessmentCalculator.zeroInput(
                "RSSD-4", LocalDate.of(2026, 9, 30),
                usd("200000000"), usd("20000000")));
        var lines = calc.renderSummary(inv);
        assertThat(lines).anyMatch(l -> l.contains("Total score"));
    }
}
