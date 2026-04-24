package com.omnibank.ledger.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A proposed journal entry. Not yet persisted — the posting engine validates
 * it, assigns an id, and books it. Debit total must equal credit total in
 * the same currency.
 */
public record JournalEntry(
        UUID proposalId,
        LocalDate postingDate,
        String businessKey,
        String description,
        List<PostingLine> lines
) {

    public JournalEntry {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(postingDate, "postingDate");
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(lines, "lines");
        if (lines.size() < 2) {
            throw new IllegalArgumentException("Journal entry must have at least 2 lines");
        }
        lines = List.copyOf(lines);
    }

    public CurrencyCode currency() {
        return lines.get(0).amount().currency();
    }

    public Money debitTotal() {
        CurrencyCode ccy = currency();
        return lines.stream()
                .filter(l -> l.direction() == PostingDirection.DEBIT)
                .map(PostingLine::amount)
                .reduce(Money.zero(ccy), Money::plus);
    }

    public Money creditTotal() {
        CurrencyCode ccy = currency();
        return lines.stream()
                .filter(l -> l.direction() == PostingDirection.CREDIT)
                .map(PostingLine::amount)
                .reduce(Money.zero(ccy), Money::plus);
    }

    public boolean isBalanced() {
        return debitTotal().equals(creditTotal());
    }
}
