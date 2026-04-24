package com.omnibank.regreporting.internal;

import com.omnibank.regreporting.internal.FfiecCallReportGenerator.CallReport;
import com.omnibank.regreporting.internal.FfiecCallReportGenerator.Schedule;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
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
import java.util.UUID;

/**
 * Generates FR Y-9C — the Consolidated Financial Statements for Holding
 * Companies with total assets $3 billion and over. The Y-9C is the Federal
 * Reserve's BHC-level equivalent of the FFIEC Call Report and feeds CCAR,
 * DFAST, and SR letters used by examiners.
 *
 * <p>Key schedules assembled here:
 * <ul>
 *   <li><b>Schedule HC:</b> Consolidated balance sheet (BHC + subsidiaries)</li>
 *   <li><b>Schedule HI:</b> Consolidated income statement</li>
 *   <li><b>Schedule HI-A:</b> Changes in holding-company equity</li>
 *   <li><b>Schedule HC-B:</b> Securities composition (AFS/HTM, with fair value)</li>
 *   <li><b>Schedule HC-C:</b> Loans and leases at the consolidated level</li>
 *   <li><b>Schedule HC-R:</b> Regulatory capital summary (CET1, Tier 1, Total)</li>
 * </ul>
 *
 * <p>Tie-out validation: the BHC's sole operating subsidiary is the bank
 * subsidiary that files the Call Report. Schedule HC must reconcile to
 * Schedule RC within materiality net of the consolidation eliminations
 * supplied separately by treasury. Unexplained differences raise ERROR edit
 * checks that block filing.
 */
