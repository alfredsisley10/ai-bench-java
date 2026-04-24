package com.omnibank.ledger.api;

public enum PostingDirection {
    DEBIT, CREDIT;

    public PostingDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
