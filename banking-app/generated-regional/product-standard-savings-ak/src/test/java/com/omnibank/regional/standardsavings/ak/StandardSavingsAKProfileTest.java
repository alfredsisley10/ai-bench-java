package com.omnibank.regional.standardsavings.ak;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StandardSavingsAKProfileTest {

    @Test
    void profile_reports_expected_state_code() {
        var p = new StandardSavingsAKProfile();
        assertThat(p.stateCode()).isEqualTo("AK");
        assertThat(p.stateName()).isEqualTo("Alaska");
    }

    @Test
    void apply_state_fee_tax_is_non_decreasing() {
        var p = new StandardSavingsAKProfile();
        var taxed = p.applyStateFeeTax(new BigDecimal("10.00"));
        assertThat(taxed).isGreaterThanOrEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void rate_table_returns_positive_apy_for_low_balance() {
        var rt = new StandardSavingsAKRateTable();
        var apy = rt.apyFor(new BigDecimal("500"));
        assertThat(apy.signum()).isGreaterThan(0);
    }

    @Test
    void rate_table_top_tier_is_highest() {
        var rt = new StandardSavingsAKRateTable();
        var top = rt.topTierApy();
        var low = rt.apyFor(new BigDecimal("100"));
        assertThat(top).isGreaterThanOrEqualTo(low);
    }

    @Test
    void disclosures_contain_state_name() {
        var d = new StandardSavingsAKDisclosures();
        assertThat(d.renderFullSupplement()).contains("Alaska");
    }

    @Test
    void regulatory_filings_produced_for_quarter() {
        var f = new StandardSavingsAKRegulatoryFilings();
        var filings = f.quarterlyFilings(YearMonth.of(2026, 3));
        assertThat(filings).isNotEmpty();
    }

    @Test
    void channel_integration_lists_web_and_mobile() {
        var b = new StandardSavingsAKBranchIntegration();
        assertThat(b.channelActive("WEB")).isTrue();
        assertThat(b.channelActive("MOBILE")).isTrue();
    }
}
