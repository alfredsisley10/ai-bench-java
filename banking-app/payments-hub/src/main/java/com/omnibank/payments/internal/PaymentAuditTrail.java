package com.omnibank.payments.internal;

import com.omnibank.payments.api.PaymentId;
import com.omnibank.payments.api.PaymentRail;
import com.omnibank.payments.api.PaymentStatus;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable audit trail for payment state changes.
 *
 * <p>Every significant event in a payment's lifecycle is recorded as an immutable
 * {@link AuditEntry} with before/after snapshots. This provides:
 * <ul>
 *   <li>Complete traceability for regulatory compliance (BSA/AML, SOX)</li>
 *   <li>Before/after snapshots of payment state for each change</li>
 *   <li>Actor attribution (system, user, external system) for every action</li>
 *   <li>Correlation IDs for tracing across distributed systems</li>
 *   <li>Tamper evidence through sequential entry numbering and hash chaining</li>
 * </ul>
 *
 * <p>Audit entries are immutable once created — there is no update or delete
 * operation. This is a regulatory requirement for payment audit trails.
 */
public class PaymentAuditTrail {

    private static final Logger log = LoggerFactory.getLogger(PaymentAuditTrail.class);

    /**
     * Captures the state of a payment at a point in time.
     */
    public record PaymentSnapshot(
            PaymentId paymentId,
            PaymentStatus status,
            PaymentRail rail,
            Money amount,
            String originatorAccount,
            String beneficiaryAccount,
            String beneficiaryName,
            String memo,
            Instant lastModified
    ) {
        public PaymentSnapshot {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(status, "status");
        }
    }

    public enum AuditAction {
        PAYMENT_INITIATED,
        PAYMENT_VALIDATED,
        PAYMENT_ROUTED,
        PAYMENT_SUBMITTED,
        PAYMENT_ACKNOWLEDGED,
        PAYMENT_SETTLED,
        PAYMENT_COMPLETED,
        PAYMENT_REJECTED,
        PAYMENT_RETURNED,
        PAYMENT_CANCELED,
        PAYMENT_FAILED,
        AMOUNT_MODIFIED,
        BENEFICIARY_MODIFIED,
        RAIL_CHANGED,
        SCREENING_INITIATED,
        SCREENING_CLEARED,
        SCREENING_HELD,
        RETURN_REQUESTED,
        RETURN_PROCESSED,
        MANUAL_OVERRIDE,
        SYSTEM_RECONCILIATION
    }

    public enum ActorType {
        SYSTEM,
        USER,
        EXTERNAL_SYSTEM,
        SCHEDULER,
        COMPLIANCE_OFFICER
    }

