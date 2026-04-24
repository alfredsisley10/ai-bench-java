package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

public record LoanSnapshot(
        LoanId loan,
        CustomerId borrower,
        LoanStatus status,
        LoanTerms terms,
        Money outstandingPrincipal,
        Money totalDrawn,
        Money totalRepaid
) {}
