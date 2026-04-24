package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.LcrReportGenerator.Commitment;
import com.omnibank.regreporting.internal.LcrReportGenerator.CommitmentType;
import com.omnibank.regreporting.internal.LcrReportGenerator.Deposit;
import com.omnibank.regreporting.internal.LcrReportGenerator.DepositType;
import com.omnibank.regreporting.internal.LcrReportGenerator.HqlaHolding;
import com.omnibank.regreporting.internal.LcrReportGenerator.HqlaLevel;
import com.omnibank.regreporting.internal.LcrReportGenerator.Inflow;
import com.omnibank.regreporting.internal.LcrReportGenerator.InflowType;
import com.omnibank.regreporting.internal.LcrReportGenerator.LcrInput;
import com.omnibank.regreporting.internal.LcrReportGenerator.LcrReport;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LcrReportGeneratorTest {

    private static Money usd(String v) { return Money.of(v, CurrencyCode.USD); }

    private LcrReportGenerator gen;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T00:00:00Z"), ZoneId.of("UTC"));
        gen = new LcrReportGenerator(clock);
    }

    @Test
    void simple_scenario_computes_compliant_ratio() {
        LcrInput in = new LcrInput("RSSD-1", LocalDate.of(2026, 4, 16),
                List.of(new HqlaHolding(HqlaLevel.LEVEL_1, "treasuries", usd("1000"))),
                List.of(new Deposit(DepositType.STABLE_RETAIL, usd("10000"))),
                List.of(), List.of(), usd("0"));
        LcrReport r = gen.compute(in);
        // Stable retail runoff = 3% of 10000 = 300. HQLA = 1000. Ratio = 1000/300 > 1.
        assertThat(r.compliant()).isTrue();
        assertThat(r.lcrRatio()).isGreaterThan(new BigDecimal("1.0"));
    }

    @Test
    void non_compliant_when_runoff_exceeds_hqla() {
        LcrInput in = new LcrInput("RSSD-2", LocalDate.of(2026, 4, 16),
                List.of(new HqlaHolding(HqlaLevel.LEVEL_1, "cash", usd("100"))),
                List.of(new Deposit(DepositType.FINANCIAL_SECTOR, usd("1000"))),
                List.of(), List.of(), usd("0"));
        LcrReport r = gen.compute(in);
        assertThat(r.compliant()).isFalse();
        assertThat(r.warnings()).anyMatch(w -> w.contains("below"));
    }

    @Test
    void level_2_cap_applies_when_l1_is_limited() {
        // Level 1 = 100; Level 2A = 1000 (would be capped to 40/60 * 100 = ~66).
        LcrInput in = new LcrInput("RSSD-3", LocalDate.of(2026, 4, 16),
                List.of(
                        new HqlaHolding(HqlaLevel.LEVEL_1, "cash", usd("100")),
                        new HqlaHolding(HqlaLevel.LEVEL_2A, "agency", usd("1000"))),
                List.of(new Deposit(DepositType.STABLE_RETAIL, usd("5000"))),
                List.of(), List.of(), usd("0"));
        LcrReport r = gen.compute(in);
        // Total HQLA = 100 + ~66 (capped) = 166. NOT > 850 after haircuts 15%.
        // Effective L2 after haircut: 1000 * 0.85 = 850 → still capped to 66.67.
        assertThat(r.totalHqla().amount()).isLessThan(new BigDecimal("200"));
        assertThat(r.warnings()).anyMatch(w -> w.contains("Level 2") || w.contains("cap"));
    }

    @Test
    void haircut_reduces_level_2b_values() {
        LcrInput in = new LcrInput("RSSD-4", LocalDate.of(2026, 4, 16),
                List.of(
                        new HqlaHolding(HqlaLevel.LEVEL_1, "cash", usd("10000")),
                        new HqlaHolding(HqlaLevel.LEVEL_2B, "corp", usd("1000"))
                ),
                List.of(new Deposit(DepositType.STABLE_RETAIL, usd("1000"))),
                List.of(), List.of(), usd("0"));
        LcrReport r = gen.compute(in);
        // L2B haircut 50% -> 500 uncapped, 15% cap of total (~ (10000+500) * 15/85) ~ 1853 so uncapped.
        assertThat(r.level2bHqla()).isEqualTo(usd("500"));
    }

    @Test
    void inflows_capped_at_75_percent_of_outflows() {
        LcrInput in = new LcrInput("RSSD-5", LocalDate.of(2026, 4, 16),
                List.of(new HqlaHolding(HqlaLevel.LEVEL_1, "cash", usd("1000"))),
                List.of(new Deposit(DepositType.STABLE_RETAIL, usd("10000"))),   // 300 out
                List.of(),
                List.of(new Inflow(InflowType.WHOLESALE_LOAN_PAYMENTS, usd("10000"))),   // 5000 in, well above cap
                usd("0"));
        LcrReport r = gen.compute(in);
        // Outflows = 300, inflow cap = 225. Cap enforced.
        assertThat(r.cappedInflows().amount()).isEqualByComparingTo(new BigDecimal("225.00"));
    }

    @Test
    void commitment_runoff_uses_correct_ccf() {
        Commitment corp = new Commitment(CommitmentType.CORPORATE_CREDIT_LINE, usd("1000"));
        assertThat(corp.runoffAmount()).isEqualTo(usd("300"));

        Commitment retail = new Commitment(CommitmentType.RETAIL_CREDIT_LINE, usd("1000"));
        assertThat(retail.runoffAmount()).isEqualTo(usd("50"));
    }

    @Test
    void one_line_summary_includes_compliance_flag() {
        LcrInput in = new LcrInput("RSSD-6", LocalDate.of(2026, 4, 16),
                List.of(new HqlaHolding(HqlaLevel.LEVEL_1, "cash", usd("2000"))),
                List.of(new Deposit(DepositType.STABLE_RETAIL, usd("10000"))),
                List.of(), List.of(), usd("0"));
        LcrReport r = gen.compute(in);
        assertThat(gen.oneLineSummary(r)).contains("OK");
    }

    @Test
    void renders_fr2052a_with_all_sections() {
        LcrInput in = new LcrInput("RSSD-7", LocalDate.of(2026, 4, 16),
                List.of(new HqlaHolding(HqlaLevel.LEVEL_1, "cash", usd("2000"))),
                List.of(new Deposit(DepositType.STABLE_RETAIL, usd("10000"))),
                List.of(), List.of(), usd("0"));
        LcrReport r = gen.compute(in);
        String file = gen.renderFr2052a(r);
        assertThat(file).contains("HQLA|L1|");
        assertThat(file).contains("OUTFLOW|DEPOSIT|");
        assertThat(file).contains("LCR|");
    }

    @Test
    void empty_outflows_do_not_crash() {
        LcrInput in = LcrReportGenerator.emptyInput("RSSD-8", LocalDate.of(2026, 4, 16));
        LcrReport r = gen.compute(in);
        assertThat(r.lcrRatio().signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void effective_level_2_cap_is_40_over_60_of_l1() {
        Money cap = LcrReportGenerator.effectiveLevel2Cap(usd("60"));
        assertThat(cap.amount()).isEqualByComparingTo("40.00");
    }

    @Test
    void stale_report_detected() {
        LcrInput in = LcrReportGenerator.emptyInput("RSSD-9", LocalDate.of(2026, 4, 10));
        LcrReport r = gen.compute(in);
        assertThat(gen.isStale(r)).isTrue();
    }
}
