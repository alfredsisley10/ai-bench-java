package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.DayCountConvention;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;

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
import java.util.UUID;

/**
 * Daily interest accrual engine for corporate loans. Supports multiple day-count
 * conventions (ACT/360, ACT/365, 30/360), rate resets for floating-rate loans,
 * simple and compound interest, and payment application with waterfall ordering.
 *
 * <p>Interest accrues daily, with each day's accrual calculated as:
 * {@code dailyInterest = principal * annualRate * dayCountFraction(1 day)}
 *
 * <p>For floating-rate loans, the rate resets periodically (monthly, quarterly)
 * based on a reference rate (e.g., SOFR) plus a credit spread.
 */
public final class InterestAccrualEngine {

    private static final MathContext MC = new MathContext(30, RoundingMode.HALF_EVEN);

    private InterestAccrualEngine() {}

    // ── Rate model ────────────────────────────────────────────────────────

    public sealed interface RateModel permits RateModel.Fixed, RateModel.Floating {

        Percent currentRate();

        record Fixed(Percent rate) implements RateModel {
            public Fixed {
                Objects.requireNonNull(rate, "rate");
            }

            @Override
            public Percent currentRate() { return rate; }
        }

        record Floating(
                String referenceRate,
                Percent currentBaseRate,
                Percent creditSpread,
                Percent floor,
                Percent ceiling,
                PaymentFrequency resetFrequency,
                LocalDate nextResetDate
        ) implements RateModel {
            public Floating {
                Objects.requireNonNull(referenceRate, "referenceRate");
                Objects.requireNonNull(currentBaseRate, "currentBaseRate");
                Objects.requireNonNull(creditSpread, "creditSpread");
                Objects.requireNonNull(floor, "floor");
                Objects.requireNonNull(ceiling, "ceiling");
                Objects.requireNonNull(resetFrequency, "resetFrequency");
                Objects.requireNonNull(nextResetDate, "nextResetDate");
            }

            @Override
            public Percent currentRate() {
                BigDecimal allIn = currentBaseRate.basisPoints().add(creditSpread.basisPoints());
                if (allIn.compareTo(floor.basisPoints()) < 0) {
                    return floor;
                }
                if (allIn.compareTo(ceiling.basisPoints()) > 0) {
                    return ceiling;
                }
                return Percent.ofBps(allIn);
            }
        }
    }

    // ── Daily accrual record ──────────────────────────────────────────────

    public record DailyAccrual(
            LocalDate accrualDate,
            Money principal,
            Percent annualRate,
            DayCountConvention dayCount,
            BigDecimal dayCountFraction,
            Money interestAmount
    ) {
        public DailyAccrual {
            Objects.requireNonNull(accrualDate, "accrualDate");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(annualRate, "annualRate");
            Objects.requireNonNull(dayCount, "dayCount");
            Objects.requireNonNull(dayCountFraction, "dayCountFraction");
            Objects.requireNonNull(interestAmount, "interestAmount");
        }
    }

    // ── Accrual period summary ────────────────────────────────────────────

    public record AccrualPeriodSummary(
            LocalDate periodStart,
            LocalDate periodEnd,
            Money openingPrincipal,
            Money totalAccruedInterest,
            int accrualDays,
            Percent weightedAverageRate,
            List<DailyAccrual> dailyAccruals,
            List<RateResetEvent> rateResets
    ) {
        public AccrualPeriodSummary {
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(openingPrincipal, "openingPrincipal");
            Objects.requireNonNull(totalAccruedInterest, "totalAccruedInterest");
            Objects.requireNonNull(weightedAverageRate, "weightedAverageRate");
            dailyAccruals = List.copyOf(dailyAccruals);
            rateResets = List.copyOf(rateResets);
        }
    }

    public record RateResetEvent(
            LocalDate resetDate,
            Percent previousRate,
            Percent newRate,
            String referenceRate,
            Percent newBaseRate
    ) {
        public RateResetEvent {
            Objects.requireNonNull(resetDate, "resetDate");
            Objects.requireNonNull(previousRate, "previousRate");
            Objects.requireNonNull(newRate, "newRate");
        }
    }

