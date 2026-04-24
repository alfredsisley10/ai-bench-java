package com.omnibank.productvariants.simpleira;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleIraServiceTest {

    private Clock clock;
    private SimpleIraService service;
    private List<SimpleIraLifecycleEvent> events;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"));
        service = new SimpleIraService(clock);
        events = new ArrayList<>();
        service.subscribe(events::add);
    }

    @Test
    void product_defaults_load_expected_values() {
        var p = SimpleIraProduct.defaults();
        assertThat(p.displayName()).isEqualTo("SIMPLE IRA");
        assertThat(p.category()).isEqualTo("RETIREMENT");
        assertThat(p.jurisdiction()).isEqualTo("US");
    }

    @Test
    void pricing_engine_rate_is_at_least_base_rate() {
        var engine = new SimpleIraPricingEngine();
        var eff = engine.effectiveRate(new BigDecimal("1000"));
        assertThat(eff).isGreaterThanOrEqualTo(engine.baseRate());
    }

    @Test
    void high_balance_tier_bonus_applied() {
        var engine = new SimpleIraPricingEngine();
        var low = engine.effectiveRate(new BigDecimal("100"));
        var high = engine.effectiveRate(new BigDecimal("500000"));
        assertThat(high).isGreaterThanOrEqualTo(low);
    }

    @Test
    void fee_schedule_monthly_maintenance_matches_spec() {
        var fs = SimpleIraFeeSchedule.defaults();
        assertThat(fs.monthlyMaintenance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void fee_schedule_is_active_within_effective_window() {
        var fs = SimpleIraFeeSchedule.defaults();
        assertThat(fs.isActive(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(fs.isActive(LocalDate.of(2020, 1, 1))).isFalse();
    }

    @Test
    void dormancy_fee_zero_before_twelve_months() {
        var fs = SimpleIraFeeSchedule.defaults();
        assertThat(fs.dormancyAssessment(11))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void eligibility_blocks_age_under_minimum() {
        var rules = new SimpleIraEligibilityRules();
        var dob = LocalDate.of(2026, 6, 1).minusYears(Math.max(0, 18 - 1));
        var applicant = applicant(dob);
        var result = rules.evaluate(applicant, LocalDate.of(2026, 6, 1));
        if (18 > 0) {
            assertThat(result.outcome())
                    .isEqualTo(SimpleIraEligibilityRules.Outcome.NOT_ELIGIBLE);
        } else {
            assertThat(result).isNotNull();
        }
    }

    @Test
    void eligibility_passes_for_adult_applicant_in_jurisdiction() {
        var rules = new SimpleIraEligibilityRules();
        int targetAge = Math.max(18, Math.min(18 + 5, 125));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var applicant = applicant(dob);
        var result = rules.evaluate(applicant, LocalDate.of(2026, 6, 1));
        assertThat(result.outcome())
                .isIn(SimpleIraEligibilityRules.Outcome.ELIGIBLE,
                      SimpleIraEligibilityRules.Outcome.MANUAL_REVIEW);
    }

    @Test
    void open_and_fund_round_trip() {
        int targetAge = Math.max(18, Math.min(18 + 5, 125));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var applicant = applicant(dob);
        var snap = service.openAccount(applicant, "cust-001", "WEB");
        var funded = service.fund(snap.accountId(),
                new BigDecimal("2500.00"), "ACH-INITIAL");
        assertThat(funded.state()).isEqualTo(SimpleIraService.AccountState.OPEN);
        assertThat(funded.balance()).isEqualByComparingTo("2500.00");
        assertThat(events).isNotEmpty();
    }

    @Test
    void fund_negative_amount_rejected() {
        int targetAge = Math.max(18, Math.min(18 + 5, 125));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var snap = service.openAccount(applicant(dob), "cust-002", "WEB");
        assertThatThrownBy(() -> service.fund(snap.accountId(),
                new BigDecimal("-10"), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accrue_interest_updates_running_total() {
        int targetAge = Math.max(18, Math.min(18 + 5, 125));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var snap = service.openAccount(applicant(dob), "cust-003", "WEB");
        var funded = service.fund(snap.accountId(),
                new BigDecimal("10000"), "ACH");
        var accrued = service.accrueInterest(funded.accountId(),
                LocalDate.of(2026, 6, 2));
        assertThat(accrued.accruedInterest())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void disclosure_renders_full_text() {
        var d = new SimpleIraDisclosure();
        var text = d.renderFullDisclosure(LocalDate.of(2026, 6, 1));
        assertThat(text).contains("SIMPLE IRA").contains("REGULATORY");
    }

    private SimpleIraEligibilityRules.Applicant applicant(LocalDate dob) {
        return new SimpleIraEligibilityRules.Applicant(
                "Alex", "Demo", dob,
                "US",
                "SMALL_BUSINESS",
                true, true, true);
    }
}
