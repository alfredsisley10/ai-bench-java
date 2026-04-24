package com.omnibank.regreporting.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Produces the daily Liquidity Coverage Ratio (LCR) report required under the
 * U.S. LCR rule (12 CFR 249 subpart D). The LCR measures whether a bank holds
 * enough High-Quality Liquid Assets (HQLA) to survive a 30-day stressed
 * liquidity scenario without resorting to the Discount Window.
 *
 * <p>The formula is:
 * <pre>
 *     LCR = HQLA(after haircuts) / Total Net Cash Outflows(30-day)
 * </pre>
 *
 * <p>Haircuts applied to HQLA by class:
 * <ul>
 *   <li><b>Level 1:</b> 0% — central bank reserves, Treasury and agency
 *       securities, sovereign debt of 0% RW countries.</li>
 *   <li><b>Level 2A:</b> 15% — agency MBS, 20% RW sovereign debt.</li>
 *   <li><b>Level 2B:</b> 50% — select corporate bonds, equities in major
 *       indices.</li>
 * </ul>
 * Cap constraints: Level 2 assets ≤ 40% of total HQLA; Level 2B ≤ 15%.
 *
 * <p>Outflow rates applied to liabilities under Basel III parameters:
 * <ul>
 *   <li>Stable retail deposits: 3%</li>
 *   <li>Less-stable retail: 10%</li>
 *   <li>Operational non-financial: 25%</li>
 *   <li>Non-operational non-financial: 40%</li>
 *   <li>Financial-sector deposits: 100%</li>
 *   <li>Undrawn committed credit lines (retail/SMB): 5% / 10%</li>
 *   <li>Undrawn committed lines (corporate): 30%</li>
 * </ul>
 *
 * <p>Inflow cap: 75% of outflows — i.e., even with unlimited inflows a bank
 * must hold HQLA for at least 25% of its outflows.
 */
