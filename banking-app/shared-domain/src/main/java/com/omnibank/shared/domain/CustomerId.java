package com.omnibank.shared.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable opaque identifier for a customer (retail or corporate).
 * Separate from AccountNumber — one customer may hold many accounts.
 */
public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "value");
    }

    public static CustomerId newId() {
        return new CustomerId(UUID.randomUUID());
    }

    public static CustomerId of(String raw) {
        return new CustomerId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return "CID-" + value;
    }
}
