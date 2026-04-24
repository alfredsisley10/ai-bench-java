package com.omnibank.shared.testing;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

/**
 * Curated money fixtures for tests. Using named fixtures instead of magic
 * numbers makes test intent readable (and lets refactors change the value
 * everywhere at once).
 */
public final class MoneyFixtures {

    public static final Money ZERO_USD = Money.zero(CurrencyCode.USD);
    public static final Money ONE_USD = Money.of(1, CurrencyCode.USD);
    public static final Money HUNDRED_USD = Money.of(100, CurrencyCode.USD);
    public static final Money THOUSAND_USD = Money.of(1_000, CurrencyCode.USD);
    public static final Money MILLION_USD = Money.of(1_000_000, CurrencyCode.USD);

    public static final Money HUNDRED_EUR = Money.of(100, CurrencyCode.EUR);
    public static final Money HUNDRED_JPY = Money.of(100, CurrencyCode.JPY);

    private MoneyFixtures() {}
}
