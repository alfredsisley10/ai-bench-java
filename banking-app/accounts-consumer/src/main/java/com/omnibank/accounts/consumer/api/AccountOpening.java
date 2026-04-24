package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import java.util.Objects;
import java.util.Optional;

public final class AccountOpening {

    public record Request(
            CustomerId customer,
            ConsumerProduct product,
            CurrencyCode currency,
            Optional<Money> initialDeposit
    ) {
        public Request {
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(initialDeposit, "initialDeposit");
            initialDeposit.ifPresent(m -> {
                if (m.currency() != currency) {
                    throw new IllegalArgumentException("Initial deposit currency mismatch");
                }
                if (m.isNegative()) {
                    throw new IllegalArgumentException("Initial deposit cannot be negative");
                }
            });
        }
    }

    private AccountOpening() {}
}
