package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.LoanId;
import com.omnibank.lending.corporate.api.LoanTerms;
import com.omnibank.lending.corporate.api.PaymentFrequency;
import com.omnibank.shared.domain.DayCountConvention;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Calculates prepayment penalties and breakage costs for corporate loans.
 * Supports three common prepayment structures:
 *
 * <ul>
 *   <li><strong>Yield maintenance</strong>: compensates the lender for lost yield
 *       by calculating the present value of remaining cash flows discounted at
 *       the Treasury rate plus a margin.</li>
 *   <li><strong>Make-whole</strong>: similar to yield maintenance but uses a
 *       specific reference rate (Treasury + spread) to discount remaining payments.</li>
 *   <li><strong>Step-down</strong>: fixed percentage penalty that decreases over
 *       the life of the loan (e.g., 3% in year 1, 2% in year 2, 1% in year 3).</li>
 * </ul>
 *
 * <p>For floating-rate loans that have been swapped to fixed, prepayment also
 * incurs breakage costs reflecting the mark-to-market value of the interest
 * rate swap.
 */
public class PrepaymentCalculator {

    private static final MathContext MC = new MathContext(30, RoundingMode.HALF_EVEN);

    // ── Prepayment structures ─────────────────────────────────────────────

    public enum PrepaymentType { YIELD_MAINTENANCE, MAKE_WHOLE, STEP_DOWN, NONE }

    public record StepDownSchedule(List<StepDownTier> tiers) {
        public StepDownSchedule {
            tiers = List.copyOf(tiers);
            if (tiers.isEmpty()) {
                throw new IllegalArgumentException("Step-down schedule must have at least one tier");
            }
        }

        public BigDecimal penaltyRateForYear(int yearOfLoan) {
            return tiers.stream()
                    .filter(t -> yearOfLoan >= t.fromYear() && yearOfLoan <= t.toYear())
                    .map(StepDownTier::penaltyPercent)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }
    }

    public record StepDownTier(int fromYear, int toYear, BigDecimal penaltyPercent) {
        public StepDownTier {
            if (fromYear < 1) throw new IllegalArgumentException("fromYear must be >= 1");
            if (toYear < fromYear) throw new IllegalArgumentException("toYear must be >= fromYear");
            Objects.requireNonNull(penaltyPercent, "penaltyPercent");
        }
    }

    // ── Prepayment result ─────────────────────────────────────────────────

    public record PrepaymentResult(
            LoanId loanId,
            Money prepaymentAmount,
            Money penaltyAmount,
            Money breakageCost,
            Money totalCost,
            Money totalPayment,
            PrepaymentType penaltyType,
            LocalDate effectiveDate,
            Money newOutstandingBalance,
            boolean isFullPayoff,
            String calculation
    ) {
        public PrepaymentResult {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(prepaymentAmount, "prepaymentAmount");
            Objects.requireNonNull(penaltyAmount, "penaltyAmount");
            Objects.requireNonNull(breakageCost, "breakageCost");
            Objects.requireNonNull(totalCost, "totalCost");
            Objects.requireNonNull(totalPayment, "totalPayment");
            Objects.requireNonNull(penaltyType, "penaltyType");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            Objects.requireNonNull(newOutstandingBalance, "newOutstandingBalance");
        }
    }

    // ── Swap breakage ─────────────────────────────────────────────────────

    public record SwapDetails(
            Money notionalAmount,
            Percent fixedRate,
            Percent currentFloatingRate,
            LocalDate swapMaturityDate,
            PaymentFrequency paymentFrequency,
            DayCountConvention dayCount
    ) {
        public SwapDetails {
            Objects.requireNonNull(notionalAmount, "notionalAmount");
            Objects.requireNonNull(fixedRate, "fixedRate");
            Objects.requireNonNull(currentFloatingRate, "currentFloatingRate");
            Objects.requireNonNull(swapMaturityDate, "swapMaturityDate");
            Objects.requireNonNull(paymentFrequency, "paymentFrequency");
            Objects.requireNonNull(dayCount, "dayCount");
        }
    }

    // ── Step-down penalty ─────────────────────────────────────────────────

    /**
     * Calculates prepayment penalty using a step-down schedule. The penalty
     * percentage decreases the longer the loan has been outstanding.
     */
    public static Money calculateStepDownPenalty(
            Money prepaymentAmount,
            LocalDate originationDate,
            LocalDate prepaymentDate,
            StepDownSchedule schedule
    ) {
        long daysSinceOrigination = ChronoUnit.DAYS.between(originationDate, prepaymentDate);
        int yearOfLoan = (int) (daysSinceOrigination / 365) + 1;
        BigDecimal penaltyRate = schedule.penaltyRateForYear(yearOfLoan);
        return prepaymentAmount.times(penaltyRate.divide(BigDecimal.valueOf(100), MC));
    }

    // ── Yield maintenance penalty ─────────────────────────────────────────

