package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.DayCountConvention;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import com.omnibank.shared.domain.Tenor;

import java.time.LocalDate;
import java.util.Objects;

public record LoanTerms(
        LoanStructure structure,
        Money principal,
        Percent rate,
        DayCountConvention dayCount,
        Tenor maturity,
        LocalDate originationDate,
        PaymentFrequency paymentFrequency,
        CurrencyCode currency
) {

    public LoanTerms {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(maturity, "maturity");
        Objects.requireNonNull(originationDate, "originationDate");
        Objects.requireNonNull(paymentFrequency, "paymentFrequency");
        Objects.requireNonNull(currency, "currency");
        if (principal.currency() != currency) {
            throw new IllegalArgumentException("Principal currency must match terms currency");
        }
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("Principal must be positive");
        }
    }

    public LocalDate maturityDate() {
        return maturity.applyTo(originationDate);
    }
}
