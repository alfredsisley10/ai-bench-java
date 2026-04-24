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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Large-exposure and concentration-limit enforcer. Keeps live running totals
 * by obligor, sector, and country, and emits domain events whenever an
 * incoming booking would breach a limit.
 *
 * <p>Three bespoke limit types are modelled:
 *
 * <ul>
 *   <li><b>Single-obligor limit</b> — total aggregate exposure to any one
 *       counterparty must not exceed the bank's policy cap (a percentage of
 *       Tier 1 capital).</li>
 *   <li><b>Sector concentration</b> — aggregate exposure to any NAICS sector
 *       (technology, real estate, energy …) capped at an internal share.</li>
 *   <li><b>Country / sovereign</b> — exposure to obligors domiciled in a
 *       given country capped at an internal share.</li>
 * </ul>
 *
 * <p>A separate <b>large-exposure register</b> tracks any obligor whose
 * exposure exceeds 10% of Tier 1 capital — the threshold the Basel
 * large-exposures framework defines. The sum of these must stay under the
 * regulatory 25% of Tier 1 cap for any single obligor.
 */
public class ExposureLimitEnforcer {

    private static final Logger log = LoggerFactory.getLogger(ExposureLimitEnforcer.class);

    /** Basel large-exposure regulatory threshold (share of Tier 1). */
    public static final BigDecimal LARGE_EXPOSURE_THRESHOLD_PCT = new BigDecimal("10.0");

    /** Regulatory hard cap on aggregate exposure to a single obligor. */
    public static final BigDecimal SINGLE_OBLIGOR_HARD_CAP_PCT = new BigDecimal("25.0");

    public enum LimitType { SINGLE_OBLIGOR, SECTOR, COUNTRY, LARGE_EXPOSURE }

    public enum Severity { INFO, WARNING, BREACH }

    /** Configured policy for a scope (obligor/sector/country). */
    public record LimitPolicy(LimitType type, String scopeKey, Money hardLimit) {
        public LimitPolicy {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(scopeKey, "scopeKey");
            Objects.requireNonNull(hardLimit, "hardLimit");
        }
    }

    public record ExposureChange(
            String changeId,
            CustomerId obligor,
            String obligorName,
            String sectorCode,    // NAICS-3 e.g. "541"
            String countryCode,   // ISO-3166 alpha-2
            Money delta
    ) {
        public ExposureChange {
            Objects.requireNonNull(changeId, "changeId");
            Objects.requireNonNull(obligor, "obligor");
            Objects.requireNonNull(delta, "delta");
        }
    }

    public record LimitBreach(
            UUID breachId,
            LimitType limitType,
            String scopeKey,
            Money current,
            Money limit,
            Severity severity,
            Instant detectedAt
    ) {}

    public record ExposureLimitBreachEvent(
            UUID eventId, Instant occurredAt,
            LimitType limitType, String scopeKey,
            Money current, Money limit, Severity severity)
            implements DomainEvent {
        @Override public String eventType() { return "risk.exposure.limit_breach"; }
    }

    private final Clock clock;
    private final EventBus events;
    private final CurrencyCode baseCurrency;

    /** Active Tier-1 capital number used when computing ratios. */
    private volatile Money tier1Capital;

    /** Configured policies by limit type and scope. */
    private final Map<LimitType, Map<String, LimitPolicy>> policies = new ConcurrentHashMap<>();

    /** Live running totals. */
    private final Map<CustomerId, Money> obligorExposure = new ConcurrentHashMap<>();
    private final Map<String, Money> sectorExposure = new ConcurrentHashMap<>();
    private final Map<String, Money> countryExposure = new ConcurrentHashMap<>();

    /** Register of large exposures (>= 10% Tier 1). */
    private final Map<CustomerId, LargeExposureEntry> largeExposures = new ConcurrentHashMap<>();

