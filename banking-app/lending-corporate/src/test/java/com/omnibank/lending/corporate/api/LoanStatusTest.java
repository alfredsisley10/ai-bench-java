package com.omnibank.lending.corporate.api;

import org.junit.jupiter.api.Test;

import static com.omnibank.lending.corporate.api.LoanStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class LoanStatusTest {

    @Test
    void approved_cannot_go_directly_to_active() {
        // The disbursement posting must be recorded — APPROVED must
        // pass through FUNDED before ACTIVE.
        assertThat(APPROVED.canTransitionTo(ACTIVE)).isFalse();
        assertThat(APPROVED.canTransitionTo(FUNDED)).isTrue();
        assertThat(FUNDED.canTransitionTo(ACTIVE)).isTrue();
    }

    @Test
    void terminal_states_have_no_outbound_transitions() {
        for (LoanStatus next : LoanStatus.values()) {
            assertThat(PAID_OFF.canTransitionTo(next)).isFalse();
            assertThat(CHARGED_OFF.canTransitionTo(next)).isFalse();
            assertThat(DECLINED.canTransitionTo(next)).isFalse();
        }
    }
}
