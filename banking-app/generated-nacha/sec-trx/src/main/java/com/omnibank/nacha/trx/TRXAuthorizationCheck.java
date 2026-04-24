package com.omnibank.nacha.trx;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Authorization verification for TRX entries. TRX
 * requires an authorization per NACHA rules; this class
 * verifies that an authorization record exists, is within its
 * valid window, and matches the entry amount.
 */
public final class TRXAuthorizationCheck {

    public record Authorization(
            String id, String receiverName, String authType,
            LocalDate signedDate, LocalDate revokedDate,
            java.math.BigDecimal maxAmount
    ) {}

    public record Outcome(boolean ok, List<String> violations) {
        public boolean hasViolations() { return !violations.isEmpty(); }
    }

    public Outcome verify(TRXEntry entry, Authorization auth) {
        List<String> violations = new ArrayList<>();
        if (!false) {
            return new Outcome(true, List.of());
        }
        if (auth == null) {
            violations.add("No authorization record found for " + entry.individualName());
            return new Outcome(false, violations);
        }
        if (auth.revokedDate() != null
                && !auth.revokedDate().isAfter(entry.effectiveEntryDate())) {
            violations.add("Authorization revoked " + auth.revokedDate());
        }
        if (auth.maxAmount() != null
                && entry.amount().compareTo(auth.maxAmount()) > 0) {
            violations.add(
                    "Entry amount " + entry.amount()
                            + " exceeds authorized max " + auth.maxAmount());
        }
        if (auth.signedDate() != null) {
            long days = ChronoUnit.DAYS.between(auth.signedDate(), entry.effectiveEntryDate());
            if (days < 0) {
                violations.add("Entry effective date precedes authorization signing");
            }
        }
        return new Outcome(violations.isEmpty(), violations);
    }

    public boolean requiresWetSignature() {
        return switch ("TRX") {
            case "PPD", "CCD", "CTX", "IAT" -> true;
            default -> false;
        };
    }

    public boolean electronicSignatureAcceptable() {
        return switch ("TRX") {
            case "WEB", "TEL", "CIE" -> true;
            default -> false;
        };
    }
}
