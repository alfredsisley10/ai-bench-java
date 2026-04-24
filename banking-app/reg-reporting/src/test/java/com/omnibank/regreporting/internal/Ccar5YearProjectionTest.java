package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.Ccar5YearProjection.Ccar5YearSubmission;
import com.omnibank.regreporting.internal.Ccar5YearProjection.QuarterProjection;
import com.omnibank.regreporting.internal.Ccar5YearProjection.Scenario;
import com.omnibank.regreporting.internal.Ccar5YearProjection.ScenarioDrivers;
import com.omnibank.regreporting.internal.Ccar5YearProjection.ScenarioProjection;
import com.omnibank.regreporting.internal.Ccar5YearProjection.StartingPosition;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ccar5YearProjectionTest {

    private static Money usd(String v) { return Money.of(v, CurrencyCode.USD); }

    private Ccar5YearProjection engine;
    private StartingPosition start;

    @BeforeEach
    void setUp() {
        engine = new Ccar5YearProjection();
        start = new StartingPosition(
                LocalDate.of(2026, 3, 31),
                usd("1000000000"),
                usd("600000000"),
                usd("700000000"),
                usd("80000000"),
                usd("85000000"),
                usd("95000000"),
                usd("50000000"));
    }

    @Test
    void baseline_scenario_produces_9_quarters() {
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.BASELINE, Ccar5YearProjection.defaultBaseline());
        Ccar5YearSubmission sub = engine.project("RSSD-1", start, drivers);
        ScenarioProjection bp = sub.results().get(Scenario.BASELINE);
        assertThat(bp.quarters()).hasSize(9);
    }

    @Test
    void severely_adverse_breaches_ratio_when_stress_is_deep() {
        // Customize drivers: massive charge-offs and no PPNR growth to force breach
        ScenarioDrivers crash = new ScenarioDrivers(
                new BigDecimal("-0.10"),    // PPNR collapses
                new BigDecimal("0.20"),     // 20% annualized COs
                new BigDecimal("0.01"),
                new BigDecimal("0.05"),
                new BigDecimal("0.00"),
                new BigDecimal("0.00"),
                new BigDecimal("0.21"));
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.SEVERELY_ADVERSE, crash);
        Ccar5YearSubmission sub = engine.project("RSSD-2", start, drivers);
        assertThat(sub.allScenariosPass()).isFalse();
        assertThat(engine.anyScenarioBreaches(sub)).isTrue();
    }

    @Test
    void empty_drivers_is_rejected() {
        assertThatThrownBy(() -> engine.project("RSSD-3", start,
                new EnumMap<>(Scenario.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projection_respects_zero_dividend_when_negative_income() {
        ScenarioDrivers nonePay = new ScenarioDrivers(
                new BigDecimal("-0.20"), new BigDecimal("0.50"),
                new BigDecimal("0.05"), new BigDecimal("0.02"),
                new BigDecimal("0.30"),     // 30% payout, but NI negative
                new BigDecimal("0.20"),
                new BigDecimal("0.21"));
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.ADVERSE, nonePay);
        Ccar5YearSubmission sub = engine.project("RSSD-4", start, drivers);
        ScenarioProjection p = sub.results().get(Scenario.ADVERSE);
        for (QuarterProjection q : p.quarters()) {
            if (q.netIncome().isNegative()) {
                assertThat(q.dividends()).isEqualTo(usd("0"));
                assertThat(q.buybacks()).isEqualTo(usd("0"));
            }
        }
    }

    @Test
    void cumulative_ni_equals_sum_of_quarterly() {
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.BASELINE, Ccar5YearProjection.defaultBaseline());
        ScenarioProjection p = engine.project("RSSD-5", start, drivers).results()
                .get(Scenario.BASELINE);
        Money sum = Money.zero(CurrencyCode.USD);
        for (QuarterProjection q : p.quarters()) {
            sum = sum.plus(q.netIncome());
        }
        assertThat(p.totalNetIncome()).isEqualTo(sum);
    }

    @Test
    void render_summary_contains_key_fields() {
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.BASELINE, Ccar5YearProjection.defaultBaseline());
        ScenarioProjection p = engine.project("RSSD-6", start, drivers).results()
                .get(Scenario.BASELINE);
        String summary = engine.renderScenarioSummary(p);
        assertThat(summary).contains("Scenario: BASELINE");
        assertThat(summary).contains("Min CET1:");
    }

    @Test
    void fry14a_summary_contains_all_scenario_rows() {
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.BASELINE, Ccar5YearProjection.defaultBaseline());
        drivers.put(Scenario.ADVERSE, Ccar5YearProjection.defaultAdverse());
        drivers.put(Scenario.SEVERELY_ADVERSE,
                Ccar5YearProjection.defaultSeverelyAdverse());
        Ccar5YearSubmission sub = engine.project("RSSD-7", start, drivers);
        String out = engine.renderFrY14aSummary(sub);
        assertThat(out).contains("BASELINE|");
        assertThat(out).contains("ADVERSE|");
        assertThat(out).contains("SEVERELY_ADVERSE|");
    }

    @Test
    void ending_cet1_map_reflects_final_quarter() {
        Map<Scenario, ScenarioDrivers> drivers = new EnumMap<>(Scenario.class);
        drivers.put(Scenario.BASELINE, Ccar5YearProjection.defaultBaseline());
        Ccar5YearSubmission sub = engine.project("RSSD-8", start, drivers);
        var ending = engine.endingCet1Ratios(sub);
        assertThat(ending).containsKey(Scenario.BASELINE);
        QuarterProjection last = sub.results().get(Scenario.BASELINE).quarters()
                .get(Ccar5YearProjection.PROJECTION_QUARTERS - 1);
        assertThat(ending.get(Scenario.BASELINE)).isEqualTo(last.cet1Ratio());
    }

    @Test
    void snap_to_quarter_end_yields_last_day_of_quarter() {
        assertThat(Ccar5YearProjection.snapToQuarterEnd(LocalDate.of(2026, 2, 14)))
                .isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(Ccar5YearProjection.snapToQuarterEnd(LocalDate.of(2026, 8, 10)))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
