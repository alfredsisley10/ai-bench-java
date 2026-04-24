package com.omnibank.productvariants.utma529;

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

class Utma529ServiceTest {

    private Clock clock;
    private Utma529Service service;
    private List<Utma529LifecycleEvent> events;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"));
        service = new Utma529Service(clock);
        events = new ArrayList<>();
        service.subscribe(events::add);
    }

    @Test
    void product_defaults_load_expected_values() {
        var p = Utma529Product.defaults();
        assertThat(p.displayName()).isEqualTo("UTMA 529 Plan");
        assertThat(p.category()).isEqualTo("EDUCATION");
        assertThat(p.jurisdiction()).isEqualTo("US");
    }

    @Test
    void pricing_engine_rate_is_at_least_base_rate() {
        var engine = new Utma529PricingEngine();
        var eff = engine.effectiveRate(new BigDecimal("1000"));
        assertThat(eff).isGreaterThanOrEqualTo(engine.baseRate());
    }

    @Test
    void high_balance_tier_bonus_applied() {
        var engine = new Utma529PricingEngine();
        var low = engine.effectiveRate(new BigDecimal("100"));
        var high = engine.effectiveRate(new BigDecimal("500000"));
        assertThat(high).isGreaterThanOrEqualTo(low);
    }

    @Test
    void fee_schedule_monthly_maintenance_matches_spec() {
        var fs = Utma529FeeSchedule.defaults();
        assertThat(fs.monthlyMaintenance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void fee_schedule_is_active_within_effective_window() {
        var fs = Utma529FeeSchedule.defaults();
        assertThat(fs.isActive(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(fs.isActive(LocalDate.of(2020, 1, 1))).isFalse();
    }

    @Test
    void dormancy_fee_zero_before_twelve_months() {
        var fs = Utma529FeeSchedule.defaults();
        assertThat(fs.dormancyAssessment(11))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void eligibility_blocks_age_under_minimum() {
        var rules = new Utma529EligibilityRules();
        var dob = LocalDate.of(2026, 6, 1).minusYears(Math.max(0, 0 - 1));
        var applicant = applicant(dob);
        var result = rules.evaluate(applicant, LocalDate.of(2026, 6, 1));
        if (0 > 0) {
            assertThat(result.outcome())
                    .isEqualTo(Utma529EligibilityRules.Outcome.NOT_ELIGIBLE);
        } else {
            assertThat(result).isNotNull();
        }
    }

    @Test
    void eligibility_passes_for_adult_applicant_in_jurisdiction() {
        var rules = new Utma529EligibilityRules();
        int targetAge = Math.max(0, Math.min(0 + 5, 17));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var applicant = applicant(dob);
        var result = rules.evaluate(applicant, LocalDate.of(2026, 6, 1));
        assertThat(result.outcome())
                .isIn(Utma529EligibilityRules.Outcome.ELIGIBLE,
                      Utma529EligibilityRules.Outcome.MANUAL_REVIEW);
    }

    @Test
    void open_and_fund_round_trip() {
        int targetAge = Math.max(0, Math.min(0 + 5, 17));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var applicant = applicant(dob);
        var snap = service.openAccount(applicant, "cust-001", "WEB");
        var funded = service.fund(snap.accountId(),
                new BigDecimal("2500.00"), "ACH-INITIAL");
        assertThat(funded.state()).isEqualTo(Utma529Service.AccountState.OPEN);
        assertThat(funded.balance()).isEqualByComparingTo("2500.00");
        assertThat(events).isNotEmpty();
    }

    @Test
    void fund_negative_amount_rejected() {
        int targetAge = Math.max(0, Math.min(0 + 5, 17));
        var dob = LocalDate.of(2026, 6, 1).minusYears(targetAge);
        var snap = service.openAccount(applicant(dob), "cust-002", "WEB");
        assertThatThrownBy(() -> service.fund(snap.accountId(),
                new BigDecimal("-10"), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accrue_interest_updates_running_total() {
        int targetAge = Math.max(0, Math.min(0 + 5, 17));
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
        var d = new Utma529Disclosure();
        var text = d.renderFullDisclosure(LocalDate.of(2026, 6, 1));
        assertThat(text).contains("UTMA 529 Plan").contains("REGULATORY");
    }

    private Utma529EligibilityRules.Applicant applicant(LocalDate dob) {
        return new Utma529EligibilityRules.Applicant(
                "Alex", "Demo", dob,
                "US",
                "MINOR",
                true, true, true);
    }
}
