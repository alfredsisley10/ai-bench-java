package com.omnibank.swift.mt103;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Parsed representation of a MT103 — Single Customer Credit Transfer.
 */
public record Mt103Message(
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

    public Mt103Message {
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
        return "MT103";
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
