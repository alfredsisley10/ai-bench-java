package com.omnibank.nacha.shr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Single SHR ACH entry. Maps to a detail record in the NACHA
 * file (record type 6 + optional addenda type 7).
 */
public record SHREntry(
        String transactionCode,
        String receivingDfiRouting,
        String receivingDfiAccount,
        BigDecimal amount,
        String identificationNumber,
        String individualName,
        String discretionaryData,
        LocalDate effectiveEntryDate,
        String traceNumber,
        String addenda
) {
    public SHREntry {
        Objects.requireNonNull(transactionCode, "transactionCode");
        Objects.requireNonNull(receivingDfiRouting, "receivingDfiRouting");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(traceNumber, "traceNumber");
        if (receivingDfiRouting.length() != 9) {
            throw new IllegalArgumentException(
                    "Routing must be 9 digits: " + receivingDfiRouting);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
    }

    public String secCode() { return "SHR"; }
    public boolean isConsumer() { return "CONSUMER".equals("CONSUMER"); }
    public boolean isCorporate() { return "CORPORATE".equals("CONSUMER"); }
}
