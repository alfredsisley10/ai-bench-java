package com.omnibank.shared.domain;

public final class CurrencyMismatchException extends RuntimeException {

    private final CurrencyCode left;
    private final CurrencyCode right;

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("Currency mismatch: " + left + " vs " + right);
        this.left = left;
        this.right = right;
    }

    public CurrencyCode left() {
        return left;
    }

    public CurrencyCode right() {
        return right;
    }
}
