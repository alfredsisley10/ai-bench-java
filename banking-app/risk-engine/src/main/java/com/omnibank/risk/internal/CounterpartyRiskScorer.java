package com.omnibank.risk.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counterparty credit-risk scorer.
 *
 * <p>Computes the three Basel IRB components for a counterparty —
 * <b>PD</b> (probability of default), <b>LGD</b> (loss given default), and
 * <b>EAD</b> (exposure at default) — and multiplies them to produce an
 * <b>Expected Loss</b> figure. The counterparty is then assigned to one of
 * five internal tiers (INSTITUTIONAL_AAA → WATCHLIST) driving pricing,
 * collateral, and credit-committee approval policy.
 *
 * <p>The scorer is deliberately self-contained: inputs are supplied by the
 * caller rather than pulled from sub-ledgers, so unit tests can exercise the
 * pricing math without stitching a full data fabric.
 */
public class CounterpartyRiskScorer {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyRiskScorer.class);

    /** Supported counterparty taxonomies — LGD floor depends on this. */
    public enum CounterpartyType {
        SOVEREIGN,
        BANK,
        LARGE_CORPORATE,
        MID_CORPORATE,
        SMALL_BUSINESS,
        RETAIL,
        SECURITIZATION
    }

    /** Internal grading tiers. */
    public enum Tier {
        INSTITUTIONAL_AAA,
        INVESTMENT_GRADE,
        STANDARD,
        SUB_INVESTMENT,
        WATCHLIST
    }

    /** Input structure aggregating everything the scorer needs. */
    public record CounterpartyProfile(
            CustomerId counterparty,
            String legalName,
            CounterpartyType type,
            String externalRating,       // S&P / Moody's equivalent (nullable)
            int internalRiskGrade,       // 1 (best) .. 10 (default)
            BigDecimal leverageRatio,    // total liabilities / equity
            BigDecimal interestCoverage, // EBIT / interest expense
            BigDecimal collateralValue,  // value of pledged collateral
            Money currentDrawn,
            Money totalCommitment,
            BigDecimal conversionFactor, // CCF for undrawn portion (0..1)
            String countryCode,
            boolean isGuaranteed
    ) {
        public CounterpartyProfile {
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currentDrawn, "currentDrawn");
            Objects.requireNonNull(totalCommitment, "totalCommitment");
            Objects.requireNonNull(conversionFactor, "conversionFactor");
            if (internalRiskGrade < 1 || internalRiskGrade > 10) {
                throw new IllegalArgumentException("internalRiskGrade must be 1..10");
            }
        }
    }

    /** The three IRB risk components + derived figures. */
    public record CounterpartyScore(
            CustomerId counterparty,
            Tier tier,
            BigDecimal pd,              // 0..1
            BigDecimal lgd,             // 0..1
            Money ead,
            Money expectedLoss,
            Money unexpectedLossProxy,
            Instant computedAt
    ) {}

    public record CounterpartyScoredEvent(
            UUID eventId, Instant occurredAt,
            CustomerId counterparty, Tier tier,
            BigDecimal pd, Money expectedLoss)
            implements DomainEvent {
        @Override public String eventType() { return "risk.counterparty.scored"; }
    }

    /** PD by internal risk grade. Monotonically increasing. */
    private static final Map<Integer, BigDecimal> PD_BY_GRADE = Map.ofEntries(
            Map.entry(1, new BigDecimal("0.0005")),
            Map.entry(2, new BigDecimal("0.0010")),
            Map.entry(3, new BigDecimal("0.0025")),
            Map.entry(4, new BigDecimal("0.0050")),
            Map.entry(5, new BigDecimal("0.0100")),
            Map.entry(6, new BigDecimal("0.0200")),
            Map.entry(7, new BigDecimal("0.0400")),
            Map.entry(8, new BigDecimal("0.0800")),
            Map.entry(9, new BigDecimal("0.1500")),
            Map.entry(10, new BigDecimal("1.0000")));

    /** Regulatory LGD floors by counterparty type (IRB). */
    private static final Map<CounterpartyType, BigDecimal> LGD_FLOOR = Map.ofEntries(
            Map.entry(CounterpartyType.SOVEREIGN, new BigDecimal("0.05")),
            Map.entry(CounterpartyType.BANK, new BigDecimal("0.30")),
            Map.entry(CounterpartyType.LARGE_CORPORATE, new BigDecimal("0.30")),
            Map.entry(CounterpartyType.MID_CORPORATE, new BigDecimal("0.35")),
            Map.entry(CounterpartyType.SMALL_BUSINESS, new BigDecimal("0.40")),
            Map.entry(CounterpartyType.RETAIL, new BigDecimal("0.45")),
            Map.entry(CounterpartyType.SECURITIZATION, new BigDecimal("0.50")));

    private final Clock clock;
    private final EventBus events;
    private final CurrencyCode baseCurrency;
    private final Map<CustomerId, CounterpartyScore> scoreCache = new ConcurrentHashMap<>();

    public CounterpartyRiskScorer(Clock clock, EventBus events, CurrencyCode baseCurrency) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
    }

    /** Compute (and cache) a full risk score for the profile. */
    public CounterpartyScore score(CounterpartyProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.currentDrawn().currency().equals(baseCurrency)) {
            throw new IllegalArgumentException("profile currency mismatch with scorer base");
        }

        BigDecimal pd = probabilityOfDefault(profile);
        BigDecimal lgd = lossGivenDefault(profile);
        Money ead = exposureAtDefault(profile);

        Money expectedLoss = ead.times(pd.multiply(lgd).setScale(8, RoundingMode.HALF_EVEN));
        // Simple UL proxy (not full Basel curve): EL × sqrt(1 - PD) × correlation factor
        BigDecimal ulMultiplier = BigDecimal.valueOf(Math.sqrt(Math.max(0.0, 1.0 - pd.doubleValue())))
                .multiply(new BigDecimal("2.50"))
                .setScale(6, RoundingMode.HALF_EVEN);
        Money ul = expectedLoss.times(ulMultiplier);

        Tier tier = assignTier(profile, pd);
        CounterpartyScore score = new CounterpartyScore(profile.counterparty(),
                tier, pd, lgd, ead, expectedLoss, ul, Timestamp.now(clock));
        scoreCache.put(profile.counterparty(), score);

        events.publish(new CounterpartyScoredEvent(
                UUID.randomUUID(), Timestamp.now(clock),
                profile.counterparty(), tier, pd, expectedLoss));
        log.debug("Counterparty scored: id={} tier={} pd={} EL={}",
                profile.counterparty(), tier, pd, expectedLoss);
        return score;
    }

    /** Previously computed score for a counterparty, if any. */
    public Optional<CounterpartyScore> cachedScore(CustomerId counterparty) {
        return Optional.ofNullable(scoreCache.get(counterparty));
    }

    /* ---------- IRB components ---------- */

    BigDecimal probabilityOfDefault(CounterpartyProfile profile) {
        BigDecimal basePd = PD_BY_GRADE.getOrDefault(profile.internalRiskGrade(),
                new BigDecimal("0.0500"));

        // Leverage-based shock: very high leverage lifts PD meaningfully.
        BigDecimal leverageAdj = BigDecimal.ONE;
        if (profile.leverageRatio() != null) {
            double lev = profile.leverageRatio().doubleValue();
            if (lev > 8.0) leverageAdj = new BigDecimal("1.60");
            else if (lev > 5.0) leverageAdj = new BigDecimal("1.30");
            else if (lev > 3.0) leverageAdj = new BigDecimal("1.10");
            else if (lev < 0.5) leverageAdj = new BigDecimal("0.85");
        }

        // Interest coverage: <1.5× is a solvency red flag.
        BigDecimal coverageAdj = BigDecimal.ONE;
        if (profile.interestCoverage() != null) {
            double cov = profile.interestCoverage().doubleValue();
            if (cov < 1.0) coverageAdj = new BigDecimal("1.80");
            else if (cov < 1.5) coverageAdj = new BigDecimal("1.40");
            else if (cov < 2.5) coverageAdj = new BigDecimal("1.15");
            else if (cov > 8.0) coverageAdj = new BigDecimal("0.80");
        }

        BigDecimal pd = basePd.multiply(leverageAdj).multiply(coverageAdj);
        // Cap at 100% — you cannot exceed certainty.
        if (pd.compareTo(BigDecimal.ONE) > 0) pd = BigDecimal.ONE;
        return pd.setScale(6, RoundingMode.HALF_EVEN);
    }

    BigDecimal lossGivenDefault(CounterpartyProfile profile) {
        BigDecimal floor = LGD_FLOOR.getOrDefault(profile.type(), new BigDecimal("0.40"));

        // Collateral reduces LGD linearly down to the regulatory floor.
        BigDecimal exposure = profile.currentDrawn().amount();
        BigDecimal collateral = profile.collateralValue() == null
                ? BigDecimal.ZERO : profile.collateralValue();
        BigDecimal coverage = exposure.signum() == 0
                ? BigDecimal.ONE
                : collateral.divide(exposure, 6, RoundingMode.HALF_EVEN);
        if (coverage.compareTo(BigDecimal.ONE) > 0) coverage = BigDecimal.ONE;

        BigDecimal unsecuredLgd = new BigDecimal("0.75");
        BigDecimal securedLgd = floor;
        BigDecimal blended = unsecuredLgd.multiply(BigDecimal.ONE.subtract(coverage))
                .add(securedLgd.multiply(coverage))
                .setScale(6, RoundingMode.HALF_EVEN);

        // Guaranteed exposures get a 20% relative reduction (substitution
        // approach — the guarantor's LGD would be applied in a full build).
        if (profile.isGuaranteed()) {
            blended = blended.multiply(new BigDecimal("0.80"))
                    .setScale(6, RoundingMode.HALF_EVEN);
        }

        if (blended.compareTo(floor) < 0) return floor;
        if (blended.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return blended;
    }

    Money exposureAtDefault(CounterpartyProfile profile) {
        Money drawn = profile.currentDrawn();
        Money undrawn = profile.totalCommitment().minus(drawn);
        if (undrawn.isNegative()) undrawn = Money.zero(baseCurrency);
        return drawn.plus(undrawn.times(profile.conversionFactor()));
    }

    Tier assignTier(CounterpartyProfile profile, BigDecimal pd) {
        if (profile.internalRiskGrade() >= 9) return Tier.WATCHLIST;

        double p = pd.doubleValue();
        if (p <= 0.0010 && profile.type() == CounterpartyType.SOVEREIGN) return Tier.INSTITUTIONAL_AAA;
        if (p <= 0.0015) return Tier.INSTITUTIONAL_AAA;
        if (p <= 0.0100) return Tier.INVESTMENT_GRADE;
        if (p <= 0.0400) return Tier.STANDARD;
        if (p <= 0.1200) return Tier.SUB_INVESTMENT;
        return Tier.WATCHLIST;
    }

    /** Compact summary for UI cells. */
    public static String formatScore(CounterpartyScore s) {
        return "%s | PD=%.2f%% | LGD=%.1f%% | EAD=%s | EL=%s".formatted(
                s.tier().name(),
                s.pd().doubleValue() * 100,
                s.lgd().doubleValue() * 100,
                s.ead(), s.expectedLoss());
    }
}
