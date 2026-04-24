package com.omnibank.cards.internal;

import com.omnibank.cards.api.CardNetwork;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardSettlementProcessorTest {

    private Clock clock;
    private CardsTestSupport.RecordingPostingService posting;
    private CardsTestSupport.RecordingEventBus events;
    private CardSettlementProcessor processor;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-16T10:00:00Z"), ZoneId.of("UTC"));
        posting = new CardsTestSupport.RecordingPostingService();
        events = new CardsTestSupport.RecordingEventBus();
        processor = new CardSettlementProcessor(posting, events, clock);
    }

    private ClearingFile.ClearingRecord rec(String amount, String mcc, boolean reversal, boolean ret) {
        return new ClearingFile.ClearingRecord(
                UUID.randomUUID(), UUID.randomUUID(),
                Money.of(amount, CurrencyCode.USD),
                Money.of("0.15", CurrencyCode.USD),
                Money.of("0.02", CurrencyCode.USD),
                mcc, "US", reversal, ret, "00");
    }

    @Test
    void posts_journal_entry_per_record_for_purchase() {
        var file = new ClearingFile("F1", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(rec("100.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        var summary = processor.process(file);

        // one purchase + one interchange + one network fee = 3 entries
        assertThat(posting.posted).hasSize(3);
        assertThat(summary.successCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.grossTransactions())
                .isEqualTo(Money.of("100.00", CurrencyCode.USD));
    }

    @Test
    void reversal_produces_opposite_postings() {
        var file = new ClearingFile("F2", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(rec("50.00", "5812", true, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        processor.process(file);

        var purchase = posting.posted.get(0);
        assertThat(purchase.businessKey()).startsWith("CARD-REVERSAL-");
    }

    @Test
    void purchase_return_produces_dedicated_postings() {
        var file = new ClearingFile("F3", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(rec("25.00", "5812", false, true)),
                Instant.parse("2026-04-16T09:00:00Z"));

        processor.process(file);

        var first = posting.posted.get(0);
        assertThat(first.businessKey()).startsWith("CARD-RETURN-");
    }

    @Test
    void amex_skips_interchange_posting() {
        var file = new ClearingFile("F-AMEX", CardNetwork.AMEX, LocalDate.of(2026, 4, 16),
                List.of(rec("200.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        processor.process(file);

        // Amex: purchase + network fee only (no interchange)
        assertThat(posting.posted).hasSize(2);
        assertThat(posting.posted.stream().noneMatch(
                je -> je.businessKey().startsWith("CARD-ICHG-"))).isTrue();
    }

    @Test
    void idempotent_reingestion_is_noop() {
        var file = new ClearingFile("F4", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(rec("30.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        processor.process(file);
        int postedAfterFirst = posting.posted.size();

        processor.process(file);

        assertThat(posting.posted.size()).isEqualTo(postedAfterFirst);
    }

    @Test
    void publishes_settlement_event_for_each_record() {
        var file = new ClearingFile("F5", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(
                        rec("10.00", "5812", false, false),
                        rec("20.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        processor.process(file);

        var settlementEvents = events.eventsOfType(CardSettlementProcessor.SettlementEvent.class);
        assertThat(settlementEvents).hasSize(2);
    }

    @Test
    void records_that_throw_are_counted_as_failed() {
        var file = new ClearingFile("F6", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(
                        rec("10.00", "5812", false, false),
                        rec("20.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        posting.throwOnNext = true;

        var summary = processor.process(file);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.successCount()).isEqualTo(1);
    }

    @Test
    void processAll_aggregates_across_networks() {
        var visa = new ClearingFile("FV", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(rec("10.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));
        var mc = new ClearingFile("FM", CardNetwork.MASTERCARD, LocalDate.of(2026, 4, 16),
                List.of(rec("20.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        var combined = processor.processAll(List.of(visa, mc));

        assertThat(combined.recordCount()).isEqualTo(2);
        assertThat(combined.grossTransactions())
                .isEqualTo(Money.of("30.00", CurrencyCode.USD));
    }

    @Test
    void isProcessed_flag_reflects_prior_ingestion() {
        var file = new ClearingFile("F-DUP", CardNetwork.VISA, LocalDate.of(2026, 4, 16),
                List.of(rec("5.00", "5812", false, false)),
                Instant.parse("2026-04-16T09:00:00Z"));

        assertThat(processor.isProcessed("F-DUP")).isFalse();
        processor.process(file);
        assertThat(processor.isProcessed("F-DUP")).isTrue();
    }
}