    /**
     * Calculates yield maintenance: the present value of the difference between
     * the loan's coupon rate and the reinvestment rate (Treasury), applied to
     * remaining scheduled payments. Essentially compensates the lender for the
     * lost interest income if they reinvest at a lower rate.
     */
    public static Money calculateYieldMaintenance(
            Money outstandingPrincipal,
            Percent loanRate,
            Percent treasuryRate,
            LocalDate prepaymentDate,
            LocalDate maturityDate,
            PaymentFrequency frequency,
            DayCountConvention dayCount
    ) {
        BigDecimal loanRateFraction = loanRate.asFraction(MC);
        BigDecimal treasuryRateFraction = treasuryRate.asFraction(MC);
        BigDecimal rateDiff = loanRateFraction.subtract(treasuryRateFraction);

        // If Treasury rate is above the loan rate, no penalty (lender can reinvest at higher rate)
        if (rateDiff.signum() <= 0) {
            return Money.zero(outstandingPrincipal.currency());
        }

        BigDecimal periodicDiff = rateDiff.divide(
                BigDecimal.valueOf(frequency.periodsPerYear()), MC);
        BigDecimal discountRate = treasuryRateFraction.divide(
                BigDecimal.valueOf(frequency.periodsPerYear()), MC);

        // Count remaining periods
        long daysRemaining = ChronoUnit.DAYS.between(prepaymentDate, maturityDate);
        int periodsRemaining = (int) Math.ceil(
                (double) daysRemaining / (365.0 / frequency.periodsPerYear()));

        if (periodsRemaining <= 0) {
            return Money.zero(outstandingPrincipal.currency());
        }

        // PV of remaining rate differentials
        BigDecimal pvFactor = BigDecimal.ZERO;
        for (int i = 1; i <= periodsRemaining; i++) {
            BigDecimal discountFactor = BigDecimal.ONE.add(discountRate)
                    .pow(i, MC);
            pvFactor = pvFactor.add(BigDecimal.ONE.divide(discountFactor, MC));
        }

        Money periodicLoss = outstandingPrincipal.times(periodicDiff);
        return Money.of(periodicLoss.amount().multiply(pvFactor, MC),
                outstandingPrincipal.currency());
    }

    // ── Make-whole premium ────────────────────────────────────────────────

    /**
     * Calculates make-whole premium: the greater of (a) par value (no penalty)
     * or (b) the present value of remaining payments discounted at the
     * reference rate (Treasury + spread), minus the outstanding principal.
     */
    public static Money calculateMakeWholePremium(
            Money outstandingPrincipal,
            Percent loanRate,
            Percent referenceRate,
            Percent makeWholeSpread,
            LocalDate prepaymentDate,
            LocalDate maturityDate,
            PaymentFrequency frequency
    ) {
        BigDecimal loanRateFraction = loanRate.asFraction(MC);
        BigDecimal discountRateFraction = referenceRate.asFraction(MC)
                .add(makeWholeSpread.asFraction(MC));
        BigDecimal periodicCoupon = loanRateFraction.divide(
                BigDecimal.valueOf(frequency.periodsPerYear()), MC);
        BigDecimal periodicDiscount = discountRateFraction.divide(
                BigDecimal.valueOf(frequency.periodsPerYear()), MC);

        long daysRemaining = ChronoUnit.DAYS.between(prepaymentDate, maturityDate);
        int periodsRemaining = (int) Math.ceil(
                (double) daysRemaining / (365.0 / frequency.periodsPerYear()));

        if (periodsRemaining <= 0) {
            return Money.zero(outstandingPrincipal.currency());
        }

        // PV of remaining coupon payments + PV of principal at maturity
        BigDecimal pvCoupons = BigDecimal.ZERO;
        Money periodicPayment = outstandingPrincipal.times(periodicCoupon);

        for (int i = 1; i <= periodsRemaining; i++) {
            BigDecimal df = BigDecimal.ONE.add(periodicDiscount).pow(i, MC);
            pvCoupons = pvCoupons.add(periodicPayment.amount().divide(df, MC));
        }

        BigDecimal terminalDf = BigDecimal.ONE.add(periodicDiscount).pow(periodsRemaining, MC);
        BigDecimal pvPrincipal = outstandingPrincipal.amount().divide(terminalDf, MC);
        BigDecimal totalPv = pvCoupons.add(pvPrincipal);

        // Make-whole premium = max(0, PV of remaining CF - outstanding principal)
        BigDecimal premium = totalPv.subtract(outstandingPrincipal.amount()).max(BigDecimal.ZERO);
        return Money.of(premium, outstandingPrincipal.currency());
    }

    // ── Swap breakage cost ────────────────────────────────────────────────

