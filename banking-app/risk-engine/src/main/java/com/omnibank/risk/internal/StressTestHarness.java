package com.omnibank.risk.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stress-testing harness aligned with the Federal Reserve's CCAR / DFAST
 * supervisory framework.
 *
 * <p>Each run projects losses, PPNR, and regulatory-capital ratios across a
 * nine-quarter planning horizon under three supervisory macro scenarios plus
 * any bank-defined BAU scenarios the harness is configured with.
 *
 * <ul>
 *   <li><b>Baseline</b> — consensus macro forecast: modest GDP growth, ~4%
 *       unemployment, stable asset prices.</li>
 *   <li><b>Adverse</b> — mild US recession, rising unemployment (~6%),
 *       moderate equity correction.</li>
 *   <li><b>Severely Adverse</b> — global recession, unemployment at ~10%,
 *       40%+ equity drawdown, CRE and residential property price collapse.</li>
 * </ul>
 *
 * <p>The harness also supports <i>reverse stress testing</i>: given a target
 * capital-ratio breach level (e.g. CET1 hitting 4.5%), it searches for the
 * macro shock magnitudes that would drive the portfolio to that outcome.
 */
public class StressTestHarness {

    private static final Logger log = LoggerFactory.getLogger(StressTestHarness.class);

    /** The nine quarters projected by the CCAR cycle. */
    public static final int PROJECTION_QUARTERS = 9;

    /** Supervisory scenario family. */
    public enum ScenarioFamily { BASELINE, ADVERSE, SEVERELY_ADVERSE, INTERNAL_BAU, REVERSE }

    /** Macroeconomic variables driving the loss/PPNR model. */
    public record MacroPath(
            String scenarioId,
            ScenarioFamily family,
            List<QuarterlyMacro> quarters
    ) {
        public MacroPath {
            Objects.requireNonNull(scenarioId, "scenarioId");
            Objects.requireNonNull(family, "family");
            quarters = quarters == null ? List.of() : List.copyOf(quarters);
        }
    }

    public record QuarterlyMacro(
            YearMonth quarter,
            BigDecimal realGdpGrowthAnnualizedPct,
            BigDecimal unemploymentRatePct,
            BigDecimal equityIndexLevel,
            BigDecimal houseIndexLevel,
            BigDecimal bbbCorpSpreadBps,
            BigDecimal shortRatePct
    ) {
        public QuarterlyMacro {
            Objects.requireNonNull(quarter, "quarter");
            Objects.requireNonNull(realGdpGrowthAnnualizedPct, "realGdpGrowthAnnualizedPct");
            Objects.requireNonNull(unemploymentRatePct, "unemploymentRatePct");
        }
    }

    /** Portfolio under stress — aggregated by segment for the exercise. */
    public record StressPortfolio(
            String portfolioId,
            CurrencyCode baseCurrency,
            Money creditExposure,         // total performing + non-performing
            Money tradingBookValue,
            Money tier1Capital,
            BigDecimal rwaEstimate,
            Map<AssetClass, BigDecimal> exposureMix  // 0..1 shares summing to ~1.0
    ) {
        public StressPortfolio {
            Objects.requireNonNull(portfolioId, "portfolioId");
            Objects.requireNonNull(baseCurrency, "baseCurrency");
            Objects.requireNonNull(creditExposure, "creditExposure");
            Objects.requireNonNull(tradingBookValue, "tradingBookValue");
            Objects.requireNonNull(tier1Capital, "tier1Capital");
            Objects.requireNonNull(rwaEstimate, "rwaEstimate");
            exposureMix = exposureMix == null ? Map.of() : Map.copyOf(exposureMix);
        }
    }

    public enum AssetClass {
        RESIDENTIAL_MORTGAGE,
        COMMERCIAL_REAL_ESTATE,
        COMMERCIAL_AND_INDUSTRIAL,
        CREDIT_CARD,
        AUTO,
        SOVEREIGN,
        TRADING_BOOK
    }

