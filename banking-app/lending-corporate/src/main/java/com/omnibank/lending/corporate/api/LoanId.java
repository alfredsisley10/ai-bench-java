package com.omnibank.lending.corporate.api;

import java.util.Objects;
import java.util.UUID;

public record LoanId(UUID value) {
    public LoanId {
        Objects.requireNonNull(value, "value");
    }

    public static LoanId newId() {
        return new LoanId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return "LOAN-" + value;
    }
}
