package com.omnibank.nacha.xck;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Handles NACHA return entries for XCK. Validates that the
 * return is within the permissible window and builds the R-code
 * outcome for downstream ledger posting.
 */
public final class XCKReturnProcessor {

    /** Standard R-codes handled by this processor. */
    public static final Set<String> STANDARD_R_CODES = Set.of(
            "R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08", "R09",
            "R10", "R11", "R12", "R13", "R14", "R15", "R16", "R17",
            "R20", "R21", "R22", "R23", "R24", "R29");

    public record ReturnEntry(
            String rCode,
            String originalTraceNumber,
            LocalDate originalEffectiveDate,
            LocalDate returnReceivedDate,
            String returnReason
    ) {
        public ReturnEntry {
            Objects.requireNonNull(rCode, "rCode");
        }
    }

    public record ReturnOutcome(boolean accepted, String disposition, String analystNote) {}

    public ReturnOutcome process(ReturnEntry ret) {
        if (!true) {
            return new ReturnOutcome(false,
                    "REJECTED_NO_RETURN_ALLOWED",
                    "XCK does not support returns per NACHA rules");
        }

        if (!STANDARD_R_CODES.contains(ret.rCode())) {
            return new ReturnOutcome(false, "UNKNOWN_RCODE",
                    "Unrecognized R-code: " + ret.rCode());
        }

        long days = ChronoUnit.DAYS.between(
                ret.originalEffectiveDate(), ret.returnReceivedDate());
        int window = 60;
        if (days > window) {
            return new ReturnOutcome(false, "OUT_OF_WINDOW",
                    "Return received " + days + " days after effective date; "
                            + "window is " + window);
        }

        return switch (ret.rCode()) {
            case "R01", "R02", "R03", "R04" -> new ReturnOutcome(true, "REVERSE_POSTING",
                    "Standard return — reverse prior credit/debit");
            case "R05" -> new ReturnOutcome(true, "UNAUTHORIZED_CONSUMER",
                    "Consumer claims unauthorized — refund and investigate");
            case "R06" -> new ReturnOutcome(true, "ODFI_REQUEST",
                    "ODFI-requested return — process without dispute");
            case "R07", "R10", "R11" -> new ReturnOutcome(true, "AUTHORIZATION_REVOKED",
                    "Authorization revoked — permanent block");
            case "R14", "R15" -> new ReturnOutcome(true, "DECEASED",
                    "Receiver deceased — halt future recurring entries");
            case "R20" -> new ReturnOutcome(true, "NON_TRANSACTION_ACCOUNT",
                    "Account type not authorized for ACH — halt and notify");
            default -> new ReturnOutcome(true, "STANDARD_REVERSAL",
                    "Process as standard return");
        };
    }

    public int returnWindowDays() {
        return 60;
    }
}
