package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Incoming clearing file from a card network. One file carries many
 * presentments; the processor iterates and posts each.
 */
public record ClearingFile(
        String fileId,
        CardNetwork network,
        LocalDate cycleDate,
        List<ClearingRecord> records,
        Instant receivedAt
) {

    public ClearingFile {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(cycleDate, "cycleDate");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(receivedAt, "receivedAt");
        records = List.copyOf(records);
    }

    /**
     * Single presentment line from a clearing file. Amounts are the net
     * amount the issuer must pay (or is due) for this transaction.
     */
    public record ClearingRecord(
            UUID authorizationId,
            UUID cardId,
            Money transactionAmount,
            Money interchangeFee,
            Money networkFee,
            String merchantCategoryCode,
            String merchantCountry,
            boolean reversal,
            boolean purchaseReturn,
            String reasonCode
    ) {
        public ClearingRecord {
            Objects.requireNonNull(authorizationId, "authorizationId");
            Objects.requireNonNull(cardId, "cardId");
            Objects.requireNonNull(transactionAmount, "transactionAmount");
            Objects.requireNonNull(interchangeFee, "interchangeFee");
            Objects.requireNonNull(networkFee, "networkFee");
            Objects.requireNonNull(merchantCategoryCode, "merchantCategoryCode");
        }
    }
}
