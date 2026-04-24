package com.omnibank.swift.mt754;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Parsed representation of a MT754 — Advice of Payment/Acceptance/Negotiation.
 */
public record Mt754Message(
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

    public Mt754Message {
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
        return "MT754";
    }

    public boolean isPayment() {
        return false;
    }

    public boolean isStatement() {
        return false;
    }

    public boolean requiresSettlement() {
        return true;
    }
}