    public ExposureLimitEnforcer(Clock clock, EventBus events,
                                  CurrencyCode baseCurrency, Money tier1Capital) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
        this.tier1Capital = Objects.requireNonNull(tier1Capital, "tier1Capital");
    }

    /** Install or replace a policy. */
    public void setPolicy(LimitPolicy policy) {
        policies.computeIfAbsent(policy.type(), k -> new ConcurrentHashMap<>())
                .put(policy.scopeKey(), policy);
    }

    /** Refresh the Tier 1 capital figure used for %-of-capital comparisons. */
    public void updateTier1Capital(Money tier1Capital) {
        this.tier1Capital = Objects.requireNonNull(tier1Capital, "tier1Capital");
    }

    /**
     * Apply an exposure change (positive = drawdown, negative = paydown)
     * and emit breach events for any limit that is violated.
     */
    public List<LimitBreach> apply(ExposureChange change) {
        Objects.requireNonNull(change, "change");
        if (!change.delta().currency().equals(baseCurrency)) {
            throw new IllegalArgumentException("currency mismatch with enforcer base");
        }

        Money newObligor = obligorExposure.merge(change.obligor(), change.delta(), Money::plus);
        Money newSector = change.sectorCode() == null
                ? Money.zero(baseCurrency)
                : sectorExposure.merge(change.sectorCode(), change.delta(), Money::plus);
        Money newCountry = change.countryCode() == null
                ? Money.zero(baseCurrency)
                : countryExposure.merge(change.countryCode(), change.delta(), Money::plus);

        List<LimitBreach> breaches = new ArrayList<>();

        checkSingleObligor(change, newObligor).ifPresent(breaches::add);
        if (change.sectorCode() != null) {
            checkSector(change.sectorCode(), newSector).ifPresent(breaches::add);
        }
        if (change.countryCode() != null) {
            checkCountry(change.countryCode(), newCountry).ifPresent(breaches::add);
        }
        checkLargeExposure(change, newObligor).ifPresent(breaches::add);

        updateLargeExposureRegister(change, newObligor);

        for (LimitBreach b : breaches) {
            publish(b);
            log.warn("Exposure limit breach: type={} scope={} current={} limit={} severity={}",
                    b.limitType(), b.scopeKey(), b.current(), b.limit(), b.severity());
        }
        return breaches;
    }

    /** Current obligor exposure snapshot. */
    public Money obligorExposure(CustomerId obligor) {
        return obligorExposure.getOrDefault(obligor, Money.zero(baseCurrency));
    }

    /** Current sector-level exposure. */
    public Money sectorExposure(String sectorCode) {
        return sectorExposure.getOrDefault(sectorCode, Money.zero(baseCurrency));
    }

    /** Current country-level exposure. */
    public Money countryExposure(String countryCode) {
        return countryExposure.getOrDefault(countryCode, Money.zero(baseCurrency));
    }

    /** Snapshot of the current large-exposure register. */
    public Map<CustomerId, LargeExposureEntry> largeExposures() {
        return Map.copyOf(largeExposures);
    }

    /** Aggregate large-exposure total as a share of Tier 1. */
    public BigDecimal aggregateLargeExposurePct() {
        Money sum = Money.zero(baseCurrency);
        for (LargeExposureEntry e : largeExposures.values()) sum = sum.plus(e.amount());
        return asSharePct(sum);
    }

    /* ---------- Individual limit checks ---------- */

    private Optional<LimitBreach> checkSingleObligor(ExposureChange change, Money current) {
        LimitPolicy explicit = lookupPolicy(LimitType.SINGLE_OBLIGOR, change.obligor().value().toString());
        BigDecimal sharePct = asSharePct(current);
        if (explicit != null && current.compareTo(explicit.hardLimit()) > 0) {
            return Optional.of(breach(LimitType.SINGLE_OBLIGOR,
                    "OBLIGOR:" + change.obligor(), current, explicit.hardLimit(),
                    Severity.BREACH));
        }
        if (sharePct.compareTo(SINGLE_OBLIGOR_HARD_CAP_PCT) > 0) {
            Money cap = capAsMoney(SINGLE_OBLIGOR_HARD_CAP_PCT);
            return Optional.of(breach(LimitType.SINGLE_OBLIGOR,
                    "OBLIGOR:" + change.obligor(), current, cap, Severity.BREACH));
        }
        return Optional.empty();
    }

    private Optional<LimitBreach> checkSector(String sectorCode, Money current) {
        LimitPolicy p = lookupPolicy(LimitType.SECTOR, sectorCode);
        if (p != null && current.compareTo(p.hardLimit()) > 0) {
            Severity sev = overBy(current, p.hardLimit()).compareTo(new BigDecimal("1.10")) > 0
                    ? Severity.BREACH : Severity.WARNING;
            return Optional.of(breach(LimitType.SECTOR, "SECTOR:" + sectorCode,
                    current, p.hardLimit(), sev));
        }
        return Optional.empty();
    }

    private Optional<LimitBreach> checkCountry(String countryCode, Money current) {
        LimitPolicy p = lookupPolicy(LimitType.COUNTRY, countryCode.toUpperCase(Locale.ROOT));
        if (p != null && current.compareTo(p.hardLimit()) > 0) {
            Severity sev = overBy(current, p.hardLimit()).compareTo(new BigDecimal("1.10")) > 0
                    ? Severity.BREACH : Severity.WARNING;
            return Optional.of(breach(LimitType.COUNTRY, "COUNTRY:" + countryCode,
                    current, p.hardLimit(), sev));
        }
        return Optional.empty();
    }

    private Optional<LimitBreach> checkLargeExposure(ExposureChange change, Money current) {
        BigDecimal share = asSharePct(current);
        if (share.compareTo(LARGE_EXPOSURE_THRESHOLD_PCT) >= 0
                && share.compareTo(SINGLE_OBLIGOR_HARD_CAP_PCT) < 0) {
            return Optional.of(breach(LimitType.LARGE_EXPOSURE,
                    "OBLIGOR:" + change.obligor(), current,
                    capAsMoney(LARGE_EXPOSURE_THRESHOLD_PCT), Severity.INFO));
        }
        return Optional.empty();
    }

    /* ---------- Large-exposure register maintenance ---------- */

    private void updateLargeExposureRegister(ExposureChange change, Money current) {
        BigDecimal share = asSharePct(current);
        if (share.compareTo(LARGE_EXPOSURE_THRESHOLD_PCT) >= 0) {
            largeExposures.put(change.obligor(), new LargeExposureEntry(
                    change.obligor(), change.obligorName(), current, share,
                    Timestamp.now(clock)));
        } else {
            largeExposures.remove(change.obligor());
        }
    }

    /* ---------- Helpers ---------- */

    private LimitBreach breach(LimitType type, String scope, Money current,
                                 Money limit, Severity severity) {
        return new LimitBreach(UUID.randomUUID(), type, scope,
                current, limit, severity, Timestamp.now(clock));
    }

    private LimitPolicy lookupPolicy(LimitType type, String scopeKey) {
        Map<String, LimitPolicy> forType = policies.get(type);
        if (forType == null) return null;
        return forType.get(scopeKey);
    }

    private BigDecimal asSharePct(Money amount) {
        if (tier1Capital.isZero()) return BigDecimal.ZERO;
        return amount.amount()
                .divide(tier1Capital.amount(), 6, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private Money capAsMoney(BigDecimal sharePct) {
        BigDecimal fraction = sharePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_EVEN);
        return tier1Capital.times(fraction);
    }

    private static BigDecimal overBy(Money current, Money limit) {
        if (limit.isZero()) return BigDecimal.TEN;
        return current.amount().divide(limit.amount(), 4, RoundingMode.HALF_EVEN);
    }

    private void publish(LimitBreach b) {
        try {
            events.publish(new ExposureLimitBreachEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    b.limitType(), b.scopeKey(), b.current(), b.limit(), b.severity()));
        } catch (Exception e) {
            log.error("Failed to publish exposure breach event: {}", e.getMessage());
        }
    }

    /** One row in the large-exposure register. */
    public record LargeExposureEntry(
            CustomerId obligor,
            String obligorName,
            Money amount,
            BigDecimal sharePct,
            Instant observedAt
    ) {}
}
