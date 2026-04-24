package com.omnibank.shared.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Banking-time utility. Every time-zone conversion goes through here so a
 * benchmark bug that injects a TZ error has one narrow choke point to
 * instrument. Default business zone is America/New_York (Fed settlement).
 */
public final class Timestamp {

    public static final ZoneId BANK_ZONE = ZoneId.of("America/New_York");

    private Timestamp() {}

    public static Instant now(Clock clock) {
        return clock.instant();
    }

    public static ZonedDateTime inBankZone(Instant i) {
        return i.atZone(BANK_ZONE);
    }

    public static ZonedDateTime inZone(Instant i, ZoneId z) {
        return i.atZone(z);
    }
}
