package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a standard amortization schedule for a term loan. Uses the textbook
 * formula: P = principal * (r * (1+r)^n) / ((1+r)^n - 1), with r being the
 * periodic rate and n the number of periods.
 *
 * <p>The last installment absorbs the cumulative rounding so the closing
 * balance lands exactly at zero — banks care about the final cent.
 */
public final class AmortizationCalculator {

    private static final MathContext MC = new MathContext(30, RoundingMode.HALF_EVEN);

    private AmortizationCalculator() {}

    public static AmortizationSchedule standardAmortizing(LoanTerms terms) {
        if (terms.structure() != LoanStructure.TERM_LOAN
                && terms.structure() != LoanStructure.SYNDICATED_TERM) {
            throw new IllegalArgumentException("Standard amortization only applies to term loans");
        }
        BigDecimal periodicRate = terms.rate().asFraction(MC)
                .divide(BigDecimal.valueOf(terms.paymentFrequency().periodsPerYear()), MC);
        int periods = totalPeriods(terms);

        BigDecimal onePlusR = BigDecimal.ONE.add(periodicRate);
        BigDecimal pow = onePlusR.pow(periods, MC);
        BigDecimal payment = terms.principal().amount()
                .multiply(periodicRate.multiply(pow, MC), MC)
                .divide(pow.subtract(BigDecimal.ONE), MC);

        Money balance = terms.principal();
        LocalDate dueDate = terms.originationDate();
        List<AmortizationSchedule.Installment> out = new ArrayList<>();
        Money paymentMoney = Money.of(payment, terms.currency());

        for (int i = 1; i <= periods; i++) {
            dueDate = terms.paymentFrequency().advance(dueDate);

            Money interest = Money.of(
                    balance.amount().multiply(periodicRate, MC),
                    terms.currency()
            );

            Money principalPortion;
            Money paymentThis;
            Money closing;
            if (i == periods) {
                // Force balance to zero — dump whatever's left onto the final payment.
                principalPortion = balance;
                paymentThis = principalPortion.plus(interest);
                closing = Money.zero(terms.currency());
            } else {
                principalPortion = paymentMoney.minus(interest);
                closing = balance.minus(principalPortion);
                paymentThis = paymentMoney;
            }

            out.add(new AmortizationSchedule.Installment(
                    i, dueDate, balance, principalPortion, interest, paymentThis, closing
            ));
            balance = closing;
        }
        return new AmortizationSchedule(out);
    }

    private static int totalPeriods(LoanTerms terms) {
        int years = switch (terms.maturity().unit()) {
            case YEARS -> terms.maturity().amount();
            case MONTHS -> (terms.maturity().amount() + 11) / 12;
            case WEEKS -> (terms.maturity().amount() * 7 + 364) / 365;
            case DAYS -> (terms.maturity().amount() + 364) / 365;
        };
        return years * terms.paymentFrequency().periodsPerYear();
    }
}