    // ── Core accrual logic ────────────────────────────────────────────────

    /**
     * Calculates the interest accrual for a single day.
     */
    public static DailyAccrual accrueOneDay(
            LocalDate date,
            Money principal,
            Percent annualRate,
            DayCountConvention dayCount
    ) {
        BigDecimal yearFraction = dayCount.yearFraction(date, date.plusDays(1), MC);
        BigDecimal rateFraction = annualRate.asFraction(MC);
        Money interest = principal.times(rateFraction.multiply(yearFraction, MC));

        return new DailyAccrual(date, principal, annualRate, dayCount, yearFraction, interest);
    }

    /**
     * Accrues interest over a date range using simple (non-compounding) interest.
     * Each day's accrual is based on the opening principal for the period.
     */
    public static AccrualPeriodSummary accrueSimple(
            Money principal,
            RateModel rateModel,
            DayCountConvention dayCount,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        List<DailyAccrual> dailyAccruals = new ArrayList<>();
        List<RateResetEvent> resets = new ArrayList<>();
        Money totalInterest = Money.zero(principal.currency());
        BigDecimal rateWeightSum = BigDecimal.ZERO;

        RateModel currentModel = rateModel;
        LocalDate cursor = periodStart;

        while (cursor.isBefore(periodEnd)) {
            Percent currentRate = currentModel.currentRate();
            DailyAccrual accrual = accrueOneDay(cursor, principal, currentRate, dayCount);
            dailyAccruals.add(accrual);
            totalInterest = totalInterest.plus(accrual.interestAmount());
            rateWeightSum = rateWeightSum.add(currentRate.basisPoints());

            // Check for rate reset on floating loans
            if (currentModel instanceof RateModel.Floating floating
                    && cursor.equals(floating.nextResetDate())) {
                // In production, the new base rate would come from a market data feed.
                // Here we record the reset event with the existing rate.
                resets.add(new RateResetEvent(
                        cursor, floating.currentRate(), floating.currentRate(),
                        floating.referenceRate(), floating.currentBaseRate()
                ));
            }

            cursor = cursor.plusDays(1);
        }

        long days = ChronoUnit.DAYS.between(periodStart, periodEnd);
        Percent weightedRate = days > 0
                ? Percent.ofBps(rateWeightSum.divide(BigDecimal.valueOf(days), MC))
                : Percent.ofBps(0);

        return new AccrualPeriodSummary(
                periodStart, periodEnd, principal, totalInterest,
                (int) days, weightedRate, dailyAccruals, resets
        );
    }

    /**
     * Accrues interest over a date range using daily compounding. Each day's
     * interest is added to the principal for the next day's calculation.
     */
    public static AccrualPeriodSummary accrueCompounding(
            Money principal,
            RateModel rateModel,
            DayCountConvention dayCount,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        List<DailyAccrual> dailyAccruals = new ArrayList<>();
        List<RateResetEvent> resets = new ArrayList<>();
        Money totalInterest = Money.zero(principal.currency());
        Money compoundedPrincipal = principal;
        BigDecimal rateWeightSum = BigDecimal.ZERO;

        LocalDate cursor = periodStart;

        while (cursor.isBefore(periodEnd)) {
            Percent currentRate = rateModel.currentRate();
            DailyAccrual accrual = accrueOneDay(cursor, compoundedPrincipal, currentRate, dayCount);
            dailyAccruals.add(accrual);
            totalInterest = totalInterest.plus(accrual.interestAmount());
            compoundedPrincipal = compoundedPrincipal.plus(accrual.interestAmount());
            rateWeightSum = rateWeightSum.add(currentRate.basisPoints());

            cursor = cursor.plusDays(1);
        }

        long days = ChronoUnit.DAYS.between(periodStart, periodEnd);
        Percent weightedRate = days > 0
                ? Percent.ofBps(rateWeightSum.divide(BigDecimal.valueOf(days), MC))
                : Percent.ofBps(0);

        return new AccrualPeriodSummary(
                periodStart, periodEnd, principal, totalInterest,
                (int) days, weightedRate, dailyAccruals, resets
        );
    }

