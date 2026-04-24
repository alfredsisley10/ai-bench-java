package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.DrawdownSchedule;
import com.omnibank.lending.corporate.api.LoanId;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Percent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Manages the lifecycle of credit facilities: commitment, activation, amendment,
 * extension, reduction, and termination. Tracks utilization across all draws
 * within a facility and enforces sublimits for letters of credit, swinglines,
 * and competitive bid options.
 *
 * <p>This service orchestrates between the {@link FacilityEntity} aggregate
 * and the broader loan management context, ensuring consistency of utilization
 * tracking across drawdowns and repayments.
 */
public class FacilityManager {

    private static final Logger log = LoggerFactory.getLogger(FacilityManager.class);
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private final FacilityRepository facilityRepository;

    public FacilityManager(FacilityRepository facilityRepository) {
        this.facilityRepository = Objects.requireNonNull(facilityRepository, "facilityRepository");
    }

    // ── Value types ───────────────────────────────────────────────────────

    public record FacilitySnapshot(
            UUID facilityId,
            String facilityName,
            FacilityEntity.FacilityType type,
            FacilityEntity.FacilityStatus status,
            Money totalCommitment,
            Money utilized,
            Money available,
            BigDecimal utilizationRate,
            LocalDate maturityDate,
            long daysToMaturity,
            SublimitSnapshot sublimits
    ) {}

    public record SublimitSnapshot(
            Optional<Money> lcSublimit,
            Optional<Money> lcUtilized,
            Optional<Money> lcAvailable,
            Optional<Money> swinglineSublimit,
            Optional<Money> swinglineUtilized
    ) {}

    public record AmendmentRequest(
            UUID facilityId,
            Optional<BigDecimal> newCommitment,
            Optional<Long> newSpreadBps,
            Optional<Long> newCommitmentFeeBps,
            Optional<LocalDate> newMaturityDate,
            Optional<LocalDate> newAvailabilityEndDate,
            String amendmentReason,
            String approvedBy
    ) {
        public AmendmentRequest {
            Objects.requireNonNull(facilityId, "facilityId");
            Objects.requireNonNull(amendmentReason, "amendmentReason");
            Objects.requireNonNull(approvedBy, "approvedBy");
        }
    }

    public record UtilizationReport(
            UUID facilityId,
            LocalDate reportDate,
            Money totalCommitment,
            Money totalUtilized,
            Money totalAvailable,
            BigDecimal utilizationPercent,
            Money commitmentFeeAccrued,
            Optional<Money> utilizationFeeAccrued,
            List<DrawSummary> activeDraws
    ) {}

    public record DrawSummary(
            UUID drawId,
            Money amount,
            LocalDate drawDate,
            String purpose
    ) {}

    // ── Facility lifecycle ────────────────────────────────────────────────

    @Transactional
    public FacilityEntity createFacility(UUID creditAgreementId, UUID borrowerId,
                                          String name, FacilityEntity.FacilityType type,
                                          BigDecimal commitment, CurrencyCode currency,
                                          LocalDate effectiveDate, LocalDate maturityDate,
                                          LocalDate availabilityEndDate,
                                          String baseRateType, long spreadBps,
                                          long commitmentFeeBps) {
        UUID id = UUID.randomUUID();
        var facility = new FacilityEntity(
                id, creditAgreementId, borrowerId, name, type,
                commitment, currency, effectiveDate, maturityDate,
                availabilityEndDate, baseRateType, spreadBps, commitmentFeeBps
        );
        facilityRepository.save(facility);
        log.info("Created facility {} ({}) with commitment {} {}",
                id, name, commitment, currency);
        return facility;
    }

    @Transactional
    public void activateFacility(UUID facilityId) {
        FacilityEntity facility = requireFacility(facilityId);
        facility.activate();
        log.info("Activated facility {}", facilityId);
    }

    @Transactional
    public void processDrawdown(UUID facilityId, BigDecimal amount, String purpose) {
        FacilityEntity facility = requireFacility(facilityId);
        validateFacilityAvailable(facility, LocalDate.now());
        facility.recordDraw(amount);
        log.info("Recorded drawdown of {} on facility {}: {}", amount, facilityId, purpose);
    }

    @Transactional
    public void processRepayment(UUID facilityId, BigDecimal amount) {
        FacilityEntity facility = requireFacility(facilityId);
        facility.recordRepayment(amount);
        log.info("Recorded repayment of {} on facility {}", amount, facilityId);
    }

    @Transactional
    public void processLetterOfCredit(UUID facilityId, BigDecimal amount) {
        FacilityEntity facility = requireFacility(facilityId);
        validateFacilityAvailable(facility, LocalDate.now());
        facility.recordLetterOfCreditIssuance(amount);
        log.info("Recorded LC issuance of {} on facility {}", amount, facilityId);
    }

    // ── Amendment ─────────────────────────────────────────────────────────

    @Transactional
    public FacilityEntity amendFacility(AmendmentRequest request) {
        FacilityEntity facility = requireFacility(request.facilityId());

        request.newCommitment().ifPresent(newCommitment -> {
            BigDecimal current = facility.totalCommitment();
            if (newCommitment.compareTo(current) < 0) {
                BigDecimal reduction = current.subtract(newCommitment);
                facility.reduceCommitment(reduction);
                log.info("Reduced facility {} commitment by {}", request.facilityId(), reduction);
            }
            // Commitment increase would require re-approval; not modeled here directly
        });

        log.info("Facility {} amended by {} for reason: {}",
                request.facilityId(), request.approvedBy(), request.amendmentReason());
        return facility;
    }

