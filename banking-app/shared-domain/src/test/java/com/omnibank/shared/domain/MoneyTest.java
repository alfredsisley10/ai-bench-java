package com.omnibank.shared.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void arithmetic_preserves_currency_scale() {
        Money a = Money.of("12.345", CurrencyCode.USD);
        // HALF_EVEN: 12.345 → 12.34
        assertThat(a.amount()).isEqualByComparingTo("12.34");
    }

    @Test
    void addition_requires_same_currency() {
        Money usd = Money.of(100, CurrencyCode.USD);
        Money eur = Money.of(100, CurrencyCode.EUR);
        assertThatThrownBy(() -> usd.plus(eur))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void jpy_has_zero_minor_units() {
        Money a = Money.of("123.67", CurrencyCode.JPY);
        assertThat(a.amount()).isEqualByComparingTo("124");
    }

    @Test
    void division_does_not_throw_on_non_terminating() {
        Money third = Money.of(100, CurrencyCode.USD).dividedBy(new BigDecimal("3"));
        assertThat(third.amount().toPlainString()).startsWith("33.33");
    }
}
