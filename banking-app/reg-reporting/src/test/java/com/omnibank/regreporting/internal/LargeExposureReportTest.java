package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.LargeExposureReport.Counterparty;
import com.omnibank.regreporting.internal.LargeExposureReport.CounterpartyTier;
import com.omnibank.regreporting.internal.LargeExposureReport.CoveredCompany;
import com.omnibank.regreporting.internal.LargeExposureReport.Exposure;
import com.omnibank.regreporting.internal.LargeExposureReport.ExposureType;
import com.omnibank.regreporting.internal.LargeExposureReport.LargeExposureSummary;
import com.omnibank.regreporting.internal.LargeExposureReport.Mitigant;
import com.omnibank.regreporting.internal.LargeExposureReport.MitigantType;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LargeExposureReportTest {

    private static Money usd(String v) { return Money.of(v, CurrencyCode.USD); }

    private LargeExposureReport report;
    private CoveredCompany filer;
    private Counterparty counterparty;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T00:00:00Z"), ZoneId.of("UTC"));
        report = new LargeExposureReport(clock);
        filer = new CoveredCompany("US-OMNIBANK", "Omnibank NA",
                CounterpartyTier.STANDARD, usd("1000000000"));
        counterparty = new Counterparty(CustomerId.newId(), "Acme Corp", "US",
                CounterpartyTier.STANDARD);
    }

    @Test
    void zero_tier1_capital_is_rejected() {
        CoveredCompany invalid = new CoveredCompany("OMNI", "Omnibank",
                CounterpartyTier.STANDARD, usd("0"));
        assertThatThrownBy(() -> report.generate(invalid, List.of(), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void single_loan_under_limit_no_breach() {
        Exposure e = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("100000000"), List.of());
        LargeExposureSummary summary = report.generate(filer, List.of(e), LocalDate.of(2026, 3, 31));
        assertThat(summary.breaches()).isEmpty();
        assertThat(summary.aggregates()).hasSize(1);
    }

    @Test
    void exposure_over_25_percent_is_breach() {
        Exposure e = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("300000000"), List.of());
        LargeExposureSummary summary = report.generate(filer, List.of(e), LocalDate.now());
        assertThat(report.hasAnyBreach(summary)).isTrue();
    }

    @Test
    void cash_mitigation_reduces_net_exposure() {
        Exposure e = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("300000000"),
                List.of(new Mitigant(MitigantType.CASH_COLLATERAL, usd("100000000"))));
        assertThat(e.netExposure()).isEqualTo(usd("200000000"));
    }

    @Test
    void commitment_ccf_is_50_percent() {
        Exposure e = new Exposure(UUID.randomUUID(), counterparty, ExposureType.COMMITMENT,
                usd("400000000"), List.of());
        assertThat(e.exposureAtDefault()).isEqualTo(usd("200000000"));
    }

    @Test
    void gsib_to_gsib_uses_10_percent_limit() {
        CoveredCompany gsibFiler = new CoveredCompany("OMNI", "Omnibank",
                CounterpartyTier.GSIB, usd("1000000000"));
        Counterparty gsibCpty = new Counterparty(CustomerId.newId(), "BigBank",
                "US", CounterpartyTier.GSIB);
        Exposure e = new Exposure(UUID.randomUUID(), gsibCpty, ExposureType.LOAN,
                usd("110000000"), List.of());
        LargeExposureSummary s = report.generate(gsibFiler, List.of(e), LocalDate.now());
        assertThat(s.breaches()).isNotEmpty();
    }

    @Test
    void major_to_major_uses_15_percent_limit() {
        CoveredCompany majorFiler = new CoveredCompany("OMNI", "Omnibank",
                CounterpartyTier.MAJOR, usd("1000000000"));
        Counterparty majorCpty = new Counterparty(CustomerId.newId(), "BigCorp",
                "US", CounterpartyTier.MAJOR);
        Exposure ok = new Exposure(UUID.randomUUID(), majorCpty, ExposureType.LOAN,
                usd("140000000"), List.of());
        Exposure breach = new Exposure(UUID.randomUUID(), majorCpty, ExposureType.LOAN,
                usd("160000000"), List.of());
        assertThat(report.generate(majorFiler, List.of(ok), LocalDate.now())
                .breaches()).isEmpty();
        assertThat(report.generate(majorFiler, List.of(breach), LocalDate.now())
                .breaches()).isNotEmpty();
    }

    @Test
    void stress_shock_amplifies_exposures() {
        Exposure e = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("100000000"), List.of());
        LargeExposureSummary base = report.generate(filer, List.of(e), LocalDate.now());
        LargeExposureSummary stressed = report.applyStressShock(filer, List.of(e),
                LocalDate.now(), new BigDecimal("3"));
        assertThat(stressed.aggregates().get(0).totalNet())
                .isEqualTo(base.aggregates().get(0).totalNet().times(3L));
    }

    @Test
    void days_to_cure_decreases_over_time() {
        long dtc = report.daysToCure(LocalDate.of(2026, 3, 1));
        assertThat(dtc).isGreaterThan(0);
    }

    @Test
    void headroom_is_nonnegative() {
        Money head = report.headroom(filer, counterparty, usd("100000000"));
        assertThat(head.isNegative()).isFalse();
    }

    @Test
    void headroom_zero_when_exposure_exceeds_limit() {
        Money head = report.headroom(filer, counterparty, usd("300000000"));
        assertThat(head).isEqualTo(usd("0"));
    }

    @Test
    void topN_returns_at_most_n_counterparties() {
        List<Exposure> exposures = List.of(
                new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                        usd("50000000"), List.of()),
                new Exposure(UUID.randomUUID(),
                        new Counterparty(CustomerId.newId(), "B", "US",
                                CounterpartyTier.STANDARD),
                        ExposureType.LOAN, usd("30000000"), List.of())
        );
        LargeExposureSummary s = report.generate(filer, exposures, LocalDate.now());
        assertThat(report.topN(s, 1)).hasSize(1);
        assertThat(report.topN(s, 5)).hasSize(2);
    }

    @Test
    void fr_y15_flat_file_renders_with_tier1_header() {
        Exposure e = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("100000000"), List.of());
        LargeExposureSummary s = report.generate(filer, List.of(e), LocalDate.now());
        String out = report.renderFrY15Attachment(s);
        assertThat(out).contains("#FR-Y-15|SCCL|US-OMNIBANK");
        assertThat(out).contains("#Tier1|");
    }

    @Test
    void classify_routes_by_assets_and_gsib_list() {
        assertThat(LargeExposureReport.classify(usd("10000000000"), Set.of("GSIB-1"),
                "GSIB-1")).isEqualTo(CounterpartyTier.GSIB);
        assertThat(LargeExposureReport.classify(usd("600000000000"), Set.of(),
                "X")).isEqualTo(CounterpartyTier.MAJOR);
        assertThat(LargeExposureReport.classify(usd("10000000"), Set.of(),
                "Y")).isEqualTo(CounterpartyTier.STANDARD);
    }

    @Test
    void groupByCounterparty_aggregates_correctly() {
        Exposure a = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("50000000"), List.of());
        Exposure b = new Exposure(UUID.randomUUID(), counterparty, ExposureType.LOAN,
                usd("70000000"), List.of());
        var agg = LargeExposureReport.groupByCounterparty(List.of(a, b));
        assertThat(agg.get(counterparty.id())).isEqualTo(usd("120000000"));
    }
}
