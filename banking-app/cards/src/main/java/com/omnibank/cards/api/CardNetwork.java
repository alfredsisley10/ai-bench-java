package com.omnibank.cards.api;

/**
 * Card networks the bank issues against. Each network has its own settlement
 * cutoffs, interchange tables, dispute rules, and PAN formats. The BIN ranges
 * below are illustrative and used by {@code CardTokenizationService} when it
 * provisions new accounts.
 */
public enum CardNetwork {

    VISA("4", 16),
    MASTERCARD("5", 16),
    AMEX("3", 15),
    DISCOVER("6", 16),
    PULSE("60", 16),
    STAR("59", 16),
    INTERLINK("43", 16),
    MAESTRO("50", 19);

    private final String binPrefix;
    private final int panLength;

    CardNetwork(String binPrefix, int panLength) {
        this.binPrefix = binPrefix;
        this.panLength = panLength;
    }

    public String binPrefix() {
        return binPrefix;
    }

    public int panLength() {
        return panLength;
    }

    public boolean isDebitRail() {
        return this == PULSE || this == STAR || this == INTERLINK || this == MAESTRO;
    }
}
