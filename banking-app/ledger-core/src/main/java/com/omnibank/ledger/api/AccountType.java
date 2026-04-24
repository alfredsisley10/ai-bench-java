package com.omnibank.ledger.api;

/**
 * Five-way GAAP classification. {@link NormalBalance} tells the posting
 * engine which direction increases the balance — an asset grows on debit,
 * a liability grows on credit. Get these wrong and everything under it
 * quietly inverts.
 */
public enum AccountType {
    ASSET(NormalBalance.DEBIT),
    LIABILITY(NormalBalance.CREDIT),
    EQUITY(NormalBalance.CREDIT),
    REVENUE(NormalBalance.CREDIT),
    EXPENSE(NormalBalance.DEBIT);

    private final NormalBalance normal;

    AccountType(NormalBalance normal) {
        this.normal = normal;
    }

    public NormalBalance normalBalance() {
        return normal;
    }

    public enum NormalBalance {
        DEBIT, CREDIT
    }
}
