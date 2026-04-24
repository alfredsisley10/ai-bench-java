package com.omnibank.brand.cdladder24month.silverbank;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CdLadder24MonthSilverbankBrandProfileTest {

    @Test
    void brand_profile_reports_expected_code() {
        var p = new CdLadder24MonthSilverbankBrandProfile();
        assertThat(p.brandCode()).isEqualTo("SILVERBANK");
    }

    @Test
    void brand_age_is_non_negative() {
        var p = new CdLadder24MonthSilverbankBrandProfile();
        assertThat(p.ageInYears(2026)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onboarding_flow_has_required_identity_step() {
        var flow = new CdLadder24MonthSilverbankOnboardingFlow();
        assertThat(flow.hasStep("IDENTITY_CAPTURE")).isTrue();
        assertThat(flow.estimatedMinutes()).isGreaterThan(0);
    }

    @Test
    void pricing_override_apy_does_not_decrease_rate() {
        var po = new CdLadder24MonthSilverbankPricingOverride();
        var adjusted = po.adjustApy(new BigDecimal("0.0300"));
        assertThat(adjusted).isGreaterThanOrEqualTo(new BigDecimal("0.0300"));
    }

    @Test
    void fee_never_negative_after_adjustment() {
        var po = new CdLadder24MonthSilverbankPricingOverride();
        assertThat(po.adjustMonthlyFee(new BigDecimal("12.00")))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(po.adjustMonthlyFee(BigDecimal.ZERO))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void marketing_headline_is_nonblank() {
        var mc = new CdLadder24MonthSilverbankMarketingCopy();
        assertThat(mc.heroHeadline()).isNotBlank();
        assertThat(mc.subheadline()).isNotBlank();
    }

    @Test
    void communication_sender_is_nonblank() {
        var cc = new CdLadder24MonthSilverbankCustomerCommunication();
        assertThat(cc.senderFromAddress()).contains("@");
    }

    @Test
    void brand_value_statements_are_nonempty() {
        var p = new CdLadder24MonthSilverbankBrandProfile();
        assertThat(p.brandValueStatements()).isNotEmpty();
    }
}
