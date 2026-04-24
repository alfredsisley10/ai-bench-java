package com.omnibank.cards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque reference to a card in the token vault. Services outside the
 * tokenization boundary must never handle the raw PAN — they pass a
 * CardToken and let the tokenization service dereference it at the
 * moment of network submission.
 */
public record CardToken(UUID value, CardNetwork network, String last4) {

    public CardToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(network, "network");
        if (last4 == null || last4.length() != 4 || !last4.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("last4 must be 4 digits");
        }
    }

    public String maskedDisplay() {
        return "**** **** **** " + last4;
    }
}
