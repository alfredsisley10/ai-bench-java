package com.omnibank.ledger.api;

import com.omnibank.shared.domain.Money;

import java.util.Objects;

/**
 * A single side of a journal entry — debit or credit a specific GL account
 * for a specific amount. Immutable. The engine validates that the sum of
 * debits equals the sum of credits across all lines before committing.
 */
public record PostingLine(GlAccountCode account, PostingDirection direction, Money amount, String memo) {

    public PostingLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative() || amount.isZero()) {
            // Signs are encoded by direction, amounts are always non-negative positive.
            throw new IllegalArgumentException("Posting amount must be positive: " + amount);
        }
    }

    public static PostingLine debit(GlAccountCode account, Money amount, String memo) {
        return new PostingLine(account, PostingDirection.DEBIT, amount, memo);
    }

    public static PostingLine credit(GlAccountCode account, Money amount, String memo) {
        return new PostingLine(account, PostingDirection.CREDIT, amount, memo);
    }
}
