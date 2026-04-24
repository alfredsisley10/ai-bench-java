package com.omnibank.risk.internal;

import com.omnibank.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Basel II Level 1 operational-risk loss event.
 *
 * <p>The seven Level-1 event types are the taxonomy used for both the
 * Advanced Measurement Approach and the 2023 standardised approach loss
 * component. Each recorded event carries its gross impact, any recoveries,
 * the business line that booked it, and the timestamps relevant for the
 * regulatory look-back window (10 years).
 *
 * <p>Net loss is computed eagerly — denormalising it here keeps downstream
 * aggregation simple in {@link OperationalRiskRegister}.
 */
public record OperationalRiskLossEvent(
        UUID eventId,
        LossEventType type,
        RiskWeightedAssetsEngine.BusinessLine businessLine,
        String subBusinessLine,
        String description,
        Money grossLoss,
        Money recoveries,
        LocalDate occurredOn,
        LocalDate discoveredOn,
        LocalDate accountingDate,
        EventStatus status,
        Optional<UUID> relatedSubLedgerEntry,
        String rootCause,
        Instant recordedAt
) {

    public OperationalRiskLossEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(businessLine, "businessLine");
        Objects.requireNonNull(grossLoss, "grossLoss");
        Objects.requireNonNull(recoveries, "recoveries");
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(discoveredOn, "discoveredOn");
        Objects.requireNonNull(accountingDate, "accountingDate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(relatedSubLedgerEntry, "relatedSubLedgerEntry");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (!grossLoss.currency().equals(recoveries.currency())) {
            throw new IllegalArgumentException("gross and recoveries must share currency");
        }
        if (recoveries.compareTo(grossLoss) > 0) {
            throw new IllegalArgumentException("recoveries cannot exceed gross loss");
        }
        if (occurredOn.isAfter(discoveredOn)) {
            throw new IllegalArgumentException("occurredOn cannot be after discoveredOn");
        }
    }

    /** The seven Basel II Level-1 event types. */
    public enum LossEventType {
        INTERNAL_FRAUD("Internal fraud"),
        EXTERNAL_FRAUD("External fraud"),
        EMPLOYMENT_PRACTICES_AND_WORKPLACE_SAFETY("Employment practices and workplace safety"),
        CLIENTS_PRODUCTS_AND_BUSINESS_PRACTICES("Clients, products & business practices"),
        DAMAGE_TO_PHYSICAL_ASSETS("Damage to physical assets"),
        BUSINESS_DISRUPTION_AND_SYSTEM_FAILURES("Business disruption and system failures"),
        EXECUTION_DELIVERY_AND_PROCESS_MANAGEMENT("Execution, delivery and process management");

        public final String label;
        LossEventType(String label) { this.label = label; }
    }

    /** Lifecycle of a recorded event. */
    public enum EventStatus {
        OPEN,            // detected, not yet booked to GL
        ACCOUNTED,       // booked to general ledger
        IN_RECOVERY,     // recovery effort in progress
        CLOSED,          // final — no further updates expected
        WRITTEN_OFF      // no recovery expected; loss final
    }

    /** Convenience: net loss = gross - recoveries. */
    public Money netLoss() {
        return grossLoss.minus(recoveries);
    }

    /** Is the event considered "material" under our internal policy? */
    public boolean isMaterial(Money threshold) {
        return netLoss().compareTo(threshold) >= 0;
    }

    /** Does the event fall inside the regulatory 10-year look-back? */
    public boolean isInLookbackWindow(LocalDate asOf) {
        LocalDate tenYearsAgo = asOf.minusYears(10);
        return !occurredOn.isBefore(tenYearsAgo);
    }

    /** Days between occurrence and discovery — a KRI in its own right. */
    public long discoveryLagDays() {
        return java.time.temporal.ChronoUnit.DAYS.between(occurredOn, discoveredOn);
    }

    /** Builder-style update: transition event to a new status. */
    public OperationalRiskLossEvent withStatus(EventStatus newStatus) {
        return new OperationalRiskLossEvent(eventId, type, businessLine,
                subBusinessLine, description, grossLoss, recoveries,
                occurredOn, discoveredOn, accountingDate, newStatus,
                relatedSubLedgerEntry, rootCause, recordedAt);
    }

    /** Builder-style update: add an incremental recovery amount. */
    public OperationalRiskLossEvent addRecovery(Money additional) {
        Money newRecoveries = recoveries.plus(additional);
        if (newRecoveries.compareTo(grossLoss) > 0) {
            throw new IllegalArgumentException("recovery would exceed gross loss");
        }
        return new OperationalRiskLossEvent(eventId, type, businessLine,
                subBusinessLine, description, grossLoss, newRecoveries,
                occurredOn, discoveredOn, accountingDate, status,
                relatedSubLedgerEntry, rootCause, recordedAt);
    }
}
