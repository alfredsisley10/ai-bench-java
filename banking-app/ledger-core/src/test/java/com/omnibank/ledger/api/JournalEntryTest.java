package com.omnibank.ledger.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final GlAccountCode CASH = new GlAccountCode("ASS-1100-001");
    private static final GlAccountCode DEPOSITS = new GlAccountCode("LIA-2100-001");

    @Test
    void balanced_entry_satisfies_invariant() {
        JournalEntry je = new JournalEntry(
                UUID.randomUUID(),
                LocalDate.of(2026, 4, 15),
                "TEST-001",
                "Customer cash deposit",
                List.of(
                        PostingLine.debit(CASH, Money.of(100, CurrencyCode.USD), "counter-deposit"),
                        PostingLine.credit(DEPOSITS, Money.of(100, CurrencyCode.USD), "dda-liability")
                )
        );
        assertThat(je.isBalanced()).isTrue();
        assertThat(je.debitTotal()).isEqualTo(je.creditTotal());
    }

    @Test
    void single_line_rejected() {
        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), LocalDate.now(), "K", "d",
                List.of(PostingLine.debit(CASH, Money.of(10, CurrencyCode.USD), "x"))
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
