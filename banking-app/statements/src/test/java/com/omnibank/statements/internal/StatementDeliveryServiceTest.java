package com.omnibank.statements.internal;

import com.omnibank.statements.internal.StatementDeliveryService.Channel;
import com.omnibank.statements.internal.StatementDeliveryService.ChannelTransport;
import com.omnibank.statements.internal.StatementDeliveryService.DeliveryDestination;
import com.omnibank.statements.internal.StatementDeliveryService.DeliveryRecord;
import com.omnibank.statements.internal.StatementDeliveryService.DeliveryStatus;
import com.omnibank.statements.internal.StatementDeliveryService.TransportException;
import com.omnibank.statements.internal.StatementGenerator.Format;
import com.omnibank.statements.internal.StatementGenerator.RenderedStatement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementDeliveryServiceTest {

    private Clock clock;
    private StatementGenerator generator;
    private StatementDeliveryService service;
    private RecordingTransport sftp;
    private RecordingTransport mail;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
        generator = new StatementGenerator(clock);
        service = new StatementDeliveryService(clock);
        sftp = new RecordingTransport(Channel.E_STATEMENT_SFTP);
        mail = new RecordingTransport(Channel.USPS_PHYSICAL_MAIL);
        service.registerTransport(sftp);
        service.registerTransport(mail);
    }

    private RenderedStatement renderPdf() {
        return generator.generateAndRender(StatementTestFixtures.simpleRequest(), Format.PDF);
    }

    @Test
    void successful_delivery_records_delivered_status_once() {
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP,
                "sftp://ex/", null, "primary");
        DeliveryRecord r = service.deliver(renderPdf(), StatementTestFixtures.ALICE, destination);
        assertThat(r.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(r.attemptCount()).isEqualTo(1);
        assertThat(sftp.invocations).isEqualTo(1);
    }

    @Test
    void transient_failure_marks_pending_with_next_retry_at() {
        sftp.returnAccepted = false;
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP,
                "sftp://ex/", null, "primary");
        DeliveryRecord r = service.deliver(renderPdf(), StatementTestFixtures.ALICE, destination);
        assertThat(r.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(r.nextRetryAt()).isNotNull();
        assertThat(r.lastError()).isNotNull();
    }

    @Test
    void permanent_transport_failure_marks_failed() {
        sftp.throwPermanent = true;
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP,
                "sftp://ex/", null, "primary");
        DeliveryRecord r = service.deliver(renderPdf(), StatementTestFixtures.ALICE, destination);
        assertThat(r.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(r.isTerminal()).isTrue();
    }

    @Test
    void rejects_delivery_when_channel_does_not_accept_format() {
        var plain = generator.generateAndRender(StatementTestFixtures.simpleRequest(), Format.PLAIN_TEXT);
        var destination = new DeliveryDestination(Channel.USPS_PHYSICAL_MAIL,
                "123 Main St", null, "primary");

        assertThatThrownBy(() -> service.deliver(plain, StatementTestFixtures.ALICE, destination))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USPS_PHYSICAL_MAIL");
    }

    @Test
    void rejects_when_no_transport_registered_for_channel() {
        StatementDeliveryService bare = new StatementDeliveryService(clock);
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP, "sftp://ex/", null, null);
        DeliveryRecord r = bare.deliver(renderPdf(), StatementTestFixtures.ALICE, destination);
        assertThat(r.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(r.lastError()).contains("No transport");
    }

    @Test
    void retry_eventually_succeeds_after_transient_failures() {
        sftp.returnAccepted = false;
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP,
                "sftp://ex/", null, "primary");
        RenderedStatement rendered = renderPdf();
        DeliveryRecord first = service.deliver(rendered, StatementTestFixtures.ALICE, destination);
        assertThat(first.status()).isEqualTo(DeliveryStatus.PENDING);

        sftp.returnAccepted = true;
        DeliveryRecord second = service.retry(first.deliveryId(), rendered, destination);
        assertThat(second.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(second.attemptCount()).isEqualTo(2);
    }

    @Test
    void retry_gives_up_after_max_attempts() {
        sftp.returnAccepted = false;
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP,
                "sftp://ex/", null, "primary");
        RenderedStatement rendered = renderPdf();
        DeliveryRecord r = service.deliver(rendered, StatementTestFixtures.ALICE, destination);
        for (int i = 0; i < StatementDeliveryService.MAX_RETRY_ATTEMPTS + 1; i++) {
            r = service.retry(r.deliveryId(), rendered, destination);
        }
        assertThat(r.status()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void retry_on_already_delivered_is_no_op() {
        var destination = new DeliveryDestination(Channel.E_STATEMENT_SFTP,
                "sftp://ex/", null, null);
        RenderedStatement rendered = renderPdf();
        DeliveryRecord r = service.deliver(rendered, StatementTestFixtures.ALICE, destination);
        assertThat(r.status()).isEqualTo(DeliveryStatus.DELIVERED);

        DeliveryRecord r2 = service.retry(r.deliveryId(), rendered, destination);
        assertThat(r2).isEqualTo(r);
    }

    @Test
    void history_lists_records_for_account() {
        var dest = new DeliveryDestination(Channel.E_STATEMENT_SFTP, "sftp://ex/", null, null);
        service.deliver(renderPdf(), StatementTestFixtures.ALICE, dest);
        service.deliver(renderPdf(), StatementTestFixtures.ALICE, dest);
        assertThat(service.historyFor(StatementTestFixtures.CHECKING)).hasSize(2);
    }

    @Test
    void due_for_retry_only_returns_pending_past_due() {
        sftp.returnAccepted = false;
        var dest = new DeliveryDestination(Channel.E_STATEMENT_SFTP, "sftp://ex/", null, null);
        service.deliver(renderPdf(), StatementTestFixtures.ALICE, dest);
        // With the frozen clock the next retry is in the future, so none due.
        assertThat(service.dueForRetry()).isEmpty();
    }

    @Test
    void count_by_status_reflects_outcomes() {
        var dest = new DeliveryDestination(Channel.E_STATEMENT_SFTP, "sftp://ex/", null, null);
        service.deliver(renderPdf(), StatementTestFixtures.ALICE, dest);
        assertThat(service.countByStatus(DeliveryStatus.DELIVERED)).isEqualTo(1);
    }

    @Test
    void wired_channels_reports_registered_transports() {
        assertThat(service.wiredChannels())
                .containsExactlyInAnyOrder(Channel.E_STATEMENT_SFTP, Channel.USPS_PHYSICAL_MAIL);
    }

    @Test
    void get_record_throws_for_unknown_id() {
        assertThatThrownBy(() -> service.getRecord("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingTransport implements ChannelTransport {
        private final Channel channel;
        int invocations = 0;
        boolean returnAccepted = true;
        boolean throwPermanent = false;

        RecordingTransport(Channel channel) {
            this.channel = channel;
        }

        @Override
        public Channel channel() { return channel; }

        @Override
        public boolean deliver(DeliveryDestination destination, RenderedStatement rendered)
                throws TransportException {
            invocations++;
            if (throwPermanent) {
                throw new TransportException("bad address");
            }
            return returnAccepted;
        }
    }
}
