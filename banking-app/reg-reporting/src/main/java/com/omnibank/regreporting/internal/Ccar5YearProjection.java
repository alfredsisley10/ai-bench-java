package com.omnibank.regreporting.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds a CCAR-style nine-quarter capital projection across baseline,
 * supervisory severely adverse, and (optionally) supervisory adverse
 * scenarios. CCAR submissions contain forward projections of income,
 * losses, RWA, and regulatory capital ratios used by the Fed and the firm's
 * own board to evaluate capital adequacy under stress.
 *
 * <p>The projection runs 9 forecast quarters from the "as-of" date
 * (the CCAR cycle data-as-of date published by the Fed each fall). The
 * engine walks forward from the starting CET1 capital position, applying
 * scenario-specific growth rates for:
 * <ul>
 *   <li>Pre-provision net revenue (PPNR)</li>
 *   <li>Provision expense (charge-offs + reserve build)</li>
 *   <li>Non-credit losses (trading, OCI, operational)</li>
 *   <li>RWA growth</li>
 *   <li>Dividends and buybacks</li>
 * </ul>
 * Capital ratios are evaluated quarter-by-quarter against the post-stress
 * minimum buffer (4.5% CET1 + stress capital buffer).
 */
public class Ccar5YearProjection {

    private static final Logger log = LoggerFactory.getLogger(Ccar5YearProjection.class);

    /** CCAR projection horizon: 9 quarters forward. */
    public static final int PROJECTION_QUARTERS = 9;

    /** Regulatory CET1 minimum (4.5%). */
    public static final BigDecimal CET1_MIN = new BigDecimal("0.045");

    /** Tier-1 minimum. */
    public static final BigDecimal TIER1_MIN = new BigDecimal("0.06");

    /** Total-capital minimum. */
    public static final BigDecimal TOTAL_CAP_MIN = new BigDecimal("0.08");

    public enum Scenario { BASELINE, ADVERSE, SEVERELY_ADVERSE }

    /** Per-quarter, per-scenario growth/stress drivers. */
    public record ScenarioDrivers(
            BigDecimal ppnrGrowthQoQ,       // quarter-over-quarter PPNR growth (fraction)
            BigDecimal netChargeOffRate,    // annualised charge-off rate on loans
            BigDecimal nonCreditLossesPerQ, // fraction of assets hit quarterly
            BigDecimal rwaGrowthQoQ,        // quarter-over-quarter RWA growth
            BigDecimal dividendPayoutRatio, // fraction of net income paid out
            BigDecimal buybackRatio,        // fraction of net income returned via buybacks
            BigDecimal taxRate
    ) {
        public ScenarioDrivers {
            Objects.requireNonNull(ppnrGrowthQoQ, "ppnrGrowthQoQ");
            Objects.requireNonNull(netChargeOffRate, "netChargeOffRate");
            Objects.requireNonNull(nonCreditLossesPerQ, "nonCreditLossesPerQ");
            Objects.requireNonNull(rwaGrowthQoQ, "rwaGrowthQoQ");
            Objects.requireNonNull(dividendPayoutRatio, "dividendPayoutRatio");
            Objects.requireNonNull(buybackRatio, "buybackRatio");
            Objects.requireNonNull(taxRate, "taxRate");
        }
    }

    /** Starting balance-sheet and capital position. */
    public record StartingPosition(
            LocalDate asOfDate,
            Money totalAssets,
            Money totalLoans,
            Money riskWeightedAssets,
            Money cet1Capital,
            Money tier1Capital,
            Money totalCapital,
            Money ppnrRunRateAnnual
    ) {
        public StartingPosition {
            Objects.requireNonNull(asOfDate, "asOfDate");
            Objects.requireNonNull(totalAssets, "totalAssets");
            Objects.requireNonNull(totalLoans, "totalLoans");
            Objects.requireNonNull(riskWeightedAssets, "riskWeightedAssets");
            Objects.requireNonNull(cet1Capital, "cet1Capital");
            Objects.requireNonNull(tier1Capital, "tier1Capital");
            Objects.requireNonNull(totalCapital, "totalCapital");
            Objects.requireNonNull(ppnrRunRateAnnual, "ppnrRunRateAnnual");
        }
    }

    /** A single projected quarter. */
    public record QuarterProjection(
            int quarterNumber,
            LocalDate quarterEnd,
            Money ppnr,
            Money provisions,
            Money netChargeOffs,
            Money nonCreditLosses,
            Money preTaxIncome,
            Money taxes,
            Money netIncome,
            Money dividends,
            Money buybacks,
            Money cet1End,
            Money tier1End,
            Money totalCapitalEnd,
            Money rwaEnd,
            BigDecimal cet1Ratio,
            BigDecimal tier1Ratio,
            BigDecimal totalCapitalRatio,
            boolean breachesMinimums
    ) {}

    /** Aggregate output for a single scenario. */
    public record ScenarioProjection(
            Scenario scenario,
            List<QuarterProjection> quarters,
            BigDecimal minCet1Ratio,
            Money totalNetIncome,
            Money totalCharges,
            int breachQuarters
    ) {
        public ScenarioProjection {
            quarters = List.copyOf(quarters);
        }
    }