    /** Single-quarter projected metrics. */
    public record QuarterlyProjection(
            YearMonth quarter,
            Money creditLoss,
            Money tradingLoss,
            Money preProvisionNetRevenue,
            Money endingTier1Capital,
            BigDecimal endingCet1RatioPct,
            BigDecimal unemploymentRatePct
    ) {}

    /** Full run result per scenario. */
    public record ScenarioResult(
            String runId,
            String scenarioId,
            ScenarioFamily family,
            StressPortfolio portfolio,
            List<QuarterlyProjection> projections,
            Money peakQuarterlyLoss,
            Money cumulativeLoss,
            BigDecimal minCet1RatioPct,
            Instant completedAt
    ) {}

    public record StressCapitalShortfallEvent(
            UUID eventId, Instant occurredAt,
            String runId, String portfolioId,
            BigDecimal minCet1RatioPct, BigDecimal threshold)
            implements DomainEvent {
        @Override public String eventType() { return "risk.stress.capital_shortfall"; }
    }

    /** CET1 regulatory minimum including capital-conservation buffer. */
    public static final BigDecimal CET1_REGULATORY_FLOOR = new BigDecimal("7.00");

    /** Per-asset-class annualised loss rates keyed by family. */
    private static final Map<ScenarioFamily, Map<AssetClass, BigDecimal>> LOSS_RATES;

    static {
        LOSS_RATES = new EnumMap<>(ScenarioFamily.class);

        Map<AssetClass, BigDecimal> baseline = new EnumMap<>(AssetClass.class);
        baseline.put(AssetClass.RESIDENTIAL_MORTGAGE, new BigDecimal("0.0020"));
        baseline.put(AssetClass.COMMERCIAL_REAL_ESTATE, new BigDecimal("0.0040"));
        baseline.put(AssetClass.COMMERCIAL_AND_INDUSTRIAL, new BigDecimal("0.0075"));
        baseline.put(AssetClass.CREDIT_CARD, new BigDecimal("0.0300"));
        baseline.put(AssetClass.AUTO, new BigDecimal("0.0125"));
        baseline.put(AssetClass.SOVEREIGN, new BigDecimal("0.0005"));
        baseline.put(AssetClass.TRADING_BOOK, new BigDecimal("0.0050"));

        Map<AssetClass, BigDecimal> adverse = new EnumMap<>(AssetClass.class);
        adverse.put(AssetClass.RESIDENTIAL_MORTGAGE, new BigDecimal("0.0080"));
        adverse.put(AssetClass.COMMERCIAL_REAL_ESTATE, new BigDecimal("0.0180"));
        adverse.put(AssetClass.COMMERCIAL_AND_INDUSTRIAL, new BigDecimal("0.0225"));
        adverse.put(AssetClass.CREDIT_CARD, new BigDecimal("0.0650"));
        adverse.put(AssetClass.AUTO, new BigDecimal("0.0275"));
        adverse.put(AssetClass.SOVEREIGN, new BigDecimal("0.0015"));
        adverse.put(AssetClass.TRADING_BOOK, new BigDecimal("0.0200"));

        Map<AssetClass, BigDecimal> severe = new EnumMap<>(AssetClass.class);
        severe.put(AssetClass.RESIDENTIAL_MORTGAGE, new BigDecimal("0.0200"));
        severe.put(AssetClass.COMMERCIAL_REAL_ESTATE, new BigDecimal("0.0450"));
        severe.put(AssetClass.COMMERCIAL_AND_INDUSTRIAL, new BigDecimal("0.0450"));
        severe.put(AssetClass.CREDIT_CARD, new BigDecimal("0.1100"));
        severe.put(AssetClass.AUTO, new BigDecimal("0.0575"));
        severe.put(AssetClass.SOVEREIGN, new BigDecimal("0.0025"));
        severe.put(AssetClass.TRADING_BOOK, new BigDecimal("0.0500"));

        LOSS_RATES.put(ScenarioFamily.BASELINE, baseline);
        LOSS_RATES.put(ScenarioFamily.ADVERSE, adverse);
        LOSS_RATES.put(ScenarioFamily.SEVERELY_ADVERSE, severe);
        LOSS_RATES.put(ScenarioFamily.INTERNAL_BAU, baseline);
        LOSS_RATES.put(ScenarioFamily.REVERSE, severe);
    }

