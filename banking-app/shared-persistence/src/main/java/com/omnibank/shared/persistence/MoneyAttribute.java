package com.omnibank.shared.persistence;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;

/**
 * Two-column embeddable for {@link Money}: amount + currency. Every monetary
 * column in the schema uses this pattern — canonical, debuggable, joinable.
 */
@Embeddable
public class MoneyAttribute {

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3)
    private CurrencyCode currency;

    protected MoneyAttribute() {
        // JPA
    }

    public MoneyAttribute(Money money) {
        this.amount = money.amount();
        this.currency = money.currency();
    }

    public Money toMoney() {
        return Money.of(amount, currency);
    }

    public BigDecimal amount() { return amount; }
    public CurrencyCode currency() { return currency; }
}
