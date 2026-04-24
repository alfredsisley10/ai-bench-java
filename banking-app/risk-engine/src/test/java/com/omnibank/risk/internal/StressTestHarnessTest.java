package com.omnibank.risk.internal;

import com.omnibank.risk.internal.StressTestHarness.AssetClass;
import com.omnibank.risk.internal.StressTestHarness.ScenarioFamily;
import com.omnibank.risk.internal.StressTestHarness.ScenarioResult;
import com.omnibank.risk.internal.StressTestHarness.StressPortfolio;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StressTestHarnessTest {

    private static final YearMonth START = YearMonth.of(2026, 4);

    private RecordingEventBus events;
    private StressTestHarness harness;

    @BeforeEach
    void setUp() {
        events = new RecordingEventBus();
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        harness = new StressTestHarness(clock, events);
    }

    @Test
    void builds_three_supervisory_scenarios() {
        var scenarios = harness.buildSupervisoryScenarios(START);
        assertThat(scenarios).hasSize(3);
        assertThat(scenarios).extracting(StressTestHarness.MacroPath::family)
                .containsExactly(ScenarioFamily.BASELINE, ScenarioFamily.ADVERSE,
                        ScenarioFamily.SEVERELY_ADVERSE);
        assertThat(scenarios).allSatisfy(s ->
                assertThat(s.quarters()).hasSize(StressTestHarness.PROJECTION_QUARTERS));
    }

    @Test
    void severely_adverse_produces_larger_peak_loss_than_baseline() {
        StressPortfolio portfolio = samplePortfolio();

        var results = harness.runSupervisorySuite(portfolio, START);
        var peakByFamily = harness.peakLossByFamily(results);

        assertThat(peakByFamily.get(ScenarioFamily.SEVERELY_ADVERSE)
                .compareTo(peakByFamily.get(ScenarioFamily.BASELINE)))
                .isGreaterThan(0);
        assertThat(peakByFamily.get(ScenarioFamily.ADVERSE)
                .compareTo(peakByFamily.get(ScenarioFamily.BASELINE)))
                .isGreaterThan(0);
    }

    @Test
    void each_scenario_produces_nine_quarterly_projections() {
        StressPortfolio portfolio = samplePortfolio();
        var results = harness.runSupervisorySuite(portfolio, START);
        for (ScenarioResult r : results) {
            assertThat(r.projections()).hasSize(StressTestHarness.PROJECTION_QUARTERS);
            assertThat(r.peakQuarterlyLoss().isPositive()
                    || r.peakQuarterlyLoss().isZero()).isTrue();
        }
    }

    @Test
    void severe_scenario_may_breach_floor_and_publish_event() {
        // A thinly-capitalised portfolio under severely adverse should breach.
        Map<AssetClass, BigDecimal> mix = new EnumMap<>(AssetClass.class);
        mix.put(AssetClass.CREDIT_CARD, new BigDecimal("0.50"));
        mix.put(AssetClass.COMMERCIAL_REAL_ESTATE, new BigDecimal("0.40"));
        mix.put(AssetClass.TRADING_BOOK, new BigDecimal("0.10"));

        StressPortfolio thin = new StressPortfolio("THIN-BANK",
                CurrencyCode.USD,
                Money.of("5000000000", CurrencyCode.USD),   // 5bn exposure
                Money.of("500000000", CurrencyCode.USD),
                Money.of("300000000", CurrencyCode.USD),    // very thin Tier 1
                new BigDecimal("4500000000"),                // 4.5bn RWA → ratio 6.7%
                mix);

        var results = harness.runSupervisorySuite(thin, START);

        boolean anyBreach = results.stream().anyMatch(harness::breachesFloor);
        assertThat(anyBreach).isTrue();
        assertThat(events.events)
                .anyMatch(e -> e instanceof StressTestHarness.StressCapitalShortfallEvent);
    }

    @Test
    void reverse_stress_converges_on_target_cet1_ratio() {
        StressPortfolio portfolio = samplePortfolio();

        var result = harness.reverseStress(portfolio, START, new BigDecimal("7.50"));
        assertThat(result.severityMultiplier()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.result().family()).isEqualTo(ScenarioFamily.REVERSE);
        // Should end near the target — allow a reasonable tolerance.
        BigDecimal diff = result.result().minCet1RatioPct()
                .subtract(new BigDecimal("7.50")).abs();
        assertThat(diff).isLessThanOrEqualTo(new BigDecimal("3.00"));
    }

    @Test
    void baseline_scenario_does_not_breach_floor_for_healthy_bank() {
        StressPortfolio healthy = samplePortfolio();
        var scenario = harness.buildSupervisoryScenarios(START).get(0);
        ScenarioResult r = harness.runScenario(healthy, scenario);
        assertThat(r.family()).isEqualTo(ScenarioFamily.BASELINE);
        assertThat(harness.breachesFloor(r)).isFalse();
    }

    @Test
    void summary_contains_scenario_id_and_min_cet1() {
        StressPortfolio portfolio = samplePortfolio();
        var scenario = harness.buildSupervisoryScenarios(START).get(2);
        ScenarioResult r = harness.runScenario(portfolio, scenario);
        String summary = StressTestHarness.summary(r);
        assertThat(summary).contains(r.scenarioId());
        assertThat(summary).contains("peakLoss");
        assertThat(summary).contains("minCET1");
    }

    private static StressPortfolio samplePortfolio() {
        Map<AssetClass, BigDecimal> mix = new EnumMap<>(AssetClass.class);
        mix.put(AssetClass.RESIDENTIAL_MORTGAGE, new BigDecimal("0.35"));
        mix.put(AssetClass.COMMERCIAL_AND_INDUSTRIAL, new BigDecimal("0.25"));
        mix.put(AssetClass.CREDIT_CARD, new BigDecimal("0.15"));
        mix.put(AssetClass.COMMERCIAL_REAL_ESTATE, new BigDecimal("0.10"));
        mix.put(AssetClass.AUTO, new BigDecimal("0.05"));
        mix.put(AssetClass.SOVEREIGN, new BigDecimal("0.05"));
        mix.put(AssetClass.TRADING_BOOK, new BigDecimal("0.05"));

        return new StressPortfolio("OMNI-BANK",
                CurrencyCode.USD,
                Money.of("50000000000", CurrencyCode.USD),
                Money.of("5000000000", CurrencyCode.USD),
                Money.of("6000000000", CurrencyCode.USD),
                new BigDecimal("40000000000"),  // 15% CET1 starting
                mix);
    }
}
