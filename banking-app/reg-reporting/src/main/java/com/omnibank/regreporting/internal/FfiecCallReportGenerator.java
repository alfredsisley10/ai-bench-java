package com.omnibank.regreporting.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles the FFIEC 031/041 Call Report, the quarterly Consolidated Report
 * of Condition and Income that every U.S. commercial bank must file with its
 * primary federal regulator (FDIC, OCC, or FRB) within 30 days of quarter-end.
 *
 * <p>The report comprises approximately 80 schedules; this generator focuses
 * on the highest-signal schedules that drive downstream regulatory metrics and
 * are always tied-out to the GL by the internal audit group:
 * <ul>
 *   <li><b>Schedule RC (Balance Sheet):</b> Assets, liabilities, equity</li>
 *   <li><b>Schedule RI (Income Statement):</b> Interest/non-interest income
 *       and expense, gains/losses, net income</li>
 *   <li><b>Schedule RI-A (Changes in Equity Capital):</b> Walk from prior
 *       period through dividends, buybacks, comprehensive income</li>
 *   <li><b>Schedule RC-C (Loans and Lease Financing):</b> Loan composition
 *       by collateral and borrower type</li>
 *   <li><b>Schedule RC-E (Deposit Liabilities):</b> Deposit mix by product
 *       (transaction, savings, time) and insurance status</li>
 *   <li><b>Schedule RC-K (Quarterly Averages):</b> Daily averages of assets,
 *       earning assets, liabilities used for regulatory ratios</li>
 * </ul>
 *
 * <p>FFIEC 031 applies to banks with foreign offices; FFIEC 041 applies to
 * domestic-only institutions. The generator auto-selects the form based on
 * the {@link FilerProfile#hasForeignOffices()} flag.
 *
 * <p>All line items are identified by their RSSD Central Data Repository
 * (CDR) item codes (e.g., {@code RCFD2170} for total assets). These codes are
 * canonical across the federal reporting stack and must not be reassigned.
 */
public class FfiecCallReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(FfiecCallReportGenerator.class);

    /** Required filing calendar — due 30 days after each quarter-end. */
    private static final int FILING_DEADLINE_DAYS = 30;

    /** Materiality threshold for edit-check warnings (1bp of total assets). */
    private static final BigDecimal MATERIALITY_BPS = new BigDecimal("0.0001");

    /** Amounts are reported in thousands of USD on the wire. */
    private static final BigDecimal THOUSANDS = new BigDecimal("1000");

    public enum CallReportForm { FFIEC_031, FFIEC_041 }

    public enum FilingStatus { DRAFT, VALIDATED, SUBMITTED, AMENDED, ACCEPTED, REJECTED }

    /**
     * Schedule identifiers. Each schedule groups a set of line items with
     * dedicated tie-out and cross-schedule validation rules.
     */
    public enum Schedule {
        RC,           // Balance sheet
        RI,           // Income statement
        RI_A,         // Changes in equity capital
        RC_C,         // Loans and leases
        RC_E,         // Deposit liabilities
        RC_K,         // Quarterly averages
        RC_R,         // Regulatory capital (summary)
        RC_N          // Past-due and non-accrual loans
    }

    public enum LoanCategory {
        REAL_ESTATE_1_4_FAMILY,
        REAL_ESTATE_COMMERCIAL,
        REAL_ESTATE_MULTIFAMILY,
        REAL_ESTATE_CONSTRUCTION,
        COMMERCIAL_AND_INDUSTRIAL,
        CREDIT_CARD,
        CONSUMER_OTHER,
        AGRICULTURAL,
        LEASES
    }

    public enum DepositCategory {
        DEMAND_DEPOSITS,
        NOW_ACCOUNTS,
        MMDA_SAVINGS,
        OTHER_SAVINGS,
        TIME_UNDER_100K,
        TIME_100K_TO_250K,
        TIME_OVER_250K,
        BROKERED_DEPOSITS
    }

    /** Identifying metadata about the filer. */
    public record FilerProfile(
            String rssdId,
            String legalName,
            boolean hasForeignOffices,
            boolean isLargeBank,
            boolean isAdvancedApproaches
    ) {
        public FilerProfile {
            Objects.requireNonNull(rssdId, "rssdId");
            Objects.requireNonNull(legalName, "legalName");
        }
    }

    /** A balance sheet line item tied to a GL aggregate. */
    public record BalanceSheetItem(String cdrCode, String label, Money amount) {
        public BalanceSheetItem {
            Objects.requireNonNull(cdrCode, "cdrCode");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** An income statement line item. */
    public record IncomeItem(String cdrCode, String label, Money amount) {
        public IncomeItem {
            Objects.requireNonNull(cdrCode, "cdrCode");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Loan composition bucket. */
    public record LoanBucket(LoanCategory category, Money principal, Money pastDue30_89,
                             Money pastDue90Plus, Money nonAccrual) {
        public LoanBucket {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(principal, "principal");
        }
    }

    /** Deposit composition bucket. */
    public record DepositBucket(DepositCategory category, Money balance, boolean fdicInsured) {
        public DepositBucket {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(balance, "balance");
        }
    }

    /** Daily-averaged balance for Schedule RC-K. */
    public record QuarterlyAverage(String cdrCode, String label, Money dailyAverage) {
        public QuarterlyAverage {
            Objects.requireNonNull(cdrCode, "cdrCode");
            Objects.requireNonNull(dailyAverage, "dailyAverage");
        }
    }

    /** Inputs required to render a Call Report. */
    public record CallReportInput(
            FilerProfile filer,
            LocalDate quarterEnd,
            LocalDate priorQuarterEnd,
            List<BalanceSheetItem> balanceSheet,
            List<IncomeItem> incomeStatement,
            List<LoanBucket> loans,
            List<DepositBucket> deposits,
            List<QuarterlyAverage> quarterlyAverages,
            Money openingEquity,
            Money netIncomeYtd,
            Money dividendsDeclaredYtd,
            Money shareRepurchasesYtd,
            Money otherComprehensiveIncome
    ) {
        public CallReportInput {
            Objects.requireNonNull(filer, "filer");
            Objects.requireNonNull(quarterEnd, "quarterEnd");
            balanceSheet = List.copyOf(Objects.requireNonNull(balanceSheet, "balanceSheet"));
            incomeStatement = List.copyOf(Objects.requireNonNull(incomeStatement, "incomeStatement"));
            loans = List.copyOf(Objects.requireNonNull(loans, "loans"));
            deposits = List.copyOf(Objects.requireNonNull(deposits, "deposits"));
            quarterlyAverages = List.copyOf(Objects.requireNonNull(quarterlyAverages, "quarterlyAverages"));
        }
    }

    /** Severity for validator findings. Mirrors FFIEC edit-check categories. */
    public enum EditSeverity { ERROR, QUALITY, REMINDER }

    /**
     * A single edit-check finding raised by the validator. Errors block
     * filing; QUALITY warnings require narrative explanation; REMINDERs are
     * informational.
     */
    public record EditCheck(String code, Schedule schedule, String itemCode,
                            EditSeverity severity, String message) {
        public EditCheck {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(message, "message");
        }
    }

    /** The rendered report plus validator findings. */
    public record CallReport(
            UUID reportId,
            CallReportForm form,
            FilerProfile filer,
            LocalDate quarterEnd,
            LocalDate dueDate,
            FilingStatus status,
            Map<Schedule, Map<String, Money>> schedules,
            List<EditCheck> editChecks
    ) {
        public CallReport {
            Objects.requireNonNull(reportId, "reportId");
            schedules = Map.copyOf(schedules);
            editChecks = List.copyOf(editChecks);
        }

        public boolean isFileable() {
            return editChecks.stream().noneMatch(e -> e.severity() == EditSeverity.ERROR);
        }
    }

    private final Clock clock;

    public FfiecCallReportGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Build the Call Report for the supplied inputs. Runs every schedule
     * assembler, the materiality sanity check, and the full validator before
     * returning.
     */
    public CallReport generate(CallReportInput input) {
        Objects.requireNonNull(input, "input");

        CallReportForm form = input.filer().hasForeignOffices()
                ? CallReportForm.FFIEC_031 : CallReportForm.FFIEC_041;

        Map<Schedule, Map<String, Money>> schedules = new EnumMap<>(Schedule.class);
        schedules.put(Schedule.RC, assembleScheduleRc(input));
        schedules.put(Schedule.RI, assembleScheduleRi(input));
        schedules.put(Schedule.RI_A, assembleScheduleRiA(input));
        schedules.put(Schedule.RC_C, assembleScheduleRcC(input));
        schedules.put(Schedule.RC_E, assembleScheduleRcE(input));
        schedules.put(Schedule.RC_K, assembleScheduleRcK(input));
        schedules.put(Schedule.RC_N, assembleScheduleRcN(input));
        schedules.put(Schedule.RC_R, assembleScheduleRcR(input, schedules));

        List<EditCheck> checks = validate(input, schedules);

        LocalDate due = input.quarterEnd().plusDays(FILING_DEADLINE_DAYS);
        CallReport report = new CallReport(UUID.randomUUID(), form, input.filer(),
                input.quarterEnd(), due, FilingStatus.DRAFT,
                Collections.unmodifiableMap(schedules), checks);

        log.info("Call Report generated: form={}, rssd={}, quarter={}, checks={} (errors={})",
                form, input.filer().rssdId(), input.quarterEnd(), checks.size(),
                checks.stream().filter(c -> c.severity() == EditSeverity.ERROR).count());

        return report;
    }

    // ── Schedule assemblers ─────────────────────────────────────────────

    private Map<String, Money> assembleScheduleRc(CallReportInput input) {
        // Schedule RC — Balance Sheet. Item mapping is canonical per the
        // FFIEC Micro Data Reference Manual (MDRM).
        Map<String, Money> rc = new LinkedHashMap<>();

        Money cash = findBalance(input.balanceSheet(), "RCFD0010", Money.zero(CurrencyCode.USD));
        Money securitiesHeldToMaturity = findBalance(input.balanceSheet(), "RCFD1754", Money.zero(CurrencyCode.USD));
        Money securitiesAvailableForSale = findBalance(input.balanceSheet(), "RCFD1773", Money.zero(CurrencyCode.USD));
        Money fedFundsSold = findBalance(input.balanceSheet(), "RCFDB987", Money.zero(CurrencyCode.USD));
        Money loansNet = findBalance(input.balanceSheet(), "RCFD2122", Money.zero(CurrencyCode.USD));
        Money allowanceLoanLoss = findBalance(input.balanceSheet(), "RCFD3123", Money.zero(CurrencyCode.USD));
        Money premisesFixedAssets = findBalance(input.balanceSheet(), "RCFD2145", Money.zero(CurrencyCode.USD));
        Money goodwill = findBalance(input.balanceSheet(), "RCFD3163", Money.zero(CurrencyCode.USD));
        Money otherAssets = findBalance(input.balanceSheet(), "RCFD2160", Money.zero(CurrencyCode.USD));

        Money totalAssets = Money.zero(CurrencyCode.USD)
                .plus(cash).plus(securitiesHeldToMaturity).plus(securitiesAvailableForSale)
                .plus(fedFundsSold).plus(loansNet).minus(allowanceLoanLoss)
                .plus(premisesFixedAssets).plus(goodwill).plus(otherAssets);

        Money totalDeposits = input.deposits().stream()
                .map(DepositBucket::balance)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);

        Money fedFundsPurchased = findBalance(input.balanceSheet(), "RCFDB993", Money.zero(CurrencyCode.USD));
        Money subordinatedDebt = findBalance(input.balanceSheet(), "RCFD3200", Money.zero(CurrencyCode.USD));
        Money otherLiabilities = findBalance(input.balanceSheet(), "RCFD2930", Money.zero(CurrencyCode.USD));

        Money totalLiabilities = totalDeposits.plus(fedFundsPurchased)
                .plus(subordinatedDebt).plus(otherLiabilities);

        // Equity comes from the RI-A walk (opening + NI − dividends − buybacks + OCI).
        // Deriving it as assets − liabilities would make V0001 tie-out impossible
        // to fail; sourcing it from the equity roll-forward lets the validator
        // catch real balance-sheet mismatches.
        Money totalEquity = input.openingEquity()
                .plus(input.netIncomeYtd())
                .minus(input.dividendsDeclaredYtd())
                .minus(input.shareRepurchasesYtd())
                .plus(input.otherComprehensiveIncome());

        rc.put("RCFD0010", cash);
        rc.put("RCFD1754", securitiesHeldToMaturity);
        rc.put("RCFD1773", securitiesAvailableForSale);
        rc.put("RCFDB987", fedFundsSold);
        rc.put("RCFD2122", loansNet);
        rc.put("RCFD3123", allowanceLoanLoss);
        rc.put("RCFD2145", premisesFixedAssets);
        rc.put("RCFD3163", goodwill);
        rc.put("RCFD2160", otherAssets);
        rc.put("RCFD2170", totalAssets);            // total assets
        rc.put("RCON2200", totalDeposits);           // total deposits (domestic)
        rc.put("RCFDB993", fedFundsPurchased);
        rc.put("RCFD3200", subordinatedDebt);
        rc.put("RCFD2930", otherLiabilities);
        rc.put("RCFD2948", totalLiabilities);
        rc.put("RCFD3210", totalEquity);             // total equity capital
        return rc;
    }

    private Map<String, Money> assembleScheduleRi(CallReportInput input) {
        Map<String, Money> ri = new LinkedHashMap<>();

        Money interestIncomeLoans = findIncome(input.incomeStatement(), "RIAD4010", Money.zero(CurrencyCode.USD));
        Money interestIncomeSecurities = findIncome(input.incomeStatement(), "RIAD4073", Money.zero(CurrencyCode.USD));
        Money otherInterestIncome = findIncome(input.incomeStatement(), "RIAD4107", Money.zero(CurrencyCode.USD));
        Money totalInterestIncome = interestIncomeLoans.plus(interestIncomeSecurities).plus(otherInterestIncome);

        Money interestExpenseDeposits = findIncome(input.incomeStatement(), "RIAD4170", Money.zero(CurrencyCode.USD));
        Money interestExpenseBorrowings = findIncome(input.incomeStatement(), "RIAD4180", Money.zero(CurrencyCode.USD));
        Money totalInterestExpense = interestExpenseDeposits.plus(interestExpenseBorrowings);

        Money netInterestIncome = totalInterestIncome.minus(totalInterestExpense);
        Money provisionForLoanLoss = findIncome(input.incomeStatement(), "RIAD4230", Money.zero(CurrencyCode.USD));

        Money nonInterestIncome = findIncome(input.incomeStatement(), "RIAD4079", Money.zero(CurrencyCode.USD));
        Money nonInterestExpense = findIncome(input.incomeStatement(), "RIAD4093", Money.zero(CurrencyCode.USD));

        Money preTaxIncome = netInterestIncome.minus(provisionForLoanLoss)
                .plus(nonInterestIncome).minus(nonInterestExpense);
        Money incomeTax = findIncome(input.incomeStatement(), "RIAD4302", Money.zero(CurrencyCode.USD));
        Money netIncome = preTaxIncome.minus(incomeTax);

        ri.put("RIAD4010", interestIncomeLoans);
        ri.put("RIAD4073", interestIncomeSecurities);
        ri.put("RIAD4107", otherInterestIncome);
        ri.put("RIAD4074", totalInterestIncome);
        ri.put("RIAD4170", interestExpenseDeposits);
        ri.put("RIAD4180", interestExpenseBorrowings);
        ri.put("RIAD4073A", totalInterestExpense);
        ri.put("RIAD4518", netInterestIncome);
        ri.put("RIAD4230", provisionForLoanLoss);
        ri.put("RIAD4079", nonInterestIncome);
        ri.put("RIAD4093", nonInterestExpense);
        ri.put("RIAD4301", preTaxIncome);
        ri.put("RIAD4302", incomeTax);
        ri.put("RIAD4340", netIncome);
        return ri;
    }

    private Map<String, Money> assembleScheduleRiA(CallReportInput input) {
        Map<String, Money> riA = new LinkedHashMap<>();
        // RI-A: walks beginning equity -> ending equity
        Money opening = input.openingEquity();
        Money closing = opening
                .plus(input.netIncomeYtd())
                .minus(input.dividendsDeclaredYtd())
                .minus(input.shareRepurchasesYtd())
                .plus(input.otherComprehensiveIncome());

        riA.put("RIAD3217", opening);                        // equity balance end of prior period
        riA.put("RIAD4340", input.netIncomeYtd());           // net income (YTD)
        riA.put("RIAD4460", input.dividendsDeclaredYtd());   // dividends declared on common stock
        riA.put("RIADB511", input.shareRepurchasesYtd());    // treasury stock / buybacks
        riA.put("RIADB530", input.otherComprehensiveIncome());
        riA.put("RIAD3210", closing);                        // equity balance end of current period
        return riA;
    }

    private Map<String, Money> assembleScheduleRcC(CallReportInput input) {
        Map<String, Money> rcC = new LinkedHashMap<>();

        for (LoanCategory c : LoanCategory.values()) {
            Money bucketTotal = input.loans().stream()
                    .filter(b -> b.category() == c)
                    .map(LoanBucket::principal)
                    .reduce(Money.zero(CurrencyCode.USD), Money::plus);
            rcC.put(itemCodeForLoanCategory(c), bucketTotal);
        }

        Money totalLoans = input.loans().stream()
                .map(LoanBucket::principal)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        rcC.put("RCFD2122", totalLoans);
        return rcC;
    }

    private Map<String, Money> assembleScheduleRcE(CallReportInput input) {
        Map<String, Money> rcE = new LinkedHashMap<>();
        for (DepositCategory c : DepositCategory.values()) {
            Money bucketTotal = input.deposits().stream()
                    .filter(b -> b.category() == c)
                    .map(DepositBucket::balance)
                    .reduce(Money.zero(CurrencyCode.USD), Money::plus);
            rcE.put(itemCodeForDepositCategory(c), bucketTotal);
        }

        Money fdicInsured = input.deposits().stream()
                .filter(DepositBucket::fdicInsured)
                .map(DepositBucket::balance)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        rcE.put("RCONF049", fdicInsured);

        Money total = input.deposits().stream()
                .map(DepositBucket::balance)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        rcE.put("RCON2200", total);
        return rcE;
    }

    private Map<String, Money> assembleScheduleRcK(CallReportInput input) {
        Map<String, Money> rcK = new LinkedHashMap<>();
        for (QuarterlyAverage qa : input.quarterlyAverages()) {
            rcK.put(qa.cdrCode(), qa.dailyAverage());
        }
        return rcK;
    }

    private Map<String, Money> assembleScheduleRcN(CallReportInput input) {
        Map<String, Money> rcN = new LinkedHashMap<>();
        Money pastDue30_89 = input.loans().stream()
                .map(LoanBucket::pastDue30_89)
                .filter(Objects::nonNull)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        Money pastDue90 = input.loans().stream()
                .map(LoanBucket::pastDue90Plus)
                .filter(Objects::nonNull)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        Money nonAccrual = input.loans().stream()
                .map(LoanBucket::nonAccrual)
                .filter(Objects::nonNull)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        rcN.put("RCFD1406", pastDue30_89);
        rcN.put("RCFD1407", pastDue90);
        rcN.put("RCFD1403", nonAccrual);
        return rcN;
    }

    private Map<String, Money> assembleScheduleRcR(CallReportInput input,
                                                   Map<Schedule, Map<String, Money>> priorSchedules) {
        Map<String, Money> rcR = new LinkedHashMap<>();
        Money totalAssets = priorSchedules.get(Schedule.RC).getOrDefault("RCFD2170",
                Money.zero(CurrencyCode.USD));
        Money equity = priorSchedules.get(Schedule.RC).getOrDefault("RCFD3210",
                Money.zero(CurrencyCode.USD));

        // Simplified CET1 proxy — in production, deductions (goodwill,
        // intangibles, DTA) are filed on RC-R Part I and derived from RC.
        Money goodwill = priorSchedules.get(Schedule.RC).getOrDefault("RCFD3163",
                Money.zero(CurrencyCode.USD));
        Money cet1 = equity.minus(goodwill);
        rcR.put("RCFAP859", cet1);
        rcR.put("RCFA8274", totalAssets);
        return rcR;
    }

    // ── Validator ───────────────────────────────────────────────────────

    /**
     * Runs cross-schedule edit checks. FFIEC publishes several hundred edits;
     * we implement the top-priority "validity" and "quality" rules that
     * overwhelmingly account for refilings.
     */
    List<EditCheck> validate(CallReportInput input,
                             Map<Schedule, Map<String, Money>> schedules) {
        List<EditCheck> checks = new ArrayList<>();
        Map<String, Money> rc = schedules.get(Schedule.RC);
        Map<String, Money> rcC = schedules.get(Schedule.RC_C);
        Map<String, Money> rcE = schedules.get(Schedule.RC_E);
        Map<String, Money> ri = schedules.get(Schedule.RI);
        Map<String, Money> riA = schedules.get(Schedule.RI_A);

        Money totalAssets = rc.getOrDefault("RCFD2170", Money.zero(CurrencyCode.USD));

        // Validity 0001 — Balance Sheet tie-out: Assets = Liabilities + Equity
        Money liabilities = rc.getOrDefault("RCFD2948", Money.zero(CurrencyCode.USD));
        Money equity = rc.getOrDefault("RCFD3210", Money.zero(CurrencyCode.USD));
        if (!approxEqual(totalAssets, liabilities.plus(equity), totalAssets)) {
            checks.add(new EditCheck("V0001", Schedule.RC, "RCFD2170",
                    EditSeverity.ERROR,
                    "Schedule RC does not balance: assets %s vs liab+equity %s"
                            .formatted(totalAssets, liabilities.plus(equity))));
        }

        // Validity 0020 — Loans sum to RC-C total
        Money loansFromRc = rc.getOrDefault("RCFD2122", Money.zero(CurrencyCode.USD));
        Money loansFromRcC = rcC.getOrDefault("RCFD2122", Money.zero(CurrencyCode.USD));
        if (!approxEqual(loansFromRc, loansFromRcC, totalAssets)) {
            checks.add(new EditCheck("V0020", Schedule.RC_C, "RCFD2122",
                    EditSeverity.ERROR,
                    "Net loans on RC (%s) do not tie to RC-C total (%s)"
                            .formatted(loansFromRc, loansFromRcC)));
        }

        // Validity 0030 — Deposits RC vs RC-E
        Money depositsRc = rc.getOrDefault("RCON2200", Money.zero(CurrencyCode.USD));
        Money depositsRcE = rcE.getOrDefault("RCON2200", Money.zero(CurrencyCode.USD));
        if (!approxEqual(depositsRc, depositsRcE, totalAssets)) {
            checks.add(new EditCheck("V0030", Schedule.RC_E, "RCON2200",
                    EditSeverity.ERROR,
                    "Deposits on RC (%s) do not match RC-E (%s)".formatted(depositsRc, depositsRcE)));
        }

        // Validity 0040 — Income statement arithmetic (NII)
        Money nii = ri.getOrDefault("RIAD4518", Money.zero(CurrencyCode.USD));
        Money intInc = ri.getOrDefault("RIAD4074", Money.zero(CurrencyCode.USD));
        Money intExp = ri.getOrDefault("RIAD4073A", Money.zero(CurrencyCode.USD));
        if (!approxEqual(nii, intInc.minus(intExp), totalAssets)) {
            checks.add(new EditCheck("V0040", Schedule.RI, "RIAD4518",
                    EditSeverity.ERROR,
                    "Net interest income %s ≠ interest income %s − expense %s"
                            .formatted(nii, intInc, intExp)));
        }

        // Validity 0050 — RI-A ending equity ties to RC equity
        Money riaEnding = riA.getOrDefault("RIAD3210", Money.zero(CurrencyCode.USD));
        if (!approxEqual(riaEnding, equity, totalAssets)) {
            checks.add(new EditCheck("V0050", Schedule.RI_A, "RIAD3210",
                    EditSeverity.ERROR,
                    "RI-A ending equity %s does not tie to RC equity %s"
                            .formatted(riaEnding, equity)));
        }

        // Validity 0060 — Net income on RI equals net income YTD movement
        Money niFromRi = ri.getOrDefault("RIAD4340", Money.zero(CurrencyCode.USD));
        if (niFromRi.amount().signum() != 0 && input.netIncomeYtd().amount().signum() == 0) {
            checks.add(new EditCheck("V0060", Schedule.RI, "RIAD4340",
                    EditSeverity.ERROR,
                    "RI reports net income %s but RI-A input shows zero YTD"
                            .formatted(niFromRi)));
        }

        // Quality Q0001 — Flag negative equity
        if (equity.isNegative()) {
            checks.add(new EditCheck("Q0001", Schedule.RC, "RCFD3210",
                    EditSeverity.ERROR,
                    "Negative equity capital reported: " + equity));
        }

        // Quality Q0002 — Flag zero allowance with non-zero loans
        Money allowance = rc.getOrDefault("RCFD3123", Money.zero(CurrencyCode.USD));
        if (allowance.isZero() && loansFromRc.isPositive()) {
            checks.add(new EditCheck("Q0002", Schedule.RC, "RCFD3123",
                    EditSeverity.QUALITY,
                    "Zero allowance for loan losses with positive loan portfolio"));
        }

        // Quality Q0003 — Very high provision ratio (> 5% of loans) requires narrative
        Money provision = ri.getOrDefault("RIAD4230", Money.zero(CurrencyCode.USD));
        if (loansFromRc.isPositive() && provision.isPositive()) {
            BigDecimal ratio = provision.amount().divide(
                    loansFromRc.amount(), 6, RoundingMode.HALF_EVEN);
            if (ratio.compareTo(new BigDecimal("0.05")) > 0) {
                checks.add(new EditCheck("Q0003", Schedule.RI, "RIAD4230",
                        EditSeverity.QUALITY,
                        "Provision for loan loss ratio %.2f%% exceeds 5% threshold"
                                .formatted(ratio.multiply(new BigDecimal("100")).doubleValue())));
            }
        }

        // Reminder R0001 — Any deposit category over 25% of total prompts concentration memo
        Money depositTotal = depositsRcE;
        if (depositTotal.isPositive()) {
            for (DepositCategory dc : DepositCategory.values()) {
                Money bucket = rcE.getOrDefault(itemCodeForDepositCategory(dc),
                        Money.zero(CurrencyCode.USD));
                if (bucket.isPositive()) {
                    BigDecimal pct = bucket.amount().divide(
                            depositTotal.amount(), 4, RoundingMode.HALF_EVEN);
                    if (pct.compareTo(new BigDecimal("0.25")) > 0) {
                        checks.add(new EditCheck("R0001-" + dc.name(), Schedule.RC_E,
                                itemCodeForDepositCategory(dc),
                                EditSeverity.REMINDER,
                                "%s is %.1f%% of total deposits — concentration narrative recommended"
                                        .formatted(dc, pct.multiply(new BigDecimal("100")).doubleValue())));
                    }
                }
            }
        }

        return checks;
    }

    /**
     * Transition a previously-generated report to a new filing status. Enforces
     * the documented state machine: DRAFT → VALIDATED → SUBMITTED → ACCEPTED
     * (or REJECTED). ACCEPTED reports can be AMENDED after the fact.
     */
    public CallReport transition(CallReport report, FilingStatus target) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(target, "target");

        FilingStatus current = report.status();
        boolean legal = switch (current) {
            case DRAFT -> target == FilingStatus.VALIDATED;
            case VALIDATED -> target == FilingStatus.SUBMITTED || target == FilingStatus.DRAFT;
            case SUBMITTED -> target == FilingStatus.ACCEPTED || target == FilingStatus.REJECTED;
            case ACCEPTED -> target == FilingStatus.AMENDED;
            case AMENDED -> target == FilingStatus.VALIDATED;
            case REJECTED -> target == FilingStatus.DRAFT;
        };
        if (!legal) {
            throw new IllegalStateException(
                    "Illegal filing transition %s → %s".formatted(current, target));
        }
        if (target == FilingStatus.SUBMITTED && !report.isFileable()) {
            throw new IllegalStateException(
                    "Cannot submit report with unresolved ERROR edit checks");
        }
        return new CallReport(report.reportId(), report.form(), report.filer(),
                report.quarterEnd(), report.dueDate(), target, report.schedules(),
                report.editChecks());
    }

    /**
     * Render the report to the fixed-width line format expected by the FFIEC
     * Central Data Repository (CDR). Each physical line is
     * {@code <schedule>|<itemCode>|<signed amount in thousands>}.
     */
    public String renderCdrFlatFile(CallReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("#FFIEC|").append(report.form().name())
                .append('|').append(report.filer().rssdId())
                .append('|').append(report.quarterEnd()).append('\n');
        for (var scheduleEntry : report.schedules().entrySet()) {
            for (var item : scheduleEntry.getValue().entrySet()) {
                BigDecimal thousands = item.getValue().amount()
                        .divide(THOUSANDS, 0, RoundingMode.HALF_EVEN);
                sb.append(scheduleEntry.getKey().name())
                        .append('|').append(item.getKey())
                        .append('|').append(thousands.toPlainString())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Find a balance-sheet line item by CDR code, defaulting if missing. */
    private static Money findBalance(List<BalanceSheetItem> items, String cdrCode, Money fallback) {
        List<Money> matches = items.stream().filter(i -> i.cdrCode().equals(cdrCode))
                .map(BalanceSheetItem::amount).toList();
        if (matches.isEmpty()) return fallback;
        return matches.stream().reduce(Money.zero(CurrencyCode.USD), Money::plus);
    }

    /** Find an income-statement line item by CDR code, defaulting if missing. */
    private static Money findIncome(List<IncomeItem> items, String cdrCode, Money fallback) {
        return items.stream().filter(i -> i.cdrCode().equals(cdrCode))
                .map(IncomeItem::amount).findFirst().orElse(fallback);
    }

    /**
     * Approximate equality scaled to materiality — two values are considered
     * equal if they differ by less than 1bp of the reference (total assets).
     */
    static boolean approxEqual(Money a, Money b, Money reference) {
        if (a.currency() != b.currency()) return false;
        BigDecimal diff = a.amount().subtract(b.amount()).abs();
        BigDecimal limit = reference.amount().abs().multiply(MATERIALITY_BPS);
        // Always allow $1 of rounding slack.
        BigDecimal threshold = limit.max(BigDecimal.ONE);
        return diff.compareTo(threshold) <= 0;
    }

    private static String itemCodeForLoanCategory(LoanCategory c) {
        return switch (c) {
            case REAL_ESTATE_1_4_FAMILY -> "RCFD1797";
            case REAL_ESTATE_COMMERCIAL -> "RCFD1460";
            case REAL_ESTATE_MULTIFAMILY -> "RCFD1288";
            case REAL_ESTATE_CONSTRUCTION -> "RCFD1415";
            case COMMERCIAL_AND_INDUSTRIAL -> "RCFD1766";
            case CREDIT_CARD -> "RCFDB538";
            case CONSUMER_OTHER -> "RCFDK137";
            case AGRICULTURAL -> "RCFD1590";
            case LEASES -> "RCFD2165";
        };
    }

    private static String itemCodeForDepositCategory(DepositCategory c) {
        return switch (c) {
            case DEMAND_DEPOSITS -> "RCON2210";
            case NOW_ACCOUNTS -> "RCONB550";
            case MMDA_SAVINGS -> "RCON6810";
            case OTHER_SAVINGS -> "RCON0352";
            case TIME_UNDER_100K -> "RCON6648";
            case TIME_100K_TO_250K -> "RCONJ473";
            case TIME_OVER_250K -> "RCONJ474";
            case BROKERED_DEPOSITS -> "RCON2365";
        };
    }

    /**
     * Sum a list of balance sheet items whose codes start with a given
     * prefix. Useful for building subtotals that are not stored on the input
     * as their own line item (e.g., all "RCFD1" loan codes).
     */
    public static Money sumByPrefix(List<BalanceSheetItem> items, String prefix) {
        return items.stream()
                .filter(i -> i.cdrCode().startsWith(prefix))
                .map(BalanceSheetItem::amount)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
    }

    /** Utility for external schedulers: true if this report is overdue today. */
    public boolean isPastDue(CallReport report) {
        return LocalDate.now(clock).isAfter(report.dueDate())
                && report.status() != FilingStatus.ACCEPTED
                && report.status() != FilingStatus.SUBMITTED;
    }

    /** Convenience accessor — returns the report's CET1 ratio estimate. */
    public Optional<Percent> cet1Ratio(CallReport report) {
        Money totalAssets = report.schedules().get(Schedule.RC)
                .getOrDefault("RCFD2170", Money.zero(CurrencyCode.USD));
        Money cet1 = report.schedules().get(Schedule.RC_R)
                .getOrDefault("RCFAP859", Money.zero(CurrencyCode.USD));
        if (totalAssets.isZero()) return Optional.empty();
        BigDecimal fraction = cet1.amount().divide(totalAssets.amount(), 6, RoundingMode.HALF_EVEN);
        return Optional.of(Percent.ofRate(fraction));
    }
}
