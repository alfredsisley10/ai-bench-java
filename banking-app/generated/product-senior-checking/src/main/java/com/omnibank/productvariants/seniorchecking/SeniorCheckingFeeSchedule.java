package com.omnibank.productvariants.seniorchecking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fee schedule for the Senior 60+ Checking product. Captures monthly maintenance,
 * dormancy, statement-delivery, overdraft, and product-specific
 * assessments in one canonical place.
 *
 * <p>All amounts are USD. Rate basis-points apply where the product
 * charges tiered rates.
 */
public final class SeniorCheckingFeeSchedule {

    public record FeeLine(String code, String label, BigDecimal amount, String cadence) {
        public FeeLine {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("fee amounts must be non-negative");
            }
        }
    }

    public enum Cadence { MONTHLY, ANNUAL, PER_EVENT, ONE_TIME }

    private final List<FeeLine> lines;
    private final Map<String, BigDecimal> waiverThresholdsByCode;
    private final LocalDate effectiveFrom;
    private final LocalDate effectiveUntil;

    public SeniorCheckingFeeSchedule(LocalDate effectiveFrom, LocalDate effectiveUntil) {
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.lines = new ArrayList<>();
        this.waiverThresholdsByCode = new LinkedHashMap<>();
        seedLines();
    }

    public static SeniorCheckingFeeSchedule defaults() {
        return new SeniorCheckingFeeSchedule(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2030, 12, 31));
    }

    public List<FeeLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public BigDecimal monthlyMaintenance() {
        return find("MONTHLY_MAINTENANCE").map(FeeLine::amount).orElse(BigDecimal.ZERO);
    }

    public BigDecimal waiverThreshold(String code) {
        return waiverThresholdsByCode.getOrDefault(code, BigDecimal.ZERO);
    }

    public BigDecimal dormancyAssessment(int monthsInactive) {
        if (monthsInactive < 12) return BigDecimal.ZERO;
        var base = find("DORMANCY").map(FeeLine::amount).orElse(BigDecimal.ZERO);
        int overflow = Math.max(0, monthsInactive - 12);
        return base.add(base.multiply(BigDecimal.valueOf(overflow))
                        .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_EVEN));
    }

    public BigDecimal overdraftItemFee() {
        return find("OVERDRAFT_ITEM").map(FeeLine::amount).orElse(BigDecimal.ZERO);
    }

    public BigDecimal paperStatementFee() {
        return find("PAPER_STATEMENT").map(FeeLine::amount).orElse(BigDecimal.ZERO);
    }

    public BigDecimal annualFee() {
        return find("ANNUAL").map(FeeLine::amount).orElse(BigDecimal.ZERO);
    }

    public BigDecimal earlyWithdrawalPenalty(BigDecimal principal, BigDecimal accruedInterest) {
        // Flat percentage of interest earned in penalty days, capped at 2% of principal.
        BigDecimal penalty = accruedInterest.abs();
        BigDecimal cap = principal.multiply(new BigDecimal("0.02"));
        return penalty.min(cap);
    }

    public boolean isActive(LocalDate asOf) {
        return !asOf.isBefore(effectiveFrom) && !asOf.isAfter(effectiveUntil);
    }

    private java.util.Optional<FeeLine> find(String code) {
        for (var l : lines) if (l.code().equals(code)) return java.util.Optional.of(l);
        return java.util.Optional.empty();
    }

    private void seedLines() {
        lines.add(new FeeLine("MONTHLY_MAINTENANCE", "Monthly maintenance",
                new BigDecimal("0.00"), Cadence.MONTHLY.name()));
        waiverThresholdsByCode.put("MONTHLY_MAINTENANCE", new BigDecimal("500.00"));

        lines.add(new FeeLine("PAPER_STATEMENT", "Paper statement delivery",
                new BigDecimal("2.00"), Cadence.MONTHLY.name()));
        lines.add(new FeeLine("OVERDRAFT_ITEM", "Overdraft item fee",
                new BigDecimal("35.00"), Cadence.PER_EVENT.name()));
        lines.add(new FeeLine("DORMANCY", "Dormant account assessment",
                new BigDecimal("5.00"), Cadence.MONTHLY.name()));
        lines.add(new FeeLine("STOP_PAYMENT", "Stop payment order",
                new BigDecimal("30.00"), Cadence.PER_EVENT.name()));
        lines.add(new FeeLine("WIRE_DOMESTIC", "Outgoing domestic wire",
                new BigDecimal("25.00"), Cadence.PER_EVENT.name()));
        lines.add(new FeeLine("WIRE_INTERNATIONAL", "Outgoing international wire",
                new BigDecimal("45.00"), Cadence.PER_EVENT.name()));
        lines.add(new FeeLine("COUNTER_CHECK", "Counter check (per sheet)",
                new BigDecimal("2.00"), Cadence.PER_EVENT.name()));
    }
}
