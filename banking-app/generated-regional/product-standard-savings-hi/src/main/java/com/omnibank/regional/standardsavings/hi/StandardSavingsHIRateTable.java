package com.omnibank.regional.standardsavings.hi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * State-specific rate table overlay for StandardSavings offered in Hawaii.
 * In markets where the bank competes aggressively, rates may be
 * bumped above the national posted rate. In regulated-rate states
 * the table is clamped by state law.
 */
public final class StandardSavingsHIRateTable {

    public record RateBand(BigDecimal lowerInclusive, BigDecimal upperExclusive, BigDecimal apy) {}

    private final List<RateBand> bands;
    private final LocalDate effectiveFrom;
    private final String rateSheetRef;

    public StandardSavingsHIRateTable() {
        this.effectiveFrom = LocalDate.of(2026, 1, 1);
        this.rateSheetRef = "RATE-HI-StandardSavings-2026Q1";
        this.bands = buildBands();
    }

    public List<RateBand> bands() {
        return Collections.unmodifiableList(bands);
    }

    public LocalDate effectiveFrom() { return effectiveFrom; }
    public String rateSheetRef() { return rateSheetRef; }

    public BigDecimal apyFor(BigDecimal balance) {
        RateBand match = null;
        for (RateBand b : bands) {
            if (balance.compareTo(b.lowerInclusive()) >= 0
                    && (b.upperExclusive() == null
                            || balance.compareTo(b.upperExclusive()) < 0)) {
                match = b;
            }
        }
        return match != null ? match.apy() : BigDecimal.ZERO;
    }

    public BigDecimal topTierApy() {
        return bands.isEmpty()
                ? BigDecimal.ZERO
                : bands.get(bands.size() - 1).apy();
    }

    private List<RateBand> buildBands() {
        List<RateBand> out = new ArrayList<>();
        BigDecimal adjustment = stateRateAdjustment();
        BigDecimal baseApy = new BigDecimal("0.0300").add(adjustment);
        out.add(new RateBand(
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                baseApy));
        out.add(new RateBand(
                new BigDecimal("1000"),
                new BigDecimal("10000"),
                baseApy.add(new BigDecimal("0.0010"))));
        out.add(new RateBand(
                new BigDecimal("10000"),
                new BigDecimal("100000"),
                baseApy.add(new BigDecimal("0.0025"))));
        out.add(new RateBand(
                new BigDecimal("100000"),
                null,
                baseApy.add(new BigDecimal("0.0050"))));
        return out;
    }

    private BigDecimal stateRateAdjustment() {
        return switch ("HI") {
            // High competition markets — bump rates slightly
            case "CA", "NY", "TX", "FL", "IL" -> new BigDecimal("0.0025");
            // Online-heavy markets
            case "MA", "NJ", "CT" -> new BigDecimal("0.0015");
            default -> BigDecimal.ZERO;
        };
    }
}
