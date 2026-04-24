package com.omnibank.ledger.api;

import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;

public interface LedgerQueries {

    Money currentBalance(GlAccountCode account);

    Money balanceAsOf(GlAccountCode account, LocalDate asOfInclusive);

    List<PostedJournal> journalHistory(GlAccountCode account, LocalDate fromInclusive, LocalDate toInclusive);

    /**
     * Trial balance snapshot as of the end of the given day. Sum of debits
     * across all accounts must equal the sum of credits — the invariant the
     * posting engine preserves.
     */
    TrialBalance trialBalance(LocalDate asOf);
}
