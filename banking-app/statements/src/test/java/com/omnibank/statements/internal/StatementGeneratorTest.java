package com.omnibank.statements.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.statements.internal.StatementGenerator.Format;
import com.omnibank.statements.internal.StatementGenerator.GenerationRequest;
import com.omnibank.statements.internal.StatementGenerator.HoldSummary;
import com.omnibank.statements.internal.StatementGenerator.LineType;
import com.omnibank.statements.internal.StatementGenerator.RenderedStatement;
import com.omnibank.statements.internal.StatementGenerator.StatementContent;
import com.omnibank.statements.internal.StatementGenerator.StatementFormatter;
import com.omnibank.statements.internal.StatementGenerator.StatementLineItem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementGeneratorTest {

    private Clock clock;
    private StatementGenerator generator;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        generator = new StatementGenerator(clock);
    }

    @Test
    void computes_closing_balance_from_opening_and_line_items() {
        StatementContent content = generator.generate(StatementTestFixtures.simpleRequest());

        // opening 1000 + deposit 2500 - withdrawal 120 - fee 15 + interest 1.25 = 3366.25
        assertThat(content.summary().closingBalance())
                .isEqualTo(Money.of("3366.25", CurrencyCode.USD));
        assertThat(content.summary().transactionCount()).isEqualTo(4);
    }

    @Test
    void summary_totals_split_by_category() {
        StatementContent content = generator.generate(StatementTestFixtures.simpleRequest());
        assertThat(content.summary().totalDeposits()).isEqualTo(Money.of(2500, CurrencyCode.USD));
        assertThat(content.summary().totalWithdrawals()).isEqualTo(Money.of(120, CurrencyCode.USD));
        assertThat(content.summary().totalFees()).isEqualTo(Money.of(15, CurrencyCode.USD));
        assertThat(content.summary().totalInterestEarned())
                .isEqualTo(Money.of("1.25", CurrencyCode.USD));
    }

    @Test
    void sorts_line_items_by_posting_date() {
        List<StatementLineItem> out_of_order = new ArrayList<>();
        out_of_order.add(new StatementLineItem(LocalDate.of(2026, 4, 10),
                "later", Money.of(10, CurrencyCode.USD), LineType.DEPOSIT, "REF-A"));
        out_of_order.add(new StatementLineItem(LocalDate.of(2026, 4, 1),
                "earlier", Money.of(20, CurrencyCode.USD), LineType.DEPOSIT, "REF-B"));
        var request = new GenerationRequest(
                StatementTestFixtures.header(StatementTestFixtures.CHECKING,
                        LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                Money.of(0, CurrencyCode.USD),
                out_of_order,
                List.of());

        StatementContent content = generator.generate(request);
        assertThat(content.lineItems().get(0).referenceId()).isEqualTo("REF-B");
        assertThat(content.lineItems().get(1).referenceId()).isEqualTo("REF-A");
    }

    @Test
    void rejects_line_items_with_mismatched_currency() {
        List<StatementLineItem> mixed = List.of(
                new StatementLineItem(LocalDate.of(2026, 4, 1),
                        "EUR charge", Money.of(10, CurrencyCode.EUR),
                        LineType.WITHDRAWAL, "REF-X"));
        var request = new GenerationRequest(
                StatementTestFixtures.header(StatementTestFixtures.CHECKING,
                        LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                Money.of(100, CurrencyCode.USD),
                mixed,
                List.of());

        assertThatThrownBy(() -> generator.generate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejects_requests_that_exceed_max_transactions() {
        List<StatementLineItem> many = new ArrayList<>();
        for (int i = 0; i < StatementGenerator.MAX_TRANSACTIONS_PER_STATEMENT + 1; i++) {
            many.add(new StatementLineItem(LocalDate.of(2026, 4, 1),
                    "n", Money.of(1, CurrencyCode.USD), LineType.DEPOSIT, "REF-" + i));
        }
        var request = new GenerationRequest(
                StatementTestFixtures.header(StatementTestFixtures.CHECKING,
                        LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                Money.of(0, CurrencyCode.USD),
                many,
                List.of());

        assertThatThrownBy(() -> generator.generate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many transactions");
    }

    @Test
    void statement_hash_is_deterministic_for_same_inputs() {
        StatementContent a = generator.generate(StatementTestFixtures.simpleRequest());
        StatementContent b = generator.generate(StatementTestFixtures.simpleRequest());
        // generatedAt differs in the id but content-hash excludes it — same hash.
        assertThat(a.contentHash()).isEqualTo(b.contentHash());
    }

    @Test
    void all_three_default_formats_are_registered() {
        assertThat(generator.hasFormatter(Format.PLAIN_TEXT)).isTrue();
        assertThat(generator.hasFormatter(Format.HTML)).isTrue();
        assertThat(generator.hasFormatter(Format.PDF)).isTrue();
    }

    @Test
    void plain_text_format_includes_header_and_totals() {
        RenderedStatement r = generator.generateAndRender(
                StatementTestFixtures.simpleRequest(), Format.PLAIN_TEXT);
        String body = new String(r.payload(), StandardCharsets.UTF_8);
        assertThat(body).contains("Omnibank");
        assertThat(body).contains("Opening Balance");
        assertThat(body).contains("Closing Balance");
        assertThat(body).contains("TRANSACTIONS");
        assertThat(body).contains("Direct deposit");
        assertThat(body).contains("HOLDS");
    }

    @Test
    void html_format_escapes_angle_brackets_in_description() {
        var tricky = new StatementLineItem(LocalDate.of(2026, 4, 2),
                "payee <script>", Money.of(10, CurrencyCode.USD),
                LineType.WITHDRAWAL, "REF-X");
        var request = new GenerationRequest(
                StatementTestFixtures.header(StatementTestFixtures.CHECKING,
                        LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                Money.of(100, CurrencyCode.USD),
                List.of(tricky),
                List.of());

        RenderedStatement rendered = generator.generateAndRender(request, Format.HTML);
        String body = new String(rendered.payload(), StandardCharsets.UTF_8);
        assertThat(body).contains("&lt;script&gt;");
        assertThat(body).doesNotContain("<script>");
    }

    @Test
    void pdf_stub_bytes_start_with_pdf_magic() {
        RenderedStatement r = generator.generateAndRender(
                StatementTestFixtures.simpleRequest(), Format.PDF);
        String prefix = new String(r.payload(), 0, Math.min(8, r.payload().length), StandardCharsets.UTF_8);
        assertThat(prefix).isEqualTo("%PDF-1.4");
    }

    @Test
    void custom_formatter_can_be_registered_and_used() {
        StatementFormatter custom = new StatementFormatter() {
            @Override
            public Format format() { return Format.HTML; }

            @Override
            public byte[] render(StatementContent content) {
                return ("CUSTOM:" + content.statementId()).getBytes(StandardCharsets.UTF_8);
            }
        };
        generator.registerFormatter(custom);
        RenderedStatement r = generator.generateAndRender(
                StatementTestFixtures.simpleRequest(), Format.HTML);
        assertThat(new String(r.payload(), StandardCharsets.UTF_8)).startsWith("CUSTOM:");
    }

    @Test
    void holds_do_not_affect_balance_totals() {
        var hold = new HoldSummary("Pending deposit hold",
                Money.of(500, CurrencyCode.USD),
                LocalDate.of(2026, 4, 20),
                LocalDate.of(2026, 4, 25));
        var request = new GenerationRequest(
                StatementTestFixtures.header(StatementTestFixtures.CHECKING,
                        LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)),
                Money.of(100, CurrencyCode.USD),
                List.of(),
                List.of(hold));

        StatementContent content = generator.generate(request);
        assertThat(content.summary().closingBalance()).isEqualTo(Money.of(100, CurrencyCode.USD));
        assertThat(content.holds()).hasSize(1);
    }

    @Test
    void invalid_header_rejects_reversed_cycle_dates() {
        assertThatThrownBy(() -> StatementTestFixtures.header(
                StatementTestFixtures.CHECKING,
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 4, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycleEnd");
    }
}
