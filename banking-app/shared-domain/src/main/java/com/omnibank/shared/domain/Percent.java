package com.omnibank.shared.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Rate or percentage stored as basis points internally (1 bp = 0.01%).
 * Use for interest rates, fee percentages, yields. Never store interest as a
 * double — the rounding disagreements downstream are brutal.
 */
public record Percent(BigDecimal basisPoints) implements Comparable<Percent> {

    private static final BigDecimal BP_PER_UNIT = new BigDecimal("10000");

    public Percent {
        Objects.requireNonNull(basisPoints, "basisPoints");
    }

    public static Percent ofBps(long bps) {
        return new Percent(BigDecimal.valueOf(bps));
    }

    public static Percent ofBps(BigDecimal bps) {
        return new Percent(bps);
    }

    public static Percent ofPercent(BigDecimal percent) {
        return new Percent(percent.multiply(new BigDecimal("100")));
    }

    public static Percent ofRate(BigDecimal fraction) {
        // e.g. 0.055 → 550 bps
        return new Percent(fraction.multiply(BP_PER_UNIT));
    }

    /** Multiplier fraction: 550 bps → 0.0550. */
    public BigDecimal asFraction(MathContext mc) {
        return basisPoints.divide(BP_PER_UNIT, mc);
    }

    public Money of(Money amount) {
        BigDecimal fraction = basisPoints.divide(BP_PER_UNIT, 10, RoundingMode.HALF_EVEN);
        return amount.times(fraction);
    }

    @Override
    public int compareTo(Percent o) {
        return basisPoints.compareTo(o.basisPoints);
    }

    @Override
    public String toString() {
        return basisPoints.toPlainString() + " bp";
    }
}
