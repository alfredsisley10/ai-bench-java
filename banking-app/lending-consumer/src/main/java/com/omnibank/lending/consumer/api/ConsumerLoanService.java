package com.omnibank.lending.consumer.api;

import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import java.util.UUID;

public interface ConsumerLoanService {

    UUID apply(CustomerId borrower, ConsumerLoanProduct product, Money principal);

    ConsumerLoanStatus status(UUID loan);

    void recordPayment(UUID loan, Money amount);
}
