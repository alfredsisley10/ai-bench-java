package com.omnibank.brand.rothira.silverbank;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RothIraSilverbankBrandProfileTest {

    @Test
    void brand_profile_reports_expected_code() {
        var p = new RothIraSilverbankBrandProfile();
        assertThat(p.brandCode()).isEqualTo("SILVERBANK");
    }

    @Test
    void brand_age_is_non_negative() {
        var p = new RothIraSilverbankBrandProfile();
        assertThat(p.ageInYears(2026)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onboarding_flow_has_required_identity_step() {
        var flow = new RothIraSilverbankOnboardingFlow();
        assertThat(flow.hasStep("IDENTITY_CAPTURE")).isTrue();
        assertThat(flow.estimatedMinutes()).isGreaterThan(0);
    }

    @Test
    void pricing_override_apy_does_not_decrease_rate() {
        var po = new RothIraSilverbankPricingOverride();
        var adjusted = po.adjustApy(new BigDecimal("0.0300"));
        assertThat(adjusted).isGreaterThanOrEqualTo(new BigDecimal("0.0300"));
    }

    @Test
    void fee_never_negative_after_adjustment() {
        var po = new RothIraSilverbankPricingOverride();
        assertThat(po.adjustMonthlyFee(new BigDecimal("12.00")))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(po.adjustMonthlyFee(BigDecimal.ZERO))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void marketing_headline_is_nonblank() {
        var mc = new RothIraSilverbankMarketingCopy();
        assertThat(mc.heroHeadline()).isNotBlank();
        assertThat(mc.subheadline()).isNotBlank();
    }

    @Test
    void communication_sender_is_nonblank() {
        var cc = new RothIraSilverbankCustomerCommunication();
        assertThat(cc.senderFromAddress()).contains("@");
    }

    @Test
    void brand_value_statements_are_nonempty() {
        var p = new RothIraSilverbankBrandProfile();
        assertThat(p.brandValueStatements()).isNotEmpty();
    }
}
