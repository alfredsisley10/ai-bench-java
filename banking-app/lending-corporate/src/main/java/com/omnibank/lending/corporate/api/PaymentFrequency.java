package com.omnibank.lending.corporate.api;

import java.time.LocalDate;

public enum PaymentFrequency {
    MONTHLY(12),
    QUARTERLY(4),
    SEMIANNUAL(2),
    ANNUAL(1);

    private final int periodsPerYear;

    PaymentFrequency(int periodsPerYear) {
        this.periodsPerYear = periodsPerYear;
    }

    public int periodsPerYear() {
        return periodsPerYear;
    }

    public LocalDate advance(LocalDate from) {
        return switch (this) {
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case SEMIANNUAL -> from.plusMonths(6);
            case ANNUAL -> from.plusYears(1);
        };
    }
}
