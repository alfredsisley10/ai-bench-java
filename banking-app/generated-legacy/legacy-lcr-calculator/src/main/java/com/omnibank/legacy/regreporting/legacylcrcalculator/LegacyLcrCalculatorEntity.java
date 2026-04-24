package com.omnibank.legacy.regreporting.legacylcrcalculator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent entity for the retired Legacy LCR Calculator.
 *
 * <p>// DO NOT MODIFY — retired 2022 under MIG-5022 (replaced by LcrReportGenerator).
 *
 * <p>Schema lives in the {@code legacy_reg_reporting} schema and
 * is read-only after 2022. New writes go through the {@code LcrReportGenerator}
 * write path; this class exists for the historical-data exporter
 * and the regulatory audit retention job.
 */
@Deprecated(since = "2022-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyLcrCalculatorEntity {

    private final UUID id;
    private final String externalReference;
    private final BigDecimal amount;
    private final String currencyCode;
    private final String status;
    private final Instant createdAt;
    private final Instant retiredAt;
    private final String retirementReason;

    public LegacyLcrCalculatorEntity(UUID id, String externalReference, BigDecimal amount,
                        String currencyCode, String status,
                        Instant createdAt, Instant retiredAt,
                        String retirementReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.externalReference = Objects.requireNonNull(externalReference, "externalReference");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.retiredAt = retiredAt;
        this.retirementReason = retirementReason;
    }

    public UUID id() { return id; }
    public String externalReference() { return externalReference; }
    public BigDecimal amount() { return amount; }
    public String currencyCode() { return currencyCode; }
    public String status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant retiredAt() { return retiredAt; }
    public String retirementReason() { return retirementReason; }

    /** Marker so the data-export job can skip retired rows. */
    public boolean isRetired() {
        return retiredAt != null;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof LegacyLcrCalculatorEntity that)) return false;
        return id.equals(that.id);
    }

    @Override public int hashCode() { return id.hashCode(); }

    @Override public String toString() {
        return "LegacyLcrCalculatorEntity[id=" + id + ", ref=" + externalReference
                + ", amount=" + amount + " " + currencyCode + "]";
    }
}
