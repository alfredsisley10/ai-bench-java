package com.omnibank.risk.internal;

import com.omnibank.risk.internal.OperationalRiskLossEvent.EventStatus;
import com.omnibank.risk.internal.OperationalRiskLossEvent.LossEventType;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalRiskRegisterTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-04-16");

    private RecordingEventBus events;
    private OperationalRiskRegister register;

    @BeforeEach
    void setUp() {
        events = new RecordingEventBus();
        Clock clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        register = new OperationalRiskRegister(clock, events, CurrencyCode.USD);
    }

    @Test
    void recording_event_stores_and_publishes() {
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "50000", "0", LocalDate.parse("2025-11-01")));
        assertThat(register.allEvents()).hasSize(1);
        assertThat(events.events)
                .anyMatch(e -> e instanceof OperationalRiskRegister.OperationalLossRecordedEvent);
    }

    @Test
    void recovery_cannot_exceed_gross() {
        assertThatThrownBy(() -> sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "1000", "2000", LocalDate.parse("2025-11-01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void event_cannot_be_discovered_before_occurrence() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new OperationalRiskLossEvent(id,
                LossEventType.INTERNAL_FRAUD,
                RiskWeightedAssetsEngine.BusinessLine.RETAIL_BANKING,
                "Branch X", "Desc",
                Money.of("1000", CurrencyCode.USD),
                Money.zero(CurrencyCode.USD),
                LocalDate.parse("2025-12-01"),
                LocalDate.parse("2025-11-15"),    // discovered before occurrence
                LocalDate.parse("2025-12-05"),
                EventStatus.OPEN, Optional.empty(), "pre-existing",
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void status_transitions_persist_in_store() {
        OperationalRiskLossEvent e = register.record(sampleEvent(
                LossEventType.EXECUTION_DELIVERY_AND_PROCESS_MANAGEMENT,
                "5000", "0", LocalDate.parse("2025-01-01")));

        OperationalRiskLossEvent updated = register.updateStatus(e.eventId(), EventStatus.ACCOUNTED);
        assertThat(updated.status()).isEqualTo(EventStatus.ACCOUNTED);
        assertThat(register.allEvents().get(0).status()).isEqualTo(EventStatus.ACCOUNTED);
    }

    @Test
    void recording_recovery_reduces_net_loss() {
        OperationalRiskLossEvent e = register.record(sampleEvent(
                LossEventType.EXTERNAL_FRAUD,
                "10000", "0", LocalDate.parse("2025-06-01")));

        OperationalRiskLossEvent recovered = register.recordRecovery(
                e.eventId(), Money.of("4000", CurrencyCode.USD));

        assertThat(recovered.recoveries()).isEqualTo(Money.of("4000", CurrencyCode.USD));
        assertThat(recovered.netLoss()).isEqualTo(Money.of("6000", CurrencyCode.USD));
    }

    @Test
    void snapshot_aggregates_by_category_and_business_line() {
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "5000", "0", LocalDate.parse("2025-05-01")));
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "8000", "1000", LocalDate.parse("2025-07-01")));
        register.record(sampleEvent(LossEventType.INTERNAL_FRAUD,
                "20000", "0", LocalDate.parse("2025-08-01")));

        var snap = register.snapshot();
        assertThat(snap.totalEvents()).isEqualTo(3);
        assertThat(snap.byType().get(LossEventType.EXTERNAL_FRAUD).count()).isEqualTo(2);
        assertThat(snap.byType().get(LossEventType.EXTERNAL_FRAUD).netTotal())
                .isEqualTo(Money.of("12000", CurrencyCode.USD));
    }

    @Test
    void average_annual_loss_excludes_events_before_window() {
        // Old event outside the 3-year window
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "1000000", "0", LocalDate.parse("2000-01-01")));
        // Recent event inside the window
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "3000", "0", LocalDate.parse("2025-03-01")));

        Money avg = register.averageAnnualLoss(AS_OF, 3);
        // Over 3 years: (0 + 3000 + 0)/3 = 1000
        assertThat(avg).isEqualTo(Money.of("1000.00", CurrencyCode.USD));
    }

    @Test
    void regulatory_window_filter_respects_ten_year_lookback() {
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "1000", "0", LocalDate.parse("2010-01-01")));
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "2000", "0", LocalDate.parse("2024-01-01")));

        var filtered = register.eventsInRegulatoryWindow(AS_OF);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).grossLoss())
                .isEqualTo(Money.of("2000", CurrencyCode.USD));
    }

    @Test
    void open_event_count_excludes_closed_and_written_off() {
        var a = register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "100", "0", LocalDate.parse("2025-05-01")));
        var b = register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "100", "0", LocalDate.parse("2025-06-01")));
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "100", "0", LocalDate.parse("2025-07-01")));

        register.updateStatus(a.eventId(), EventStatus.CLOSED);
        register.updateStatus(b.eventId(), EventStatus.WRITTEN_OFF);

        assertThat(register.openEventCount()).isEqualTo(1);
    }

    @Test
    void currency_mismatch_is_rejected_by_register() {
        OperationalRiskLossEvent wrong = new OperationalRiskLossEvent(UUID.randomUUID(),
                LossEventType.EXTERNAL_FRAUD,
                RiskWeightedAssetsEngine.BusinessLine.RETAIL_BANKING,
                "Branch", "Desc",
                Money.of("1000", CurrencyCode.EUR),
                Money.of("0", CurrencyCode.EUR),
                LocalDate.parse("2025-05-01"),
                LocalDate.parse("2025-05-02"),
                LocalDate.parse("2025-05-03"),
                EventStatus.OPEN, Optional.empty(), "cause",
                Instant.now());
        assertThatThrownBy(() -> register.record(wrong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monthly_frequency_returns_requested_number_of_months() {
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "1000", "0", LocalDate.parse("2026-03-10")));
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "1000", "0", LocalDate.parse("2026-03-20")));
        register.record(sampleEvent(LossEventType.EXTERNAL_FRAUD,
                "1000", "0", LocalDate.parse("2026-04-01")));

        var freq = register.monthlyFrequency(AS_OF, 3);
        assertThat(freq).hasSize(3);
        assertThat(freq.values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(3);
    }

    private OperationalRiskLossEvent sampleEvent(LossEventType type, String gross,
                                                   String rec, LocalDate occurred) {
        return new OperationalRiskLossEvent(UUID.randomUUID(),
                type, RiskWeightedAssetsEngine.BusinessLine.RETAIL_BANKING,
                "Branch X", "Sample loss",
                Money.of(gross, CurrencyCode.USD),
                Money.of(rec, CurrencyCode.USD),
                occurred, occurred.plusDays(2), occurred.plusDays(5),
                EventStatus.OPEN, Optional.empty(), "sample",
                Instant.now());
    }
}
