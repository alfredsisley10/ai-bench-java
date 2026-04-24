package com.omnibank.swift.mt720;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Parsed representation of a MT720 — Transfer of a Documentary Credit.
 */
public record Mt720Message(
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

    public Mt720Message {
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
        return "MT720";
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
