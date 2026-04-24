package com.omnibank.swift.mt101;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Parsed representation of a MT101 — Request for Transfer.
 */
public record Mt101Message(
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

    public Mt101Message {
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
        return "MT101";
    }

    public boolean isPayment() {
        return true;
    }

    public boolean isStatement() {
        return false;
    }

    public boolean requiresSettlement() {
        return true;
    }
}