    // ── Payment application ───────────────────────────────────────────────

    public enum PaymentWaterfallOrder { INTEREST_FIRST, PRINCIPAL_FIRST }

    public record PaymentApplicationResult(
            Money paymentAmount,
            Money appliedToInterest,
            Money appliedToPrincipal,
            Money appliedToFees,
            Money remainingPayment,
            Money newOutstandingPrincipal,
            Money newAccruedInterest
    ) {
        public PaymentApplicationResult {
            Objects.requireNonNull(paymentAmount, "paymentAmount");
            Objects.requireNonNull(appliedToInterest, "appliedToInterest");
            Objects.requireNonNull(appliedToPrincipal, "appliedToPrincipal");
            Objects.requireNonNull(appliedToFees, "appliedToFees");
            Objects.requireNonNull(remainingPayment, "remainingPayment");
            Objects.requireNonNull(newOutstandingPrincipal, "newOutstandingPrincipal");
            Objects.requireNonNull(newAccruedInterest, "newAccruedInterest");
        }
    }

    /**
     * Applies a payment to a loan following the specified waterfall order.
     * Standard banking practice applies payments to fees first, then interest,
     * then principal (unless a different order is contractually specified).
     */
    public static PaymentApplicationResult applyPayment(
            Money payment,
            Money outstandingPrincipal,
            Money accruedInterest,
            Money outstandingFees,
            PaymentWaterfallOrder order
    ) {
        Money remaining = payment;
        Money toFees = Money.zero(payment.currency());
        Money toInterest = Money.zero(payment.currency());
        Money toPrincipal = Money.zero(payment.currency());

        // Fees are always paid first regardless of waterfall order
        if (remaining.compareTo(outstandingFees) >= 0) {
            toFees = outstandingFees;
            remaining = remaining.minus(outstandingFees);
        } else {
            toFees = remaining;
            remaining = Money.zero(payment.currency());
        }

        if (order == PaymentWaterfallOrder.INTEREST_FIRST) {
            // Apply to interest, then principal
            if (remaining.compareTo(accruedInterest) >= 0) {
                toInterest = accruedInterest;
                remaining = remaining.minus(accruedInterest);
            } else {
                toInterest = remaining;
                remaining = Money.zero(payment.currency());
            }
            if (remaining.compareTo(outstandingPrincipal) >= 0) {
                toPrincipal = outstandingPrincipal;
                remaining = remaining.minus(outstandingPrincipal);
            } else {
                toPrincipal = remaining;
                remaining = Money.zero(payment.currency());
            }
        } else {
            // Apply to principal, then interest
            if (remaining.compareTo(outstandingPrincipal) >= 0) {
                toPrincipal = outstandingPrincipal;
                remaining = remaining.minus(outstandingPrincipal);
            } else {
                toPrincipal = remaining;
                remaining = Money.zero(payment.currency());
            }
            if (remaining.compareTo(accruedInterest) >= 0) {
                toInterest = accruedInterest;
                remaining = remaining.minus(accruedInterest);
            } else {
                toInterest = remaining;
                remaining = Money.zero(payment.currency());
            }
        }

        return new PaymentApplicationResult(
                payment, toInterest, toPrincipal, toFees, remaining,
                outstandingPrincipal.minus(toPrincipal),
                accruedInterest.minus(toInterest)
        );
    }

    /**
     * Calculates the per-diem (daily) interest rate for display and accrual
     * verification purposes.
     */
    public static Money perDiem(Money principal, Percent annualRate, DayCountConvention dayCount) {
        BigDecimal rateFraction = annualRate.asFraction(MC);
        BigDecimal daysInYear = switch (dayCount) {
            case ACTUAL_360 -> BigDecimal.valueOf(360);
            case ACTUAL_365 -> BigDecimal.valueOf(365);
            case THIRTY_360 -> BigDecimal.valueOf(360);
            case ACTUAL_ACTUAL -> BigDecimal.valueOf(365); // approximate for display
        };
        BigDecimal dailyRate = rateFraction.divide(daysInYear, MC);
        return principal.times(dailyRate);
    }
}
