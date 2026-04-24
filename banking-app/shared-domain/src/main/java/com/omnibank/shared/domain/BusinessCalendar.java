package com.omnibank.shared.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.Set;

/**
 * US Federal Reserve business calendar. Treats Saturday, Sunday, and the
 * Fed's 11 observed holidays as non-business days.
 *
 * <p>Omnibank uses this calendar for same-day ACH cutoffs, wire settlement,
 * statement cycle rollovers, and regulatory reporting windows.
 */
public final class BusinessCalendar {

    private static final Set<Month> FIXED_DATE_HOLIDAYS = EnumSet.noneOf(Month.class);

    private BusinessCalendar() {}

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !isFederalHoliday(date);
    }

    public static LocalDate nextBusinessDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (!isBusinessDay(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    public static LocalDate addBusinessDays(LocalDate start, int days) {
        LocalDate cursor = start;
        int remaining = days;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) remaining--;
        }
        return cursor;
    }

    /** Observed Fed holidays. When a fixed-date holiday lands on Sat/Sun, the observed date shifts. */
    public static boolean isFederalHoliday(LocalDate date) {
        // Fixed-date holidays: New Year's (Jan 1), Juneteenth (Jun 19),
        // Independence Day (Jul 4), Veterans Day (Nov 11), Christmas (Dec 25).
        if (matchesObservedFixed(date, Month.JANUARY, 1)) return true;
        if (matchesObservedFixed(date, Month.JUNE, 19)) return true;
        if (matchesObservedFixed(date, Month.JULY, 4)) return true;
        if (matchesObservedFixed(date, Month.NOVEMBER, 11)) return true;
        if (matchesObservedFixed(date, Month.DECEMBER, 25)) return true;

        // Floating holidays:
        // MLK Day — 3rd Monday in January
        if (isNthDayOfWeek(date, Month.JANUARY, 3, DayOfWeek.MONDAY)) return true;
        // Presidents' Day — 3rd Monday in February
        if (isNthDayOfWeek(date, Month.FEBRUARY, 3, DayOfWeek.MONDAY)) return true;
        // Memorial Day — last Monday in May
        if (isLastDayOfWeek(date, Month.MAY, DayOfWeek.MONDAY)) return true;
        // Labor Day — 1st Monday in September
        if (isNthDayOfWeek(date, Month.SEPTEMBER, 1, DayOfWeek.MONDAY)) return true;
        // Columbus / Indigenous Peoples' Day — 2nd Monday in October
        if (isNthDayOfWeek(date, Month.OCTOBER, 2, DayOfWeek.MONDAY)) return true;
        // Thanksgiving — 4th Thursday in November
        return isNthDayOfWeek(date, Month.NOVEMBER, 4, DayOfWeek.THURSDAY);
    }

    private static boolean matchesObservedFixed(LocalDate date, Month month, int day) {
        LocalDate natural = LocalDate.of(date.getYear(), month, day);
        LocalDate observed = switch (natural.getDayOfWeek()) {
            case SATURDAY -> natural.minusDays(1);
            case SUNDAY -> natural.plusDays(1);
            default -> natural;
        };
        return date.equals(observed);
    }

    private static boolean isNthDayOfWeek(LocalDate date, Month month, int nth, DayOfWeek dow) {
        if (date.getMonth() != month) return false;
        LocalDate firstOfMonth = date.withDayOfMonth(1);
        LocalDate target = firstOfMonth.with(TemporalAdjusters.dayOfWeekInMonth(nth, dow));
        return date.equals(target);
    }

    private static boolean isLastDayOfWeek(LocalDate date, Month month, DayOfWeek dow) {
        if (date.getMonth() != month) return false;
        LocalDate target = date.withDayOfMonth(1).with(TemporalAdjusters.lastInMonth(dow));
        return date.equals(target);
    }
}
