package com.omnibank.regional.essentialchecking.ks;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EssentialCheckingKSProfileTest {

    @Test
    void profile_reports_expected_state_code() {
        var p = new EssentialCheckingKSProfile();
        assertThat(p.stateCode()).isEqualTo("KS");
        assertThat(p.stateName()).isEqualTo("Kansas");
    }

    @Test
    void apply_state_fee_tax_is_non_decreasing() {
        var p = new EssentialCheckingKSProfile();
        var taxed = p.applyStateFeeTax(new BigDecimal("10.00"));
        assertThat(taxed).isGreaterThanOrEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void rate_table_returns_positive_apy_for_low_balance() {
        var rt = new EssentialCheckingKSRateTable();
        var apy = rt.apyFor(new BigDecimal("500"));
        assertThat(apy.signum()).isGreaterThan(0);
    }

    @Test
    void rate_table_top_tier_is_highest() {
        var rt = new EssentialCheckingKSRateTable();
        var top = rt.topTierApy();
        var low = rt.apyFor(new BigDecimal("100"));
        assertThat(top).isGreaterThanOrEqualTo(low);
    }

    @Test
    void disclosures_contain_state_name() {
        var d = new EssentialCheckingKSDisclosures();
        assertThat(d.renderFullSupplement()).contains("Kansas");
    }

    @Test
    void regulatory_filings_produced_for_quarter() {
        var f = new EssentialCheckingKSRegulatoryFilings();
        var filings = f.quarterlyFilings(YearMonth.of(2026, 3));
        assertThat(filings).isNotEmpty();
    }

    @Test
    void channel_integration_lists_web_and_mobile() {
        var b = new EssentialCheckingKSBranchIntegration();
        assertThat(b.channelActive("WEB")).isTrue();
        assertThat(b.channelActive("MOBILE")).isTrue();
    }
}
