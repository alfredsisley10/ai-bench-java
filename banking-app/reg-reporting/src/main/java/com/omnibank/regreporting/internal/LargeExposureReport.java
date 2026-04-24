package com.omnibank.regreporting.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Implements the Single-Counterparty Credit Limits (SCCL) reporting specified
 * in Federal Reserve Regulation YY Part 252.70 et seq., enacted under the
 * Dodd-Frank Act section 165(e).
 *
 * <p>Limits enforced:
 * <ul>
 *   <li><b>Standard limit:</b> Net credit exposure to any single counterparty
 *       ≤ 25% of the covered company's Tier 1 capital.</li>
 *   <li><b>Major-to-Major limit:</b> Net credit exposure between a "major
 *       covered company" and another "major counterparty" ≤ 15% of Tier 1.</li>
 *   <li><b>Covered-company limit:</b> Exposure to G-SIBs from any covered
 *       company ≤ 10% for the most stringent tier.</li>
 * </ul>
 *
 * <p>Exposure includes: gross credit extensions, unfunded commitments at
 * appropriate credit conversion factors, securities-financing transactions
 * (repo / reverse repo) after collateral haircut, and derivative positions
 * (current exposure method or SA-CCR). Eligible credit mitigants reduce the
 * net exposure subject to the supervisory haircut schedule.
 *
 * <p>Reported quarterly alongside FR Y-15 and tracked daily for breach
 * monitoring. Breaches must be cured within 90 days of occurring.
 */
public class LargeExposureReport {

    private static final Logger log = LoggerFactory.getLogger(LargeExposureReport.class);

    /** SCCL standard limit: 25% of Tier 1 capital. */
    public static final BigDecimal STANDARD_LIMIT_FRACTION = new BigDecimal("0.25");
    /** Major-to-major limit: 15%. */
    public static final BigDecimal MAJOR_MAJOR_LIMIT_FRACTION = new BigDecimal("0.15");
    /** Covered-company limit to G-SIBs: 10%. */
    public static final BigDecimal GSIB_LIMIT_FRACTION = new BigDecimal("0.10");
    /** Breach cure window. */
    public static final int BREACH_CURE_DAYS = 90;

    public enum CounterpartyTier {
        STANDARD,        // non-major
        MAJOR,            // >= $500B total assets
        GSIB              // designated G-SIB
    }

    public enum ExposureType {
        LOAN,
        COMMITMENT,
        SECURITIES_FINANCING,
        DERIVATIVE,
        SECURITIES_HOLDING,
        LETTER_OF_CREDIT
    }

    public enum MitigantType { CASH_COLLATERAL, GOVERNMENT_SECURITIES, GUARANTEE, CREDIT_DEFAULT_SWAP }

    /** Credit conversion factors for off-balance-sheet exposures. */
    private static final Map<ExposureType, BigDecimal> CCF = new EnumMap<>(Map.of(
            ExposureType.LOAN, new BigDecimal("1.00"),
            ExposureType.COMMITMENT, new BigDecimal("0.50"),
            ExposureType.SECURITIES_FINANCING, new BigDecimal("1.00"),
            ExposureType.DERIVATIVE, new BigDecimal("1.00"),     // already at EAD
            ExposureType.SECURITIES_HOLDING, new BigDecimal("1.00"),
            ExposureType.LETTER_OF_CREDIT, new BigDecimal("1.00")
    ));

    /** Collateral haircuts (per Reg YY table). */
    private static final Map<MitigantType, BigDecimal> HAIRCUT = new EnumMap<>(Map.of(
            MitigantType.CASH_COLLATERAL, new BigDecimal("0.00"),
            MitigantType.GOVERNMENT_SECURITIES, new BigDecimal("0.005"),
            MitigantType.GUARANTEE, new BigDecimal("0.10"),
            MitigantType.CREDIT_DEFAULT_SWAP, new BigDecimal("0.15")
    ));

    public record Counterparty(CustomerId id, String legalName, String jurisdiction,
                                CounterpartyTier tier) {
        public Counterparty {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(legalName, "legalName");
            Objects.requireNonNull(tier, "tier");
        }
    }

    public record Mitigant(MitigantType type, Money amount) {
        public Mitigant {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
        }

        public Money effectiveValue() {
            BigDecimal haircut = HAIRCUT.getOrDefault(type, BigDecimal.ZERO);
            BigDecimal retained = BigDecimal.ONE.subtract(haircut);
            return amount.times(retained);
        }
    }

