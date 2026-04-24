package com.omnibank.regional.essentialchecking.tx;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EssentialCheckingTXProfileTest {

    @Test
    void profile_reports_expected_state_code() {
        var p = new EssentialCheckingTXProfile();
        assertThat(p.stateCode()).isEqualTo("TX");
        assertThat(p.stateName()).isEqualTo("Texas");
    }

    @Test
    void apply_state_fee_tax_is_non_decreasing() {
        var p = new EssentialCheckingTXProfile();
        var taxed = p.applyStateFeeTax(new BigDecimal("10.00"));
        assertThat(taxed).isGreaterThanOrEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void rate_table_returns_positive_apy_for_low_balance() {
        var rt = new EssentialCheckingTXRateTable();
        var apy = rt.apyFor(new BigDecimal("500"));
        assertThat(apy.signum()).isGreaterThan(0);
    }

    @Test
    void rate_table_top_tier_is_highest() {
        var rt = new EssentialCheckingTXRateTable();
        var top = rt.topTierApy();
        var low = rt.apyFor(new BigDecimal("100"));
        assertThat(top).isGreaterThanOrEqualTo(low);
    }

    @Test
    void disclosures_contain_state_name() {
        var d = new EssentialCheckingTXDisclosures();
        assertThat(d.renderFullSupplement()).contains("Texas");
    }

    @Test
    void regulatory_filings_produced_for_quarter() {
        var f = new EssentialCheckingTXRegulatoryFilings();
        var filings = f.quarterlyFilings(YearMonth.of(2026, 3));
        assertThat(filings).isNotEmpty();
    }

    @Test
    void channel_integration_lists_web_and_mobile() {
        var b = new EssentialCheckingTXBranchIntegration();
        assertThat(b.channelActive("WEB")).isTrue();
        assertThat(b.channelActive("MOBILE")).isTrue();
    }
}
