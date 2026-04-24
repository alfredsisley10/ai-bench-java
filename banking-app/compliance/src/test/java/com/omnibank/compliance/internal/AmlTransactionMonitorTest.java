package com.omnibank.compliance.internal;

import com.omnibank.compliance.internal.AmlTransactionMonitor.AmlAlert;
import com.omnibank.compliance.internal.AmlTransactionMonitor.AlertStatus;
import com.omnibank.compliance.internal.AmlTransactionMonitor.MonitoredTransaction;
import com.omnibank.compliance.internal.AmlTransactionMonitor.TypologyCode;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmlTransactionMonitorTest {

    private static final AccountNumber ACCT = AccountNumber.of("OB-C-ABCD1234");
    private static final CustomerId CUST = CustomerId.newId();
    private static final Instant BASE = Instant.parse("2026-04-16T10:00:00Z");

    private Clock clock;
    private RecordingEventBus events;
    private AmlTransactionMonitor monitor;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(BASE.plus(Duration.ofHours(10)), ZoneId.of("UTC"));
        events = new RecordingEventBus();
        monitor = new AmlTransactionMonitor(events, clock);
    }

    @Test
    void single_small_cash_transaction_produces_no_alerts() {
        var txn = txn(Money.of("2500.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE);
        assertThat(monitor.monitorTransaction(txn)).isEmpty();
    }

    @Test
    void structuring_pattern_generates_alert() {
        // Three cash deposits in the $3k-$10k range within 48 hours, totaling over $10k CTR.
        monitor.monitorTransaction(txn(Money.of("4000.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE));
        monitor.monitorTransaction(txn(Money.of("4500.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE.plus(Duration.ofHours(3))));
        var alerts = monitor.monitorTransaction(txn(Money.of("3500.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE.plus(Duration.ofHours(6))));

        assertThat(alerts).anySatisfy(a ->
                assertThat(a.typology()).isEqualTo(TypologyCode.STRUCTURING));
        assertThat(events.events).anySatisfy(e ->
                assertThat(e).isInstanceOf(AmlTransactionMonitor.AmlAlertEvent.class));
    }

    @Test
    void non_cash_transactions_do_not_trigger_structuring() {
        monitor.monitorTransaction(txn(Money.of("4000.00", CurrencyCode.USD), "INBOUND", "WIRE", null, BASE));
        monitor.monitorTransaction(txn(Money.of("4500.00", CurrencyCode.USD), "INBOUND", "WIRE", null, BASE.plus(Duration.ofHours(3))));
        var alerts = monitor.monitorTransaction(txn(Money.of("3500.00", CurrencyCode.USD), "INBOUND", "WIRE", null, BASE.plus(Duration.ofHours(6))));

        assertThat(alerts).noneSatisfy(a ->
                assertThat(a.typology()).isEqualTo(TypologyCode.STRUCTURING));
    }

    @Test
    void high_risk_jurisdiction_transaction_generates_alert() {
        var txn = txnWithCounterparty(Money.of("2000.00", CurrencyCode.USD),
                "OUTBOUND", "WIRE", "IR", "Acme Ltd", BASE);
        var alerts = monitor.monitorTransaction(txn);

        assertThat(alerts).anySatisfy(a ->
                assertThat(a.typology()).isEqualTo(TypologyCode.HIGH_RISK_JURISDICTION));
    }

    @Test
    void low_risk_jurisdiction_does_not_generate_alert() {
        var txn = txnWithCounterparty(Money.of("2000.00", CurrencyCode.USD),
                "OUTBOUND", "WIRE", "CA", "Acme Ltd", BASE);
        var alerts = monitor.monitorTransaction(txn);

        assertThat(alerts).noneSatisfy(a ->
                assertThat(a.typology()).isEqualTo(TypologyCode.HIGH_RISK_JURISDICTION));
    }

    @Test
    void rapid_movement_detection_fires_when_most_funds_leave_quickly() {
        monitor.monitorTransaction(txn(Money.of("20000.00", CurrencyCode.USD), "INBOUND", "WIRE", null, BASE));
        monitor.monitorTransaction(txn(Money.of("18000.00", CurrencyCode.USD), "OUTBOUND", "WIRE", null, BASE.plus(Duration.ofMinutes(30))));
        var alerts = monitor.monitorTransaction(txn(Money.of("100.00", CurrencyCode.USD), "INBOUND", "WIRE", null, BASE.plus(Duration.ofHours(1))));

        assertThat(alerts).anySatisfy(a ->
                assertThat(a.typology()).isEqualTo(TypologyCode.RAPID_MOVEMENT));
    }

    @Test
    void round_trip_detected_when_funds_sent_then_received_same_counterparty() {
        monitor.monitorTransaction(txnWithCounterparty(Money.of("5000.00", CurrencyCode.USD),
                "OUTBOUND", "WIRE", "US", "Loop Co", BASE));
        var alerts = monitor.monitorTransaction(txnWithCounterparty(Money.of("5000.00", CurrencyCode.USD),
                "INBOUND", "WIRE", "US", "Loop Co", BASE.plus(Duration.ofDays(3))));

        assertThat(alerts).anySatisfy(a ->
                assertThat(a.typology()).isEqualTo(TypologyCode.ROUND_TRIP));
    }

    @Test
    void file_sar_updates_alert_status() {
        monitor.monitorTransaction(txn(Money.of("4000.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE));
        monitor.monitorTransaction(txn(Money.of("4500.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE.plus(Duration.ofHours(3))));
        var alerts = monitor.monitorTransaction(txn(Money.of("3500.00", CurrencyCode.USD), "INBOUND", "CASH", null, BASE.plus(Duration.ofHours(6))));

        AmlAlert alert = alerts.stream()
                .filter(a -> a.typology() == TypologyCode.STRUCTURING)
                .findFirst().orElseThrow();

        var sared = monitor.fileSar(alert.alertId(), "Clear structuring pattern detected", "analyst-1");
        assertThat(sared.status()).isEqualTo(AlertStatus.SAR_FILED);
        assertThat(sared.sarFilingId()).isNotNull();
        assertThat(sared.assignedAnalyst()).isEqualTo("analyst-1");
    }

    @Test
    void close_alert_updates_status_to_no_action() {
        monitor.monitorTransaction(txnWithCounterparty(Money.of("2000.00", CurrencyCode.USD),
                "OUTBOUND", "WIRE", "IR", "Foreign Co", BASE));
        var alerts = monitor.monitorTransaction(txnWithCounterparty(Money.of("2000.00", CurrencyCode.USD),
                "OUTBOUND", "WIRE", "IR", "Foreign Co", BASE.plus(Duration.ofHours(1))));

        AmlAlert alert = alerts.get(0);

        var closed = monitor.closeAlert(alert.alertId(), "Known business counterparty", "analyst-2");
        assertThat(closed.status()).isEqualTo(AlertStatus.CLOSED_NO_ACTION);
        assertThat(closed.sarFilingId()).isNull();
    }

    @Test
    void sar_on_unknown_alert_throws() {
        assertThatThrownBy(() -> monitor.fileSar(UUID.randomUUID(), "narr", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batch_analysis_runs_without_history_and_returns_empty() {
        assertThat(monitor.runBatchAnalysis()).isEmpty();
    }

    @Test
    void typology_description_is_populated() {
        for (var t : TypologyCode.values()) {
            assertThat(t.description()).isNotBlank();
        }
    }

    private MonitoredTransaction txn(Money amount, String direction, String channel,
                                       String counterpartyCountry, Instant ts) {
        return new MonitoredTransaction(UUID.randomUUID(), ACCT, CUST, amount,
                direction, channel, counterpartyCountry, null, ts);
    }

    private MonitoredTransaction txnWithCounterparty(Money amount, String direction, String channel,
                                                       String counterpartyCountry, String counterpartyName,
                                                       Instant ts) {
        return new MonitoredTransaction(UUID.randomUUID(), ACCT, CUST, amount,
                direction, channel, counterpartyCountry, counterpartyName, ts);
    }

    private static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }
}
