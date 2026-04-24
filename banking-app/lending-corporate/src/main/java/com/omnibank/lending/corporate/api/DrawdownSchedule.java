package com.omnibank.lending.corporate.api;

import com.omnibank.shared.domain.CurrencyCode;
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
 * Manages committed and uncommitted revolving credit facilities. Tracks
 * availability, utilization, drawdown requests, and commitment fee computation.
 *
 * <p>A revolving credit facility allows a borrower to draw, repay, and re-draw
 * up to a commitment amount during the availability period. This schedule
 * maintains the running state of draws and repayments, enforces sublimits,
 * and calculates commitment fees on the undrawn portion.
 */
public final class DrawdownSchedule {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    // ── Value types ───────────────────────────────────────────────────────

    public enum FacilityType { COMMITTED, UNCOMMITTED }

    public enum DrawdownStatus { PENDING, APPROVED, FUNDED, REJECTED, CANCELLED }

    public record FacilityTerms(
            UUID facilityId,
            FacilityType type,
            Money totalCommitment,
            CurrencyCode currency,
            LocalDate availabilityStart,
            LocalDate availabilityEnd,
            LocalDate maturityDate,
            Percent commitmentFeeRate,
            Percent utilizationFeeRate,
            Optional<Money> minimumDraw,
            Optional<Money> sublimit
    ) {
        public FacilityTerms {
            Objects.requireNonNull(facilityId, "facilityId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(totalCommitment, "totalCommitment");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(availabilityStart, "availabilityStart");
            Objects.requireNonNull(availabilityEnd, "availabilityEnd");
            Objects.requireNonNull(maturityDate, "maturityDate");
            Objects.requireNonNull(commitmentFeeRate, "commitmentFeeRate");
            Objects.requireNonNull(utilizationFeeRate, "utilizationFeeRate");
            Objects.requireNonNull(minimumDraw, "minimumDraw");
            Objects.requireNonNull(sublimit, "sublimit");
            if (availabilityEnd.isBefore(availabilityStart)) {
                throw new IllegalArgumentException("Availability end must be on or after start");
            }
            if (maturityDate.isBefore(availabilityEnd)) {
                throw new IllegalArgumentException("Maturity must be on or after availability end");
            }
        }

        public boolean isAvailable(LocalDate asOf) {
            return !asOf.isBefore(availabilityStart) && !asOf.isAfter(availabilityEnd);
        }
    }

    public record DrawdownRequest(
            UUID requestId,
            UUID facilityId,
            Money amount,
            LocalDate requestDate,
            LocalDate valueDate,
            String purpose,
            DrawdownStatus status
    ) {
        public DrawdownRequest {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(facilityId, "facilityId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(requestDate, "requestDate");
            Objects.requireNonNull(valueDate, "valueDate");
            Objects.requireNonNull(status, "status");
        }
    }

    public record UtilizationSnapshot(
            UUID facilityId,
            Money totalCommitment,
            Money totalDrawn,
            Money availableAmount,
            BigDecimal utilizationRate,
            LocalDate asOfDate,
            int activeDrawdowns,
            List<DrawdownRequest> drawdownHistory
    ) {
        public UtilizationSnapshot {
            Objects.requireNonNull(facilityId, "facilityId");
            Objects.requireNonNull(totalCommitment, "totalCommitment");
            Objects.requireNonNull(totalDrawn, "totalDrawn");
            Objects.requireNonNull(availableAmount, "availableAmount");
            Objects.requireNonNull(utilizationRate, "utilizationRate");
            Objects.requireNonNull(asOfDate, "asOfDate");
            drawdownHistory = List.copyOf(drawdownHistory);
        }

        public boolean isFullyDrawn() {
            return availableAmount.isZero();
        }

        public boolean isOverUtilized() {
            return totalDrawn.compareTo(totalCommitment) > 0;
        }
    }

    // ── Commitment fee calculation ────────────────────────────────────────

    public record CommitmentFeeResult(
            UUID facilityId,
            Money undrawnAmount,
            Percent feeRate,
            LocalDate periodStart,
            LocalDate periodEnd,
            int accrualDays,
            Money feeAmount
    ) {
        public CommitmentFeeResult {
            Objects.requireNonNull(facilityId, "facilityId");
            Objects.requireNonNull(undrawnAmount, "undrawnAmount");
            Objects.requireNonNull(feeRate, "feeRate");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(periodEnd, "periodEnd");
            Objects.requireNonNull(feeAmount, "feeAmount");
        }
    }

    /**
     * Computes the commitment fee on the undrawn portion of a revolving facility
     * for a given accrual period. Uses ACT/360 day count by default.
     */
    public static CommitmentFeeResult computeCommitmentFee(
            FacilityTerms terms,
            Money currentUtilization,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        Money undrawn = terms.totalCommitment().minus(currentUtilization);
        if (undrawn.isNegative()) {
            undrawn = Money.zero(terms.currency());
        }

        long days = ChronoUnit.DAYS.between(periodStart, periodEnd);
        BigDecimal dayFraction = BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(360), MC);
        BigDecimal rateFraction = terms.commitmentFeeRate().asFraction(MC);
        Money feeAmount = undrawn.times(rateFraction.multiply(dayFraction, MC));

        return new CommitmentFeeResult(
                terms.facilityId(), undrawn, terms.commitmentFeeRate(),
                periodStart, periodEnd, (int) days, feeAmount
        );
    }

    // ── Utilization fee (charged when utilization exceeds threshold) ──────

    public record UtilizationFeeResult(
            UUID facilityId,
            BigDecimal utilizationRate,
            Money drawnAmount,
            Percent feeRate,
            LocalDate periodStart,
            LocalDate periodEnd,
            Money feeAmount
    ) {
        public UtilizationFeeResult {
            Objects.requireNonNull(facilityId, "facilityId");
            Objects.requireNonNull(utilizationRate, "utilizationRate");
        }
    }

    /**
     * Computes utilization fee when the facility usage exceeds a given threshold
     * (typically 50% or higher). This incentivizes borrowers to use the facility
     * rather than just holding it as a backup line.
     */
    public static Optional<UtilizationFeeResult> computeUtilizationFee(
            FacilityTerms terms,
            Money currentUtilization,
            BigDecimal utilizationThreshold,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        if (terms.totalCommitment().isZero()) {
            return Optional.empty();
        }
        BigDecimal utilizationRate = currentUtilization.amount()
                .divide(terms.totalCommitment().amount(), MC);

        if (utilizationRate.compareTo(utilizationThreshold) <= 0) {
            return Optional.empty();
        }

        long days = ChronoUnit.DAYS.between(periodStart, periodEnd);
        BigDecimal dayFraction = BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(360), MC);
        BigDecimal rateFraction = terms.utilizationFeeRate().asFraction(MC);
        Money feeAmount = currentUtilization.times(rateFraction.multiply(dayFraction, MC));

        return Optional.of(new UtilizationFeeResult(
                terms.facilityId(), utilizationRate, currentUtilization,
                terms.utilizationFeeRate(), periodStart, periodEnd, feeAmount
        ));
    }