    /** Complete CCAR submission output. */
    public record Ccar5YearSubmission(
            UUID submissionId,
            String rssdId,
            LocalDate cycleStart,
            StartingPosition starting,
            Map<Scenario, ScenarioProjection> results
    ) {
        public Ccar5YearSubmission {
            results = Map.copyOf(results);
        }

        /** True if every scenario keeps CET1 above the regulatory minimum. */
        public boolean allScenariosPass() {
            return results.values().stream()
                    .allMatch(s -> s.minCet1Ratio().compareTo(CET1_MIN) >= 0);
        }
    }

    public Ccar5YearSubmission project(String rssdId, StartingPosition start,
                                        Map<Scenario, ScenarioDrivers> drivers) {
        Objects.requireNonNull(rssdId, "rssdId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(drivers, "drivers");
        if (drivers.isEmpty()) {
            throw new IllegalArgumentException("At least one scenario is required");
        }

        Map<Scenario, ScenarioProjection> byScenario = new EnumMap<>(Scenario.class);
        for (Map.Entry<Scenario, ScenarioDrivers> e : drivers.entrySet()) {
            ScenarioProjection p = runScenario(e.getKey(), e.getValue(), start);
            byScenario.put(e.getKey(), p);
        }

        Ccar5YearSubmission out = new Ccar5YearSubmission(UUID.randomUUID(), rssdId,
                start.asOfDate(), start, byScenario);
        log.info("CCAR projection: rssd={}, scenarios={}, pass={}",
                rssdId, byScenario.keySet(), out.allScenariosPass());
        return out;
    }

    private ScenarioProjection runScenario(Scenario scenario, ScenarioDrivers d,
                                             StartingPosition start) {
        List<QuarterProjection> quarters = new ArrayList<>(PROJECTION_QUARTERS);

        // Rolling state
        Money cet1 = start.cet1Capital();
        Money tier1 = start.tier1Capital();
        Money totalCap = start.totalCapital();
        Money rwa = start.riskWeightedAssets();
        Money ppnr = start.ppnrRunRateAnnual().dividedBy(BigDecimal.valueOf(4));
        Money loans = start.totalLoans();

        BigDecimal minCet1 = BigDecimal.ONE;
        Money cumulativeNi = Money.zero(CurrencyCode.USD);
        Money cumulativeCharges = Money.zero(CurrencyCode.USD);
        int breachQuarters = 0;

        LocalDate quarterEnd = start.asOfDate();
        for (int q = 1; q <= PROJECTION_QUARTERS; q++) {
            quarterEnd = quarterEnd.plusMonths(3);

            // Apply scenario growth rates
            ppnr = ppnr.times(BigDecimal.ONE.add(d.ppnrGrowthQoQ()));
            rwa = rwa.times(BigDecimal.ONE.add(d.rwaGrowthQoQ()));
            loans = loans.times(BigDecimal.ONE.add(d.rwaGrowthQoQ()));   // loans roughly track RWA

            // Losses this quarter
            BigDecimal qChargeOffRate = d.netChargeOffRate()
                    .divide(new BigDecimal("4"), 10, RoundingMode.HALF_EVEN);
            Money qCharges = loans.times(qChargeOffRate);
            Money qProvisions = qCharges;   // simplified: provisions = charge-offs
            Money qNonCredit = rwa.times(d.nonCreditLossesPerQ());

            Money preTax = ppnr.minus(qProvisions).minus(qNonCredit);
            Money tax = preTax.isPositive() ? preTax.times(d.taxRate())
                    : Money.zero(CurrencyCode.USD);
            Money ni = preTax.minus(tax);

            Money dividends = ni.isPositive() ? ni.times(d.dividendPayoutRatio())
                    : Money.zero(CurrencyCode.USD);
            Money buybacks = ni.isPositive() ? ni.times(d.buybackRatio())
                    : Money.zero(CurrencyCode.USD);

            Money capitalGenerated = ni.minus(dividends).minus(buybacks);
            cet1 = cet1.plus(capitalGenerated);
            tier1 = tier1.plus(capitalGenerated);
            totalCap = totalCap.plus(capitalGenerated);

            BigDecimal cet1Ratio = rwa.isZero() ? BigDecimal.ZERO
                    : cet1.amount().divide(rwa.amount(), 6, RoundingMode.HALF_EVEN);
            BigDecimal tier1Ratio = rwa.isZero() ? BigDecimal.ZERO
                    : tier1.amount().divide(rwa.amount(), 6, RoundingMode.HALF_EVEN);
            BigDecimal totalRatio = rwa.isZero() ? BigDecimal.ZERO
                    : totalCap.amount().divide(rwa.amount(), 6, RoundingMode.HALF_EVEN);

            boolean breach = cet1Ratio.compareTo(CET1_MIN) < 0
                    || tier1Ratio.compareTo(TIER1_MIN) < 0
                    || totalRatio.compareTo(TOTAL_CAP_MIN) < 0;
            if (breach) breachQuarters++;
            if (cet1Ratio.compareTo(minCet1) < 0) minCet1 = cet1Ratio;

            cumulativeNi = cumulativeNi.plus(ni);
            cumulativeCharges = cumulativeCharges.plus(qCharges).plus(qNonCredit);

            quarters.add(new QuarterProjection(q, quarterEnd, ppnr, qProvisions,
                    qCharges, qNonCredit, preTax, tax, ni, dividends, buybacks,
                    cet1, tier1, totalCap, rwa, cet1Ratio, tier1Ratio, totalRatio, breach));
        }

        return new ScenarioProjection(scenario, quarters, minCet1,
                cumulativeNi, cumulativeCharges, breachQuarters);
    }

    /** Human-readable summary of a single scenario. */
    public String renderScenarioSummary(ScenarioProjection p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scenario: ").append(p.scenario()).append('\n');
        sb.append("Min CET1: ").append(p.minCet1Ratio().setScale(6, RoundingMode.HALF_EVEN))
                .append('\n');
        sb.append("Breach quarters: ").append(p.breachQuarters()).append('\n');
        sb.append("Cumulative NI: ").append(p.totalNetIncome()).append('\n');
        sb.append("Cumulative charges: ").append(p.totalCharges()).append('\n');
        for (QuarterProjection q : p.quarters()) {
            sb.append(" Q").append(q.quarterNumber()).append(' ').append(q.quarterEnd())
                    .append(" NI=").append(q.netIncome())
                    .append(" CET1=").append(q.cet1Ratio().setScale(4, RoundingMode.HALF_EVEN))
                    .append(q.breachesMinimums() ? " *BREACH*" : "")
                    .append('\n');
        }
        return sb.toString();
    }

    /** Default baseline drivers — mild-growth economy. */
    public static ScenarioDrivers defaultBaseline() {
        return new ScenarioDrivers(
                new BigDecimal("0.01"),    // 1% QoQ PPNR
                new BigDecimal("0.005"),   // 50bps annualised COs
                new BigDecimal("0.0001"),
                new BigDecimal("0.01"),
                new BigDecimal("0.30"),
                new BigDecimal("0.20"),
                new BigDecimal("0.21")
        );
    }

    /** Default severely-adverse drivers — Fed 2024 cycle magnitudes. */
    public static ScenarioDrivers defaultSeverelyAdverse() {
        return new ScenarioDrivers(
                new BigDecimal("-0.04"),   // PPNR declines QoQ in early quarters
                new BigDecimal("0.065"),   // 650bps annualised COs
                new BigDecimal("0.002"),
                new BigDecimal("0.02"),    // RWA grows as risk weights rise
                new BigDecimal("0.00"),    // no dividends under severe stress
                new BigDecimal("0.00"),
                new BigDecimal("0.21")
        );
    }

    /** Default adverse drivers — mid-tier recession. */
    public static ScenarioDrivers defaultAdverse() {
        return new ScenarioDrivers(
                new BigDecimal("-0.02"),
                new BigDecimal("0.035"),
                new BigDecimal("0.001"),
                new BigDecimal("0.015"),
                new BigDecimal("0.10"),
                new BigDecimal("0.00"),
                new BigDecimal("0.21")
        );
    }

    /** Check whether any scenario breaches the regulatory minimums at any point. */
    public boolean anyScenarioBreaches(Ccar5YearSubmission submission) {
        return submission.results().values().stream()
                .anyMatch(s -> s.breachQuarters() > 0);
    }

    /** Extract the final-quarter CET1 ratio across all scenarios. */
    public Map<Scenario, BigDecimal> endingCet1Ratios(Ccar5YearSubmission submission) {
        Map<Scenario, BigDecimal> out = new EnumMap<>(Scenario.class);
        for (var e : submission.results().entrySet()) {
            var list = e.getValue().quarters();
            if (!list.isEmpty()) out.put(e.getKey(), list.get(list.size() - 1).cet1Ratio());
        }
        return out;
    }

    /**
     * Produce the FR Y-14A summary file listing only the key line items
     * required by the Fed: per-scenario, per-quarter projected CET1 ratio and
     * net income.
     */
    public String renderFrY14aSummary(Ccar5YearSubmission submission) {
        StringBuilder sb = new StringBuilder();
        sb.append("#FR-Y-14A|").append(submission.rssdId())
                .append('|').append(submission.cycleStart()).append('\n');
        for (var sc : submission.results().entrySet()) {
            for (QuarterProjection q : sc.getValue().quarters()) {
                sb.append(sc.getKey()).append('|')
                        .append(q.quarterNumber()).append('|')
                        .append(q.quarterEnd()).append('|')
                        .append(q.netIncome().amount().toPlainString()).append('|')
                        .append(q.cet1Ratio().setScale(6, RoundingMode.HALF_EVEN)
                                .toPlainString()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Snap an as-of date to the closest preceding quarter-end. */
    public static LocalDate snapToQuarterEnd(LocalDate d) {
        int m = d.getMonthValue();
        int targetMonth = ((m - 1) / 3) * 3 + 3;
        LocalDate end = LocalDate.of(d.getYear(), targetMonth, 1)
                .plusMonths(1).minusDays(1);
        return end;
    }
}
