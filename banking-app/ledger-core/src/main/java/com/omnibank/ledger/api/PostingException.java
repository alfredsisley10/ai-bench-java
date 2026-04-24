package com.omnibank.ledger.api;

public class PostingException extends RuntimeException {

    public enum Reason {
        UNBALANCED,
        MIXED_CURRENCIES,
        UNKNOWN_ACCOUNT,
        ACCOUNT_CLOSED,
        FUTURE_POST_DATE,
        DUPLICATE_BUSINESS_KEY
    }

    private final Reason reason;

    public PostingException(Reason reason, String msg) {
        super(reason + ": " + msg);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
