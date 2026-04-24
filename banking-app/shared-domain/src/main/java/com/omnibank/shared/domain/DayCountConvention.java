package com.omnibank.shared.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;

/**
 * Day-count conventions for interest accrual. Each convention computes a
 * <em>year fraction</em> between two dates — the numerator in {@code interest
 * = principal * rate * yearFraction}.
 *
 * <p>Getting these wrong by even a day is a rich source of classic banking
 * bugs — deliberately kept as an enum so all cases are grep-findable when
 * the harness injects a defect into one of them.
 */
public enum DayCountConvention {

    /** 30/360 (US): months treated as 30d, years as 360d. Corporate bond convention. */
    THIRTY_360 {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end, MathContext mc) {
            int d1 = Math.min(30, start.getDayOfMonth());
            int d2 = end.getDayOfMonth();
            if (d1 == 30 && d2 == 31) d2 = 30;
            int days = 360 * (end.getYear() - start.getYear())
                    + 30 * (end.getMonthValue() - start.getMonthValue())
                    + (d2 - d1);
            return new BigDecimal(days).divide(new BigDecimal(360), mc);
        }
    },

    /** Actual/360: actual calendar days, 360d year. Money-market standard (ACH, LIBOR, SOFR). */
    ACTUAL_360 {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end, MathContext mc) {
            long days = ChronoUnit.DAYS.between(start, end);
            return new BigDecimal(days).divide(new BigDecimal(360), mc);
        }
    },

    /** Actual/365: actual calendar days, 365d year. Some consumer deposit products. */
    ACTUAL_365 {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end, MathContext mc) {
            long days = ChronoUnit.DAYS.between(start, end);
            return new BigDecimal(days).divide(new BigDecimal(365), mc);
        }
    },

    /** Actual/Actual ISDA: honors leap years. Used on most sovereign bonds. */
    ACTUAL_ACTUAL {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end, MathContext mc) {
            // Simplified ISDA: split by calendar year, use each year's actual day count.
            BigDecimal frac = BigDecimal.ZERO;
            LocalDate cursor = start;
            while (cursor.isBefore(end)) {
                LocalDate yearEnd = LocalDate.of(cursor.getYear(), 12, 31);
                LocalDate segmentEnd = yearEnd.isBefore(end) ? yearEnd.plusDays(1) : end;
                long daysInSeg = ChronoUnit.DAYS.between(cursor, segmentEnd);
                int daysInYear = Year.of(cursor.getYear()).length();
                frac = frac.add(new BigDecimal(daysInSeg)
                        .divide(new BigDecimal(daysInYear), mc));
                cursor = segmentEnd;
            }
            return frac;
        }
    };

    public abstract BigDecimal yearFraction(LocalDate start, LocalDate end, MathContext mc);
}