    private final Clock clock;
    private final EventBus events;

    public StressTestHarness(Clock clock, EventBus events) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Build the three canonical supervisory scenarios for the given starting
     * quarter. Values are stylised but directionally consistent with the
     * 2024-cycle FRB instructions.
     */
    public List<MacroPath> buildSupervisoryScenarios(YearMonth startQuarter) {
        Objects.requireNonNull(startQuarter, "startQuarter");
        List<MacroPath> scenarios = new ArrayList<>(3);
        scenarios.add(buildScenario("CCAR-BASELINE-" + startQuarter, ScenarioFamily.BASELINE,
                startQuarter, 2.2, 4.0, 4500.0, 320.0, 150.0, 3.5));
        scenarios.add(buildScenario("CCAR-ADVERSE-" + startQuarter, ScenarioFamily.ADVERSE,
                startQuarter, -1.0, 6.5, 3800.0, 285.0, 275.0, 2.5));
        scenarios.add(buildScenario("CCAR-SEVERE-" + startQuarter, ScenarioFamily.SEVERELY_ADVERSE,
                startQuarter, -3.5, 9.75, 2700.0, 230.0, 575.0, 0.5));
        return scenarios;
    }

    /**
     * Run a single scenario. Produces nine quarterly projections, peak loss,
     * cumulative loss, and the minimum CET1 ratio observed across the horizon.
     */
    public ScenarioResult runScenario(StressPortfolio portfolio, MacroPath macro) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(macro, "macro");

        List<QuarterlyProjection> projections = new ArrayList<>(PROJECTION_QUARTERS);
        Map<AssetClass, BigDecimal> rates = LOSS_RATES.getOrDefault(
                macro.family(), LOSS_RATES.get(ScenarioFamily.BASELINE));

        Money runningTier1 = portfolio.tier1Capital();
        Money peakLoss = Money.zero(portfolio.baseCurrency());
        Money cumulativeLoss = Money.zero(portfolio.baseCurrency());
        BigDecimal minCet1 = null;

        List<QuarterlyMacro> quarters = macro.quarters().isEmpty()
                ? fillBaselineQuarters(YearMonth.now())
                : macro.quarters();

        for (int i = 0; i < PROJECTION_QUARTERS; i++) {
            QuarterlyMacro q = quarters.get(Math.min(i, quarters.size() - 1));
            Money quarterlyCreditLoss = computeCreditLoss(portfolio, rates, q, i);
            Money quarterlyTradingLoss = computeTradingLoss(portfolio, rates, q);
            Money totalLoss = quarterlyCreditLoss.plus(quarterlyTradingLoss);

            Money ppnr = computePpnr(portfolio, q);
            Money netIncome = ppnr.minus(totalLoss);

            runningTier1 = runningTier1.plus(netIncome);
            BigDecimal cet1Ratio = cet1Ratio(runningTier1, portfolio.rwaEstimate());

            if (totalLoss.compareTo(peakLoss) > 0) peakLoss = totalLoss;
            cumulativeLoss = cumulativeLoss.plus(totalLoss);
            if (minCet1 == null || cet1Ratio.compareTo(minCet1) < 0) minCet1 = cet1Ratio;

            projections.add(new QuarterlyProjection(
                    q.quarter(), quarterlyCreditLoss, quarterlyTradingLoss, ppnr,
                    runningTier1, cet1Ratio, q.unemploymentRatePct()));
        }

