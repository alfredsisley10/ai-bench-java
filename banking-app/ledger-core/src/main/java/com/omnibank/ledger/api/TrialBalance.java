package com.omnibank.ledger.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TrialBalance(LocalDate asOf, Map<CurrencyCode, List<Row>> byCurrency) {

    public TrialBalance {
        Objects.requireNonNull(asOf, "asOf");
        byCurrency = Map.copyOf(byCurrency);
    }

    public record Row(GlAccountCode account, AccountType type, Money debit, Money credit) {}

    /**
     * Per-currency invariant — sum of debits equals sum of credits. A failed
     * assertion means the posting engine booked something that escaped the
     * balance check: a serious integrity bug.
     */
    public boolean invariantHolds(CurrencyCode currency) {
        List<Row> rows = byCurrency.getOrDefault(currency, List.of());
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (Row r : rows) {
            debits = debits.plus(r.debit());
            credits = credits.plus(r.credit());
        }
        return debits.equals(credits);
    }
}
