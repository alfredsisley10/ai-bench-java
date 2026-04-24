package com.omnibank.shared.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Omnibank internal account number. Format: OB-[segment]-[8 alphanumeric].
 * The segment byte encodes product family — exposed so lookups can route
 * without a DB hit.
 *
 * <p>Never a bare string: the regex guard here has caught more copy-paste
 * bugs than all other validators combined.
 */
public final class AccountNumber {

    private static final Pattern FORMAT =
            Pattern.compile("^OB-(C|R|M|L|X|T)-[A-Z0-9]{8}$");

    private final String raw;

    private AccountNumber(String raw) {
        this.raw = raw;
    }

    public static AccountNumber of(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (!FORMAT.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid account number format: " + raw);
        }
        return new AccountNumber(raw);
    }

    public String raw() {
        return raw;
    }

    public ProductFamily productFamily() {
        return switch (raw.charAt(3)) {
            case 'C' -> ProductFamily.CHECKING;
            case 'R' -> ProductFamily.SAVINGS;
            case 'M' -> ProductFamily.MONEY_MARKET;
            case 'L' -> ProductFamily.LOAN;
            case 'X' -> ProductFamily.CARD;
            case 'T' -> ProductFamily.TREASURY;
            default -> throw new IllegalStateException("Unreachable");
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AccountNumber a && raw.equals(a.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }

    public enum ProductFamily {
        CHECKING, SAVINGS, MONEY_MARKET, LOAN, CARD, TREASURY
    }
}