    // ── Extension ─────────────────────────────────────────────────────────

    @Transactional
    public void extendFacility(UUID facilityId, int extensionMonths) {
        FacilityEntity facility = requireFacility(facilityId);
        facility.extendMaturity(extensionMonths);
        log.info("Extended facility {} by {} months, new maturity: {}",
                facilityId, extensionMonths, facility.maturityDate());
    }

    // ── Termination ───────────────────────────────────────────────────────

    @Transactional
    public void terminateFacility(UUID facilityId) {
        FacilityEntity facility = requireFacility(facilityId);
        facility.terminate();
        log.info("Terminated facility {}", facilityId);
    }

    // ── Snapshot and reporting ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FacilitySnapshot getSnapshot(UUID facilityId, LocalDate asOf) {
        FacilityEntity f = requireFacility(facilityId);
        CurrencyCode ccy = f.baseCurrency();

        SublimitSnapshot sublimits = new SublimitSnapshot(
                Optional.ofNullable(f.letterOfCreditSublimit()).map(v -> Money.of(v, ccy)),
                Optional.ofNullable(f.letterOfCreditUtilized()).map(v -> Money.of(v, ccy)),
                Optional.ofNullable(f.letterOfCreditSublimit())
                        .map(s -> Money.of(s.subtract(f.letterOfCreditUtilized()), ccy)),
                Optional.ofNullable(f.swinglineSublimit()).map(v -> Money.of(v, ccy)),
                Optional.empty()
        );

        long daysToMaturity = ChronoUnit.DAYS.between(asOf, f.maturityDate());

        return new FacilitySnapshot(
                f.id(), f.facilityName(), f.facilityType(), f.status(),
                Money.of(f.totalCommitment(), ccy),
                Money.of(f.utilizedAmount(), ccy),
                Money.of(f.availableAmount(), ccy),
                f.utilizationRate(),
                f.maturityDate(),
                Math.max(0, daysToMaturity),
                sublimits
        );
    }

    @Transactional(readOnly = true)
    public UtilizationReport generateUtilizationReport(
            UUID facilityId, LocalDate periodStart, LocalDate periodEnd) {
        FacilityEntity f = requireFacility(facilityId);
        CurrencyCode ccy = f.baseCurrency();

        // Calculate commitment fee for the period
        Money undrawn = Money.of(f.availableAmount(), ccy);
        long days = ChronoUnit.DAYS.between(periodStart, periodEnd);
        BigDecimal dayFraction = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(360), MC);
        BigDecimal commitmentRate = Percent.ofBps(f.commitmentFeeBps()).asFraction(MC);
        Money commitmentFee = undrawn.times(commitmentRate.multiply(dayFraction, MC));

        // Calculate utilization fee if applicable
        Optional<Money> utilizationFee = Optional.empty();
        if (f.utilizationFeeBps() != null && f.utilizationFeeBps() > 0) {
            BigDecimal utilizationRate = Percent.ofBps(f.utilizationFeeBps()).asFraction(MC);
            Money drawn = Money.of(f.utilizedAmount(), ccy);
            utilizationFee = Optional.of(drawn.times(utilizationRate.multiply(dayFraction, MC)));
        }

        return new UtilizationReport(
                f.id(), periodEnd,
                Money.of(f.totalCommitment(), ccy),
                Money.of(f.utilizedAmount(), ccy),
                Money.of(f.availableAmount(), ccy),
                f.utilizationRate(),
                commitmentFee,
                utilizationFee,
                Collections.emptyList() // Draw history would come from DrawEntity queries
        );
    }

    @Transactional(readOnly = true)
    public List<FacilitySnapshot> getFacilitiesForBorrower(UUID borrowerId, LocalDate asOf) {
        List<FacilityEntity> facilities = facilityRepository.findByBorrowerIdAndStatusNot(
                borrowerId, FacilityEntity.FacilityStatus.TERMINATED);
        return facilities.stream()
                .map(f -> getSnapshot(f.id(), asOf))
                .toList();
    }

    // ── Maturity monitoring ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FacilitySnapshot> findFacilitiesMaturingWithin(int days) {
        LocalDate asOf = LocalDate.now();
        LocalDate cutoff = asOf.plusDays(days);
        List<FacilityEntity> maturing = facilityRepository.findByMaturityDateBetweenAndStatusNot(
                asOf, cutoff, FacilityEntity.FacilityStatus.TERMINATED);
        return maturing.stream()
                .map(f -> getSnapshot(f.id(), asOf))
                .toList();
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private FacilityEntity requireFacility(UUID facilityId) {
        return facilityRepository.findById(facilityId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown facility: " + facilityId));
    }

    private void validateFacilityAvailable(FacilityEntity facility, LocalDate asOf) {
        if (!facility.isAvailable(asOf)) {
            throw new IllegalStateException(
                    "Facility %s is not available for draws as of %s (status=%s, availability ends=%s)"
                            .formatted(facility.id(), asOf, facility.status(), facility.availabilityEndDate()));
        }
    }
}