    public record Exposure(UUID exposureId, Counterparty counterparty, ExposureType type,
                           Money grossAmount, List<Mitigant> mitigants) {
        public Exposure {
            Objects.requireNonNull(exposureId, "exposureId");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(grossAmount, "grossAmount");
            mitigants = List.copyOf(Objects.requireNonNull(mitigants, "mitigants"));
        }

        public Money exposureAtDefault() {
            BigDecimal ccf = CCF.getOrDefault(type, BigDecimal.ONE);
            return grossAmount.times(ccf);
        }

        public Money netExposure() {
            Money ead = exposureAtDefault();
            Money mitigation = mitigants.stream()
                    .map(Mitigant::effectiveValue)
                    .reduce(Money.zero(ead.currency()), Money::plus);
            Money net = ead.minus(mitigation);
            return net.isNegative() ? Money.zero(ead.currency()) : net;
        }
    }

    public record CoveredCompany(String legalEntityId, String name, CounterpartyTier tier,
                                  Money tier1Capital) {
        public CoveredCompany {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(tier1Capital, "tier1Capital");
        }
    }

    public record CounterpartyAggregate(Counterparty counterparty,
                                         Map<ExposureType, Money> byType,
                                         Money totalGross,
                                         Money totalNet,
                                         BigDecimal percentOfTier1,
                                         BigDecimal limitFraction,
                                         boolean isBreach) {}

    public record LargeExposureSummary(
            UUID reportId,
            CoveredCompany filer,
            LocalDate asOfDate,
            Money tier1Capital,
            List<CounterpartyAggregate> aggregates,
            List<String> breaches,
            Map<ExposureType, Money> typeSubtotals
    ) {
        public LargeExposureSummary {
            Objects.requireNonNull(reportId, "reportId");
            aggregates = List.copyOf(aggregates);
            breaches = List.copyOf(breaches);
            typeSubtotals = Map.copyOf(typeSubtotals);
        }
    }

    private final Clock clock;

