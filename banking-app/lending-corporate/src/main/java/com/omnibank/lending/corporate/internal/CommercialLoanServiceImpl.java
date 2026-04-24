package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.AmortizationCalculator;
import com.omnibank.lending.corporate.api.AmortizationSchedule;
import com.omnibank.lending.corporate.api.CommercialLoanService;
import com.omnibank.lending.corporate.api.Covenant;
import com.omnibank.lending.corporate.api.LoanId;
import com.omnibank.lending.corporate.api.LoanSnapshot;
import com.omnibank.lending.corporate.api.LoanStatus;
import com.omnibank.lending.corporate.api.LoanTerms;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Tenor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class CommercialLoanServiceImpl implements CommercialLoanService {

    private final CommercialLoanRepository loans;

    public CommercialLoanServiceImpl(CommercialLoanRepository loans) {
        this.loans = loans;
    }

    @Override
    @Transactional
    public LoanId originate(CustomerId borrower, LoanTerms terms, List<Covenant> covenants) {
        LoanId id = LoanId.newId();
        CommercialLoanEntity entity = new CommercialLoanEntity(
                id.value(),
                borrower.value(),
                terms.structure(),
                terms.principal().amount(),
                terms.currency(),
                terms.rate().basisPoints().longValueExact(),
                terms.dayCount(),
                terms.maturity().toString(),
                terms.originationDate(),
                terms.paymentFrequency()
        );
        loans.save(entity);
        // Covenant persistence omitted in skeleton — TODO: CovenantEntity + repository
        return id;
    }

    @Override
    @Transactional
    public void submitForUnderwriting(LoanId loan) {
        require(loan).transitionTo(LoanStatus.UNDERWRITING);
    }

    @Override
    @Transactional
    public void approve(LoanId loan, String underwriter) {
        require(loan).transitionTo(LoanStatus.APPROVED);
    }

    @Override
    @Transactional
    public void decline(LoanId loan, String reason) {
        require(loan).transitionTo(LoanStatus.DECLINED);
    }

    @Override
    @Transactional
    public void fund(LoanId loan) {
        CommercialLoanEntity e = require(loan);
        e.transitionTo(LoanStatus.FUNDED);
        e.transitionTo(LoanStatus.ACTIVE);
    }

    @Override
    @Transactional
    public void recordDraw(LoanId loan, Money amount, String purpose) {
        CommercialLoanEntity e = require(loan);
        if (e.status() != LoanStatus.ACTIVE) {
            throw new IllegalStateException("Cannot draw against non-active loan: " + e.status());
        }
        if (amount.currency() != e.currency()) {
            throw new IllegalArgumentException("Draw currency mismatch");
        }
        e.recordDraw(amount.amount());
    }

    @Override
    @Transactional
    public void recordRepayment(LoanId loan, Money amount, LocalDate effectiveDate) {
        CommercialLoanEntity e = require(loan);
        if (amount.currency() != e.currency()) {
            throw new IllegalArgumentException("Repayment currency mismatch");
        }
        e.recordRepayment(amount.amount());
        if (e.outstandingPrincipal().signum() == 0) {
            e.transitionTo(LoanStatus.PAID_OFF);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LoanSnapshot snapshot(LoanId loan) {
        CommercialLoanEntity e = require(loan);
        LoanTerms terms = reconstitute(e);
        return new LoanSnapshot(
                loan,
                new CustomerId(e.borrower()),
                e.status(),
                terms,
                Money.of(e.outstandingPrincipal(), e.currency()),
                Money.of(e.totalDrawn(), e.currency()),
                Money.of(e.totalRepaid(), e.currency())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AmortizationSchedule schedule(LoanId loan) {
        CommercialLoanEntity e = require(loan);
        return AmortizationCalculator.standardAmortizing(reconstitute(e));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Covenant> covenants(LoanId loan) {
        require(loan);
        return List.of();  // TODO: implement when CovenantEntity lands
    }

    private CommercialLoanEntity require(LoanId loan) {
        return loans.findById(loan.value()).orElseThrow(() ->
                new IllegalArgumentException("Unknown loan: " + loan));
    }

    private LoanTerms reconstitute(CommercialLoanEntity e) {
        return new LoanTerms(
                e.structure(),
                Money.of(e.principalAmount(), e.currency()),
                com.omnibank.shared.domain.Percent.ofBps(e.rateBps()),
                e.dayCount(),
                Tenor.parse(e.tenorSpec()),
                e.originationDate(),
                e.paymentFrequency(),
                e.currency()
        );
    }
}
