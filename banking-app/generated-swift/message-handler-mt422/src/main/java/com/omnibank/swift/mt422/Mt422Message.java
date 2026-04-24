package com.omnibank.swift.mt422;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Parsed representation of a MT422 — Advice of Fate and Request for Instructions.
 */
public record Mt422Message(
        String senderBic,
        String receiverBic,
        String messageReference,
        String relatedReference,
        LocalDate valueDate,
        String currency,
        BigDecimal amount,
        String orderingCustomer,
        String beneficiary,
        String remittanceInfo,
        List<String> rawTags,
        Instant receivedAt
) {

    public Mt422Message {
        Objects.requireNonNull(senderBic, "senderBic");
        Objects.requireNonNull(receiverBic, "receiverBic");
        Objects.requireNonNull(messageReference, "messageReference");
        if (senderBic.length() != 8 && senderBic.length() != 11) {
            throw new IllegalArgumentException("BIC must be 8 or 11 chars: " + senderBic);
        }
        if (receiverBic.length() != 8 && receiverBic.length() != 11) {
            throw new IllegalArgumentException("BIC must be 8 or 11 chars: " + receiverBic);
        }
        rawTags = rawTags == null ? List.of() : List.copyOf(rawTags);
    }

    public String messageType() {
        return "MT422";
    }

    public boolean isPayment() {
        return false;
    }

    public boolean isStatement() {
        return false;
    }

    public boolean requiresSettlement() {
        return false;
    }
}
