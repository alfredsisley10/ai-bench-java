package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;

public record AmortizationSchedule(List<Installment> installments) {

    public AmortizationSchedule {
        installments = List.copyOf(installments);
    }

    public record Installment(
            int number,
            LocalDate dueDate,
            Money openingBalance,
            Money principal,
            Money interest,
            Money payment,
            Money closingBalance
    ) {}
}
