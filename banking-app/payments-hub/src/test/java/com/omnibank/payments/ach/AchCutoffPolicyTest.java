package com.omnibank.payments.ach;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AchCutoffPolicyTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @Test
    void accepts_submission_ten_minutes_before_final_cutoff() {
        Instant t = ZonedDateTime.of(2026, 4, 14, 16, 35, 0, 0, ET).toInstant();
        AchCutoffPolicy policy = new AchCutoffPolicy(Clock.fixed(t, ET));
        assertThat(policy.isBeforeFinalSameDayCutoff()).isTrue();
    }

    @Test
    void rejects_submission_after_final_cutoff() {
        Instant t = ZonedDateTime.of(2026, 4, 14, 17, 0, 0, 0, ET).toInstant();
        AchCutoffPolicy policy = new AchCutoffPolicy(Clock.fixed(t, ET));
        assertThat(policy.isBeforeFinalSameDayCutoff()).isFalse();
    }

    @Test
    void rejects_submission_on_saturday() {
        Instant t = ZonedDateTime.of(2026, 4, 18, 10, 0, 0, 0, ET).toInstant();
        AchCutoffPolicy policy = new AchCutoffPolicy(Clock.fixed(t, ET));
        assertThat(policy.isBeforeFinalSameDayCutoff()).isFalse();
    }
}
