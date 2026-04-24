package com.omnibank.shared.domain;

import java.util.Locale;

/**
 * ISO 4217 currencies handled by Omnibank. Each carries its standard minor-unit
 * scale (decimal places) — banker's rounding everywhere, never float math.
 *
 * <p>Intentionally a closed enum rather than JDK {@link java.util.Currency}
 * because downstream ledger rules (e.g. JPY has no cents, KWD has three) are
 * baked into posting logic.
 */
public enum CurrencyCode {
    USD(2),
    EUR(2),
    GBP(2),
    CAD(2),
    JPY(0),
    CHF(2),
    AUD(2),
    KWD(3);

    private final int minorUnits;

    CurrencyCode(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    public int minorUnits() {
        return minorUnits;
    }

    public String iso4217() {
        return name();
    }

    public static CurrencyCode parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("currency code is null");
        }
        return valueOf(raw.toUpperCase(Locale.ROOT));
    }
}
