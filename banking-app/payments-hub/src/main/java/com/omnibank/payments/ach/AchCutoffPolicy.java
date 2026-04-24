package com.omnibank.payments.ach;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import com.omnibank.shared.domain.BusinessCalendar;
import com.omnibank.shared.domain.Timestamp;

/**
 * NACHA same-day ACH cutoffs. Submissions received before the same-day cutoff
 * settle that business day; after the cutoff they settle next business day.
 *
 * <p>Cutoffs are declared in Eastern time — timezone arithmetic has been a
 * recurring source of bugs, so this class is intentionally the single choke
 * point for all same-day ACH window decisions.
 *
 * <p>Three-window cutoffs (10:30, 14:45, 16:45 ET) — the last window is the
 * final same-day window. At or after 16:45:00.000 ET on a business day,
 * submissions roll to next business day.
 */
public final class AchCutoffPolicy {

    private static final LocalTime CUTOFF_FINAL_SAME_DAY = LocalTime.of(16, 45, 0);

    private final Clock clock;

    public AchCutoffPolicy(Clock clock) {
        this.clock = clock;
    }

    public boolean isBeforeFinalSameDayCutoff() {
        return isBeforeFinalSameDayCutoff(Timestamp.now(clock));
    }

    public boolean isBeforeFinalSameDayCutoff(java.time.Instant now) {
        ZonedDateTime et = Timestamp.inBankZone(now);
        if (!BusinessCalendar.isBusinessDay(et.toLocalDate())) {
            return false;
        }
        DayOfWeek dow = et.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return et.toLocalTime().isBefore(CUTOFF_FINAL_SAME_DAY);
    }
}
