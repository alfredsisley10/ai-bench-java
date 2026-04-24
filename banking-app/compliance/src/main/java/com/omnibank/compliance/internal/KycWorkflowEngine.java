package com.omnibank.compliance.internal;

import com.omnibank.compliance.api.KycStatus;
import com.omnibank.compliance.api.SanctionsScreeningResult;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Know Your Customer (KYC) workflow engine. Manages the full lifecycle of
 * customer identity verification for regulatory compliance (BSA/AML, CIP,
 * CDD, and EDD requirements).
 *
 * <p>Workflow stages:
 * <ol>
 *   <li><b>Identity verification:</b> Name, DOB, SSN/TIN, address validation
 *       against authoritative sources (LexisNexis, Equifax ID)</li>
 *   <li><b>Document collection:</b> Government-issued ID, proof of address,
 *       incorporation docs for business entities</li>
 *   <li><b>Beneficial ownership:</b> Identify and verify individuals with
 *       25%+ ownership (CDD Rule requirement)</li>
 *   <li><b>PEP screening:</b> Check against Politically Exposed Persons lists</li>
 *   <li><b>Adverse media:</b> Search for negative news coverage</li>
 *   <li><b>Sanctions screening:</b> OFAC SDN, EU consolidated, UN sanctions</li>
 *   <li><b>Risk assessment:</b> Assign customer risk rating (LOW, MEDIUM, HIGH)</li>
 *   <li><b>Periodic review:</b> Schedule re-verification based on risk tier</li>
 * </ol>
 *
 * <p>Each stage produces a verdict. All verdicts must pass for KYC to be approved.
 * Any FAIL or MANUAL_REVIEW verdict pauses the workflow for analyst action.
 */
