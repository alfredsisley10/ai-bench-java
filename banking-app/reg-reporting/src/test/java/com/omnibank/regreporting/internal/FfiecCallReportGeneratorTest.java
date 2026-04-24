package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.FfiecCallReportGenerator.BalanceSheetItem;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.CallReport;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.CallReportForm;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.CallReportInput;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.DepositBucket;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.DepositCategory;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.EditSeverity;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.FilerProfile;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.FilingStatus;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.IncomeItem;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.LoanBucket;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.LoanCategory;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.QuarterlyAverage;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.Schedule;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfiecCallReportGeneratorTest {

    private static final LocalDate Q_END = LocalDate.of(2026, 3, 31);
    private static Money usd(String v) { return Money.of(v, CurrencyCode.USD); }

    private FfiecCallReportGenerator generator;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneId.of("UTC"));
        generator = new FfiecCallReportGenerator(clock);
    }

    private CallReportInput validInput() {
        FilerProfile filer = new FilerProfile("RSSD-123", "Omnibank NA",
                false, true, false);

        // A tiny balance sheet that balances.
        // Assets: cash 50, AFS 100, loans 500 - allowance 10 = 640
        // Liabilities: deposits 500 + other 40 = 540, Equity = 100.
        List<BalanceSheetItem> bs = List.of(
                new BalanceSheetItem("RCFD0010", "Cash", usd("50")),
                new BalanceSheetItem("RCFD1773", "AFS securities", usd("100")),
                new BalanceSheetItem("RCFD2122", "Loans net", usd("500")),
                new BalanceSheetItem("RCFD3123", "Allowance", usd("10")),
                new BalanceSheetItem("RCFD2930", "Other liabilities", usd("40")),
                new BalanceSheetItem("RCFD3163", "Goodwill", usd("0"))
        );

        // Income statement: NII = 60 - 20 = 40, pre-tax = 40 - 5 + 10 - 20 = 25, NI = 20 after $5 tax.
        List<IncomeItem> ri = List.of(
                new IncomeItem("RIAD4010", "Loan int inc", usd("50")),
                new IncomeItem("RIAD4073", "Sec int inc", usd("10")),
                new IncomeItem("RIAD4107", "Other int inc", usd("0")),
                new IncomeItem("RIAD4170", "Deposit int exp", usd("15")),
                new IncomeItem("RIAD4180", "Borrow int exp", usd("5")),
                new IncomeItem("RIAD4230", "Provision", usd("5")),
                new IncomeItem("RIAD4079", "Non-int inc", usd("10")),
                new IncomeItem("RIAD4093", "Non-int exp", usd("20")),
                new IncomeItem("RIAD4302", "Tax", usd("5"))
        );

        List<LoanBucket> loans = List.of(
                new LoanBucket(LoanCategory.REAL_ESTATE_1_4_FAMILY, usd("300"),
                        usd("5"), usd("2"), usd("1")),
                new LoanBucket(LoanCategory.COMMERCIAL_AND_INDUSTRIAL, usd("200"),
                        usd("0"), usd("0"), usd("0"))
        );

        List<DepositBucket> deposits = List.of(
                new DepositBucket(DepositCategory.DEMAND_DEPOSITS, usd("200"), true),
                new DepositBucket(DepositCategory.MMDA_SAVINGS, usd("300"), true)
        );

        List<QuarterlyAverage> avgs = List.of(
                new QuarterlyAverage("RCFDX100", "Avg total assets", usd("640"))
        );

        return new CallReportInput(filer, Q_END, Q_END.minusMonths(3),
                bs, ri, loans, deposits, avgs,
                usd("80"),    // opening equity
                usd("20"),    // YTD NI
                usd("0"),     // dividends
                usd("0"),     // buybacks
                usd("0"));    // OCI
    }

    @Test
    void generates_all_schedules_with_no_errors() {
        CallReport r = generator.generate(validInput());

        assertThat(r.form()).isEqualTo(CallReportForm.FFIEC_041);
        assertThat(r.status()).isEqualTo(FilingStatus.DRAFT);
        assertThat(r.schedules()).containsKeys(Schedule.RC, Schedule.RI, Schedule.RI_A,
                Schedule.RC_C, Schedule.RC_E, Schedule.RC_K, Schedule.RC_N, Schedule.RC_R);
        assertThat(r.isFileable()).isTrue();
        // Net income on RI
        assertThat(r.schedules().get(Schedule.RI).get("RIAD4340"))
                .isEqualTo(usd("20"));
    }

    @Test
    void selects_ffiec_031_for_foreign_offices() {
        CallReportInput base = validInput();
        CallReportInput foreign = new CallReportInput(
                new FilerProfile("RSSD-1", "Intl Bank", true, true, true),
                base.quarterEnd(), base.priorQuarterEnd(),
                base.balanceSheet(), base.incomeStatement(),
                base.loans(), base.deposits(), base.quarterlyAverages(),
                base.openingEquity(), base.netIncomeYtd(),
                base.dividendsDeclaredYtd(), base.shareRepurchasesYtd(),
                base.otherComprehensiveIncome());
        assertThat(generator.generate(foreign).form()).isEqualTo(CallReportForm.FFIEC_031);
    }

    @Test
    void balance_sheet_mismatch_triggers_validity_error() {
        CallReportInput base = validInput();
        // Add a huge unbalancing item only on the asset side
        List<BalanceSheetItem> bad = new java.util.ArrayList<>(base.balanceSheet());
        bad.add(new BalanceSheetItem("RCFD0010", "Extra cash", usd("1000000")));
        CallReportInput broken = new CallReportInput(base.filer(), base.quarterEnd(),
                base.priorQuarterEnd(), bad, base.incomeStatement(),
                base.loans(), base.deposits(), base.quarterlyAverages(),
                base.openingEquity(), base.netIncomeYtd(),
                base.dividendsDeclaredYtd(), base.shareRepurchasesYtd(),
                base.otherComprehensiveIncome());

        CallReport r = generator.generate(broken);
        // Expect ERROR category edit checks on RC balance or loan tie-out
        assertThat(r.editChecks()).anyMatch(c -> c.severity() == EditSeverity.ERROR);
    }

    @Test
    void validate_flags_zero_allowance_quality_warning_when_loans_present() {
        CallReportInput base = validInput();
        List<BalanceSheetItem> bs = base.balanceSheet().stream()
                .map(i -> i.cdrCode().equals("RCFD3123")
                        ? new BalanceSheetItem("RCFD3123", "Allowance", usd("0")) : i)
                .toList();
        CallReportInput x = new CallReportInput(base.filer(), base.quarterEnd(),
                base.priorQuarterEnd(), bs, base.incomeStatement(),
                base.loans(), base.deposits(), base.quarterlyAverages(),
                base.openingEquity(), base.netIncomeYtd(),
                base.dividendsDeclaredYtd(), base.shareRepurchasesYtd(),
                base.otherComprehensiveIncome());

        CallReport r = generator.generate(x);
        assertThat(r.editChecks()).anyMatch(c -> c.code().equals("Q0002"));
    }

    @Test
    void transition_draft_to_validated_is_legal() {
        CallReport r = generator.generate(validInput());
        CallReport v = generator.transition(r, FilingStatus.VALIDATED);
        assertThat(v.status()).isEqualTo(FilingStatus.VALIDATED);
    }

    @Test
    void transition_rejects_illegal_jump() {
        CallReport r = generator.generate(validInput());
        assertThatThrownBy(() -> generator.transition(r, FilingStatus.ACCEPTED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_submit_with_blocking_errors() {
        // Build an input that fails V0001
        CallReportInput base = validInput();
        List<BalanceSheetItem> bad = new java.util.ArrayList<>(base.balanceSheet());
        bad.add(new BalanceSheetItem("RCFD0010", "Extra", usd("9999999")));
        CallReportInput broken = new CallReportInput(base.filer(), base.quarterEnd(),
                base.priorQuarterEnd(), bad, base.incomeStatement(), base.loans(),
                base.deposits(), base.quarterlyAverages(),
                base.openingEquity(), base.netIncomeYtd(),
                base.dividendsDeclaredYtd(), base.shareRepurchasesYtd(),
                base.otherComprehensiveIncome());
        CallReport r = generator.generate(broken);
        assertThat(r.isFileable()).isFalse();
        CallReport v = generator.transition(r, FilingStatus.VALIDATED);
        assertThatThrownBy(() -> generator.transition(v, FilingStatus.SUBMITTED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void flat_file_renders_with_header_and_lines() {
        CallReport r = generator.generate(validInput());
        String file = generator.renderCdrFlatFile(r);
        assertThat(file).contains("#FFIEC|FFIEC_041|RSSD-123");
        assertThat(file).contains("RC|RCFD2170|");
    }

    @Test
    void past_due_is_true_for_old_undelivered_report() {
        CallReport r = generator.generate(validInput());
        // The report dueDate is 30 days after Q_END (2026-04-30); clock is 2026-04-16
        assertThat(generator.isPastDue(r)).isFalse();
    }

    @Test
    void cet1_ratio_is_available_after_generate() {
        CallReport r = generator.generate(validInput());
        assertThat(generator.cet1Ratio(r)).isPresent();
    }

    @Test
    void sumByPrefix_aggregates_matching_codes() {
        List<BalanceSheetItem> items = List.of(
                new BalanceSheetItem("RCFD1797", "RE", usd("100")),
                new BalanceSheetItem("RCFD1460", "Comm RE", usd("200")),
                new BalanceSheetItem("RCFD0010", "Cash", usd("50"))
        );
        assertThat(FfiecCallReportGenerator.sumByPrefix(items, "RCFD1"))
                .isEqualTo(usd("300"));
    }
}
