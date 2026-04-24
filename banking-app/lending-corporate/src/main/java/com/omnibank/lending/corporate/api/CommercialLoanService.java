package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;

public interface CommercialLoanService {

    LoanId originate(CustomerId borrower, LoanTerms terms, List<Covenant> covenants);

    void submitForUnderwriting(LoanId loan);

    void approve(LoanId loan, String underwriter);

    void decline(LoanId loan, String reason);

    void fund(LoanId loan);

    void recordDraw(LoanId loan, Money amount, String purpose);

    void recordRepayment(LoanId loan, Money amount, LocalDate effectiveDate);

    LoanSnapshot snapshot(LoanId loan);

    AmortizationSchedule schedule(LoanId loan);

    List<Covenant> covenants(LoanId loan);
}
