package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.Covenant;
import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity for covenant storage with breach history, waiver tracking, and
 * scheduled test dates. Supports both financial and behavioral covenants.
 *
 * <p>Each covenant belongs to a single loan and maintains a full audit trail
 * of test results, breaches, and any waivers granted by the credit committee.
 */
@Entity
@Table(name = "loan_covenant", schema = "lending_corporate")
public class CovenantEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "covenant_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CovenantType covenantType;

    @Column(name = "metric", length = 40)
    @Enumerated(EnumType.STRING)
    private Covenant.Financial.Metric metric;

    @Column(name = "operator", length = 4)
    @Enumerated(EnumType.STRING)
    private Covenant.Financial.Operator operator;

    @Column(name = "threshold_value", precision = 19, scale = 6)
    private BigDecimal thresholdValue;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "next_test_date", nullable = false)
    private LocalDate nextTestDate;

    @Column(name = "test_frequency_months", nullable = false)
    private int testFrequencyMonths;

    @Column(name = "cure_period_days", nullable = false)
    private int curePeriodDays;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "is_waived", nullable = false)
    private boolean waived;

    @Column(name = "waiver_expiry_date")
    private LocalDate waiverExpiryDate;

    @Column(name = "waiver_granted_by", length = 128)
    private String waiverGrantedBy;

    @Column(name = "waiver_reason", length = 1024)
    private String waiverReason;

    @OneToMany(mappedBy = "covenantId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("testDate DESC")
    private List<CovenantTestResultEntity> testResults = new ArrayList<>();

    @OneToMany(mappedBy = "covenantId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("breachDate DESC")
    private List<CovenantBreachEntity> breachHistory = new ArrayList<>();

    public enum CovenantType { FINANCIAL, BEHAVIORAL }

    protected CovenantEntity() {}

    public CovenantEntity(UUID id, UUID loanId, CovenantType type,
                          Covenant.Financial.Metric metric,
                          Covenant.Financial.Operator operator,
                          BigDecimal thresholdValue,
                          String description,
                          LocalDate nextTestDate,
                          int testFrequencyMonths,
                          int curePeriodDays) {
        this.id = id;
        this.loanId = loanId;
        this.covenantType = type;
        this.metric = metric;
        this.operator = operator;
        this.thresholdValue = thresholdValue;
        this.description = description;
        this.nextTestDate = nextTestDate;
        this.testFrequencyMonths = testFrequencyMonths;
        this.curePeriodDays = curePeriodDays;
        this.active = true;
        this.waived = false;
    }

    // ── Business methods ──────────────────────────────────────────────────

    public void advanceTestDate() {
        this.nextTestDate = this.nextTestDate.plusMonths(testFrequencyMonths);
    }

    public void grantWaiver(String grantedBy, String reason, LocalDate expiryDate) {
        Objects.requireNonNull(grantedBy, "grantedBy");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(expiryDate, "expiryDate");
        this.waived = true;
        this.waiverGrantedBy = grantedBy;
        this.waiverReason = reason;
        this.waiverExpiryDate = expiryDate;
    }

    public void revokeWaiver() {
        this.waived = false;
        this.waiverGrantedBy = null;
        this.waiverReason = null;
        this.waiverExpiryDate = null;
    }

    public boolean isWaiverExpired(LocalDate asOf) {
        return waived && waiverExpiryDate != null && asOf.isAfter(waiverExpiryDate);
    }

    public void deactivate() {
        this.active = false;
    }

    public void recordTestResult(CovenantTestResultEntity result) {
        this.testResults.add(result);
    }

    public void recordBreach(CovenantBreachEntity breach) {
        this.breachHistory.add(breach);
    }

    public boolean hasUnresolvedBreach() {
        return breachHistory.stream()
                .anyMatch(b -> b.status() == CovenantBreachEntity.BreachStatus.OPEN);
    }

    public int consecutiveBreachCount() {
        int count = 0;
        for (var result : testResults) {
            if (!result.passed()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public UUID id() { return id; }
    public UUID loanId() { return loanId; }
    public CovenantType covenantType() { return covenantType; }
    public Covenant.Financial.Metric metric() { return metric; }
    public Covenant.Financial.Operator operator() { return operator; }
    public BigDecimal thresholdValue() { return thresholdValue; }
    public String description() { return description; }
    public LocalDate nextTestDate() { return nextTestDate; }
    public int testFrequencyMonths() { return testFrequencyMonths; }
    public int curePeriodDays() { return curePeriodDays; }
    public boolean isActive() { return active; }
    public boolean isWaived() { return waived; }
    public LocalDate waiverExpiryDate() { return waiverExpiryDate; }
    public String waiverGrantedBy() { return waiverGrantedBy; }
    public String waiverReason() { return waiverReason; }
    public List<CovenantTestResultEntity> testResults() { return Collections.unmodifiableList(testResults); }
    public List<CovenantBreachEntity> breachHistory() { return Collections.unmodifiableList(breachHistory); }

    // ── Embedded entity: test result ──────────────────────────────────────

    @Entity
    @Table(name = "covenant_test_result", schema = "lending_corporate")
    public static class CovenantTestResultEntity extends AuditableEntity {

        @Id
        @Column(name = "id")
        private UUID id;

        @Column(name = "covenant_id", nullable = false)
        private UUID covenantId;

        @Column(name = "test_date", nullable = false)
        private LocalDate testDate;

        @Column(name = "passed", nullable = false)
        private boolean passed;

        @Column(name = "actual_value", precision = 19, scale = 6)
        private BigDecimal actualValue;

        @Column(name = "threshold_value", precision = 19, scale = 6)
        private BigDecimal thresholdValue;

        @Column(name = "narrative", length = 2048)
        private String narrative;

        protected CovenantTestResultEntity() {}

        public CovenantTestResultEntity(UUID id, UUID covenantId, LocalDate testDate,
                                        boolean passed, BigDecimal actualValue,
                                        BigDecimal thresholdValue, String narrative) {
            this.id = id;
            this.covenantId = covenantId;
            this.testDate = testDate;
            this.passed = passed;
            this.actualValue = actualValue;
            this.thresholdValue = thresholdValue;
            this.narrative = narrative;
        }

        public UUID id() { return id; }
        public UUID covenantId() { return covenantId; }
        public LocalDate testDate() { return testDate; }
        public boolean passed() { return passed; }
        public BigDecimal actualValue() { return actualValue; }
        public BigDecimal thresholdValue() { return thresholdValue; }
        public String narrative() { return narrative; }
    }

    // ── Embedded entity: breach record ────────────────────────────────────

    @Entity
    @Table(name = "covenant_breach", schema = "lending_corporate")
    public static class CovenantBreachEntity extends AuditableEntity {

        public enum BreachStatus { OPEN, CURED, WAIVED, DEFAULTED }

        @Id
        @Column(name = "id")
        private UUID id;

        @Column(name = "covenant_id", nullable = false)
        private UUID covenantId;

        @Column(name = "breach_date", nullable = false)
        private LocalDate breachDate;

        @Column(name = "cure_deadline")
        private LocalDate cureDeadline;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 12)
        private BreachStatus status;

        @Column(name = "resolution_date")
        private LocalDate resolutionDate;

        @Column(name = "resolution_notes", length = 2048)
        private String resolutionNotes;

        protected CovenantBreachEntity() {}

        public CovenantBreachEntity(UUID id, UUID covenantId, LocalDate breachDate,
                                    LocalDate cureDeadline) {
            this.id = id;
            this.covenantId = covenantId;
            this.breachDate = breachDate;
            this.cureDeadline = cureDeadline;
            this.status = BreachStatus.OPEN;
        }

        public void cure(LocalDate resolutionDate, String notes) {
            this.status = BreachStatus.CURED;
            this.resolutionDate = resolutionDate;
            this.resolutionNotes = notes;
        }

        public void waive(LocalDate resolutionDate, String notes) {
            this.status = BreachStatus.WAIVED;
            this.resolutionDate = resolutionDate;
            this.resolutionNotes = notes;
        }

        public void escalateToDefault(String notes) {
            this.status = BreachStatus.DEFAULTED;
            this.resolutionNotes = notes;
        }

        public UUID id() { return id; }
        public UUID covenantId() { return covenantId; }
        public LocalDate breachDate() { return breachDate; }
        public LocalDate cureDeadline() { return cureDeadline; }
        public BreachStatus status() { return status; }
        public LocalDate resolutionDate() { return resolutionDate; }
        public String resolutionNotes() { return resolutionNotes; }
    }
}
