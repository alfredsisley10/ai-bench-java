package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.statements.internal.StatementCycleScheduler.AccountCycleSpec;
import com.omnibank.statements.internal.StatementCycleScheduler.AccountEnumerator;
import com.omnibank.statements.internal.StatementCycleScheduler.CycleDataProvider;
import com.omnibank.statements.internal.StatementCycleScheduler.CycleOutcome;
import com.omnibank.statements.internal.StatementCycleScheduler.OutcomeStatus;
import com.omnibank.statements.internal.StatementDeliveryService.Channel;
import com.omnibank.statements.internal.StatementDeliveryService.ChannelTransport;
import com.omnibank.statements.internal.StatementDeliveryService.DeliveryDestination;
import com.omnibank.statements.internal.StatementDeliveryService.TransportException;
import com.omnibank.statements.internal.StatementGenerator.GenerationRequest;
import com.omnibank.statements.internal.StatementGenerator.RenderedStatement;
import com.omnibank.statements.internal.StatementPreferencesManager.Frequency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StatementCycleSchedulerTest {

    private Clock clock;
    private StatementGenerator generator;
    private StatementArchiveService archive;
    private StatementDeliveryService delivery;
    private StatementPreferencesManager preferences;
    private FakeEnumerator enumerator;
    private FakeDataProvider dataProvider;
    private RecordingTransport mailTransport;
    private RecordingTransport portalTransport;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC);
        generator = new StatementGenerator(clock);
        byte[] key = new byte[32];
        archive = new StatementArchiveService(clock, key);
        delivery = new StatementDeliveryService(clock);
        mailTransport = new RecordingTransport(Channel.USPS_PHYSICAL_MAIL);
        portalTransport = new RecordingTransport(Channel.SECURE_MESSAGE_PORTAL);
        delivery.registerTransport(mailTransport);
        delivery.registerTransport(portalTransport);

        preferences = new StatementPreferencesManager(clock);
        preferences.enrollDefault(StatementTestFixtures.ALICE);

        enumerator = new FakeEnumerator();
        dataProvider = new FakeDataProvider();
    }

    private StatementCycleScheduler scheduler(Set<LocalDate> holidays) {
        return new StatementCycleScheduler(clock, generator, archive, delivery, preferences,
                enumerator, dataProvider, holidays);
    }

    @Test
    void runs_cycle_for_enumerated_accounts_and_delivers_success() {
        enumerator.due.add(new AccountCycleSpec(StatementTestFixtures.CHECKING,
                StatementTestFixtures.ALICE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));

        StatementCycleScheduler s = scheduler(Set.of());
        List<CycleOutcome> outcomes = s.runCycle(LocalDate.of(2026, 5, 1), Frequency.MONTHLY);

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).status()).isEqualTo(OutcomeStatus.GENERATED_AND_DELIVERED);
        assertThat(mailTransport.invocations).isEqualTo(1);
    }

    @Test
    void cycle_falls_back_to_secondary_channel_on_primary_transient_failure() {
        enumerator.due.add(new AccountCycleSpec(StatementTestFixtures.CHECKING,
                StatementTestFixtures.ALICE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        mailTransport.returnAccepted = false;

        StatementCycleScheduler s = scheduler(Set.of());
        List<CycleOutcome> outcomes = s.runCycle(LocalDate.of(2026, 5, 1), Frequency.MONTHLY);

        // Default preferences have SECURE_MESSAGE_PORTAL as fallback.
        assertThat(outcomes.get(0).status()).isEqualTo(OutcomeStatus.GENERATED_AND_DELIVERED);
        assertThat(outcomes.get(0).message()).contains("fallback");
    }

    @Test
    void skipped_when_preferences_missing_for_customer() {
        enumerator.due.add(new AccountCycleSpec(StatementTestFixtures.SAVINGS,
                StatementTestFixtures.BOB,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));

        StatementCycleScheduler s = scheduler(Set.of());
        List<CycleOutcome> outcomes = s.runCycle(LocalDate.of(2026, 5, 1), Frequency.MONTHLY);

        assertThat(outcomes.get(0).status()).isEqualTo(OutcomeStatus.SKIPPED_NO_PREFERENCES);
    }

    @Test
    void skipped_when_data_provider_has_no_destination() {
        enumerator.due.add(new AccountCycleSpec(StatementTestFixtures.CHECKING,
                StatementTestFixtures.ALICE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        dataProvider.returnDestination = false;

        StatementCycleScheduler s = scheduler(Set.of());
        List<CycleOutcome> outcomes = s.runCycle(LocalDate.of(2026, 5, 1), Frequency.MONTHLY);
        assertThat(outcomes.get(0).status()).isEqualTo(OutcomeStatus.SKIPPED_NO_DESTINATION);
    }

    @Test
    void weekend_nominal_date_rolls_back_to_friday() {
        StatementCycleScheduler s = scheduler(Set.of());
        // May 2, 2026 is a Saturday.
        LocalDate adjusted = s.adjustForWeekendOrHoliday(LocalDate.of(2026, 5, 2));
        assertThat(adjusted).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void holiday_date_rolls_back_to_prior_business_day() {
        StatementCycleScheduler s = scheduler(Set.of(LocalDate.of(2026, 5, 4)));
        // May 4 is a Monday, but we've marked it holiday — roll to Friday May 1.
        LocalDate adjusted = s.adjustForWeekendOrHoliday(LocalDate.of(2026, 5, 4));
        assertThat(adjusted).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void is_business_day_respects_weekend_and_holiday() {
        StatementCycleScheduler s = scheduler(Set.of(LocalDate.of(2026, 5, 4)));
        assertThat(s.isBusinessDay(LocalDate.of(2026, 5, 2))).isFalse();
        assertThat(s.isBusinessDay(LocalDate.of(2026, 5, 3))).isFalse();
        assertThat(s.isBusinessDay(LocalDate.of(2026, 5, 4))).isFalse();
        assertThat(s.isBusinessDay(LocalDate.of(2026, 5, 5))).isTrue();
    }

    @Test
    void cycle_history_retains_per_date_outcomes() {
        enumerator.due.add(new AccountCycleSpec(StatementTestFixtures.CHECKING,
                StatementTestFixtures.ALICE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        StatementCycleScheduler s = scheduler(Set.of());
        s.runCycle(LocalDate.of(2026, 5, 1), Frequency.MONTHLY);
        assertThat(s.historyFor(LocalDate.of(2026, 5, 1))).hasSize(1);
        assertThat(s.historyFor(LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void data_provider_exception_surfaces_as_failed_outcome() {
        enumerator.due.add(new AccountCycleSpec(StatementTestFixtures.CHECKING,
                StatementTestFixtures.ALICE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        dataProvider.throwOnRequest = true;

        StatementCycleScheduler s = scheduler(Set.of());
        List<CycleOutcome> outcomes = s.runCycle(LocalDate.of(2026, 5, 1), Frequency.MONTHLY);
        assertThat(outcomes.get(0).status()).isEqualTo(OutcomeStatus.FAILED);
    }

    private static final class FakeEnumerator implements AccountEnumerator {
        final List<AccountCycleSpec> due = new ArrayList<>();

        @Override
        public List<AccountCycleSpec> dueOn(LocalDate cycleEnd, Frequency frequency) {
            return new ArrayList<>(due);
        }
    }

    private static final class FakeDataProvider implements CycleDataProvider {
        boolean returnDestination = true;
        boolean throwOnRequest = false;

        @Override
        public GenerationRequest requestFor(AccountNumber account,
                                            LocalDate cycleStart, LocalDate cycleEnd) {
            if (throwOnRequest) throw new RuntimeException("boom");
            return new GenerationRequest(
                    StatementTestFixtures.header(account, cycleStart, cycleEnd),
                    Money.of(1000, CurrencyCode.USD),
                    List.of(),
                    List.of());
        }

        @Override
        public DeliveryDestination destinationFor(AccountNumber account, Channel channel) {
            if (!returnDestination) return null;
            String addr = switch (channel) {
                case USPS_PHYSICAL_MAIL -> "123 Main";
                case SECURE_MESSAGE_PORTAL -> "mbx-1";
                case E_STATEMENT_SFTP -> "sftp://ex/";
                case THIRD_PARTY_AGGREGATOR -> "plaid://";
            };
            return new DeliveryDestination(channel, addr, null, null);
        }
    }

    private static final class RecordingTransport implements ChannelTransport {
        private final Channel channel;
        int invocations = 0;
        boolean returnAccepted = true;

        RecordingTransport(Channel channel) {
            this.channel = channel;
        }

        @Override
        public Channel channel() { return channel; }

        @Override
        public boolean deliver(DeliveryDestination destination, RenderedStatement rendered)
                throws TransportException {
            invocations++;
            return returnAccepted;
        }
    }
}
