package com.omnibank.shared.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * Shorthand time-to-maturity: "1D", "30D", "3M", "1Y", "30Y". Used across
 * lending, treasury, FX. Immutable and self-validating.
 */
public record Tenor(int amount, Unit unit) {

    public Tenor {
        Objects.requireNonNull(unit, "unit");
        if (amount <= 0) {
            throw new IllegalArgumentException("Tenor amount must be positive: " + amount);
        }
    }

    public static Tenor parse(String s) {
        Objects.requireNonNull(s, "s");
        if (s.length() < 2) {
            throw new IllegalArgumentException("Invalid tenor: " + s);
        }
        char last = s.charAt(s.length() - 1);
        int num = Integer.parseInt(s.substring(0, s.length() - 1));
        Unit u = switch (last) {
            case 'D', 'd' -> Unit.DAYS;
            case 'W', 'w' -> Unit.WEEKS;
            case 'M', 'm' -> Unit.MONTHS;
            case 'Y', 'y' -> Unit.YEARS;
            default -> throw new IllegalArgumentException("Unknown tenor unit: " + last);
        };
        return new Tenor(num, u);
    }

    public LocalDate applyTo(LocalDate start) {
        return switch (unit) {
            case DAYS -> start.plusDays(amount);
            case WEEKS -> start.plusWeeks(amount);
            case MONTHS -> start.plus(Period.ofMonths(amount));
            case YEARS -> start.plus(Period.ofYears(amount));
        };
    }

    @Override
    public String toString() {
        return amount + unit.marker;
    }

    public enum Unit {
        DAYS("D"), WEEKS("W"), MONTHS("M"), YEARS("Y");
        final String marker;
        Unit(String m) { this.marker = m; }
    }
}
