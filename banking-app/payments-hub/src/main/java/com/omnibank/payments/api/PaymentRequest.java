package com.omnibank.payments.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PaymentRequest(
        String idempotencyKey,
        PaymentRail rail,
        AccountNumber originator,
        Optional<RoutingNumber> beneficiaryRouting,
        String beneficiaryAccount,
        String beneficiaryName,
        Money amount,
        String memo,
        Instant requestedAt
) {

    public PaymentRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(originator, "originator");
        Objects.requireNonNull(beneficiaryRouting, "beneficiaryRouting");
        Objects.requireNonNull(beneficiaryAccount, "beneficiaryAccount");
        Objects.requireNonNull(beneficiaryName, "beneficiaryName");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException("Payment amount must be positive: " + amount);
        }
        if (rail != PaymentRail.BOOK && beneficiaryRouting.isEmpty()) {
            throw new IllegalArgumentException("External rail requires routing number: " + rail);
        }
    }
}
