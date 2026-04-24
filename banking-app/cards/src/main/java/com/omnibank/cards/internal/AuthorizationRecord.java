package com.omnibank.cards.internal;

import com.omnibank.cards.api.AuthorizationDecision;
import com.omnibank.shared.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Historical record of a completed authorization attempt. Velocity and
 * geographic checks consult these records; settlement reconciles against
 * them; fraud investigations replay them.
 */
public record AuthorizationRecord(
        UUID authorizationId,
        UUID cardId,
        Money amount,
        String merchantCategoryCode,
        String merchantCountry,
        AuthorizationDecision decision,
        Instant decidedAt,
        int riskScore
) {

    public AuthorizationRecord {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(merchantCategoryCode, "merchantCategoryCode");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public boolean approved() {
        return decision.approved();
    }
}
