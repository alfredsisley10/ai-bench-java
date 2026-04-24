package com.omnibank.cards.internal;

import com.omnibank.shared.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inbound authorization message from the card network. Mirrors the minimum
 * set of ISO 8583 fields needed for decisioning. Does not carry the PAN —
 * the network routes by BIN and we join by {@link #cardId}.
 */
public record AuthorizationRequest(
        UUID cardId,
        Money amount,
        String merchantCategoryCode,
        String merchantName,
        String merchantCountry,
        boolean cardPresent,
        boolean chipUsed,
        boolean pinEntered,
        boolean contactless,
        boolean ecommerce,
        boolean recurring,
        String deviceFingerprint,
        String acquirerId,
        String authorizationCode,
        Instant timestamp
) {
    public AuthorizationRequest {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(merchantCategoryCode, "merchantCategoryCode");
        Objects.requireNonNull(timestamp, "timestamp");
        if (!merchantCategoryCode.matches("\\d{4}")) {
            throw new IllegalArgumentException(
                    "merchantCategoryCode must be a 4-digit MCC: " + merchantCategoryCode);
        }
    }
}
