package com.omnibank.shared.messaging;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity for saga state persistence. Each saga instance is tracked from
 * creation through completion (or compensation), with full step-level detail,
 * a compensation log for rollback, and deadline management for timeout detection.
 *
 * <p>A saga progresses through steps in order. On failure at any step the
 * orchestrator walks backward through completed steps, executing each step's
 * compensation action. The compensation log records which compensations have
 * been executed and their outcomes.</p>
 *
 * <p>Deadlines are stored per saga for timeout detection. The
 * {@link SagaOrchestrator} periodically scans for sagas whose deadline has
 * passed and initiates compensation.</p>
 */
@Entity
@Table(
    name = "saga_state",
    indexes = {
        @Index(name = "idx_saga_status",     columnList = "status"),
        @Index(name = "idx_saga_type",       columnList = "saga_type"),
        @Index(name = "idx_saga_correlation", columnList = "correlation_id"),
        @Index(name = "idx_saga_deadline",   columnList = "deadline"),
        @Index(name = "idx_saga_aggregate",  columnList = "aggregate_type, aggregate_id")
    }
)
public class SagaState {

    @Id
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "saga_type", nullable = false, updatable = false, length = 128)
    private String sagaType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SagaStatus status;

    @Column(name = "current_step_index", nullable = false)
    private int currentStepIndex;

    @Column(name = "total_steps", nullable = false, updatable = false)
    private int totalSteps;

    @Column(name = "aggregate_type", length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 64)
    private String aggregateId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "initiated_by", length = 64)
    private String initiatedBy;

    @Lob
    @Column(name = "saga_data_json", columnDefinition = "TEXT")
    private String sagaDataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 2048)
    private String failureReason;

    @Column(name = "failure_step", length = 128)
    private String failureStep;

    @Version
    @Column(name = "row_version")
    private int rowVersion;

    // -----------------------------------------------------------------------
    //  Step tracking
    // -----------------------------------------------------------------------

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "saga_step_log", joinColumns = @JoinColumn(name = "saga_id"))
    @OrderColumn(name = "step_order")
    private List<StepLogEntry> stepLog = new ArrayList<>();

    // -----------------------------------------------------------------------
    //  Compensation log
    // -----------------------------------------------------------------------

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "saga_compensation_log", joinColumns = @JoinColumn(name = "saga_id"))
    @OrderColumn(name = "comp_order")
    private List<CompensationLogEntry> compensationLog = new ArrayList<>();

    // -----------------------------------------------------------------------
    //  Enums
    // -----------------------------------------------------------------------

    public enum SagaStatus {
        /** Saga has been created but not yet started. */
        CREATED,
        /** Actively executing forward steps. */
        RUNNING,
        /** Waiting for an external response or timer. */
        AWAITING,
        /** All forward steps completed successfully. */
        COMPLETED,
        /** A step failed; compensation is in progress. */
        COMPENSATING,
        /** All compensations have completed. */
        COMPENSATED,
        /** Compensation itself failed — requires manual intervention. */
        FAILED,
        /** The saga exceeded its deadline before completing. */
        TIMED_OUT
    }

    // -----------------------------------------------------------------------
    //  Embeddable step log entry
    // -----------------------------------------------------------------------

    @Embeddable
    public static class StepLogEntry {

        @Column(name = "step_name", nullable = false, length = 128)
        private String stepName;

        @Enumerated(EnumType.STRING)
        @Column(name = "step_status", nullable = false, length = 24)
        private StepStatus stepStatus;

        @Column(name = "started_at")
        private Instant startedAt;

        @Column(name = "completed_at")
        private Instant completedAt;

        @Column(name = "error_message", length = 1024)
        private String errorMessage;

        @Column(name = "output_json", columnDefinition = "TEXT")
        private String outputJson;

        protected StepLogEntry() {}

        public StepLogEntry(String stepName) {
            this.stepName = Objects.requireNonNull(stepName);
            this.stepStatus = StepStatus.PENDING;
        }

        public String stepName()        { return stepName; }
        public StepStatus stepStatus()  { return stepStatus; }
        public Instant startedAt()      { return startedAt; }
        public Instant completedAt()    { return completedAt; }
        public String errorMessage()    { return errorMessage; }
        public String outputJson()      { return outputJson; }

        public void markRunning() {
            this.stepStatus = StepStatus.RUNNING;
            this.startedAt = Instant.now();
        }

        public void markCompleted(String outputJson) {
            this.stepStatus = StepStatus.COMPLETED;
            this.completedAt = Instant.now();
            this.outputJson = outputJson;
        }

        public void markFailed(String error) {
            this.stepStatus = StepStatus.FAILED;
            this.completedAt = Instant.now();
            this.errorMessage = error;
        }

        public void markSkipped(String reason) {
            this.stepStatus = StepStatus.SKIPPED;
            this.completedAt = Instant.now();
            this.errorMessage = reason;
        }

        public enum StepStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, COMPENSATED }
    }

    // -----------------------------------------------------------------------
    //  Embeddable compensation log entry
    // -----------------------------------------------------------------------

    @Embeddable
    public static class CompensationLogEntry {

        @Column(name = "comp_step_name", nullable = false, length = 128)
        private String stepName;

        @Enumerated(EnumType.STRING)
        @Column(name = "comp_status", nullable = false, length = 24)
        private CompensationStatus compensationStatus;

        @Column(name = "comp_started_at")
        private Instant startedAt;

        @Column(name = "comp_completed_at")
        private Instant completedAt;

        @Column(name = "comp_error", length = 1024)
        private String error;

        protected CompensationLogEntry() {}

        public CompensationLogEntry(String stepName) {
            this.stepName = Objects.requireNonNull(stepName);
            this.compensationStatus = CompensationStatus.PENDING;
        }

        public String stepName()                       { return stepName; }
        public CompensationStatus compensationStatus() { return compensationStatus; }
        public Instant startedAt()                     { return startedAt; }
        public Instant completedAt()                   { return completedAt; }
        public String error()                          { return error; }

        public void markRunning() {
            this.compensationStatus = CompensationStatus.RUNNING;
            this.startedAt = Instant.now();
        }

        public void markCompleted() {
            this.compensationStatus = CompensationStatus.COMPLETED;
            this.completedAt = Instant.now();
        }

        public void markFailed(String error) {
            this.compensationStatus = CompensationStatus.FAILED;
            this.completedAt = Instant.now();
            this.error = error;
        }

        public enum CompensationStatus { PENDING, RUNNING, COMPLETED, FAILED }
    }

    // -----------------------------------------------------------------------
    //  Constructors
    // -----------------------------------------------------------------------

    protected SagaState() {
        // JPA
    }

    public SagaState(UUID sagaId,
                     String sagaType,
                     int totalSteps,
                     String aggregateType,
                     String aggregateId,
                     UUID correlationId,
                     String initiatedBy,
                     String sagaDataJson,
                     Instant deadline) {
        this.sagaId = Objects.requireNonNull(sagaId);
        this.sagaType = Objects.requireNonNull(sagaType);
        this.status = SagaStatus.CREATED;
        this.currentStepIndex = 0;
        this.totalSteps = totalSteps;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.correlationId = correlationId;
        this.initiatedBy = initiatedBy;
        this.sagaDataJson = sagaDataJson;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.deadline = deadline;
    }

    // -----------------------------------------------------------------------
    //  State transitions
    // -----------------------------------------------------------------------

    public void start() {
        if (status != SagaStatus.CREATED) {
            throw new IllegalStateException("Cannot start saga in status: " + status);
        }
        this.status = SagaStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    public void advanceStep() {
        this.currentStepIndex++;
        this.updatedAt = Instant.now();
    }

    public void markAwaiting() {
        this.status = SagaStatus.AWAITING;
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = SagaStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markCompensating(String failureReason, String failureStep) {
        this.status = SagaStatus.COMPENSATING;
        this.failureReason = failureReason;
        this.failureStep = failureStep;
        this.updatedAt = Instant.now();
    }

    public void markCompensated() {
        this.status = SagaStatus.COMPENSATED;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = SagaStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markTimedOut() {
        this.status = SagaStatus.TIMED_OUT;
        this.failureReason = "Saga exceeded deadline: " + deadline;
        this.updatedAt = Instant.now();
    }

    public void addStepLog(StepLogEntry entry)               { stepLog.add(entry); }
    public void addCompensationLog(CompensationLogEntry entry){ compensationLog.add(entry); }

    public void updateSagaData(String json) {
        this.sagaDataJson = json;
        this.updatedAt = Instant.now();
    }

    // -----------------------------------------------------------------------
    //  Accessors
    // -----------------------------------------------------------------------

    public UUID sagaId()            { return sagaId; }
    public String sagaType()        { return sagaType; }
    public SagaStatus status()      { return status; }
    public int currentStepIndex()   { return currentStepIndex; }
    public int totalSteps()         { return totalSteps; }
    public String aggregateType()   { return aggregateType; }
    public String aggregateId()     { return aggregateId; }
    public UUID correlationId()     { return correlationId; }
    public String initiatedBy()     { return initiatedBy; }
    public String sagaDataJson()    { return sagaDataJson; }
    public Instant createdAt()      { return createdAt; }
    public Instant updatedAt()      { return updatedAt; }
    public Instant deadline()       { return deadline; }
    public Instant completedAt()    { return completedAt; }
    public String failureReason()   { return failureReason; }
    public String failureStep()     { return failureStep; }

    public List<StepLogEntry> stepLog()                 { return List.copyOf(stepLog); }
    public List<CompensationLogEntry> compensationLog() { return List.copyOf(compensationLog); }

    public boolean isTerminal() {
        return switch (status) {
            case COMPLETED, COMPENSATED, FAILED -> true;
            default -> false;
        };
    }

    public boolean isExpired() {
        return deadline != null && Instant.now().isAfter(deadline) && !isTerminal();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SagaState that)) return false;
        return Objects.equals(sagaId, that.sagaId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sagaId);
    }

    @Override
    public String toString() {
        return "SagaState{sagaId=%s, type='%s', status=%s, step=%d/%d}"
                .formatted(sagaId, sagaType, status, currentStepIndex, totalSteps);
    }
}
