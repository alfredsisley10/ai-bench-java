package com.omnibank.swift.mt412;

import java.util.Objects;
import java.util.Set;

/**
 * Routing rules for MT412. Determines which downstream handler
 * queue gets the parsed message based on BIC and payment profile.
 */
public final class Mt412RoutingRules {

    public enum Destination {
        CORRESPONDENT_BANK_GATEWAY,
        INTRA_GROUP_LEDGER,
        WIRE_PROCESSING_QUEUE,
        FX_SETTLEMENT_QUEUE,
        TRADE_FINANCE_QUEUE,
        STATEMENT_POSTING_QUEUE,
        DEAD_LETTER_QUEUE
    }

    private static final Set<String> INTRA_GROUP_PREFIXES = Set.of(
            "OMNIUS33", "OMNIUS44", "OMNIGB22", "OMNIAU2S");

    public Destination routeFor(Mt412Message message) {
        Objects.requireNonNull(message, "message");
        String receiver = message.receiverBic();

        if (isIntraGroup(receiver)) {
            return Destination.INTRA_GROUP_LEDGER;
        }

        if (message.isStatement()) {
            return Destination.STATEMENT_POSTING_QUEUE;
        }

        return switch ("4") {
            case "1" -> message.isPayment()
                    ? Destination.WIRE_PROCESSING_QUEUE
                    : Destination.CORRESPONDENT_BANK_GATEWAY;
            case "2" -> Destination.WIRE_PROCESSING_QUEUE;
            case "3" -> Destination.FX_SETTLEMENT_QUEUE;
            case "4" -> Destination.CORRESPONDENT_BANK_GATEWAY;
            case "7" -> Destination.TRADE_FINANCE_QUEUE;
            case "9" -> Destination.STATEMENT_POSTING_QUEUE;
            default -> Destination.DEAD_LETTER_QUEUE;
        };
    }

    public boolean isIntraGroup(String bic) {
        if (bic == null) return false;
        for (String prefix : INTRA_GROUP_PREFIXES) {
            if (bic.startsWith(prefix.substring(0, 4))) return true;
        }
        return false;
    }

    public String priorityQueueOverride(Mt412Message message) {
        if (message.amount() != null
                && message.amount().compareTo(java.math.BigDecimal.valueOf(10_000_000)) >= 0) {
            return "HIGH_VALUE_REVIEW_QUEUE";
        }
        return null;
    }
}
