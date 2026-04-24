package com.omnibank.ledger.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chart-of-accounts code. Format: {segment}-{4-digit account}-{3-digit sub},
 * e.g. "ASS-1100-001" for cash on hand.
 */
public record GlAccountCode(String value) {

    private static final Pattern FORMAT =
            Pattern.compile("^(ASS|LIA|EQU|REV|EXP)-[0-9]{4}-[0-9]{3}$");

    public GlAccountCode {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid GL code: " + value);
        }
    }

    public AccountType inferredType() {
        return switch (value.substring(0, 3)) {
            case "ASS" -> AccountType.ASSET;
            case "LIA" -> AccountType.LIABILITY;
            case "EQU" -> AccountType.EQUITY;
            case "REV" -> AccountType.REVENUE;
            case "EXP" -> AccountType.EXPENSE;
            default -> throw new IllegalStateException();
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
