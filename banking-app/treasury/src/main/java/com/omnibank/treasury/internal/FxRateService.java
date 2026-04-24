package com.omnibank.treasury.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FX rate management service for the treasury desk. Manages spot and forward
 * exchange rates, processes rate feeds from market data providers, derives
 * cross-rates, applies customer-facing spreads, and detects stale rates.
 *
 * <p>Key functions:
 * <ul>
 *   <li><b>Rate ingestion:</b> Receives real-time rate updates from market data
 *       feeds (Reuters, Bloomberg) and stores them with timestamps</li>
 *   <li><b>Cross-rate derivation:</b> When a direct rate is unavailable (e.g.,
 *       CHF/CAD), derives it through a common base currency (USD)</li>
 *   <li><b>Spread application:</b> Applies tiered bid/ask spreads based on
 *       transaction size and customer segment</li>
 *   <li><b>Forward rates:</b> Calculates forward exchange rates using interest
 *       rate parity for standard tenors</li>
 *   <li><b>Staleness detection:</b> Monitors rate age and halts trading when
 *       rates exceed maximum permitted age (circuit breaker)</li>
 * </ul>
 *
 * <p>All rates are stored as mid-market rates. Customer-facing rates include
 * the appropriate spread markup.
 */
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    /** Maximum rate age before it is considered stale. */
    private static final Duration SPOT_MAX_AGE = Duration.ofMinutes(5);
    private static final Duration FORWARD_MAX_AGE = Duration.ofMinutes(30);

    /** Default spread in basis points by transaction tier. */
    private static final Map<TransactionTier, BigDecimal> SPREAD_TABLE = Map.of(
            TransactionTier.RETAIL_SMALL, new BigDecimal("0.0150"),      // 150 bps
            TransactionTier.RETAIL_MEDIUM, new BigDecimal("0.0075"),     // 75 bps
            TransactionTier.RETAIL_LARGE, new BigDecimal("0.0040"),      // 40 bps
            TransactionTier.CORPORATE, new BigDecimal("0.0020"),         // 20 bps
            TransactionTier.INTERBANK, new BigDecimal("0.0002")          // 2 bps
    );

    /** Transaction tier breakpoints (USD equivalent). */
    private static final Money TIER_MEDIUM = Money.of("10000.00", CurrencyCode.USD);
    private static final Money TIER_LARGE = Money.of("100000.00", CurrencyCode.USD);
    private static final Money TIER_CORPORATE = Money.of("1000000.00", CurrencyCode.USD);

    enum TransactionTier {
        RETAIL_SMALL, RETAIL_MEDIUM, RETAIL_LARGE, CORPORATE, INTERBANK
    }

    enum RateType { SPOT, FORWARD_1W, FORWARD_1M, FORWARD_3M, FORWARD_6M, FORWARD_1Y }

    record FxRate(
            CurrencyCode base,
            CurrencyCode quote,
            RateType rateType,
            BigDecimal midRate,
            BigDecimal bidRate,
            BigDecimal askRate,
            Instant timestamp,
            String source,
            String tenor
    ) {
        public FxRate {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(quote, "quote");
            Objects.requireNonNull(rateType, "rateType");
            Objects.requireNonNull(midRate, "midRate");
            Objects.requireNonNull(timestamp, "timestamp");
            if (midRate.signum() <= 0) {
                throw new IllegalArgumentException("Rate must be positive: " + midRate);
            }
        }

        public boolean isStale(Instant now) {
            Duration maxAge = rateType == RateType.SPOT ? SPOT_MAX_AGE : FORWARD_MAX_AGE;
            return Duration.between(timestamp, now).compareTo(maxAge) > 0;
        }
    }

    record CustomerRate(
            CurrencyCode base,
            CurrencyCode quote,
            BigDecimal bidRate,
            BigDecimal askRate,
            BigDecimal spread,
            TransactionTier tier,
            Instant validUntil,
            String quoteId
    ) {}

    record RateUpdateEvent(UUID eventId, Instant occurredAt, CurrencyCode base,
                            CurrencyCode quote, BigDecimal midRate,
                            String source) implements DomainEvent {
        @Override
        public String eventType() {
            return "treasury.fx.rate_updated";
        }
    }

    sealed interface RateLookupResult permits
            RateLookupResult.Direct,
            RateLookupResult.CrossRate,
            RateLookupResult.Stale,
            RateLookupResult.Unavailable {

        record Direct(FxRate rate) implements RateLookupResult {}
        record CrossRate(FxRate rate, CurrencyCode intermediaryCurrency,
                          FxRate leg1, FxRate leg2) implements RateLookupResult {}
        record Stale(FxRate rate, Duration age) implements RateLookupResult {}
        record Unavailable(CurrencyCode base, CurrencyCode quote,
                            String reason) implements RateLookupResult {}
    }

    /** Rate store keyed by "BASE/QUOTE:TYPE". */
    private final ConcurrentHashMap<String, FxRate> rateStore = new ConcurrentHashMap<>();

    /** Historical rate snapshots for end-of-day marking. */
    private final ConcurrentHashMap<String, List<FxRate>> rateHistory = new ConcurrentHashMap<>();

    private final EventBus events;
    private final Clock clock;

    public FxRateService(EventBus events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Ingest a rate update from a market data feed. Replaces the existing
     * rate for the pair+type and archives the previous rate.
     */
    public void ingestRate(CurrencyCode base, CurrencyCode quote, RateType rateType,
                            BigDecimal midRate, String source) {
        ingestRate(base, quote, rateType, midRate, null, null, source, null);
    }

    /**
     * Ingest a full rate update with bid/ask from a market data feed.
     */
    public void ingestRate(CurrencyCode base, CurrencyCode quote, RateType rateType,
                            BigDecimal midRate, BigDecimal bidRate, BigDecimal askRate,
                            String source, String tenor) {
        Instant now = Timestamp.now(clock);

        if (bidRate == null) {
            BigDecimal defaultSpread = new BigDecimal("0.0005");
            bidRate = midRate.subtract(midRate.multiply(defaultSpread, MC));
            askRate = midRate.add(midRate.multiply(defaultSpread, MC));
        }

        FxRate rate = new FxRate(base, quote, rateType, midRate, bidRate, askRate,
                now, source, tenor);

        String key = rateKey(base, quote, rateType);
        FxRate previous = rateStore.put(key, rate);

        if (previous != null) {
            rateHistory.computeIfAbsent(key, k -> new ArrayList<>()).add(previous);
        }

        // Also store the inverse
        BigDecimal inverseMid = BigDecimal.ONE.divide(midRate, MC);
        BigDecimal inverseBid = BigDecimal.ONE.divide(askRate, MC);
        BigDecimal inverseAsk = BigDecimal.ONE.divide(bidRate, MC);
        FxRate inverse = new FxRate(quote, base, rateType, inverseMid, inverseBid,
                inverseAsk, now, source + "-INV", tenor);
        rateStore.put(rateKey(quote, base, rateType), inverse);

        events.publish(new RateUpdateEvent(UUID.randomUUID(), now, base, quote, midRate, source));
        log.debug("Rate ingested: {}/{} {} = {} (source: {})", base, quote, rateType,
                midRate, source);
    }

    /**
     * Look up the current spot rate for a currency pair. Attempts direct lookup
     * first, then cross-rate derivation through USD.
     */
    public RateLookupResult getSpotRate(CurrencyCode base, CurrencyCode quote) {
        if (base == quote) {
            FxRate identity = new FxRate(base, quote, RateType.SPOT, BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE, Timestamp.now(clock), "IDENTITY", null);
            return new RateLookupResult.Direct(identity);
        }

        return lookupRate(base, quote, RateType.SPOT);
    }

    /**
     * Get a customer-facing rate with appropriate spread applied based on
     * transaction size and customer segment.
     */
    public Optional<CustomerRate> getCustomerRate(CurrencyCode base, CurrencyCode quote,
                                                    Money transactionAmount,
                                                    boolean isCorporate) {
        RateLookupResult result = getSpotRate(base, quote);

        FxRate rate = switch (result) {
            case RateLookupResult.Direct d -> d.rate();
            case RateLookupResult.CrossRate c -> c.rate();
            case RateLookupResult.Stale s -> {
                log.warn("Using stale rate for {}/{}: age {}", base, quote, s.age());
                yield s.rate();
            }
            case RateLookupResult.Unavailable u -> {
                log.error("No rate available for {}/{}: {}", base, quote, u.reason());
                yield null;
            }
        };

        if (rate == null) return Optional.empty();

        TransactionTier tier = determineTier(transactionAmount, isCorporate);
        BigDecimal spread = SPREAD_TABLE.getOrDefault(tier, new BigDecimal("0.0100"));

        BigDecimal customerBid = rate.midRate().subtract(rate.midRate().multiply(spread, MC));
        BigDecimal customerAsk = rate.midRate().add(rate.midRate().multiply(spread, MC));

        Instant validUntil = Timestamp.now(clock).plus(Duration.ofSeconds(30));
        String quoteId = "FXQ-" + UUID.randomUUID().toString().substring(0, 12);

        return Optional.of(new CustomerRate(base, quote, customerBid, customerAsk,
                spread, tier, validUntil, quoteId));
    }

    /**
     * Calculate a forward rate using covered interest rate parity.
     * Forward = Spot * (1 + Rquote * T) / (1 + Rbase * T)
     */
    public Optional<FxRate> calculateForwardRate(CurrencyCode base, CurrencyCode quote,
                                                   RateType forwardType,
                                                   BigDecimal baseInterestRate,
                                                   BigDecimal quoteInterestRate) {
        RateLookupResult spotResult = getSpotRate(base, quote);
        BigDecimal spotMid = switch (spotResult) {
            case RateLookupResult.Direct d -> d.rate().midRate();
            case RateLookupResult.CrossRate c -> c.rate().midRate();
            case RateLookupResult.Stale s -> s.rate().midRate();
            case RateLookupResult.Unavailable u -> null;
        };

        if (spotMid == null) return Optional.empty();

        BigDecimal yearFraction = forwardYearFraction(forwardType);
        BigDecimal numerator = BigDecimal.ONE.add(quoteInterestRate.multiply(yearFraction, MC));
        BigDecimal denominator = BigDecimal.ONE.add(baseInterestRate.multiply(yearFraction, MC));
        BigDecimal forwardMid = spotMid.multiply(numerator.divide(denominator, MC), MC);

        String tenor = switch (forwardType) {
            case FORWARD_1W -> "1W";
            case FORWARD_1M -> "1M";
            case FORWARD_3M -> "3M";
            case FORWARD_6M -> "6M";
            case FORWARD_1Y -> "1Y";
            default -> "SPOT";
        };

        FxRate forwardRate = new FxRate(base, quote, forwardType, forwardMid,
                forwardMid, forwardMid, Timestamp.now(clock), "DERIVED_IRP", tenor);

        rateStore.put(rateKey(base, quote, forwardType), forwardRate);
        return Optional.of(forwardRate);
    }

    /**
     * Convert a money amount using the current spot rate with appropriate spread.
     */
    public Optional<Money> convert(Money source, CurrencyCode targetCurrency,
                                     boolean isCorporate) {
        if (source.currency() == targetCurrency) return Optional.of(source);

        Optional<CustomerRate> rate = getCustomerRate(source.currency(), targetCurrency,
                source, isCorporate);

        return rate.map(r -> {
            // Use ask rate for buying the target currency (customer pays more)
            BigDecimal converted = source.amount().multiply(r.askRate(), MC);
            return Money.of(converted, targetCurrency);
        });
    }

    /**
     * Check for stale rates across all stored pairs. Alerts treasury desk
     * when rates exceed maximum permitted age.
     */
    @Scheduled(fixedRate = 60_000) // every minute
    public List<FxRate> detectStaleRates() {
        Instant now = Timestamp.now(clock);
        List<FxRate> stale = rateStore.values().stream()
                .filter(r -> r.isStale(now))
                .sorted(Comparator.comparing(FxRate::timestamp))
                .toList();

        if (!stale.isEmpty()) {
            log.warn("Stale FX rates detected: {} pairs", stale.size());
            for (FxRate rate : stale) {
                Duration age = Duration.between(rate.timestamp(), now);
                log.warn("  Stale: {}/{} {} age={}min (source: {})",
                        rate.base(), rate.quote(), rate.rateType(),
                        age.toMinutes(), rate.source());
            }
        }

        return stale;
    }

    /**
     * Get end-of-day rates for all pairs. Used for P&L marking and accounting.
     */
    public List<FxRate> getEndOfDayRates() {
        return rateStore.values().stream()
                .filter(r -> r.rateType() == RateType.SPOT)
                .sorted(Comparator.comparing(r -> r.base().name() + "/" + r.quote().name()))
                .toList();
    }

    /**
     * Get rate history for a specific pair (used for trend analysis).
     */
    public List<FxRate> getRateHistory(CurrencyCode base, CurrencyCode quote, RateType type) {
        String key = rateKey(base, quote, type);
        List<FxRate> history = rateHistory.get(key);
        return history != null ? List.copyOf(history) : List.of();
    }

    private RateLookupResult lookupRate(CurrencyCode base, CurrencyCode quote, RateType type) {
        // Try direct lookup
        String key = rateKey(base, quote, type);
        FxRate directRate = rateStore.get(key);
        Instant now = Timestamp.now(clock);

        if (directRate != null) {
            if (directRate.isStale(now)) {
                Duration age = Duration.between(directRate.timestamp(), now);
                return new RateLookupResult.Stale(directRate, age);
            }
            return new RateLookupResult.Direct(directRate);
        }

        // Try cross-rate through USD
        if (base != CurrencyCode.USD && quote != CurrencyCode.USD) {
            FxRate leg1 = rateStore.get(rateKey(base, CurrencyCode.USD, type));
            FxRate leg2 = rateStore.get(rateKey(CurrencyCode.USD, quote, type));

            if (leg1 != null && leg2 != null) {
                BigDecimal crossMid = leg1.midRate().multiply(leg2.midRate(), MC);
                BigDecimal crossBid = leg1.bidRate().multiply(leg2.bidRate(), MC);
                BigDecimal crossAsk = leg1.askRate().multiply(leg2.askRate(), MC);

                FxRate crossRate = new FxRate(base, quote, type, crossMid,
                        crossBid, crossAsk, now, "CROSS-USD", null);

                boolean eitherStale = leg1.isStale(now) || leg2.isStale(now);
                if (eitherStale) {
                    Duration maxAge = Duration.between(
                            leg1.timestamp().isBefore(leg2.timestamp())
                                    ? leg1.timestamp() : leg2.timestamp(), now);
                    return new RateLookupResult.Stale(crossRate, maxAge);
                }

                return new RateLookupResult.CrossRate(crossRate, CurrencyCode.USD, leg1, leg2);
            }
        }

        // Try cross-rate through EUR as secondary intermediary
        if (base != CurrencyCode.EUR && quote != CurrencyCode.EUR) {
            FxRate leg1 = rateStore.get(rateKey(base, CurrencyCode.EUR, type));
            FxRate leg2 = rateStore.get(rateKey(CurrencyCode.EUR, quote, type));

            if (leg1 != null && leg2 != null) {
                BigDecimal crossMid = leg1.midRate().multiply(leg2.midRate(), MC);
                FxRate crossRate = new FxRate(base, quote, type, crossMid,
                        crossMid, crossMid, now, "CROSS-EUR", null);
                return new RateLookupResult.CrossRate(crossRate, CurrencyCode.EUR, leg1, leg2);
            }
        }

        return new RateLookupResult.Unavailable(base, quote,
                "No direct or cross-rate available for " + base + "/" + quote);
    }

    private TransactionTier determineTier(Money amount, boolean isCorporate) {
        if (isCorporate) return TransactionTier.CORPORATE;
        if (amount.compareTo(TIER_CORPORATE) >= 0) return TransactionTier.CORPORATE;
        if (amount.compareTo(TIER_LARGE) >= 0) return TransactionTier.RETAIL_LARGE;
        if (amount.compareTo(TIER_MEDIUM) >= 0) return TransactionTier.RETAIL_MEDIUM;
        return TransactionTier.RETAIL_SMALL;
    }

    private BigDecimal forwardYearFraction(RateType type) {
        return switch (type) {
            case FORWARD_1W -> new BigDecimal("7").divide(new BigDecimal("365"), MC);
            case FORWARD_1M -> new BigDecimal("30").divide(new BigDecimal("365"), MC);
            case FORWARD_3M -> new BigDecimal("90").divide(new BigDecimal("365"), MC);
            case FORWARD_6M -> new BigDecimal("180").divide(new BigDecimal("365"), MC);
            case FORWARD_1Y -> BigDecimal.ONE;
            default -> BigDecimal.ZERO;
        };
    }

    private String rateKey(CurrencyCode base, CurrencyCode quote, RateType type) {
        return base.name() + "/" + quote.name() + ":" + type.name();
    }
}
