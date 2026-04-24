package com.omnibank.shared.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Generic counterparty identifier. Covers external parties (correspondent
 * banks, vendors, government agencies) that are not Omnibank customers but
 * participate in flows — e.g. a Fed beneficiary bank on a wire.
 */
public record PartyId(UUID value) {

    public PartyId {
        Objects.requireNonNull(value, "value");
    }

    public static PartyId newId() {
        return new PartyId(UUID.randomUUID());
    }

    public static PartyId of(String raw) {
        return new PartyId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return "PID-" + value;
    }
}
