package com.omnibank.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Money is the fundamental numeric primitive of the bank. Never use raw
 * {@link BigDecimal} in business logic — wrap it here so scale and currency
 * discipline are enforced at the type level.
 *
 * <p>Invariants:
 * <ul>
 *   <li>amount is always scaled to the currency's {@link CurrencyCode#minorUnits()}</li>
 *   <li>rounding uses {@link RoundingMode#HALF_EVEN} (banker's rounding) at the
 *       point of arithmetic, never truncation</li>
 *   <li>operations between different currencies fail fast with
 *       {@link CurrencyMismatchException}</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        this.currency = currency;
        this.amount = amount.setScale(currency.minorUnits(), ROUNDING);
    }

    public static Money of(BigDecimal amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return new Money(amount, currency);
    }

    public static Money of(long major, CurrencyCode currency) {
        return of(BigDecimal.valueOf(major), currency);
    }

    public static Money of(String amount, CurrencyCode currency) {
        return of(new BigDecimal(amount), currency);
    }

    public static Money zero(CurrencyCode currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return of(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return of(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return of(amount.negate(), currency);
    }

    public Money abs() {
        return of(amount.abs(), currency);
    }

    public Money times(BigDecimal factor) {
        return of(amount.multiply(factor), currency);
    }

    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    public Money dividedBy(BigDecimal divisor) {
        // Explicit scale here to survive non-terminating division (e.g. /3) —
        // classic silent-crash spot in junior money math.
        return of(amount.divide(divisor, currency.minorUnits() + 4, ROUNDING), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return currency == m.currency && amount.compareTo(m.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return currency.iso4217() + " " + amount.toPlainString();
    }
}
