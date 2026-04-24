package com.omnibank.risk.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Basel III standardised-approach Risk-Weighted Assets (RWA) engine.
 *
 * <p>Computes the three RWA pillars consumed by the CET1 / Total Capital
 * ratios:
 *
 * <ul>
 *   <li><b>Credit RWA</b> — each on-balance-sheet exposure bucketed into an
 *       asset class and multiplied by the supervisory risk weight from the
 *       US Agencies standardised rule (12 CFR §217 subpart D).</li>
 *   <li><b>Operational RWA</b> — Basic Indicator Approach (BIA) at 15% of
 *       three-year average gross income, or the 2023 Standardised Approach
 *       that multiplies the Business Indicator by an internal-loss
 *       multiplier.</li>
 *   <li><b>Market RWA</b> — standardised approach for trading-book positions:
 *       interest rate, equity, FX, commodity, and specific-risk charges
 *       scaled by the supervisory 12.5× factor.</li>
 * </ul>
 *
 * <p>Risk weights are kept in a clearly-labelled enum so the numbers sit
 * next to the regulatory citation for audit traceability.
 */
public class RiskWeightedAssetsEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskWeightedAssetsEngine.class);

    /** Factor converting market-risk capital charge to RWA (Basel 2.5 §718). */
    public static final BigDecimal MARKET_RWA_MULTIPLIER = BigDecimal.valueOf(12.5);

    /** BIA alpha factor (15% of average gross income over 3y). */
    public static final BigDecimal BIA_ALPHA = new BigDecimal("0.15");

    /** Standardised approach internal-loss multiplier floor / ceiling. */
    public static final BigDecimal SA_ILM_FLOOR = new BigDecimal("0.541");
    public static final BigDecimal SA_ILM_CEILING = new BigDecimal("2.500");

    /** Standardised regulatory risk weights for the common credit asset classes. */
    public enum CreditRiskCategory {
        CASH_AND_EQUIVALENT(new BigDecimal("0.00")),
        US_TREASURY(new BigDecimal("0.00")),
        OECD_SOVEREIGN(new BigDecimal("0.20")),
        NON_OECD_SOVEREIGN(new BigDecimal("1.00")),
        GSE_MBS(new BigDecimal("0.20")),
        MUNICIPAL_GO(new BigDecimal("0.20")),
        MUNICIPAL_REVENUE(new BigDecimal("0.50")),
        BANK_EXPOSURE_AAA_AA(new BigDecimal("0.20")),
        BANK_EXPOSURE_A(new BigDecimal("0.50")),
        BANK_EXPOSURE_BBB(new BigDecimal("1.00")),
        BANK_EXPOSURE_BELOW(new BigDecimal("1.50")),
        CORPORATE_AAA_AA(new BigDecimal("0.20")),
        CORPORATE_A(new BigDecimal("0.50")),
        CORPORATE_BBB(new BigDecimal("1.00")),
        CORPORATE_SPECULATIVE(new BigDecimal("1.50")),
        RETAIL_UNSECURED(new BigDecimal("0.75")),
        CREDIT_CARD(new BigDecimal("0.75")),
        RESIDENTIAL_MORTGAGE_LTV_LT_80(new BigDecimal("0.50")),
        RESIDENTIAL_MORTGAGE_LTV_80_90(new BigDecimal("0.75")),
        RESIDENTIAL_MORTGAGE_LTV_GT_90(new BigDecimal("1.00")),
        HVCRE(new BigDecimal("1.50")),
        PAST_DUE(new BigDecimal("1.50")),
        EQUITY_PUBLIC(new BigDecimal("3.00")),
        EQUITY_PRIVATE(new BigDecimal("4.00"));

        public final BigDecimal riskWeight;

        CreditRiskCategory(BigDecimal riskWeight) {
            this.riskWeight = riskWeight;
        }
    }

    /** One credit exposure that contributes to credit RWA. */
    public record CreditExposure(
            String exposureId,
            CreditRiskCategory category,
            Money drawnBalance,
            Money undrawnCommitment,
            BigDecimal conversionFactor    // CCF for undrawn (0..1)
    ) {
        public CreditExposure {
            Objects.requireNonNull(exposureId, "exposureId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(drawnBalance, "drawnBalance");
            Objects.requireNonNull(undrawnCommitment, "undrawnCommitment");
            Objects.requireNonNull(conversionFactor, "conversionFactor");
            if (conversionFactor.compareTo(BigDecimal.ZERO) < 0
                    || conversionFactor.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("conversionFactor must be in [0,1]");
            }
        }
    }

    /** Basel II/III business lines for the operational-risk standardised approach. */
    public enum BusinessLine {
        CORPORATE_FINANCE(new BigDecimal("0.18")),
        TRADING_AND_SALES(new BigDecimal("0.18")),
        RETAIL_BANKING(new BigDecimal("0.12")),
        COMMERCIAL_BANKING(new BigDecimal("0.15")),
        PAYMENT_AND_SETTLEMENT(new BigDecimal("0.18")),
        AGENCY_SERVICES(new BigDecimal("0.15")),
        ASSET_MANAGEMENT(new BigDecimal("0.12")),
        RETAIL_BROKERAGE(new BigDecimal("0.12"));

        public final BigDecimal beta;

        BusinessLine(BigDecimal beta) {
            this.beta = beta;
        }
    }

    /** Market-risk factor buckets (FRTB-light). */
    public enum MarketRiskBucket {
        INTEREST_RATE_GENERAL(new BigDecimal("0.008")),
        INTEREST_RATE_SPECIFIC(new BigDecimal("0.016")),
        EQUITY_GENERAL(new BigDecimal("0.080")),
        EQUITY_SPECIFIC(new BigDecimal("0.040")),
        FOREIGN_EXCHANGE(new BigDecimal("0.080")),
        COMMODITY(new BigDecimal("0.150"));

        public final BigDecimal capitalChargeRate;

        MarketRiskBucket(BigDecimal capitalChargeRate) {
            this.capitalChargeRate = capitalChargeRate;
        }
    }

    public record MarketRiskPosition(String positionId, MarketRiskBucket bucket, Money grossPosition) {
        public MarketRiskPosition {
            Objects.requireNonNull(positionId, "positionId");
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(grossPosition, "grossPosition");
        }
    }

    /** Output structure: per-category RWA plus the grand total. */
    public record CreditRwaReport(
            Money totalEad,
            Money totalRwa,
            Map<CreditRiskCategory, Money> rwaByCategory
    ) {}

    public record OperationalRwaReport(
            Approach approach,
            Money capitalRequirement,
            Money rwa
    ) {}

    public enum Approach { BIA, STANDARDISED_APPROACH_2023 }

    public record MarketRwaReport(
            Money totalCapitalCharge,
            Money totalRwa,
            Map<MarketRiskBucket, Money> chargeByBucket
    ) {}

    public record TotalRwaReport(
            Money creditRwa,
            Money operationalRwa,
            Money marketRwa,
            Money totalRwa
    ) {}

    /** Compute Credit RWA for a portfolio of exposures. */
    public CreditRwaReport computeCreditRwa(List<CreditExposure> exposures, CurrencyCode currency) {
        Objects.requireNonNull(exposures, "exposures");
        Objects.requireNonNull(currency, "currency");

        Money totalEad = Money.zero(currency);
        Money totalRwa = Money.zero(currency);
        Map<CreditRiskCategory, Money> byCat = new EnumMap<>(CreditRiskCategory.class);

        for (CreditExposure ex : exposures) {
            Money ead = effectiveExposureAtDefault(ex);
            Money rwa = ead.times(ex.category().riskWeight);
            totalEad = totalEad.plus(ead);
            totalRwa = totalRwa.plus(rwa);
            byCat.merge(ex.category(), rwa, Money::plus);
        }
        log.debug("Credit RWA computed: exposures={} total EAD={} total RWA={}",
                exposures.size(), totalEad, totalRwa);
        return new CreditRwaReport(totalEad, totalRwa, byCat);
    }

    /**
     * Basic Indicator Approach: 15% × average positive gross income over
     * three preceding years. Negative or zero years are excluded from the
     * average.
     */
    public OperationalRwaReport computeOperationalRwaBia(List<Money> threeYearGrossIncome) {
        Objects.requireNonNull(threeYearGrossIncome, "threeYearGrossIncome");
        if (threeYearGrossIncome.isEmpty()) {
            throw new IllegalArgumentException("at least one year of income is required");
        }
        CurrencyCode ccy = threeYearGrossIncome.get(0).currency();
        List<Money> positives = threeYearGrossIncome.stream()
                .filter(Money::isPositive).toList();
        if (positives.isEmpty()) {
            Money zero = Money.zero(ccy);
            return new OperationalRwaReport(Approach.BIA, zero, zero);
        }
        Money sum = positives.stream().reduce(Money.zero(ccy), Money::plus);
        BigDecimal divisor = BigDecimal.valueOf(positives.size());
        Money avg = sum.dividedBy(divisor);
        Money capital = avg.times(BIA_ALPHA);
        Money rwa = capital.times(MARKET_RWA_MULTIPLIER);
        return new OperationalRwaReport(Approach.BIA, capital, rwa);
    }

    /**
     * Basel 2023 Standardised Approach: BI (Business Indicator) component
     * multiplied by internal-loss multiplier (ILM). We compute a simplified
     * BI component and use the loss-component / BI ratio to derive the ILM.
     */
    public OperationalRwaReport computeOperationalRwaStandardised(
            Money interestComponent,
            Money servicesComponent,
            Money financialComponent,
            Money avgAnnualLossLast10Y) {

        Objects.requireNonNull(interestComponent, "interestComponent");
        Money bi = interestComponent.plus(servicesComponent).plus(financialComponent);

        BigDecimal marginalCoefficient;
        Money oneBillion = Money.of("1000000000", bi.currency());
        Money thirtyBillion = Money.of("30000000000", bi.currency());
        if (bi.compareTo(oneBillion) <= 0) {
            marginalCoefficient = new BigDecimal("0.12");
        } else if (bi.compareTo(thirtyBillion) <= 0) {
            marginalCoefficient = new BigDecimal("0.15");
        } else {
            marginalCoefficient = new BigDecimal("0.18");
        }

        Money bic = bi.times(marginalCoefficient);

        // ILM = ln(exp(1) − 1 + (LC/BIC)^0.8)
        BigDecimal ratio = bic.isZero()
                ? BigDecimal.ZERO
                : avgAnnualLossLast10Y.amount().divide(bic.amount(), 8, RoundingMode.HALF_EVEN);
        double ilmRaw = Math.log(Math.exp(1.0) - 1.0 + Math.pow(ratio.doubleValue(), 0.8));
        BigDecimal ilm = BigDecimal.valueOf(ilmRaw)
                .setScale(6, RoundingMode.HALF_EVEN);
        if (ilm.compareTo(SA_ILM_FLOOR) < 0) ilm = SA_ILM_FLOOR;
        if (ilm.compareTo(SA_ILM_CEILING) > 0) ilm = SA_ILM_CEILING;

        Money capital = bic.times(ilm);
        Money rwa = capital.times(MARKET_RWA_MULTIPLIER);
        return new OperationalRwaReport(Approach.STANDARDISED_APPROACH_2023, capital, rwa);
    }

    /** Compute Market RWA via the supervisory standardised approach (simplified). */
    public MarketRwaReport computeMarketRwa(List<MarketRiskPosition> positions, CurrencyCode currency) {
        Objects.requireNonNull(positions, "positions");
        Money totalCharge = Money.zero(currency);
        Map<MarketRiskBucket, Money> byBucket = new EnumMap<>(MarketRiskBucket.class);
        for (MarketRiskPosition p : positions) {
            Money charge = p.grossPosition().abs().times(p.bucket().capitalChargeRate);
            totalCharge = totalCharge.plus(charge);
            byBucket.merge(p.bucket(), charge, Money::plus);
        }
        Money rwa = totalCharge.times(MARKET_RWA_MULTIPLIER);
        return new MarketRwaReport(totalCharge, rwa, byBucket);
    }

    /** Roll credit/operational/market RWA into the total reported on the call report. */
    public TotalRwaReport totalRwa(CreditRwaReport credit,
                                   OperationalRwaReport operational,
                                   MarketRwaReport market) {
        CurrencyCode ccy = credit.totalRwa().currency();
        Money total = credit.totalRwa().plus(operational.rwa()).plus(market.totalRwa());
        return new TotalRwaReport(credit.totalRwa(), operational.rwa(), market.totalRwa(), total);
    }

    /**
     * Convenience breakdown: category RWA as a share of total. Useful for
     * the "top risk concentrations" block in the call-report footnote.
     */
    public Map<CreditRiskCategory, BigDecimal> creditMix(CreditRwaReport report) {
        Map<CreditRiskCategory, BigDecimal> out = new LinkedHashMap<>();
        if (report.totalRwa().isZero()) return out;
        for (Map.Entry<CreditRiskCategory, Money> e : report.rwaByCategory().entrySet()) {
            BigDecimal share = e.getValue().amount()
                    .divide(report.totalRwa().amount(), 6, RoundingMode.HALF_EVEN);
            out.put(e.getKey(), share);
        }
        return out;
    }

    /** Choose a supervisory risk weight for a residential mortgage given LTV. */
    public static CreditRiskCategory mortgageCategoryForLtv(BigDecimal ltvPct) {
        Objects.requireNonNull(ltvPct, "ltvPct");
        if (ltvPct.compareTo(new BigDecimal("80")) < 0)
            return CreditRiskCategory.RESIDENTIAL_MORTGAGE_LTV_LT_80;
        if (ltvPct.compareTo(new BigDecimal("90")) <= 0)
            return CreditRiskCategory.RESIDENTIAL_MORTGAGE_LTV_80_90;
        return CreditRiskCategory.RESIDENTIAL_MORTGAGE_LTV_GT_90;
    }

    /** Compute EAD for an exposure: drawn + undrawn × CCF. */
    public static Money effectiveExposureAtDefault(CreditExposure ex) {
        Money undrawn = ex.undrawnCommitment().times(ex.conversionFactor());
        return ex.drawnBalance().plus(undrawn);
    }

    /**
     * Categorise unrated corporate exposures by a 4-tier score used for
     * batch RWA pipelines where instrument-level ratings aren't available.
     */
    public static CreditRiskCategory corporateCategoryForRating(String externalRating) {
        if (externalRating == null) return CreditRiskCategory.CORPORATE_BBB;
        String r = externalRating.trim().toUpperCase();
        if (r.startsWith("AAA") || r.startsWith("AA")) return CreditRiskCategory.CORPORATE_AAA_AA;
        if (r.startsWith("A")) return CreditRiskCategory.CORPORATE_A;
        if (r.startsWith("BBB")) return CreditRiskCategory.CORPORATE_BBB;
        return CreditRiskCategory.CORPORATE_SPECULATIVE;
    }

    /**
     * Very small sanity helper: do the reported component sums line up?
     * Catches copy-paste bugs in call-report footnotes.
     */
    public static boolean isInternallyConsistent(TotalRwaReport r) {
        Money sum = r.creditRwa().plus(r.operationalRwa()).plus(r.marketRwa());
        return sum.compareTo(r.totalRwa()) == 0;
    }

    /** Produce a flat audit list of RWA lines. Useful for a call-report drill-down. */
    public List<RwaLine> asAuditLines(CreditRwaReport credit,
                                      OperationalRwaReport operational,
                                      MarketRwaReport market) {
        List<RwaLine> out = new ArrayList<>();
        for (Map.Entry<CreditRiskCategory, Money> e : credit.rwaByCategory().entrySet()) {
            out.add(new RwaLine("CREDIT:" + e.getKey().name(), e.getValue()));
        }
        out.add(new RwaLine("OPERATIONAL:" + operational.approach().name(), operational.rwa()));
        for (Map.Entry<MarketRiskBucket, Money> e : market.chargeByBucket().entrySet()) {
            out.add(new RwaLine("MARKET:" + e.getKey().name(),
                    e.getValue().times(MARKET_RWA_MULTIPLIER)));
        }
        return out;
    }

    public record RwaLine(String lineLabel, Money amount) {}
}
