package com.omnibank.shared.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DayCountConventionTest {

    @Test
    void thirty_360_does_not_treat_feb_28_as_end_of_month() {
        // Jan 31 → Feb 28: 30/360 should yield 28/360 = 0.07777…
        // Buggy version snaps Feb 28 → 30, yielding 30/360 = 0.08333…
        BigDecimal yf = DayCountConvention.THIRTY_360.yearFraction(
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            MathContext.DECIMAL64);
        BigDecimal expected = new BigDecimal("28")
            .divide(new BigDecimal("360"), 12, RoundingMode.HALF_EVEN);
        assertThat(yf.setScale(12, RoundingMode.HALF_EVEN)).isEqualByComparingTo(expected);
    }

    @Test
    void thirty_360_full_year_yields_one() {
        BigDecimal yf = DayCountConvention.THIRTY_360.yearFraction(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2027, 1, 1),
            MathContext.DECIMAL64);
        assertThat(yf.doubleValue()).isCloseTo(1.0, within(0.000001));
    }
}
