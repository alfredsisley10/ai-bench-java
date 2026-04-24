package com.omnibank.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * FX conversion rate from base → quote. "EUR → USD = 1.08" means one EUR
 * converts to 1.08 USD. Timestamped so downstream callers record which rate
 * snapshot they priced against.
 */
public record ExchangeRate(CurrencyCode base, CurrencyCode quote, BigDecimal rate, Instant asOf) {

    public ExchangeRate {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(asOf, "asOf");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate must be positive: " + rate);
        }
        if (base == quote) {
            throw new IllegalArgumentException("Base and quote must differ");
        }
    }

    public Money convert(Money source) {
        if (source.currency() != base) {
            throw new CurrencyMismatchException(base, source.currency());
        }
        BigDecimal converted = source.amount().multiply(rate);
        return Money.of(converted.setScale(quote.minorUnits(), RoundingMode.HALF_EVEN), quote);
    }

    public ExchangeRate inverse(Instant asOf) {
        BigDecimal inv = BigDecimal.ONE.divide(rate, 12, RoundingMode.HALF_EVEN);
        return new ExchangeRate(quote, base, inv, asOf);
    }
}