public class KycWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(KycWorkflowEngine.class);

    /** Re-verification periods by customer risk tier. */
    private static final Map<CustomerRiskTier, Duration> REVIEW_PERIODS = Map.of(
            CustomerRiskTier.LOW, Duration.ofDays(365 * 3),    // 3 years
            CustomerRiskTier.MEDIUM, Duration.ofDays(365),      // 1 year
            CustomerRiskTier.HIGH, Duration.ofDays(180),        // 6 months
            CustomerRiskTier.PROHIBITED, Duration.ZERO          // immediate
    );

    /** Maximum age of valid ID documents. */
    private static final Duration MAX_DOCUMENT_AGE = Duration.ofDays(365 * 5); // 5 years

    public enum CustomerRiskTier { LOW, MEDIUM, HIGH, PROHIBITED }

    public enum KycStage {
        IDENTITY_VERIFICATION,
        DOCUMENT_COLLECTION,
        BENEFICIAL_OWNERSHIP,
        PEP_SCREENING,
        ADVERSE_MEDIA,
        SANCTIONS_SCREENING,
        RISK_ASSESSMENT;

        /** Stages that can run in parallel. */
        static final Set<KycStage> PARALLEL_STAGES = EnumSet.of(
                PEP_SCREENING, ADVERSE_MEDIA, SANCTIONS_SCREENING);
    }

    public enum StageVerdict { PASS, FAIL, MANUAL_REVIEW, PENDING, NOT_STARTED }

    public record KycWorkflow(
            UUID workflowId,
            CustomerId customer,
            Map<KycStage, StageResult> stageResults,
            KycStatus overallStatus,
            CustomerRiskTier riskTier,
            Instant startedAt,
            Instant completedAt,
            LocalDate nextReviewDate,
            String assignedAnalyst
    ) {}

    public record StageResult(KycStage stage, StageVerdict verdict, Instant evaluatedAt,
                        String details, List<String> findings) {
        public StageResult {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(verdict, "verdict");
            findings = findings != null ? List.copyOf(findings) : List.of();
        }
    }

    record KycEvent(UUID eventId, Instant occurredAt, CustomerId customer,
                    KycStage stage, StageVerdict verdict) implements DomainEvent {
        @Override
        public String eventType() {
            return "compliance.kyc.stage_completed";
        }
    }

    public record IdentityData(String firstName, String lastName, LocalDate dateOfBirth,
                         String ssn, String addressLine1, String city,
                         String state, String zip, String country) {
        public IdentityData {
            Objects.requireNonNull(firstName, "firstName");
            Objects.requireNonNull(lastName, "lastName");
            Objects.requireNonNull(dateOfBirth, "dateOfBirth");
        }
    }

    public record DocumentSubmission(String documentType, String documentNumber,
                               String issuingCountry, LocalDate issueDate,
                               LocalDate expiryDate, byte[] documentImageHash) {}

    public record BeneficialOwner(String name, LocalDate dateOfBirth, String ssn,
                            double ownershipPercent, String title) {}

    /** In-memory workflow store. Production would use a persistent store. */
    private final ConcurrentHashMap<String, KycWorkflow> workflows = new ConcurrentHashMap<>();

    private final EventBus events;
    private final Clock clock;

    public KycWorkflowEngine(EventBus events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Initiate a new KYC workflow for a customer. Creates the workflow record
     * and begins identity verification.
     */
    public KycWorkflow initiateKyc(CustomerId customer, IdentityData identityData) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(identityData, "identityData");

        UUID workflowId = UUID.randomUUID();
        Instant now = Timestamp.now(clock);

        Map<KycStage, StageResult> stages = new EnumMap<>(KycStage.class);
        for (KycStage stage : KycStage.values()) {
            stages.put(stage, new StageResult(stage, StageVerdict.NOT_STARTED, null,
                    "Not yet evaluated", List.of()));
        }

        // Start identity verification immediately
        StageResult idResult = performIdentityVerification(identityData);
        stages.put(KycStage.IDENTITY_VERIFICATION, idResult);

        KycWorkflow workflow = new KycWorkflow(workflowId, customer, Map.copyOf(stages),
                KycStatus.IN_PROGRESS, null, now, null, null, null);
        workflows.put(customer.toString(), workflow);

        publishStageEvent(customer, KycStage.IDENTITY_VERIFICATION, idResult.verdict());
        log.info("KYC workflow {} initiated for customer {}", workflowId, customer);

        if (idResult.verdict() == StageVerdict.PASS) {
            return advanceWorkflow(customer);
        }
        return workflow;
    }

    /**
     * Submit documents for the document collection stage.
     */
    public KycWorkflow submitDocuments(CustomerId customer, List<DocumentSubmission> documents) {
        KycWorkflow workflow = requireWorkflow(customer);
        List<String> findings = new ArrayList<>();

        StageVerdict verdict = StageVerdict.PASS;
        for (DocumentSubmission doc : documents) {
            if (doc.expiryDate() != null && doc.expiryDate().isBefore(LocalDate.now(clock))) {
                findings.add("Expired document: " + doc.documentType()
                        + " expired " + doc.expiryDate());
                verdict = StageVerdict.FAIL;
            }

            if (doc.issueDate() != null) {
                Duration age = Duration.between(
                        doc.issueDate().atStartOfDay(Timestamp.BANK_ZONE).toInstant(),
                        Timestamp.now(clock));
                if (age.compareTo(MAX_DOCUMENT_AGE) > 0) {
                    findings.add("Document too old: " + doc.documentType()
                            + " issued " + doc.issueDate());
                    // Do not downgrade an existing FAIL verdict — once a
                    // document fails outright the whole submission fails.
                    if (verdict != StageVerdict.FAIL) {
                        verdict = StageVerdict.MANUAL_REVIEW;
                    }
                }
            }
        }

        if (documents.isEmpty()) {
            verdict = StageVerdict.FAIL;
            findings.add("No documents submitted");
        }

        StageResult result = new StageResult(KycStage.DOCUMENT_COLLECTION, verdict,
                Timestamp.now(clock),
                verdict == StageVerdict.PASS ? "All documents verified" : "Document issues found",
                findings);

        workflow = updateStage(customer, KycStage.DOCUMENT_COLLECTION, result);
        publishStageEvent(customer, KycStage.DOCUMENT_COLLECTION, verdict);

        if (verdict == StageVerdict.PASS) {
            return advanceWorkflow(customer);
        }
        return workflow;
    }

    /**
     * Submit beneficial ownership information (required for entity accounts
     * under the CDD Rule — 31 CFR 1010.230).
     */
    public KycWorkflow submitBeneficialOwnership(CustomerId customer,
                                                   List<BeneficialOwner> owners) {
        List<String> findings = new ArrayList<>();
        StageVerdict verdict = StageVerdict.PASS;

        double totalOwnership = owners.stream()
                .mapToDouble(BeneficialOwner::ownershipPercent)
                .sum();

        if (totalOwnership < 25.0) {
            findings.add("Warning: reported beneficial ownership < 25%. Verify no owners omitted.");
            verdict = StageVerdict.MANUAL_REVIEW;
        }

        for (BeneficialOwner owner : owners) {
            if (owner.ownershipPercent() >= 25.0) {
                if (owner.ssn() == null || owner.ssn().isBlank()) {
                    findings.add("Missing SSN for 25%+ owner: " + owner.name());
                    verdict = StageVerdict.FAIL;
                }
                if (owner.dateOfBirth() == null) {
                    findings.add("Missing DOB for 25%+ owner: " + owner.name());
                    verdict = StageVerdict.FAIL;
                }
            }
        }

        StageResult result = new StageResult(KycStage.BENEFICIAL_OWNERSHIP, verdict,
                Timestamp.now(clock),
                "Beneficial ownership review: " + owners.size() + " owners reported",
                findings);

        updateStage(customer, KycStage.BENEFICIAL_OWNERSHIP, result);
        publishStageEvent(customer, KycStage.BENEFICIAL_OWNERSHIP, verdict);
        return advanceWorkflow(customer);
    }

    /**
     * Run PEP, adverse media, and sanctions screening in parallel.
     * These stages are typically automated with external vendor APIs.
     */
    public KycWorkflow runScreenings(CustomerId customer, String fullName, String country) {
        // PEP screening
        StageResult pepResult = performPepScreening(fullName, country);
        updateStage(customer, KycStage.PEP_SCREENING, pepResult);
        publishStageEvent(customer, KycStage.PEP_SCREENING, pepResult.verdict());

        // Adverse media
        StageResult mediaResult = performAdverseMediaScreening(fullName);
        updateStage(customer, KycStage.ADVERSE_MEDIA, mediaResult);
        publishStageEvent(customer, KycStage.ADVERSE_MEDIA, mediaResult.verdict());

        // Sanctions
        StageResult sanctionsResult = performSanctionsScreening(fullName, country);
        updateStage(customer, KycStage.SANCTIONS_SCREENING, sanctionsResult);
        publishStageEvent(customer, KycStage.SANCTIONS_SCREENING, sanctionsResult.verdict());

        return advanceWorkflow(customer);
    }

    /**
     * Advance the workflow to the next incomplete stage and compute overall status.
     */
    private KycWorkflow advanceWorkflow(CustomerId customer) {
        KycWorkflow current = requireWorkflow(customer);
        Map<KycStage, StageResult> stages = new java.util.HashMap<>(current.stageResults());

        // RISK_ASSESSMENT is computed inside this method, not by callers, so
        // exclude it from the prerequisite checks below — otherwise the stage
        // would never be reachable (its NOT_STARTED state would block both
        // anyPending and allPassed from settling).
        boolean anyFailed = stages.entrySet().stream()
                .filter(e -> e.getKey() != KycStage.RISK_ASSESSMENT)
                .anyMatch(e -> e.getValue().verdict() == StageVerdict.FAIL);
        boolean anyReview = stages.entrySet().stream()
                .filter(e -> e.getKey() != KycStage.RISK_ASSESSMENT)
                .anyMatch(e -> e.getValue().verdict() == StageVerdict.MANUAL_REVIEW);
        boolean anyPending = stages.entrySet().stream()
                .filter(e -> e.getKey() != KycStage.RISK_ASSESSMENT)
                .anyMatch(e -> e.getValue().verdict() == StageVerdict.NOT_STARTED
                        || e.getValue().verdict() == StageVerdict.PENDING);
        boolean allPassed = stages.entrySet().stream()
                .filter(e -> e.getKey() != KycStage.RISK_ASSESSMENT)
                .allMatch(e -> e.getValue().verdict() == StageVerdict.PASS);

        KycStatus newStatus;
        if (anyFailed) {
            newStatus = KycStatus.FAILED;
        } else if (anyReview) {
            newStatus = KycStatus.REQUIRES_DOCS;
        } else if (allPassed) {
            newStatus = KycStatus.PASSED;
        } else if (anyPending) {
            newStatus = KycStatus.IN_PROGRESS;
        } else {
            newStatus = KycStatus.IN_PROGRESS;
        }

        // Risk assessment (final stage)
        CustomerRiskTier riskTier = current.riskTier();
        LocalDate nextReview = current.nextReviewDate();
        Instant completedAt = current.completedAt();

        if (allPassed && current.riskTier() == null) {
            riskTier = assessRiskTier(stages);
            StageResult riskResult = new StageResult(KycStage.RISK_ASSESSMENT,
                    StageVerdict.PASS, Timestamp.now(clock),
                    "Risk tier assigned: " + riskTier, List.of());
            stages.put(KycStage.RISK_ASSESSMENT, riskResult);
            nextReview = computeNextReviewDate(riskTier);
            completedAt = Timestamp.now(clock);
            newStatus = KycStatus.PASSED;

            log.info("KYC completed for customer {}: risk={}, next review={}",
                    customer, riskTier, nextReview);
        }

        KycWorkflow updated = new KycWorkflow(current.workflowId(), customer,
                Map.copyOf(stages), newStatus, riskTier, current.startedAt(),
                completedAt, nextReview, current.assignedAnalyst());
        workflows.put(customer.toString(), updated);
        return updated;
    }

    private StageResult performIdentityVerification(IdentityData data) {
        List<String> findings = new ArrayList<>();

        // Validate SSN format
        if (data.ssn() != null && !data.ssn().matches("\\d{3}-?\\d{2}-?\\d{4}")) {
            findings.add("Invalid SSN format");
            return new StageResult(KycStage.IDENTITY_VERIFICATION, StageVerdict.FAIL,
                    Timestamp.now(clock), "Identity verification failed", findings);
        }

        // Age validation (must be 18+)
        if (data.dateOfBirth().plusYears(18).isAfter(LocalDate.now(clock))) {
            findings.add("Customer under 18 years of age");
            return new StageResult(KycStage.IDENTITY_VERIFICATION, StageVerdict.FAIL,
                    Timestamp.now(clock), "Age requirement not met", findings);
        }

        return new StageResult(KycStage.IDENTITY_VERIFICATION, StageVerdict.PASS,
                Timestamp.now(clock), "Identity verified successfully", findings);
    }

    private StageResult performPepScreening(String fullName, String country) {
        // In production, calls an external PEP screening vendor (e.g., Dow Jones, Refinitiv).
        return new StageResult(KycStage.PEP_SCREENING, StageVerdict.PASS,
                Timestamp.now(clock), "No PEP matches found for " + fullName, List.of());
    }

    private StageResult performAdverseMediaScreening(String fullName) {
        // In production, calls an adverse media screening service.
        return new StageResult(KycStage.ADVERSE_MEDIA, StageVerdict.PASS,
                Timestamp.now(clock), "No adverse media found for " + fullName, List.of());
    }

    private StageResult performSanctionsScreening(String fullName, String country) {
        // In production, calls OFAC SDN, EU, UN sanctions lists.
        return new StageResult(KycStage.SANCTIONS_SCREENING, StageVerdict.PASS,
                Timestamp.now(clock), "Sanctions screening clear for " + fullName, List.of());
    }

    private CustomerRiskTier assessRiskTier(Map<KycStage, StageResult> stages) {
        long totalFindings = stages.values().stream()
                .mapToLong(r -> r.findings().size())
                .sum();

        if (totalFindings > 5) return CustomerRiskTier.HIGH;
        if (totalFindings > 2) return CustomerRiskTier.MEDIUM;
        return CustomerRiskTier.LOW;
    }

    private LocalDate computeNextReviewDate(CustomerRiskTier tier) {
        Duration period = REVIEW_PERIODS.getOrDefault(tier, Duration.ofDays(365));
        return LocalDate.now(clock).plusDays(period.toDays());
    }

    private KycWorkflow updateStage(CustomerId customer, KycStage stage, StageResult result) {
        KycWorkflow current = requireWorkflow(customer);
        Map<KycStage, StageResult> stages = new java.util.HashMap<>(current.stageResults());
        stages.put(stage, result);
        KycWorkflow updated = new KycWorkflow(current.workflowId(), customer,
                Map.copyOf(stages), current.overallStatus(), current.riskTier(),
                current.startedAt(), current.completedAt(), current.nextReviewDate(),
                current.assignedAnalyst());
        workflows.put(customer.toString(), updated);
        return updated;
    }

    private void publishStageEvent(CustomerId customer, KycStage stage, StageVerdict verdict) {
        events.publish(new KycEvent(UUID.randomUUID(), Timestamp.now(clock),
                customer, stage, verdict));
    }

    public Optional<KycWorkflow> getWorkflow(CustomerId customer) {
        return Optional.ofNullable(workflows.get(customer.toString()));
    }

    private KycWorkflow requireWorkflow(CustomerId customer) {
        KycWorkflow workflow = workflows.get(customer.toString());
        if (workflow == null) {
            throw new IllegalStateException("No KYC workflow found for customer: " + customer);
        }
        return workflow;
    }
}