public class LcrReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(LcrReportGenerator.class);

    /** Minimum LCR required under the full rule. */
    public static final BigDecimal MIN_LCR = new BigDecimal("1.00");

    /** Cap on Level 2 assets as share of total HQLA. */
    public static final BigDecimal LEVEL_2_CAP = new BigDecimal("0.40");

    /** Cap on Level 2B assets as share of total HQLA. */
    public static final BigDecimal LEVEL_2B_CAP = new BigDecimal("0.15");

    /** Cap on inflows as share of outflows. */
    public static final BigDecimal INFLOW_CAP = new BigDecimal("0.75");

    public enum HqlaLevel { LEVEL_1, LEVEL_2A, LEVEL_2B }

    public enum DepositType {
        STABLE_RETAIL,
        LESS_STABLE_RETAIL,
        OPERATIONAL_NON_FINANCIAL,
        NON_OPERATIONAL_NON_FINANCIAL,
        FINANCIAL_SECTOR,
        SECURED_FUNDING_LEVEL_1,
        SECURED_FUNDING_LEVEL_2A,
        SECURED_FUNDING_OTHER
    }

    public enum CommitmentType {
        RETAIL_CREDIT_LINE,
        SMB_CREDIT_LINE,
        CORPORATE_CREDIT_LINE,
        LIQUIDITY_FACILITY_NON_FINANCIAL,
        LIQUIDITY_FACILITY_FINANCIAL
    }

    public enum InflowType {
        MATURING_SECURED_LENDING_L1,
        MATURING_SECURED_LENDING_L2A,
        MATURING_SECURED_LENDING_OTHER,
        RETAIL_LOAN_PAYMENTS,
        WHOLESALE_LOAN_PAYMENTS,
        FINANCIAL_DEPOSITS_MATURING,
        OPERATIONAL_DEPOSITS_HELD
    }

    private static final Map<HqlaLevel, BigDecimal> HAIRCUT = new EnumMap<>(Map.of(
            HqlaLevel.LEVEL_1, BigDecimal.ZERO,
            HqlaLevel.LEVEL_2A, new BigDecimal("0.15"),
            HqlaLevel.LEVEL_2B, new BigDecimal("0.50")
    ));

    private static final Map<DepositType, BigDecimal> DEPOSIT_RUNOFF = new EnumMap<>(Map.of(
            DepositType.STABLE_RETAIL, new BigDecimal("0.03"),
            DepositType.LESS_STABLE_RETAIL, new BigDecimal("0.10"),
            DepositType.OPERATIONAL_NON_FINANCIAL, new BigDecimal("0.25"),
            DepositType.NON_OPERATIONAL_NON_FINANCIAL, new BigDecimal("0.40"),
            DepositType.FINANCIAL_SECTOR, new BigDecimal("1.00"),
            DepositType.SECURED_FUNDING_LEVEL_1, BigDecimal.ZERO,
            DepositType.SECURED_FUNDING_LEVEL_2A, new BigDecimal("0.15"),
            DepositType.SECURED_FUNDING_OTHER, new BigDecimal("1.00")
    ));

    private static final Map<CommitmentType, BigDecimal> COMMITMENT_RUNOFF = new EnumMap<>(Map.of(
            CommitmentType.RETAIL_CREDIT_LINE, new BigDecimal("0.05"),
            CommitmentType.SMB_CREDIT_LINE, new BigDecimal("0.10"),
            CommitmentType.CORPORATE_CREDIT_LINE, new BigDecimal("0.30"),
            CommitmentType.LIQUIDITY_FACILITY_NON_FINANCIAL, new BigDecimal("0.30"),
            CommitmentType.LIQUIDITY_FACILITY_FINANCIAL, new BigDecimal("1.00")
    ));

    private static final Map<InflowType, BigDecimal> INFLOW_RATE = new EnumMap<>(Map.of(
            InflowType.MATURING_SECURED_LENDING_L1, BigDecimal.ZERO,
            InflowType.MATURING_SECURED_LENDING_L2A, new BigDecimal("0.15"),
            InflowType.MATURING_SECURED_LENDING_OTHER, new BigDecimal("0.50"),
            InflowType.RETAIL_LOAN_PAYMENTS, new BigDecimal("0.50"),
            InflowType.WHOLESALE_LOAN_PAYMENTS, new BigDecimal("0.50"),
            InflowType.FINANCIAL_DEPOSITS_MATURING, new BigDecimal("1.00"),
            InflowType.OPERATIONAL_DEPOSITS_HELD, BigDecimal.ZERO
    ));

    public record HqlaHolding(HqlaLevel level, String description, Money marketValue) {
        public HqlaHolding {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(marketValue, "marketValue");
        }

        public Money hairCutValue() {
            BigDecimal haircut = HAIRCUT.getOrDefault(level, BigDecimal.ZERO);
            return marketValue.times(BigDecimal.ONE.subtract(haircut));
        }
    }

    public record Deposit(DepositType type, Money balance) {
        public Deposit {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(balance, "balance");
        }

        public Money runoffAmount() {
            return balance.times(DEPOSIT_RUNOFF.getOrDefault(type, BigDecimal.ONE));
        }
    }

    public record Commitment(CommitmentType type, Money undrawnAmount) {
        public Commitment {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(undrawnAmount, "undrawnAmount");
        }

        public Money runoffAmount() {
            return undrawnAmount.times(COMMITMENT_RUNOFF.getOrDefault(type, BigDecimal.ONE));
        }
    }

    public record Inflow(InflowType type, Money amount) {
        public Inflow {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
        }

        public Money effective() {
            BigDecimal rate = INFLOW_RATE.getOrDefault(type, BigDecimal.ZERO);
            return amount.times(BigDecimal.ONE.subtract(rate));
        }
    }

    public record LcrInput(
            String rssdId,
            LocalDate asOf,
            List<HqlaHolding> hqla,
            List<Deposit> deposits,
            List<Commitment> commitments,
            List<Inflow> inflows,
            Money otherOutflows
    ) {
        public LcrInput {
            Objects.requireNonNull(rssdId, "rssdId");
            Objects.requireNonNull(asOf, "asOf");
            hqla = List.copyOf(Objects.requireNonNull(hqla, "hqla"));
            deposits = List.copyOf(Objects.requireNonNull(deposits, "deposits"));
            commitments = List.copyOf(Objects.requireNonNull(commitments, "commitments"));
            inflows = List.copyOf(Objects.requireNonNull(inflows, "inflows"));
            Objects.requireNonNull(otherOutflows, "otherOutflows");
        }
    }

    public record LcrReport(
            UUID reportId,
            String rssdId,
            LocalDate asOf,
            Money level1Hqla,
            Money level2aHqla,
            Money level2bHqla,
            Money totalHqla,
            Money depositRunoff,
            Money commitmentRunoff,
            Money otherOutflows,
            Money grossInflows,
            Money cappedInflows,
            Money netCashOutflows,
            BigDecimal lcrRatio,
            boolean compliant,
            List<String> warnings
    ) {
        public LcrReport {
            Objects.requireNonNull(reportId, "reportId");
            warnings = List.copyOf(warnings);
        }
    }

    private final Clock clock;

    public LcrReportGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LcrReport compute(LcrInput input) {
        Objects.requireNonNull(input, "input");

        // Categorize HQLA
        Money l1 = sumByLevel(input.hqla(), HqlaLevel.LEVEL_1);
        Money l2a = sumByLevel(input.hqla(), HqlaLevel.LEVEL_2A);
        Money l2b = sumByLevel(input.hqla(), HqlaLevel.LEVEL_2B);

        Money totalUncapped = l1.plus(l2a).plus(l2b);
        List<String> warnings = new ArrayList<>();
        // Apply Level 2 caps
        Money totalHqla = applyLevelCaps(l1, l2a, l2b, warnings);

        // Outflows
        Money depositRunoff = input.deposits().stream()
                .map(Deposit::runoffAmount)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        Money commitmentRunoff = input.commitments().stream()
                .map(Commitment::runoffAmount)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);

        Money grossOutflows = depositRunoff.plus(commitmentRunoff).plus(input.otherOutflows());

        // Inflows (capped at 75% of outflows)
        Money grossInflows = input.inflows().stream()
                .map(Inflow::effective)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        Money inflowCap = grossOutflows.times(INFLOW_CAP);
        Money cappedInflows = grossInflows.compareTo(inflowCap) > 0
                ? inflowCap : grossInflows;

        Money net = grossOutflows.minus(cappedInflows);
        if (net.isNegative() || net.isZero()) {
            net = Money.of("1.00", CurrencyCode.USD);   // prevent div-by-zero
            warnings.add("Net cash outflows rounded to $1 floor to avoid division by zero");
        }

        BigDecimal ratio = totalHqla.amount().divide(
                net.amount(), 6, RoundingMode.HALF_EVEN);

        if (ratio.compareTo(MIN_LCR) < 0) {
            warnings.add("LCR ratio %s below 100%% minimum".formatted(ratio));
        }
        if (totalUncapped.isPositive()
                && !l2a.plus(l2b).isZero()
                && l2a.plus(l2b).amount()
                .divide(totalUncapped.amount(), 6, RoundingMode.HALF_EVEN)
                .compareTo(LEVEL_2_CAP) > 0) {
            warnings.add("Level-2 holdings exceed 40%% cap — capped for LCR");
        }

        LcrReport report = new LcrReport(
                UUID.randomUUID(), input.rssdId(), input.asOf(),
                l1, l2a, l2b, totalHqla,
                depositRunoff, commitmentRunoff, input.otherOutflows(),
                grossInflows, cappedInflows, net, ratio,
                ratio.compareTo(MIN_LCR) >= 0, warnings);
        log.info("LCR computed: rssd={}, asOf={}, ratio={}, compliant={}",
                input.rssdId(), input.asOf(), ratio, report.compliant());
        return report;
    }

    /**
     * Apply Level 2 and Level 2B caps in the order described in 12 CFR
     * 249.21(b): first constrain Level 2B ≤ 15% of total HQLA, then
     * constrain combined Level 2 ≤ 40%.
     */
    static Money applyLevelCaps(Money l1, Money l2a, Money l2b, List<String> warnings) {
        BigDecimal l1Amt = l1.amount();
        BigDecimal l2aAmt = l2a.amount();
        BigDecimal l2bAmt = l2b.amount();

        // Iteratively solve for the adjusted amount allowed under the caps.
        // We check 2B cap first since it applies directly.
        // Allowed Level 2B = min(l2b, 15/85 * (l1 + l2a))
        BigDecimal l2bCapAmount = l1Amt.add(l2aAmt)
                .multiply(new BigDecimal("15"))
                .divide(new BigDecimal("85"), 10, RoundingMode.HALF_EVEN);
        BigDecimal l2bAllowed = l2bAmt.min(l2bCapAmount);
        if (l2bAllowed.compareTo(l2bAmt) < 0) {
            warnings.add("Level 2B capped from %s to %s".formatted(l2bAmt, l2bAllowed));
        }

        // Then: Level 2 (2A + 2B_allowed) <= 40/60 * L1
        BigDecimal l2CapAmount = l1Amt.multiply(new BigDecimal("40"))
                .divide(new BigDecimal("60"), 10, RoundingMode.HALF_EVEN);
        BigDecimal level2Allowed = l2aAmt.add(l2bAllowed).min(l2CapAmount);
        if (level2Allowed.compareTo(l2aAmt.add(l2bAllowed)) < 0) {
            warnings.add("Level 2 total capped from %s to %s"
                    .formatted(l2aAmt.add(l2bAllowed), level2Allowed));
        }

        return Money.of(l1Amt.add(level2Allowed), CurrencyCode.USD);
    }

    private Money sumByLevel(List<HqlaHolding> list, HqlaLevel lvl) {
        return list.stream().filter(h -> h.level() == lvl)
                .map(HqlaHolding::hairCutValue)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
    }

    /** Render a compact one-line daily summary for ops dashboards. */
    public String oneLineSummary(LcrReport report) {
        return "LCR[%s %s] = %s/%s = %s (%s)".formatted(
                report.rssdId(), report.asOf(), report.totalHqla(),
                report.netCashOutflows(),
                report.lcrRatio().setScale(4, RoundingMode.HALF_EVEN).toPlainString(),
                report.compliant() ? "OK" : "BELOW_MIN");
    }

    /** True if the report's asOf date is older than 1 business day. */
    public boolean isStale(LcrReport report) {
        return LocalDate.now(clock).isAfter(report.asOf().plusDays(1));
    }

    /** Convenience: synthesize an empty input (used by tests). */
    public static LcrInput emptyInput(String rssdId, LocalDate date) {
        return new LcrInput(rssdId, date, List.of(), List.of(), List.of(), List.of(),
                Money.zero(CurrencyCode.USD));
    }

    /** Get the effective cap amount for a combined L2 set given L1 holdings. */
    public static Money effectiveLevel2Cap(Money level1) {
        BigDecimal cap = level1.amount().multiply(new BigDecimal("40"))
                .divide(new BigDecimal("60"), 10, RoundingMode.HALF_EVEN);
        return Money.of(cap, level1.currency());
    }

    /** Flat-file output for the Form FR 2052a daily liquidity report. */
    public String renderFr2052a(LcrReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("#FR2052A|").append(r.rssdId()).append('|').append(r.asOf()).append('\n');
        sb.append("HQLA|L1|").append(r.level1Hqla().amount().toPlainString()).append('\n');
        sb.append("HQLA|L2A|").append(r.level2aHqla().amount().toPlainString()).append('\n');
        sb.append("HQLA|L2B|").append(r.level2bHqla().amount().toPlainString()).append('\n');
        sb.append("HQLA|TOTAL|").append(r.totalHqla().amount().toPlainString()).append('\n');
        sb.append("OUTFLOW|DEPOSIT|").append(r.depositRunoff().amount().toPlainString()).append('\n');
        sb.append("OUTFLOW|COMMITMENT|").append(r.commitmentRunoff().amount().toPlainString()).append('\n');
        sb.append("OUTFLOW|OTHER|").append(r.otherOutflows().amount().toPlainString()).append('\n');
        sb.append("INFLOW|GROSS|").append(r.grossInflows().amount().toPlainString()).append('\n');
        sb.append("INFLOW|CAPPED|").append(r.cappedInflows().amount().toPlainString()).append('\n');
        sb.append("LCR|").append(r.lcrRatio().toPlainString()).append('\n');
        return sb.toString();
    }
}