    public LargeExposureReport(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Build the SCCL summary for the supplied covered company and its full
     * exposure book. Aggregates exposures by counterparty, computes %-of-Tier-1
     * ratios, and flags limit breaches.
     */
    public LargeExposureSummary generate(CoveredCompany filer, List<Exposure> exposures,
                                          LocalDate asOfDate) {
        Objects.requireNonNull(filer, "filer");
        Objects.requireNonNull(exposures, "exposures");
        Objects.requireNonNull(asOfDate, "asOfDate");
        if (filer.tier1Capital().isZero() || filer.tier1Capital().isNegative()) {
            throw new IllegalArgumentException(
                    "Tier 1 capital must be positive to compute SCCL ratios: "
                            + filer.tier1Capital());
        }

        Map<String, List<Exposure>> byCpty = new LinkedHashMap<>();
        for (Exposure e : exposures) {
            byCpty.computeIfAbsent(e.counterparty().id().toString(), k -> new ArrayList<>())
                    .add(e);
        }

        List<CounterpartyAggregate> aggregates = new ArrayList<>();
        List<String> breachNotes = new ArrayList<>();
        Map<ExposureType, Money> typeSubtotals = new EnumMap<>(ExposureType.class);
        for (ExposureType t : ExposureType.values()) {
            typeSubtotals.put(t, Money.zero(CurrencyCode.USD));
        }

        for (var entry : byCpty.entrySet()) {
            List<Exposure> list = entry.getValue();
            Counterparty cpty = list.get(0).counterparty();
            Map<ExposureType, Money> byType = new EnumMap<>(ExposureType.class);
            Money gross = Money.zero(filer.tier1Capital().currency());
            Money net = Money.zero(filer.tier1Capital().currency());
            for (Exposure e : list) {
                byType.merge(e.type(), e.netExposure(), Money::plus);
                gross = gross.plus(e.grossAmount());
                net = net.plus(e.netExposure());
                typeSubtotals.merge(e.type(), e.netExposure(), Money::plus);
            }

            BigDecimal pct = net.amount().divide(
                    filer.tier1Capital().amount(), 6, RoundingMode.HALF_EVEN);
            BigDecimal limit = limitFractionFor(filer.tier(), cpty.tier());
            boolean breach = pct.compareTo(limit) > 0;
            if (breach) {
                breachNotes.add("%s (%s): exposure %s = %.4f of Tier 1 exceeds %.4f"
                        .formatted(cpty.legalName(), cpty.tier(), net, pct, limit));
            }
            aggregates.add(new CounterpartyAggregate(cpty, byType, gross, net, pct, limit, breach));
        }

        aggregates.sort(Comparator.comparing(
                (CounterpartyAggregate a) -> a.totalNet().amount()).reversed());

        LargeExposureSummary summary = new LargeExposureSummary(
                UUID.randomUUID(), filer, asOfDate, filer.tier1Capital(),
                aggregates, breachNotes, typeSubtotals);

        log.info("Large Exposure report generated: filer={}, counterparties={}, breaches={}",
                filer.legalEntityId(), aggregates.size(), breachNotes.size());
        return summary;
    }

    /** Limit fraction lookup based on the tier matrix in Reg YY. */
    static BigDecimal limitFractionFor(CounterpartyTier filerTier, CounterpartyTier cptyTier) {
        // GSIB-to-GSIB: 10%
        if (filerTier == CounterpartyTier.GSIB && cptyTier == CounterpartyTier.GSIB) {
            return GSIB_LIMIT_FRACTION;
        }
        // MAJOR-to-MAJOR: 15%
        if (filerTier == CounterpartyTier.MAJOR && cptyTier == CounterpartyTier.MAJOR) {
            return MAJOR_MAJOR_LIMIT_FRACTION;
        }
        if (filerTier == CounterpartyTier.GSIB || cptyTier == CounterpartyTier.GSIB) {
            return GSIB_LIMIT_FRACTION;
        }
        return STANDARD_LIMIT_FRACTION;
    }

    /** Top-N counterparty list for examiner summary pages. */
    public List<CounterpartyAggregate> topN(LargeExposureSummary summary, int n) {
        return summary.aggregates().stream().limit(Math.max(0, n)).toList();
    }

    /** True if the exposure limits are breached for any counterparty. */
    public boolean hasAnyBreach(LargeExposureSummary summary) {
        return !summary.breaches().isEmpty();
    }

    /**
     * Compute the days remaining in the cure window for a given breach
     * detected on {@code detectedOn}. Negative values indicate the window
     * expired.
     */
    public long daysToCure(LocalDate detectedOn) {
        LocalDate today = LocalDate.now(clock);
        LocalDate deadline = detectedOn.plusDays(BREACH_CURE_DAYS);
        return java.time.temporal.ChronoUnit.DAYS.between(today, deadline);
    }

    /** Calculate the net exposure headroom (available) to a specific counterparty. */
    public Money headroom(CoveredCompany filer, Counterparty counterparty, Money currentNet) {
        BigDecimal limit = limitFractionFor(filer.tier(), counterparty.tier());
        Money capacity = filer.tier1Capital().times(limit);
        Money hr = capacity.minus(currentNet);
        return hr.isNegative() ? Money.zero(filer.tier1Capital().currency()) : hr;
    }

    /**
     * Stress check — apply a uniform credit shock to every exposure and
     * re-evaluate limit compliance. Useful for counterparty stress scenarios
     * in risk committees.
     */
    public LargeExposureSummary applyStressShock(CoveredCompany filer,
                                                    List<Exposure> exposures,
                                                    LocalDate asOf,
                                                    BigDecimal shockFactor) {
        Objects.requireNonNull(shockFactor, "shockFactor");
        List<Exposure> stressed = new ArrayList<>();
        for (Exposure e : exposures) {
            Money shocked = e.grossAmount().times(shockFactor);
            stressed.add(new Exposure(e.exposureId(), e.counterparty(), e.type(),
                    shocked, e.mitigants()));
        }
        return generate(filer, stressed, asOf);
    }

    /** Flat-file renderer for the FR-Y-15 attachment containing SCCL data. */
    public String renderFrY15Attachment(LargeExposureSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("#FR-Y-15|SCCL|").append(summary.filer().legalEntityId())
                .append('|').append(summary.asOfDate()).append('\n');
        sb.append("#Tier1|").append(summary.tier1Capital().amount().toPlainString()).append('\n');
        for (CounterpartyAggregate agg : summary.aggregates()) {
            sb.append(agg.counterparty().id()).append('|')
                    .append(agg.counterparty().tier()).append('|')
                    .append(agg.totalNet().amount().toPlainString()).append('|')
                    .append(agg.percentOfTier1().toPlainString()).append('|')
                    .append(agg.limitFraction().toPlainString()).append('|')
                    .append(agg.isBreach() ? "BREACH" : "OK").append('\n');
        }
        return sb.toString();
    }

    /**
     * Group exposures by counterparty, summing net exposure. Exposed as a
     * standalone helper because the risk group reuses it for ad-hoc reporting.
     */
    public static Map<CustomerId, Money> groupByCounterparty(List<Exposure> exposures) {
        Map<CustomerId, Money> map = new HashMap<>();
        for (Exposure e : exposures) {
            Money net = e.netExposure();
            map.merge(e.counterparty().id(), net, Money::plus);
        }
        return Collections.unmodifiableMap(map);
    }

    /** Evaluate whether an entity qualifies as a "major" counterparty. */
    public static CounterpartyTier classify(Money totalAssets, Set<String> gsibList,
                                              String legalEntityId) {
        if (gsibList.contains(legalEntityId)) return CounterpartyTier.GSIB;
        BigDecimal fiveHundredBillion = new BigDecimal("500000000000");
        if (totalAssets.amount().compareTo(fiveHundredBillion) >= 0) return CounterpartyTier.MAJOR;
        return CounterpartyTier.STANDARD;
    }
}
