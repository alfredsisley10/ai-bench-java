package com.omnibank.risk.internal;

import com.omnibank.risk.internal.ValueAtRiskCalculator.Confidence;
import com.omnibank.risk.internal.ValueAtRiskCalculator.Methodology;
import com.omnibank.risk.internal.ValueAtRiskCalculator.Portfolio;
import com.omnibank.risk.internal.ValueAtRiskCalculator.Position;
import com.omnibank.risk.internal.ValueAtRiskCalculator.ReturnSeries;
import com.omnibank.risk.internal.ValueAtRiskCalculator.VarResult;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueAtRiskCalculatorTest {

    private RecordingEventBus events;
    private ValueAtRiskCalculator calc;

    @BeforeEach
    void setUp() {
        events = new RecordingEventBus();
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        calc = new ValueAtRiskCalculator(clock, events);
    }

    @Test
    void empty_portfolio_returns_zero_var() {
        Portfolio p = ValueAtRiskCalculator.emptyPortfolio("EMPTY", CurrencyCode.USD);
        VarResult r = calc.historicalVar(p, Map.of(), Confidence.C95, 1);
        assertThat(r.varAmount().isZero()).isTrue();
        assertThat(r.expectedShortfall().isZero()).isTrue();
    }

    @Test
    void historical_var_is_non_zero_for_risky_portfolio() {
        Portfolio portfolio = new Portfolio("PF-1",
                List.of(new Position("P1", "INST-1",
                        BigDecimal.ONE, Money.of("1000000", CurrencyCode.USD))),
                CurrencyCode.USD);

        // Symmetric returns centred at zero with a fat downside: -5% .. +5% in 1% steps.
        List<BigDecimal> returns = DoubleStream.of(
                        -0.05, -0.04, -0.03, -0.025, -0.02, -0.015, -0.01,
                        -0.005, 0.0, 0.005, 0.01, 0.015, 0.02, 0.025, 0.03, 0.04)
                .mapToObj(BigDecimal::valueOf)
                .toList();

        Map<String, ReturnSeries> series = Map.of(
                "INST-1", new ReturnSeries("INST-1", returns));

        VarResult r = calc.historicalVar(portfolio, series, Confidence.C95, 1);
        assertThat(r.varAmount().isPositive()).isTrue();
        // ES ≥ VaR by definition
        assertThat(r.expectedShortfall().compareTo(r.varAmount())).isGreaterThanOrEqualTo(0);
    }

    @Test
    void parametric_99_var_is_greater_than_parametric_95_var() {
        Portfolio portfolio = new Portfolio("PF-1",
                List.of(new Position("P1", "INST-1",
                        BigDecimal.ONE, Money.of("1000000", CurrencyCode.USD))),
                CurrencyCode.USD);

        // 60 pseudo-random-ish returns
        List<BigDecimal> returns = IntStream.range(0, 60)
                .mapToDouble(i -> Math.sin(i * 0.7) * 0.02)
                .mapToObj(BigDecimal::valueOf).toList();

        Map<String, ReturnSeries> series = Map.of(
                "INST-1", new ReturnSeries("INST-1", returns));

        VarResult v95 = calc.parametricVar(portfolio, series, Confidence.C95, 1);
        VarResult v99 = calc.parametricVar(portfolio, series, Confidence.C99, 1);
        assertThat(v99.varAmount().compareTo(v95.varAmount())).isGreaterThan(0);
    }

    @Test
    void horizon_scaling_follows_sqrt_time() {
        Portfolio portfolio = new Portfolio("PF-1",
                List.of(new Position("P1", "INST-1",
                        BigDecimal.ONE, Money.of("100000", CurrencyCode.USD))),
                CurrencyCode.USD);
        List<BigDecimal> returns = IntStream.range(0, 50)
                .mapToDouble(i -> Math.sin(i) * 0.01)
                .mapToObj(BigDecimal::valueOf).toList();
        Map<String, ReturnSeries> series = Map.of("INST-1", new ReturnSeries("INST-1", returns));

        VarResult oneDay = calc.parametricVar(portfolio, series, Confidence.C95, 1);
        VarResult tenDay = calc.parametricVar(portfolio, series, Confidence.C95, 10);
        // sqrt(10)  3.16 — expect ~3x scaling
        double ratio = tenDay.varAmount().amount().doubleValue()
                / Math.max(1e-9, oneDay.varAmount().amount().doubleValue());
        assertThat(ratio).isBetween(2.9, 3.4);
    }

    @Test
    void stressed_var_labels_methodology_correctly() {
        Portfolio portfolio = new Portfolio("PF-1",
                List.of(new Position("P1", "INST-1",
                        BigDecimal.ONE, Money.of("100000", CurrencyCode.USD))),
                CurrencyCode.USD);
        List<BigDecimal> stress = IntStream.range(0, 40)
                .mapToDouble(i -> -0.05 + 0.002 * i)
                .mapToObj(BigDecimal::valueOf).toList();
        Map<String, ReturnSeries> series = Map.of("INST-1", new ReturnSeries("INST-1", stress));

        VarResult r = calc.stressedVar(portfolio, series, Confidence.C99, 1);
        assertThat(r.methodology()).isEqualTo(Methodology.STRESSED);
    }

    @Test
    void full_suite_produces_both_confidence_levels() {
        Portfolio portfolio = new Portfolio("PF-1",
                List.of(new Position("P1", "INST-1",
                        BigDecimal.ONE, Money.of("100000", CurrencyCode.USD))),
                CurrencyCode.USD);
        List<BigDecimal> returns = IntStream.range(0, 40)
                .mapToDouble(i -> Math.cos(i) * 0.01)
                .mapToObj(BigDecimal::valueOf).toList();
        Map<String, ReturnSeries> series = Map.of("INST-1", new ReturnSeries("INST-1", returns));

        List<VarResult> suite = calc.fullSuite(portfolio, series, Methodology.HISTORICAL, 1);
        assertThat(suite).hasSize(2);
        assertThat(suite).extracting(VarResult::confidence)
                .containsExactlyInAnyOrder(Confidence.C95, Confidence.C99);
    }

    @Test
    void limit_breach_publishes_event() {
        Portfolio portfolio = new Portfolio("PF-1",
                List.of(new Position("P1", "INST-1",
                        BigDecimal.ONE, Money.of("100000", CurrencyCode.USD))),
                CurrencyCode.USD);
        List<BigDecimal> returns = DoubleStream.of(
                        -0.05, -0.04, -0.03, -0.02, -0.01, 0.0, 0.01, 0.02, 0.03, 0.04)
                .mapToObj(BigDecimal::valueOf).toList();
        Map<String, ReturnSeries> series = Map.of("INST-1", new ReturnSeries("INST-1", returns));

        VarResult r = calc.historicalVar(portfolio, series, Confidence.C99, 1);
        calc.checkLimit(r, Money.of("1.00", CurrencyCode.USD));

        assertThat(events.events)
                .anyMatch(e -> e instanceof ValueAtRiskCalculator.VarLimitBreachEvent);
    }

    @Test
    void percentile_of_single_value_is_that_value() {
        assertThat(ValueAtRiskCalculator.percentile(new double[]{42.0}, 0.95)).isEqualTo(42.0);
    }

    @Test
    void tail_mean_is_at_least_cutoff() {
        double[] losses = {-10, -5, 0, 5, 10, 15, 20};
        double cutoff = ValueAtRiskCalculator.percentile(losses, 0.80);
        double tail = ValueAtRiskCalculator.tailMean(losses, 0.80);
        assertThat(tail).isGreaterThanOrEqualTo(cutoff);
    }

    @Test
    void horizon_validation_rejects_out_of_range_values() {
        Portfolio p = ValueAtRiskCalculator.emptyPortfolio("EMPTY", CurrencyCode.USD);
        assertThatThrownBy(() -> calc.historicalVar(p, Map.of(), Confidence.C95, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.historicalVar(p, Map.of(), Confidence.C95, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stdev_of_identical_values_is_zero() {
        assertThat(ValueAtRiskCalculator.stdev(new double[]{3, 3, 3, 3}, 3.0)).isEqualTo(0.0);
    }
}