    /**
     * Calculates the breakage cost for an interest rate swap associated with
     * a floating-rate loan that has been swapped to fixed. The cost is the
     * mark-to-market value of the remaining swap, which can be a cost or a
     * benefit depending on rate movement since inception.
     */
    public static Money calculateSwapBreakageCost(
            SwapDetails swap,
            LocalDate breakDate,
            Percent currentMarketSwapRate
    ) {
        BigDecimal fixedFraction = swap.fixedRate().asFraction(MC);
        BigDecimal currentFraction = currentMarketSwapRate.asFraction(MC);
        BigDecimal rateDiff = fixedFraction.subtract(currentFraction);

        long daysRemaining = ChronoUnit.DAYS.between(breakDate, swap.swapMaturityDate());
        int periodsRemaining = (int) Math.ceil(
                (double) daysRemaining / (365.0 / swap.paymentFrequency().periodsPerYear()));

        if (periodsRemaining <= 0) {
            return Money.zero(swap.notionalAmount().currency());
        }

        BigDecimal periodicDiff = rateDiff.divide(
                BigDecimal.valueOf(swap.paymentFrequency().periodsPerYear()), MC);
        BigDecimal periodicDiscount = currentFraction.divide(
                BigDecimal.valueOf(swap.paymentFrequency().periodsPerYear()), MC);

        BigDecimal pvBreakage = BigDecimal.ZERO;
        Money periodicAmount = swap.notionalAmount().times(periodicDiff.abs());

        for (int i = 1; i <= periodsRemaining; i++) {
            BigDecimal df = BigDecimal.ONE.add(periodicDiscount).pow(i, MC);
            pvBreakage = pvBreakage.add(periodicAmount.amount().divide(df, MC));
        }

        // Positive = cost to borrower (rates dropped), Negative = benefit (rates rose)
        if (rateDiff.signum() > 0) {
            return Money.of(pvBreakage, swap.notionalAmount().currency());
        } else {
            return Money.of(pvBreakage.negate(), swap.notionalAmount().currency());
        }
    }

    // ── Full prepayment calculation ───────────────────────────────────────

    /**
     * Computes the full cost of a prepayment including penalty and any swap
     * breakage. Combines all components into a single result.
     */
    public static PrepaymentResult calculateFullPrepayment(
            LoanId loanId,
            Money outstandingPrincipal,
            Money prepaymentAmount,
            PrepaymentType penaltyType,
            LoanTerms terms,
            LocalDate prepaymentDate,
            Optional<Percent> treasuryRate,
            Optional<StepDownSchedule> stepDownSchedule,
            Optional<Percent> makeWholeSpread,
            Optional<SwapDetails> swapDetails,
            Optional<Percent> currentMarketSwapRate
    ) {
        boolean isFullPayoff = prepaymentAmount.compareTo(outstandingPrincipal) >= 0;
        Money effectivePrepayment = isFullPayoff ? outstandingPrincipal : prepaymentAmount;

        // Calculate prepayment penalty
        Money penalty = switch (penaltyType) {
            case STEP_DOWN -> {
                StepDownSchedule schedule = stepDownSchedule.orElseThrow(
                        () -> new IllegalArgumentException("Step-down schedule required"));
                yield calculateStepDownPenalty(
                        effectivePrepayment, terms.originationDate(), prepaymentDate, schedule);
            }
            case YIELD_MAINTENANCE -> {
                Percent treasury = treasuryRate.orElseThrow(
                        () -> new IllegalArgumentException("Treasury rate required for yield maintenance"));
                yield calculateYieldMaintenance(
                        effectivePrepayment, terms.rate(), treasury,
                        prepaymentDate, terms.maturityDate(),
                        terms.paymentFrequency(), terms.dayCount());
            }
            case MAKE_WHOLE -> {
                Percent treasury = treasuryRate.orElseThrow(
                        () -> new IllegalArgumentException("Treasury rate required for make-whole"));
                Percent spread = makeWholeSpread.orElse(Percent.ofBps(50));
                yield calculateMakeWholePremium(
                        effectivePrepayment, terms.rate(), treasury, spread,
                        prepaymentDate, terms.maturityDate(), terms.paymentFrequency());
            }
            case NONE -> Money.zero(effectivePrepayment.currency());
        };

        // Calculate swap breakage if applicable
        Money breakage = Money.zero(effectivePrepayment.currency());
        if (swapDetails.isPresent() && currentMarketSwapRate.isPresent()) {
            breakage = calculateSwapBreakageCost(
                    swapDetails.get(), prepaymentDate, currentMarketSwapRate.get());
            // Only charge if it's a cost to the borrower
            if (breakage.isNegative()) {
                breakage = Money.zero(effectivePrepayment.currency());
            }
        }

        Money totalCost = penalty.plus(breakage);
        Money totalPayment = effectivePrepayment.plus(totalCost);
        Money newBalance = outstandingPrincipal.minus(effectivePrepayment);

        String calcNarrative = "Prepayment of %s with %s penalty of %s and breakage cost of %s. Total payment: %s."
                .formatted(effectivePrepayment, penaltyType, penalty, breakage, totalPayment);

        return new PrepaymentResult(
                loanId, effectivePrepayment, penalty, breakage,
                totalCost, totalPayment, penaltyType, prepaymentDate,
                newBalance, isFullPayoff, calcNarrative
        );
    }
}