    /**
     * A single immutable audit entry recording a state change.
     */
    public record AuditEntry(
            String entryId,
            long sequenceNumber,
            PaymentId paymentId,
            AuditAction action,
            ActorType actorType,
            String actorId,
            String actorName,
            PaymentSnapshot beforeState,
            PaymentSnapshot afterState,
            String reason,
            String correlationId,
            Map<String, String> metadata,
            Instant recordedAt,
            String previousEntryHash
    ) {
        public AuditEntry {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(actorType, "actorType");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(recordedAt, "recordedAt");
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        /**
         * Computes a simple hash of this entry for chain verification.
         */
        public String computeHash() {
            var data = "%s|%d|%s|%s|%s|%s|%s"
                    .formatted(entryId, sequenceNumber, paymentId, action, actorId,
                            recordedAt, previousEntryHash);
            return Integer.toHexString(data.hashCode());
        }
    }

    /**
     * Builder for constructing audit entries with all required context.
     */
    public static final class AuditEntryBuilder {
        private PaymentId paymentId;
        private AuditAction action;
        private ActorType actorType = ActorType.SYSTEM;
        private String actorId = "SYSTEM";
        private String actorName = "System";
        private PaymentSnapshot beforeState;
        private PaymentSnapshot afterState;
        private String reason;
        private String correlationId;
        private Map<String, String> metadata = Map.of();

        public AuditEntryBuilder paymentId(PaymentId paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public AuditEntryBuilder action(AuditAction action) {
            this.action = action;
            return this;
        }

        public AuditEntryBuilder actor(ActorType type, String id, String name) {
            this.actorType = type;
            this.actorId = id;
            this.actorName = name;
            return this;
        }

        public AuditEntryBuilder beforeState(PaymentSnapshot snapshot) {
            this.beforeState = snapshot;
            return this;
        }

        public AuditEntryBuilder afterState(PaymentSnapshot snapshot) {
            this.afterState = snapshot;
            return this;
        }

        public AuditEntryBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public AuditEntryBuilder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public AuditEntryBuilder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        PaymentId paymentId() { return paymentId; }
        AuditAction action() { return action; }
        ActorType actorType() { return actorType; }
        String actorId() { return actorId; }
        String actorName() { return actorName; }
        PaymentSnapshot beforeState() { return beforeState; }
        PaymentSnapshot afterState() { return afterState; }
        String reason() { return reason; }
        String correlationId() { return correlationId; }
        Map<String, String> metadata() { return metadata; }
    }

    private final Clock clock;
    private final Map<UUID, List<AuditEntry>> auditTrails = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sequenceCounters = new ConcurrentHashMap<>();

    public PaymentAuditTrail(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static AuditEntryBuilder builder() {
        return new AuditEntryBuilder();
    }

    /**
     * Records a new audit entry for a payment. The entry is immutable once recorded.
     */
    public AuditEntry record(AuditEntryBuilder builder) {
        Objects.requireNonNull(builder.paymentId(), "paymentId is required");
        Objects.requireNonNull(builder.action(), "action is required");

        var paymentUuid = builder.paymentId().value();
        var now = Timestamp.now(clock);

        // Get or create the audit trail for this payment
        var trail = auditTrails.computeIfAbsent(paymentUuid, k -> Collections.synchronizedList(new ArrayList<>()));

        // Compute sequence number and previous hash for chain integrity
        var sequence = sequenceCounters.merge(paymentUuid, 1L, Long::sum);
        var previousHash = trail.isEmpty() ? "GENESIS" : trail.get(trail.size() - 1).computeHash();

        var entry = new AuditEntry(
                UUID.randomUUID().toString(),
                sequence,
                builder.paymentId(),
                builder.action(),
                builder.actorType(),
                builder.actorId(),
                builder.actorName(),
                builder.beforeState(),
                builder.afterState(),
                builder.reason(),
                builder.correlationId(),
                builder.metadata(),
                now,
                previousHash
        );

        trail.add(entry);

        log.info("Audit entry recorded: paymentId={}, action={}, actor={}/{}, seq={}",
                builder.paymentId(), builder.action(), builder.actorType(),
                builder.actorId(), sequence);

        return entry;
    }

    /**
     * Retrieves the complete audit trail for a payment, ordered chronologically.
     */
    public List<AuditEntry> getTrail(PaymentId paymentId) {
        var trail = auditTrails.get(paymentId.value());
        if (trail == null) return List.of();
        return List.copyOf(trail);
    }

    /**
     * Retrieves audit entries filtered by action type.
     */
    public List<AuditEntry> getTrailByAction(PaymentId paymentId, AuditAction action) {
        return getTrail(paymentId).stream()
                .filter(e -> e.action() == action)
                .toList();
    }

    /**
     * Retrieves audit entries filtered by actor.
     */
    public List<AuditEntry> getTrailByActor(PaymentId paymentId, String actorId) {
        return getTrail(paymentId).stream()
                .filter(e -> e.actorId().equals(actorId))
                .toList();
    }

    /**
     * Retrieves all audit entries across all payments within a time range.
     */
    public List<AuditEntry> queryByTimeRange(Instant from, Instant to) {
        return auditTrails.values().stream()
                .flatMap(List::stream)
                .filter(e -> !e.recordedAt().isBefore(from) && !e.recordedAt().isAfter(to))
                .sorted(Comparator.comparing(AuditEntry::recordedAt))
                .toList();
    }

    /**
     * Retrieves all audit entries with a specific correlation ID.
     */
    public List<AuditEntry> queryByCorrelation(String correlationId) {
        return auditTrails.values().stream()
                .flatMap(List::stream)
                .filter(e -> correlationId.equals(e.correlationId()))
                .sorted(Comparator.comparing(AuditEntry::recordedAt))
                .toList();
    }

    /**
     * Verifies the integrity of a payment's audit trail by checking hash chain.
     */
    public boolean verifyChainIntegrity(PaymentId paymentId) {
        var trail = getTrail(paymentId);
        if (trail.isEmpty()) return true;

        // First entry should reference GENESIS
        if (!"GENESIS".equals(trail.get(0).previousEntryHash())) {
            log.error("Audit chain integrity failure: first entry does not reference GENESIS for {}",
                    paymentId);
            return false;
        }

        // Subsequent entries should reference the hash of the previous entry
        for (int i = 1; i < trail.size(); i++) {
            var current = trail.get(i);
            var previous = trail.get(i - 1);
            if (!previous.computeHash().equals(current.previousEntryHash())) {
                log.error("Audit chain integrity failure at seq {}: expected hash={}, found={}",
                        current.sequenceNumber(), previous.computeHash(), current.previousEntryHash());
                return false;
            }
        }

        log.debug("Audit chain integrity verified: paymentId={}, entries={}", paymentId, trail.size());
        return true;
    }

    /**
     * Returns the total number of audit entries across all payments.
     */
    public long totalEntryCount() {
        return auditTrails.values().stream().mapToLong(List::size).sum();
    }

    /**
     * Returns the number of payments that have audit trails.
     */
    public int trackedPaymentCount() {
        return auditTrails.size();
    }
}
