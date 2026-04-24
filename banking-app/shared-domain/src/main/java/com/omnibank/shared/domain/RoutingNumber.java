package com.omnibank.shared.domain;

import java.util.Objects;

/**
 * US ABA routing number (9 digits) with checksum validation.
 * Checksum formula: 3*(d1+d4+d7) + 7*(d2+d5+d8) + (d3+d6+d9) mod 10 == 0.
 */
public final class RoutingNumber {

    private final String raw;

    private RoutingNumber(String raw) {
        this.raw = raw;
    }

    public static RoutingNumber of(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length() != 9 || !raw.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Routing number must be 9 digits: " + raw);
        }
        int[] d = raw.chars().map(c -> c - '0').toArray();
        int sum = 3 * (d[0] + d[3] + d[6])
                + 7 * (d[1] + d[4] + d[7])
                +     (d[2] + d[5] + d[8]);
        if (sum % 10 != 0) {
            throw new IllegalArgumentException("Routing number fails ABA checksum: " + raw);
        }
        return new RoutingNumber(raw);
    }

    public String raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoutingNumber r && raw.equals(r.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
