package com.omnibank.risk.internal;

import com.omnibank.risk.internal.RiskWeightedAssetsEngine.Approach;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.CreditExposure;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.CreditRiskCategory;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.CreditRwaReport;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.MarketRiskBucket;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.MarketRiskPosition;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.MarketRwaReport;
import com.omnibank.risk.internal.RiskWeightedAssetsEngine.OperationalRwaReport;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskWeightedAssetsEngineTest {

    private RiskWeightedAssetsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RiskWeightedAssetsEngine();
    }

    @Test
    void cash_exposures_carry_zero_risk_weight() {
        CreditExposure cash = new CreditExposure("E1",
                CreditRiskCategory.CASH_AND_EQUIVALENT,
                Money.of("1000000", CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                BigDecimal.ZERO);
        CreditRwaReport report = engine.computeCreditRwa(List.of(cash), CurrencyCode.USD);

        assertThat(report.totalRwa().isZero()).isTrue();
        assertThat(report.totalEad()).isEqualTo(Money.of("1000000", CurrencyCode.USD));
    }

    @Test
    void past_due_exposures_carry_150pct_risk_weight() {
        CreditExposure pastDue = new CreditExposure("E1",
                CreditRiskCategory.PAST_DUE,
                Money.of("1000000", CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                BigDecimal.ZERO);
        CreditRwaReport report = engine.computeCreditRwa(List.of(pastDue), CurrencyCode.USD);

        assertThat(report.totalRwa()).isEqualTo(Money.of("1500000", CurrencyCode.USD));
    }

    @Test
    void ead_includes_ccf_times_undrawn() {
        CreditExposure commitment = new CreditExposure("E1",
                CreditRiskCategory.CORPORATE_A,
                Money.of("500000", CurrencyCode.USD),
                Money.of("500000", CurrencyCode.USD),
                new BigDecimal("0.50"));
        Money ead = RiskWeightedAssetsEngine.effectiveExposureAtDefault(commitment);

        assertThat(ead).isEqualTo(Money.of("750000", CurrencyCode.USD));
    }

    @Test
    void mortgage_category_respects_ltv_tiers() {
        assertThat(RiskWeightedAssetsEngine.mortgageCategoryForLtv(new BigDecimal("70")))
                .isEqualTo(CreditRiskCategory.RESIDENTIAL_MORTGAGE_LTV_LT_80);
        assertThat(RiskWeightedAssetsEngine.mortgageCategoryForLtv(new BigDecimal("85")))
                .isEqualTo(CreditRiskCategory.RESIDENTIAL_MORTGAGE_LTV_80_90);
        assertThat(RiskWeightedAssetsEngine.mortgageCategoryForLtv(new BigDecimal("95")))
                .isEqualTo(CreditRiskCategory.RESIDENTIAL_MORTGAGE_LTV_GT_90);
    }

    @Test
    void corporate_rating_mapping_defaults_speculative_for_unknown() {
        assertThat(RiskWeightedAssetsEngine.corporateCategoryForRating("AAA"))
                .isEqualTo(CreditRiskCategory.CORPORATE_AAA_AA);
        assertThat(RiskWeightedAssetsEngine.corporateCategoryForRating("A+"))
                .isEqualTo(CreditRiskCategory.CORPORATE_A);
        assertThat(RiskWeightedAssetsEngine.corporateCategoryForRating("BBB-"))
                .isEqualTo(CreditRiskCategory.CORPORATE_BBB);
        assertThat(RiskWeightedAssetsEngine.corporateCategoryForRating("CCC+"))
                .isEqualTo(CreditRiskCategory.CORPORATE_SPECULATIVE);
    }

    @Test
    void bia_uses_only_positive_income_years() {
        OperationalRwaReport r = engine.computeOperationalRwaBia(List.of(
                Money.of("100000000", CurrencyCode.USD),
                Money.of("-5000000", CurrencyCode.USD),
                Money.of("120000000", CurrencyCode.USD)));
        assertThat(r.approach()).isEqualTo(Approach.BIA);
        // average of positives = 110M; capital = 16.5M
        assertThat(r.capitalRequirement()).isEqualTo(Money.of("16500000", CurrencyCode.USD));
        assertThat(r.rwa()).isEqualTo(Money.of("206250000", CurrencyCode.USD));
    }

    @Test
    void bia_rejects_empty_input() {
        assertThatThrownBy(() -> engine.computeOperationalRwaBia(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standardised_operational_rwa_respects_ilm_bounds() {
        OperationalRwaReport r = engine.computeOperationalRwaStandardised(
                Money.of("400000000", CurrencyCode.USD),
                Money.of("300000000", CurrencyCode.USD),
                Money.of("100000000", CurrencyCode.USD),
                Money.of("1000000", CurrencyCode.USD));

        assertThat(r.approach()).isEqualTo(Approach.STANDARDISED_APPROACH_2023);
        assertThat(r.capitalRequirement().isPositive()).isTrue();
        assertThat(r.rwa().isPositive()).isTrue();
    }

    @Test
    void market_rwa_scales_by_regulatory_multiplier() {
        MarketRwaReport r = engine.computeMarketRwa(List.of(
                new MarketRiskPosition("P1", MarketRiskBucket.EQUITY_GENERAL,
                        Money.of("1000000", CurrencyCode.USD))
        ), CurrencyCode.USD);

        // charge = 1M * 0.08 = 80k; rwa = 80k * 12.5 = 1M
        assertThat(r.totalCapitalCharge()).isEqualTo(Money.of("80000", CurrencyCode.USD));
        assertThat(r.totalRwa()).isEqualTo(Money.of("1000000", CurrencyCode.USD));
    }

    @Test
    void total_rwa_is_sum_of_pillars_and_is_internally_consistent() {
        CreditExposure cash = new CreditExposure("E1",
                CreditRiskCategory.CORPORATE_BBB,
                Money.of("1000000", CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                BigDecimal.ZERO);
        CreditRwaReport credit = engine.computeCreditRwa(List.of(cash), CurrencyCode.USD);
        OperationalRwaReport op = engine.computeOperationalRwaBia(
                List.of(Money.of("10000000", CurrencyCode.USD),
                        Money.of("12000000", CurrencyCode.USD),
                        Money.of("8000000", CurrencyCode.USD)));
        MarketRwaReport market = engine.computeMarketRwa(List.of(
                new MarketRiskPosition("P1", MarketRiskBucket.FOREIGN_EXCHANGE,
                        Money.of("500000", CurrencyCode.USD))
        ), CurrencyCode.USD);

        var total = engine.totalRwa(credit, op, market);
        assertThat(RiskWeightedAssetsEngine.isInternallyConsistent(total)).isTrue();
        assertThat(total.totalRwa().compareTo(total.creditRwa())).isGreaterThan(0);
    }

    @Test
    void audit_lines_cover_all_pillars() {
        CreditExposure e = new CreditExposure("E1",
                CreditRiskCategory.RETAIL_UNSECURED,
                Money.of("100000", CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                BigDecimal.ZERO);
        CreditRwaReport credit = engine.computeCreditRwa(List.of(e), CurrencyCode.USD);
        OperationalRwaReport op = engine.computeOperationalRwaBia(
                List.of(Money.of("1000000", CurrencyCode.USD)));
        MarketRwaReport market = engine.computeMarketRwa(List.of(
                new MarketRiskPosition("P1", MarketRiskBucket.COMMODITY,
                        Money.of("250000", CurrencyCode.USD))
        ), CurrencyCode.USD);

        var lines = engine.asAuditLines(credit, op, market);
        assertThat(lines).anySatisfy(l -> assertThat(l.lineLabel()).startsWith("CREDIT:"));
        assertThat(lines).anySatisfy(l -> assertThat(l.lineLabel()).startsWith("OPERATIONAL:"));
        assertThat(lines).anySatisfy(l -> assertThat(l.lineLabel()).startsWith("MARKET:"));
    }

    @Test
    void conversion_factor_is_validated_in_exposure_record() {
        assertThatThrownBy(() -> new CreditExposure("E1",
                CreditRiskCategory.CORPORATE_BBB,
                Money.of("1000", CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                new BigDecimal("1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