public class FrY9cReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(FrY9cReportGenerator.class);

    /** Due 40 days after quarter end for most BHCs (45 for calendar Q4). */
    private static final int FILING_DEADLINE_DAYS_STANDARD = 40;
    private static final int FILING_DEADLINE_DAYS_Q4 = 45;

    /** 1bp-of-assets materiality for tie-out. */
    private static final BigDecimal MATERIALITY_BPS = new BigDecimal("0.0001");

    public enum FilingStatus { DRAFT, VALIDATED, SUBMITTED, ACCEPTED, REJECTED, AMENDED }

    public enum Schedule9c { HC, HI, HI_A, HC_B, HC_C, HC_R }

    public enum SecurityClassification { AVAILABLE_FOR_SALE, HELD_TO_MATURITY, TRADING }

    public enum SecurityType {
        US_TREASURY,
        US_AGENCY,
        RESIDENTIAL_MBS,
        COMMERCIAL_MBS,
        MUNICIPAL,
        CORPORATE_DEBT,
        EQUITY
    }

    public record BhcProfile(
            String rssdId,
            String lei,
            String legalName,
            boolean hasSubsidiaryBank,
            boolean isCategoryIvOrHigher
    ) {
        public BhcProfile {
            Objects.requireNonNull(rssdId, "rssdId");
            Objects.requireNonNull(legalName, "legalName");
        }
    }

    public record SecurityHolding(
            SecurityType type,
            SecurityClassification classification,
            Money amortizedCost,
            Money fairValue
    ) {
        public SecurityHolding {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(amortizedCost, "amortizedCost");
            Objects.requireNonNull(fairValue, "fairValue");
        }

        public Money unrealizedGainLoss() {
            return fairValue.minus(amortizedCost);
        }
    }

    public record ConsolidationAdjustment(
            String description,
            String scheduleItemCode,
            Money amount
    ) {
        public ConsolidationAdjustment {
            Objects.requireNonNull(scheduleItemCode, "scheduleItemCode");
            Objects.requireNonNull(amount, "amount");
        }
    }

    public record FrY9cInput(
            BhcProfile profile,
            LocalDate asOf,
            CallReport bankCallReport,
            List<SecurityHolding> securities,
            List<ConsolidationAdjustment> adjustments,
            Money parentCompanyInvestments,
            Money parentOnlyExpenses,
            Money minorityInterest
    ) {
        public FrY9cInput {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(bankCallReport, "bankCallReport");
            securities = List.copyOf(Objects.requireNonNull(securities, "securities"));
            adjustments = List.copyOf(Objects.requireNonNull(adjustments, "adjustments"));
        }
    }

    public enum TieOutSeverity { OK, WARNING, ERROR }

    public record TieOutFinding(String itemCode, Schedule9c schedule, TieOutSeverity severity,
                                String message) {
        public TieOutFinding {
            Objects.requireNonNull(itemCode, "itemCode");
            Objects.requireNonNull(severity, "severity");
        }
    }

    public record FrY9cReport(
            UUID reportId,
            BhcProfile profile,
            LocalDate asOf,
            LocalDate dueDate,
            FilingStatus status,
            Map<Schedule9c, Map<String, Money>> schedules,
            List<TieOutFinding> tieOuts
    ) {
        public FrY9cReport {
            Objects.requireNonNull(reportId, "reportId");
            schedules = Map.copyOf(schedules);
            tieOuts = List.copyOf(tieOuts);
        }

        public boolean hasBlockingFindings() {
            return tieOuts.stream().anyMatch(f -> f.severity() == TieOutSeverity.ERROR);
        }
    }

    private final Clock clock;

    public FrY9cReportGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FrY9cReport generate(FrY9cInput input) {
        Objects.requireNonNull(input, "input");

        Map<Schedule9c, Map<String, Money>> schedules = new EnumMap<>(Schedule9c.class);
        schedules.put(Schedule9c.HC, assembleHc(input));
        schedules.put(Schedule9c.HI, assembleHi(input));
        schedules.put(Schedule9c.HI_A, assembleHiA(input));
        schedules.put(Schedule9c.HC_B, assembleHcB(input));
        schedules.put(Schedule9c.HC_C, assembleHcC(input));
        schedules.put(Schedule9c.HC_R, assembleHcR(input, schedules));

        List<TieOutFinding> findings = tieOut(input, schedules);

        LocalDate due = calcDueDate(input.asOf());
        FrY9cReport report = new FrY9cReport(UUID.randomUUID(), input.profile(),
                input.asOf(), due, FilingStatus.DRAFT,
                Collections.unmodifiableMap(schedules), findings);

        log.info("FR Y-9C generated: rssd={}, asOf={}, tieOutErrors={}",
                input.profile().rssdId(), input.asOf(),
                findings.stream().filter(f -> f.severity() == TieOutSeverity.ERROR).count());
        return report;
    }

    private Map<String, Money> assembleHc(FrY9cInput input) {
        // HC mirrors RC but adds BHC-only balances and parent-only assets.
        Map<String, Money> hc = new LinkedHashMap<>();
        Map<String, Money> rc = input.bankCallReport().schedules().getOrDefault(
                Schedule.RC, Map.of());

        // Import bank-sub numbers (BHCK codes align 1:1 with RCFD in most cases).
        copy(rc, hc, "RCFD0010", "BHCK0010");   // cash and balances
        copy(rc, hc, "RCFD1754", "BHCK1754");   // HTM securities
        copy(rc, hc, "RCFD1773", "BHCK1773");   // AFS securities
        copy(rc, hc, "RCFD2122", "BHCK2122");   // loans net
        copy(rc, hc, "RCFD3123", "BHCK3123");   // allowance

        Money parentInvestments = input.parentCompanyInvestments();
        hc.put("BHCKG103", parentInvestments);

        Money subTotal = hc.values().stream().reduce(Money.zero(CurrencyCode.USD), Money::plus);

        // Apply consolidation adjustments
        for (ConsolidationAdjustment adj : input.adjustments()) {
            hc.merge(adj.scheduleItemCode(), adj.amount(), Money::plus);
        }

        // Total consolidated assets
        Money totalAssets = subTotal
                .plus(input.adjustments().stream()
                        .map(ConsolidationAdjustment::amount)
                        .reduce(Money.zero(CurrencyCode.USD), Money::plus));
        hc.put("BHCK2170", totalAssets);

        // Liabilities passed through
        Money liab = rc.getOrDefault("RCFD2948", Money.zero(CurrencyCode.USD));
        hc.put("BHCK2948", liab);

        Money equity = rc.getOrDefault("RCFD3210", Money.zero(CurrencyCode.USD));
        hc.put("BHCK3210", equity);
        hc.put("BHCK3000", input.minorityInterest());
        return hc;
    }

    private Map<String, Money> assembleHi(FrY9cInput input) {
        Map<String, Money> hi = new LinkedHashMap<>();
        Map<String, Money> ri = input.bankCallReport().schedules().getOrDefault(
                Schedule.RI, Map.of());
        copy(ri, hi, "RIAD4074", "BHCK4074");
        copy(ri, hi, "RIAD4073A", "BHCK4073");
        copy(ri, hi, "RIAD4518", "BHCK4518");
        copy(ri, hi, "RIAD4230", "BHCK4230");
        copy(ri, hi, "RIAD4079", "BHCK4079");
        copy(ri, hi, "RIAD4093", "BHCK4093");
        copy(ri, hi, "RIAD4302", "BHCK4302");
        Money bankNi = ri.getOrDefault("RIAD4340", Money.zero(CurrencyCode.USD));
        Money consolidatedNi = bankNi.minus(input.parentOnlyExpenses());
        hi.put("BHCK4340", consolidatedNi);
        return hi;
    }

    private Map<String, Money> assembleHiA(FrY9cInput input) {
        Map<String, Money> hiA = new LinkedHashMap<>();
        Map<String, Money> riA = input.bankCallReport().schedules().getOrDefault(
                Schedule.RI_A, Map.of());
        copy(riA, hiA, "RIAD3217", "BHCK3217");
        copy(riA, hiA, "RIAD4340", "BHCK4340");
        copy(riA, hiA, "RIAD4460", "BHCK4460");
        copy(riA, hiA, "RIADB511", "BHCKB511");
        copy(riA, hiA, "RIADB530", "BHCKB530");
        copy(riA, hiA, "RIAD3210", "BHCK3210");
        return hiA;
    }

    private Map<String, Money> assembleHcB(FrY9cInput input) {
        // Schedule HC-B: securities, partitioned by type and classification,
        // with separate amortized cost and fair value columns.
        Map<String, Money> hcB = new LinkedHashMap<>();
        Money afsCost = Money.zero(CurrencyCode.USD);
        Money afsFair = Money.zero(CurrencyCode.USD);
        Money htmCost = Money.zero(CurrencyCode.USD);
        Money htmFair = Money.zero(CurrencyCode.USD);

        for (SecurityHolding h : input.securities()) {
            String cost = "BHCK" + securityCodeSuffix(h.type(), false);
            String fair = "BHCK" + securityCodeSuffix(h.type(), true);
            hcB.merge(cost, h.amortizedCost(), Money::plus);
            hcB.merge(fair, h.fairValue(), Money::plus);
            if (h.classification() == SecurityClassification.AVAILABLE_FOR_SALE) {
                afsCost = afsCost.plus(h.amortizedCost());
                afsFair = afsFair.plus(h.fairValue());
            } else if (h.classification() == SecurityClassification.HELD_TO_MATURITY) {
                htmCost = htmCost.plus(h.amortizedCost());
                htmFair = htmFair.plus(h.fairValue());
            }
        }
        hcB.put("BHCK1754", htmCost);
        hcB.put("BHCK1771", htmFair);
        hcB.put("BHCK1772", afsCost);
        hcB.put("BHCK1773", afsFair);
        hcB.put("BHCKG303", afsFair.minus(afsCost));   // net unrealized on AFS
        return hcB;
    }

    private Map<String, Money> assembleHcC(FrY9cInput input) {
        Map<String, Money> hcC = new LinkedHashMap<>();
        Map<String, Money> rcC = input.bankCallReport().schedules().getOrDefault(
                Schedule.RC_C, Map.of());
        // 1:1 import — Y-9C HC-C line item codes use BHCK prefix but same digits.
        for (var e : rcC.entrySet()) {
            String code = "BHCK" + e.getKey().substring(4);
            hcC.put(code, e.getValue());
        }
        return hcC;
    }

    private Map<String, Money> assembleHcR(FrY9cInput input,
                                            Map<Schedule9c, Map<String, Money>> priors) {
        Map<String, Money> hcR = new LinkedHashMap<>();
        Money equity = priors.get(Schedule9c.HC).getOrDefault(
                "BHCK3210", Money.zero(CurrencyCode.USD));
        Money totalAssets = priors.get(Schedule9c.HC).getOrDefault(
                "BHCK2170", Money.zero(CurrencyCode.USD));
        // Simplified regulatory capital: CET1 proxy from equity, Tier 1 and Total
        // derived as multiplicative adjustments (full RC-R/Y-9C HC-R Part I
        // deductions are out of scope for this generator).
        Money cet1 = equity;
        Money tier1 = cet1.plus(input.minorityInterest());
        Money totalCapital = tier1;        // treat as tier1-only institution

        hcR.put("BHCAP859", cet1);
        hcR.put("BHCA8274", totalAssets);
        hcR.put("BHCAT195", tier1);
        hcR.put("BHCAT197", totalCapital);
        return hcR;
    }

    List<TieOutFinding> tieOut(FrY9cInput input,
                                Map<Schedule9c, Map<String, Money>> schedules) {
        List<TieOutFinding> findings = new ArrayList<>();

        Map<String, Money> hc = schedules.get(Schedule9c.HC);
        Map<String, Money> hi = schedules.get(Schedule9c.HI);
        Map<String, Money> hiA = schedules.get(Schedule9c.HI_A);
        Map<String, Money> rc = input.bankCallReport().schedules().getOrDefault(
                Schedule.RC, Map.of());
        Map<String, Money> ri = input.bankCallReport().schedules().getOrDefault(
                Schedule.RI, Map.of());

        Money hcAssets = hc.getOrDefault("BHCK2170", Money.zero(CurrencyCode.USD));
        Money rcAssets = rc.getOrDefault("RCFD2170", Money.zero(CurrencyCode.USD));

        Money adjustmentTotal = input.adjustments().stream()
                .map(ConsolidationAdjustment::amount)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);
        Money expected = rcAssets.plus(input.parentCompanyInvestments()).plus(adjustmentTotal);

        if (!approxEqual(hcAssets, expected, rcAssets)) {
            findings.add(new TieOutFinding("BHCK2170", Schedule9c.HC, TieOutSeverity.ERROR,
                    "HC total assets %s does not reconcile to bank RC + parent investments + adjustments (%s)"
                            .formatted(hcAssets, expected)));
        }

        // HI net income tie-out (consolidated NI should be within 5% of bank NI
        // net of parent-only expenses).
        Money bankNi = ri.getOrDefault("RIAD4340", Money.zero(CurrencyCode.USD));
        Money hcNi = hi.getOrDefault("BHCK4340", Money.zero(CurrencyCode.USD));
        Money expectedNi = bankNi.minus(input.parentOnlyExpenses());
        if (!approxEqual(hcNi, expectedNi, rcAssets)) {
            findings.add(new TieOutFinding("BHCK4340", Schedule9c.HI, TieOutSeverity.ERROR,
                    "HI net income %s ≠ bank NI %s − parent expenses %s"
                            .formatted(hcNi, bankNi, input.parentOnlyExpenses())));
        }

        // HI-A ending equity should tie to HC
        Money hcEquity = hc.getOrDefault("BHCK3210", Money.zero(CurrencyCode.USD));
        Money hiAEquity = hiA.getOrDefault("BHCK3210", Money.zero(CurrencyCode.USD));
        if (!approxEqual(hiAEquity, hcEquity, rcAssets)) {
            findings.add(new TieOutFinding("BHCK3210", Schedule9c.HI_A, TieOutSeverity.ERROR,
                    "HI-A ending equity %s does not tie to HC %s".formatted(hiAEquity, hcEquity)));
        }

        // Materiality warning: large consolidation adjustments
        Money materialityFloor = rcAssets.times(new BigDecimal("0.01"));
        for (ConsolidationAdjustment a : input.adjustments()) {
            if (a.amount().abs().compareTo(materialityFloor) > 0) {
                findings.add(new TieOutFinding(a.scheduleItemCode(), Schedule9c.HC,
                        TieOutSeverity.WARNING,
                        "Large consolidation adjustment of %s (>1%% of bank assets): %s"
                                .formatted(a.amount(), a.description())));
            }
        }

        if (!input.bankCallReport().isFileable()) {
            findings.add(new TieOutFinding("ROOT", Schedule9c.HC, TieOutSeverity.ERROR,
                    "Underlying Call Report has unresolved ERROR edit checks"));
        }

        return findings;
    }

    public FrY9cReport transition(FrY9cReport report, FilingStatus target) {
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
                    "Illegal Y-9C transition %s → %s".formatted(current, target));
        }
        if (target == FilingStatus.SUBMITTED && report.hasBlockingFindings()) {
            throw new IllegalStateException("Cannot submit Y-9C with blocking findings");
        }
        return new FrY9cReport(report.reportId(), report.profile(), report.asOf(),
                report.dueDate(), target, report.schedules(), report.tieOuts());
    }

    public boolean isPastDue(FrY9cReport report) {
        return LocalDate.now(clock).isAfter(report.dueDate())
                && report.status() != FilingStatus.ACCEPTED
                && report.status() != FilingStatus.SUBMITTED;
    }

    private LocalDate calcDueDate(LocalDate asOf) {
        // Q4 gets an extra 5 days under Fed Reg Y.
        int month = asOf.getMonthValue();
        int days = (month == 12) ? FILING_DEADLINE_DAYS_Q4 : FILING_DEADLINE_DAYS_STANDARD;
        return asOf.plusDays(days);
    }

    private static void copy(Map<String, Money> src, Map<String, Money> dest,
                              String srcKey, String destKey) {
        Money v = src.get(srcKey);
        dest.put(destKey, v != null ? v : Money.zero(CurrencyCode.USD));
    }

    private static String securityCodeSuffix(SecurityType t, boolean fairValue) {
        int base = switch (t) {
            case US_TREASURY -> 0211;
            case US_AGENCY -> 0212;
            case RESIDENTIAL_MBS -> 0213;
            case COMMERCIAL_MBS -> 0214;
            case MUNICIPAL -> 0215;
            case CORPORATE_DEBT -> 0216;
            case EQUITY -> 0217;
        };
        return String.valueOf(base + (fairValue ? 1000 : 0));
    }

    static boolean approxEqual(Money a, Money b, Money reference) {
        if (a.currency() != b.currency()) return false;
        BigDecimal diff = a.amount().subtract(b.amount()).abs();
        BigDecimal limit = reference.amount().abs().multiply(MATERIALITY_BPS);
        BigDecimal threshold = limit.max(BigDecimal.ONE);
        return diff.compareTo(threshold) <= 0;
    }

    /** Compute the tier-1 leverage ratio from an assembled Y-9C. */
    public BigDecimal tier1LeverageRatio(FrY9cReport report) {
        Money tier1 = report.schedules().get(Schedule9c.HC_R).getOrDefault(
                "BHCAT195", Money.zero(CurrencyCode.USD));
        Money assets = report.schedules().get(Schedule9c.HC_R).getOrDefault(
                "BHCA8274", Money.zero(CurrencyCode.USD));
        if (assets.isZero()) return BigDecimal.ZERO;
        return tier1.amount().divide(assets.amount(), 6, RoundingMode.HALF_EVEN);
    }
}
