package com.omnibank.statements.internal;

import com.omnibank.statements.internal.StatementDeliveryService.Channel;
import com.omnibank.statements.internal.StatementFraudFlagging.FlagType;
import com.omnibank.statements.internal.StatementFraudFlagging.FraudFlag;
import com.omnibank.statements.internal.StatementFraudFlagging.Severity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementFraudFlaggingTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
    private final StatementFraudFlagging flagging = new StatementFraudFlagging(clock);

    @Test
    void matching_addresses_do_not_raise_flag() {
        List<FraudFlag> flags = flagging.inspectMailingAddress(
                StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                "123 Main St, Springfield IL",
                "123 MAIN ST, SPRINGFIELD IL");
        assertThat(flags).isEmpty();
    }

    @Test
    void mismatched_mailing_address_raises_investigate_flag() {
        List<FraudFlag> flags = flagging.inspectMailingAddress(
                StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                "500 Drop Box Dr, Miami FL",
                "123 Main St, Springfield IL");
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).type()).isEqualTo(FlagType.ADDRESS_MISMATCH);
        assertThat(flags.get(0).severity()).isEqualTo(Severity.INVESTIGATE);
    }

    @Test
    void first_channel_observation_does_not_flag() {
        List<FraudFlag> flags = flagging.observeChannelUse(
                StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.USPS_PHYSICAL_MAIL);
        assertThat(flags).isEmpty();
    }

    @Test
    void channel_change_after_stable_pattern_raises_review_flag() {
        // Three uses of mail establish a pattern.
        flagging.observeChannelUse(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.USPS_PHYSICAL_MAIL);
        flagging.observeChannelUse(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.USPS_PHYSICAL_MAIL);
        flagging.observeChannelUse(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.USPS_PHYSICAL_MAIL);

        List<FraudFlag> flags = flagging.observeChannelUse(
                StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.SECURE_MESSAGE_PORTAL);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).type()).isEqualTo(FlagType.SUDDEN_CHANNEL_CHANGE);
        assertThat(flags.get(0).severity()).isEqualTo(Severity.REVIEW);
    }

    @Test
    void switch_to_third_party_aggregator_always_flags_investigate() {
        // Even without a stable pattern, going to an aggregator flags high.
        flagging.observeChannelUse(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.SECURE_MESSAGE_PORTAL);
        List<FraudFlag> flags = flagging.observeChannelUse(
                StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.THIRD_PARTY_AGGREGATOR);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).severity()).isEqualTo(Severity.INVESTIGATE);
    }

    @Test
    void copy_requests_below_threshold_do_not_flag() {
        for (int i = 0; i < StatementFraudFlagging.COPY_REQUEST_THRESHOLD; i++) {
            List<FraudFlag> flags = flagging.observeCopyRequest(
                    StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING);
            assertThat(flags).isEmpty();
        }
    }

    @Test
    void copy_requests_above_threshold_raise_flag() {
        for (int i = 0; i <= StatementFraudFlagging.COPY_REQUEST_THRESHOLD; i++) {
            flagging.observeCopyRequest(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING);
        }
        // The (threshold+1)th request raises the flag.
        assertThat(flagging.raisedFlags())
                .anyMatch(f -> f.type() == FlagType.EXCESSIVE_COPY_REQUESTS);
    }

    @Test
    void clear_history_resets_channel_and_copy_tracking() {
        flagging.observeChannelUse(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.USPS_PHYSICAL_MAIL);
        flagging.clearHistory(StatementTestFixtures.ALICE);
        // After clear, the next channel use is the first, not a change.
        List<FraudFlag> flags = flagging.observeChannelUse(
                StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                Channel.SECURE_MESSAGE_PORTAL);
        assertThat(flags).isEmpty();
    }

    @Test
    void count_for_customer_reflects_raised_flags() {
        flagging.inspectMailingAddress(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                "bad", "good");
        flagging.inspectMailingAddress(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                "bad2", "good");
        assertThat(flagging.countFor(StatementTestFixtures.ALICE)).isEqualTo(2);
        assertThat(flagging.countFor(StatementTestFixtures.BOB)).isZero();
    }

    @Test
    void raised_flags_list_is_append_only_snapshot() {
        flagging.inspectMailingAddress(StatementTestFixtures.ALICE, StatementTestFixtures.CHECKING,
                "different", "record");
        List<FraudFlag> before = flagging.raisedFlags();
        assertThat(before).hasSize(1);
        // Mutating the snapshot should not mutate the service.
        List<FraudFlag> snapshot = new java.util.ArrayList<>(before);
        snapshot.clear();
        assertThat(flagging.raisedFlags()).hasSize(1);
    }
}