        ScenarioResult result = new ScenarioResult(
                UUID.randomUUID().toString(), macro.scenarioId(), macro.family(),
                portfolio, List.copyOf(projections),
                peakLoss, cumulativeLoss,
                minCet1 == null ? BigDecimal.ZERO : minCet1,
                Timestamp.now(clock));

        if (result.minCet1RatioPct().compareTo(CET1_REGULATORY_FLOOR) < 0) {
            events.publish(new StressCapitalShortfallEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    result.runId(), portfolio.portfolioId(),
                    result.minCet1RatioPct(), CET1_REGULATORY_FLOOR));
            log.warn("Stress shortfall: portfolio={} scenario={} minCET1={}%",
                    portfolio.portfolioId(), macro.scenarioId(), result.minCet1RatioPct());
        }
        return result;
    }

    /**
     * Run all three supervisory scenarios in one pass. The result list is in
     * the canonical order (baseline → adverse → severely adverse).
     */
    public List<ScenarioResult> runSupervisorySuite(StressPortfolio portfolio, YearMonth startQuarter) {
        List<ScenarioResult> out = new ArrayList<>(3);
        for (MacroPath m : buildSupervisoryScenarios(startQuarter)) {
            out.add(runScenario(portfolio, m));
        }
        return out;
    }

    /**
     * Reverse stress test. Iteratively scales the severely-adverse loss rates
     * until the minimum CET1 ratio touches {@code targetCet1Pct}. Returns the
     * scaling factor that achieved the target along with the resulting
     * projection.
     */
    public ReverseStressResult reverseStress(StressPortfolio portfolio, YearMonth startQuarter,
                                              BigDecimal targetCet1Pct) {
        Objects.requireNonNull(targetCet1Pct, "targetCet1Pct");
        MacroPath base = buildScenario("REVERSE-" + startQuarter,
                ScenarioFamily.REVERSE, startQuarter,
                -3.5, 9.75, 2700.0, 230.0, 575.0, 0.5);

        double low = 0.1, high = 4.0;
        ScenarioResult best = null;
        double bestScale = 1.0;
        for (int i = 0; i < 28; i++) {
            double mid = (low + high) / 2.0;
            ScenarioResult res = runScaledScenario(portfolio, base, mid);
            if (res.minCet1RatioPct().compareTo(targetCet1Pct) < 0) {
                // Too severe — reduce magnitude
                high = mid;
                best = res;
                bestScale = mid;
            } else if (res.minCet1RatioPct().compareTo(targetCet1Pct) > 0) {
                low = mid;
                best = res;
                bestScale = mid;
            } else {
                return new ReverseStressResult(BigDecimal.valueOf(mid), res);
            }
            if (Math.abs(high - low) < 0.01) break;
        }
        return new ReverseStressResult(
                BigDecimal.valueOf(bestScale).setScale(3, RoundingMode.HALF_EVEN),
                Objects.requireNonNull(best));
    }

    public record ReverseStressResult(BigDecimal severityMultiplier, ScenarioResult result) {}

    /* ---------- Projection mechanics ---------- */

    private ScenarioResult runScaledScenario(StressPortfolio portfolio, MacroPath base, double scale) {
        Map<AssetClass, BigDecimal> scaled = new EnumMap<>(AssetClass.class);
        for (Map.Entry<AssetClass, BigDecimal> e :
                LOSS_RATES.get(ScenarioFamily.REVERSE).entrySet()) {
            scaled.put(e.getKey(), e.getValue().multiply(BigDecimal.valueOf(scale))
                    .setScale(6, RoundingMode.HALF_EVEN));
        }
        Map<ScenarioFamily, Map<AssetClass, BigDecimal>> saved = new EnumMap<>(LOSS_RATES);
        LOSS_RATES.put(ScenarioFamily.REVERSE, scaled);
        try {
            return runScenario(portfolio, base);
        } finally {
            LOSS_RATES.put(ScenarioFamily.REVERSE, saved.get(ScenarioFamily.REVERSE));
        }
    }

    private Money computeCreditLoss(StressPortfolio portfolio,
                                     Map<AssetClass, BigDecimal> annualRates,
                                     QuarterlyMacro macro,
                                     int quarterIndex) {
        Money loss = Money.zero(portfolio.baseCurrency());
        for (Map.Entry<AssetClass, BigDecimal> e : portfolio.exposureMix().entrySet()) {
            AssetClass ac = e.getKey();
            if (ac == AssetClass.TRADING_BOOK) continue;
            BigDecimal share = e.getValue();
            BigDecimal rate = annualRates.getOrDefault(ac, BigDecimal.ZERO);
            BigDecimal quarterlyRate = rate.divide(BigDecimal.valueOf(4), 6, RoundingMode.HALF_EVEN);
            BigDecimal macroMultiplier = unemploymentMultiplier(macro.unemploymentRatePct());
            BigDecimal frontLoad = quarterIndex < 4
                    ? new BigDecimal("1.15")
                    : new BigDecimal("0.85");
            BigDecimal effective = quarterlyRate
                    .multiply(macroMultiplier)
                    .multiply(frontLoad)
                    .multiply(share);
            loss = loss.plus(portfolio.creditExposure().times(effective));
        }
        return loss;
    }

    private Money computeTradingLoss(StressPortfolio portfolio,
                                      Map<AssetClass, BigDecimal> annualRates,
                                      QuarterlyMacro macro) {
        BigDecimal share = portfolio.exposureMix().getOrDefault(AssetClass.TRADING_BOOK, BigDecimal.ZERO);
        if (share.signum() == 0) return Money.zero(portfolio.baseCurrency());
        BigDecimal rate = annualRates.getOrDefault(AssetClass.TRADING_BOOK, BigDecimal.ZERO);
        BigDecimal quarterly = rate.divide(BigDecimal.valueOf(4), 6, RoundingMode.HALF_EVEN);
        BigDecimal spreadMultiplier = spreadMultiplier(macro.bbbCorpSpreadBps());
        BigDecimal effective = quarterly.multiply(share).multiply(spreadMultiplier);
        return portfolio.tradingBookValue().times(effective);
    }

    private Money computePpnr(StressPortfolio portfolio, QuarterlyMacro macro) {
        // PPNR ≈ 1.25% quarterly of credit exposure, dampened by downturn macros.
        BigDecimal basePct = new BigDecimal("0.0125");
        BigDecimal macroAdj = macro.unemploymentRatePct().compareTo(new BigDecimal("6.0")) > 0
                ? new BigDecimal("0.70")
                : new BigDecimal("1.00");
        return portfolio.creditExposure().times(basePct.multiply(macroAdj));
    }

    private static BigDecimal unemploymentMultiplier(BigDecimal unemp) {
        if (unemp.compareTo(new BigDecimal("9.0")) >= 0) return new BigDecimal("1.80");
        if (unemp.compareTo(new BigDecimal("6.5")) >= 0) return new BigDecimal("1.35");
        if (unemp.compareTo(new BigDecimal("5.0")) >= 0) return new BigDecimal("1.10");
        return new BigDecimal("0.90");
    }

    private static BigDecimal spreadMultiplier(BigDecimal spreadBps) {
        if (spreadBps == null) return BigDecimal.ONE;
        if (spreadBps.compareTo(new BigDecimal("500")) >= 0) return new BigDecimal("1.75");
        if (spreadBps.compareTo(new BigDecimal("300")) >= 0) return new BigDecimal("1.30");
        if (spreadBps.compareTo(new BigDecimal("200")) >= 0) return new BigDecimal("1.10");
        return new BigDecimal("0.95");
    }

    private static BigDecimal cet1Ratio(Money tier1, BigDecimal rwa) {
        if (rwa.signum() == 0) return BigDecimal.ZERO;
        return tier1.amount()
                .divide(rwa, 6, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private MacroPath buildScenario(String id, ScenarioFamily family, YearMonth start,
                                     double troughGdp, double peakUnemp, double minEquity,
                                     double minHousing, double maxSpread, double minRate) {
        List<QuarterlyMacro> q = new ArrayList<>(PROJECTION_QUARTERS);
        for (int i = 0; i < PROJECTION_QUARTERS; i++) {
            double progress = i / (double) (PROJECTION_QUARTERS - 1);
            double bowl = Math.sin(progress * Math.PI);   // 0..1..0
            q.add(new QuarterlyMacro(
                    start.plusMonths(i * 3L),
                    BigDecimal.valueOf(interp(2.0, troughGdp, bowl))
                            .setScale(2, RoundingMode.HALF_EVEN),
                    BigDecimal.valueOf(interp(4.0, peakUnemp, bowl))
                            .setScale(2, RoundingMode.HALF_EVEN),
                    BigDecimal.valueOf(interp(4500.0, minEquity, bowl))
                            .setScale(2, RoundingMode.HALF_EVEN),
                    BigDecimal.valueOf(interp(320.0, minHousing, bowl))
                            .setScale(2, RoundingMode.HALF_EVEN),
                    BigDecimal.valueOf(interp(150.0, maxSpread, bowl))
                            .setScale(2, RoundingMode.HALF_EVEN),
                    BigDecimal.valueOf(interp(3.5, minRate, bowl))
                            .setScale(2, RoundingMode.HALF_EVEN)));
        }
        return new MacroPath(id, family, q);
    }

    private static double interp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    private static List<QuarterlyMacro> fillBaselineQuarters(YearMonth start) {
        List<QuarterlyMacro> q = new ArrayList<>(PROJECTION_QUARTERS);
        for (int i = 0; i < PROJECTION_QUARTERS; i++) {
            q.add(new QuarterlyMacro(
                    start.plusMonths(i * 3L),
                    new BigDecimal("2.20"), new BigDecimal("4.00"),
                    new BigDecimal("4500.00"), new BigDecimal("320.00"),
                    new BigDecimal("150.00"), new BigDecimal("3.50")));
        }
        return q;
    }

    /** Aggregate a set of scenario results into a simple comparable map. */
    public Map<ScenarioFamily, Money> peakLossByFamily(List<ScenarioResult> results) {
        Map<ScenarioFamily, Money> out = new LinkedHashMap<>();
        for (ScenarioResult r : results) {
            Money existing = out.get(r.family());
            if (existing == null || r.peakQuarterlyLoss().compareTo(existing) > 0) {
                out.put(r.family(), r.peakQuarterlyLoss());
            }
        }
        return out;
    }

    /** Convert a raw fraction (0..1) into a Percent value. */
    static Percent asPercent(BigDecimal fraction) {
        return Percent.ofRate(fraction);
    }

    /** Convenience: true if any projection in the run falls below the floor. */
    public boolean breachesFloor(ScenarioResult result) {
        return result.minCet1RatioPct().compareTo(CET1_REGULATORY_FLOOR) < 0;
    }

    /** Short diagnostic string for log lines. */
    public static String summary(ScenarioResult r) {
        return "scenario=%s peakLoss=%s cumLoss=%s minCET1=%s%%".formatted(
                r.scenarioId(), r.peakQuarterlyLoss(),
                r.cumulativeLoss(), r.minCet1RatioPct());
    }

    /** For external callers building ad-hoc internal scenarios. */
    public Optional<MacroPath> lookupScenario(List<MacroPath> scenarios, ScenarioFamily family) {
        return scenarios.stream().filter(s -> s.family() == family).findFirst();
    }

    /** Last observed quarter in the projection. Useful for chart axes. */
    public static YearMonth finalQuarter(ScenarioResult r) {
        List<QuarterlyProjection> p = r.projections();
        return p.isEmpty() ? YearMonth.from(LocalDate.now()) : p.get(p.size() - 1).quarter();
    }
}
