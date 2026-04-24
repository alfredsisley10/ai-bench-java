package com.omnibank.payments.wire;

import com.omnibank.shared.domain.BusinessCalendar;
import com.omnibank.shared.domain.Timestamp;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Fedwire operates 9:00 PM ET Sunday through 7:00 PM ET Friday on business
 * days. Customer-initiated third-party wires have an earlier cutoff (5:00 PM
 * ET by bank policy — ours is 5:00:00.000 ET).
 *
 * <p>After the customer cutoff: the wire can be queued for next business
 * day, but must not settle same-day.
 */
public final class WireCutoffPolicy {

    private static final LocalTime CUSTOMER_WIRE_CUTOFF = LocalTime.of(17, 0, 0);

    private final Clock clock;

    public WireCutoffPolicy(Clock clock) {
        this.clock = clock;
    }

    public boolean isFedwireOpen() {
        return isFedwireOpen(Timestamp.now(clock));
    }

    public boolean isFedwireOpen(Instant now) {
        ZonedDateTime et = Timestamp.inBankZone(now);
        if (!BusinessCalendar.isBusinessDay(et.toLocalDate())) {
            return false;
        }
        return et.toLocalTime().isBefore(CUSTOMER_WIRE_CUTOFF);
    }
}