    // ── Availability calculations ─────────────────────────────────────────

    /**
     * Calculates the current available amount, considering the total commitment,
     * outstanding draws, any sublimits, and the availability period.
     */
    public static Money calculateAvailability(
            FacilityTerms terms,
            Money currentUtilization,
            LocalDate asOf
    ) {
        if (!terms.isAvailable(asOf)) {
            return Money.zero(terms.currency());
        }

        Money available = terms.totalCommitment().minus(currentUtilization);
        if (available.isNegative()) {
            return Money.zero(terms.currency());
        }

        // Apply sublimit if present
        if (terms.sublimit().isPresent()) {
            Money sublimitRemaining = terms.sublimit().get().minus(currentUtilization);
            if (sublimitRemaining.isNegative()) {
                sublimitRemaining = Money.zero(terms.currency());
            }
            if (sublimitRemaining.compareTo(available) < 0) {
                available = sublimitRemaining;
            }
        }

        return available;
    }

    /**
     * Validates a drawdown request against the facility terms and current
     * utilization. Returns a list of validation errors; empty means the
     * request is valid.
     */
    public static List<String> validateDrawdownRequest(
            FacilityTerms terms,
            DrawdownRequest request,
            Money currentUtilization
    ) {
        List<String> errors = new ArrayList<>();

        if (!terms.isAvailable(request.valueDate())) {
            errors.add("Drawdown value date %s is outside the availability period [%s, %s]"
                    .formatted(request.valueDate(), terms.availabilityStart(), terms.availabilityEnd()));
        }

        Money available = calculateAvailability(terms, currentUtilization, request.valueDate());
        if (request.amount().compareTo(available) > 0) {
            errors.add("Requested amount %s exceeds available amount %s"
                    .formatted(request.amount(), available));
        }

        if (terms.minimumDraw().isPresent()
                && request.amount().compareTo(terms.minimumDraw().get()) < 0) {
            errors.add("Requested amount %s is below the minimum draw of %s"
                    .formatted(request.amount(), terms.minimumDraw().get()));
        }

        if (request.amount().currency() != terms.currency()) {
            errors.add("Draw currency %s does not match facility currency %s"
                    .formatted(request.amount().currency(), terms.currency()));
        }

        if (terms.type() == FacilityType.UNCOMMITTED) {
            errors.add("Drawdowns on uncommitted facilities require explicit bank approval");
        }

        return Collections.unmodifiableList(errors);
    }

    /**
     * Builds a utilization snapshot for reporting and monitoring.
     */
    public static UtilizationSnapshot buildSnapshot(
            FacilityTerms terms,
            Money currentUtilization,
            List<DrawdownRequest> drawdownHistory,
            LocalDate asOf
    ) {
        Money available = calculateAvailability(terms, currentUtilization, asOf);
        BigDecimal utilizationRate = terms.totalCommitment().isZero()
                ? BigDecimal.ZERO
                : currentUtilization.amount()
                        .divide(terms.totalCommitment().amount(), MC);

        long activeDrawdowns = drawdownHistory.stream()
                .filter(d -> d.status() == DrawdownStatus.FUNDED)
                .count();

        return new UtilizationSnapshot(
                terms.facilityId(), terms.totalCommitment(), currentUtilization,
                available, utilizationRate, asOf, (int) activeDrawdowns, drawdownHistory
        );
    }
}
