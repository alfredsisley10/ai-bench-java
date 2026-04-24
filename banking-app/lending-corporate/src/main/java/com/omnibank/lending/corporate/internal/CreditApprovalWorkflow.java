package com.omnibank.lending.corporate.internal;

import com.omnibank.lending.corporate.api.LoanId;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Multi-level credit approval workflow with delegated authority limits, committee
 * routing, and conditions precedent tracking. The approval process is determined
 * by the loan amount, risk rating, and borrower type.
 *
 * <p>Approval authority is delegated as follows:
 * <ul>
 *   <li>Relationship Manager: up to $1M</li>
 *   <li>Senior Credit Officer: up to $5M</li>
 *   <li>Regional Credit Committee: up to $25M</li>
 *   <li>Executive Credit Committee: up to $100M</li>
 *   <li>Board: above $100M</li>
 * </ul>
 *
 * <p>Conditions precedent must all be satisfied before the loan can be funded.
 */
public class CreditApprovalWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CreditApprovalWorkflow.class);

    // ── Authority levels ──────────────────────────────────────────────────

    public enum ApprovalAuthority {
        RELATIONSHIP_MANAGER(1_000_000L),
        SENIOR_CREDIT_OFFICER(5_000_000L),
        REGIONAL_CREDIT_COMMITTEE(25_000_000L),
        EXECUTIVE_CREDIT_COMMITTEE(100_000_000L),
        BOARD(Long.MAX_VALUE);

        private final long maxAmountUsd;

        ApprovalAuthority(long maxAmountUsd) {
            this.maxAmountUsd = maxAmountUsd;
        }

        public long maxAmountUsd() { return maxAmountUsd; }
    }

    // ── Workflow state ────────────────────────────────────────────────────

    public enum WorkflowStatus {
        INITIATED, PENDING_REVIEW, PENDING_COMMITTEE, APPROVED,
        CONDITIONALLY_APPROVED, DECLINED, WITHDRAWN, EXPIRED
    }

    public enum ConditionStatus { PENDING, SATISFIED, WAIVED, FAILED }

    // ── Value types ───────────────────────────────────────────────────────

    public record ApprovalRequest(
            UUID requestId,
            LoanId loanId,
            UUID borrowerId,
            Money loanAmount,
            String loanPurpose,
            String riskRating,
            LocalDate requestDate,
            String requestedBy,
            WorkflowStatus status,
            ApprovalAuthority requiredAuthority,
            List<ApprovalDecision> decisions,
            List<ConditionPrecedent> conditions
    ) {
        public ApprovalRequest {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(borrowerId, "borrowerId");
            Objects.requireNonNull(loanAmount, "loanAmount");
            Objects.requireNonNull(requestDate, "requestDate");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(requiredAuthority, "requiredAuthority");
            decisions = List.copyOf(decisions);
            conditions = List.copyOf(conditions);
        }

        public boolean isFullyApproved() {
            return status == WorkflowStatus.APPROVED;
        }

        public boolean allConditionsMet() {
            return conditions.stream().allMatch(c ->
                    c.status() == ConditionStatus.SATISFIED || c.status() == ConditionStatus.WAIVED);
        }
    }

    public record ApprovalDecision(
            UUID decisionId,
            ApprovalAuthority authority,
            String decidedBy,
            DecisionType decision,
            String comments,
            Instant decidedAt,
            Optional<LocalDate> expiryDate
    ) {
        public ApprovalDecision {
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(expiryDate, "expiryDate");
        }
    }

    public enum DecisionType { APPROVE, CONDITIONAL_APPROVE, DECLINE, REFER_UP, ABSTAIN }

    public record ConditionPrecedent(
            UUID conditionId,
            String description,
            ConditionCategory category,
            ConditionStatus status,
            LocalDate dueDate,
            Optional<LocalDate> satisfiedDate,
            Optional<String> satisfiedBy,
            Optional<String> documentReference
    ) {
        public ConditionPrecedent {
            Objects.requireNonNull(conditionId, "conditionId");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(dueDate, "dueDate");
            Objects.requireNonNull(satisfiedDate, "satisfiedDate");
            Objects.requireNonNull(satisfiedBy, "satisfiedBy");
            Objects.requireNonNull(documentReference, "documentReference");
        }
    }

    public enum ConditionCategory {
        LEGAL, FINANCIAL, COLLATERAL, REGULATORY, INSURANCE, ENVIRONMENTAL, OTHER
    }

    // ── Authority determination ───────────────────────────────────────────

    /**
     * Determines the required approval authority based on the loan amount and
     * risk rating. Higher-risk loans require higher approval levels regardless
     * of amount.
     */
    public static ApprovalAuthority determineRequiredAuthority(Money loanAmount, String riskRating) {
        long amountInUsd = loanAmount.amount().longValue(); // Simplified; real impl uses FX

        // Risk escalation: subinvestment grade loans require at least committee approval
        boolean isHighRisk = riskRating != null && switch (riskRating.toUpperCase()) {
            case "BB", "B", "CCC", "CC", "C", "D" -> true;
            default -> false;
        };

        ApprovalAuthority baseAuthority = ApprovalAuthority.RELATIONSHIP_MANAGER;
        for (ApprovalAuthority auth : ApprovalAuthority.values()) {
            if (amountInUsd <= auth.maxAmountUsd()) {
                baseAuthority = auth;
                break;
            }
        }

        // Escalate high-risk loans to at least regional committee
        if (isHighRisk && baseAuthority.ordinal() < ApprovalAuthority.REGIONAL_CREDIT_COMMITTEE.ordinal()) {
            return ApprovalAuthority.REGIONAL_CREDIT_COMMITTEE;
        }

        return baseAuthority;
    }

    // ── Workflow operations ───────────────────────────────────────────────

    /**
     * Initiates a new credit approval workflow for a loan.
     */
    public ApprovalRequest initiateApproval(
            LoanId loanId, UUID borrowerId, Money loanAmount,
            String loanPurpose, String riskRating, String requestedBy
    ) {
        ApprovalAuthority required = determineRequiredAuthority(loanAmount, riskRating);

        List<ConditionPrecedent> standardConditions = generateStandardConditions(loanAmount);

        var request = new ApprovalRequest(
                UUID.randomUUID(), loanId, borrowerId, loanAmount, loanPurpose,
                riskRating, LocalDate.now(), requestedBy,
                WorkflowStatus.INITIATED, required,
                List.of(), standardConditions
        );

        log.info("Initiated credit approval for loan {} (amount={}, authority={})",
                loanId, loanAmount, required);
        return request;
    }

    /**
     * Records an approval decision from an authorized approver.
     */
    public ApprovalRequest recordDecision(
            ApprovalRequest request,
            ApprovalAuthority authority,
            String decidedBy,
            DecisionType decision,
            String comments,
            Optional<LocalDate> expiryDate
    ) {
        if (request.status() == WorkflowStatus.DECLINED
                || request.status() == WorkflowStatus.WITHDRAWN) {
            throw new IllegalStateException("Cannot add decision to %s workflow".formatted(request.status()));
        }

        if (authority.ordinal() < request.requiredAuthority().ordinal()
                && decision == DecisionType.APPROVE) {
            throw new IllegalStateException(
                    "Authority %s is insufficient for loan requiring %s"
                            .formatted(authority, request.requiredAuthority()));
        }

        var newDecision = new ApprovalDecision(
                UUID.randomUUID(), authority, decidedBy, decision,
                comments, Instant.now(), expiryDate
        );

        List<ApprovalDecision> updatedDecisions = new ArrayList<>(request.decisions());
        updatedDecisions.add(newDecision);

        WorkflowStatus newStatus = resolveStatus(decision, request.requiredAuthority(), authority);

        log.info("Decision recorded for loan {}: {} by {} at authority level {}",
                request.loanId(), decision, decidedBy, authority);

        return new ApprovalRequest(
                request.requestId(), request.loanId(), request.borrowerId(),
                request.loanAmount(), request.loanPurpose(), request.riskRating(),
                request.requestDate(), request.requestedBy(), newStatus,
                request.requiredAuthority(), updatedDecisions, request.conditions()
        );
    }

    /**
     * Satisfies a condition precedent.
     */
    public ApprovalRequest satisfyCondition(
            ApprovalRequest request, UUID conditionId,
            String satisfiedBy, String documentReference
    ) {
        List<ConditionPrecedent> updatedConditions = request.conditions().stream()
                .map(c -> {
                    if (c.conditionId().equals(conditionId)) {
                        return new ConditionPrecedent(
                                c.conditionId(), c.description(), c.category(),
                                ConditionStatus.SATISFIED, c.dueDate(),
                                Optional.of(LocalDate.now()),
                                Optional.of(satisfiedBy),
                                Optional.ofNullable(documentReference)
                        );
                    }
                    return c;
                })
                .toList();

        log.info("Condition {} satisfied for loan {} by {}", conditionId, request.loanId(), satisfiedBy);

        return new ApprovalRequest(
                request.requestId(), request.loanId(), request.borrowerId(),
                request.loanAmount(), request.loanPurpose(), request.riskRating(),
                request.requestDate(), request.requestedBy(), request.status(),
                request.requiredAuthority(), request.decisions(), updatedConditions
        );
    }

    /**
     * Checks whether the loan is ready for funding: approved and all
     * conditions satisfied or waived.
     */
    public boolean isReadyForFunding(ApprovalRequest request) {
        boolean approved = request.status() == WorkflowStatus.APPROVED
                || request.status() == WorkflowStatus.CONDITIONALLY_APPROVED;
        return approved && request.allConditionsMet();
    }

    /**
     * Checks if any approval decisions have expired.
     */
    public boolean hasExpiredApprovals(ApprovalRequest request, LocalDate asOf) {
        return request.decisions().stream()
                .filter(d -> d.decision() == DecisionType.APPROVE
                        || d.decision() == DecisionType.CONDITIONAL_APPROVE)
                .anyMatch(d -> d.expiryDate().isPresent() && asOf.isAfter(d.expiryDate().get()));
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private WorkflowStatus resolveStatus(DecisionType decision, ApprovalAuthority required,
                                         ApprovalAuthority actual) {
        return switch (decision) {
            case APPROVE -> actual.ordinal() >= required.ordinal()
                    ? WorkflowStatus.APPROVED
                    : WorkflowStatus.PENDING_COMMITTEE;
            case CONDITIONAL_APPROVE -> WorkflowStatus.CONDITIONALLY_APPROVED;
            case DECLINE -> WorkflowStatus.DECLINED;
            case REFER_UP -> WorkflowStatus.PENDING_COMMITTEE;
            case ABSTAIN -> WorkflowStatus.PENDING_REVIEW;
        };
    }

    private List<ConditionPrecedent> generateStandardConditions(Money loanAmount) {
        LocalDate dueDate = LocalDate.now().plusDays(60);
        List<ConditionPrecedent> conditions = new ArrayList<>();

        conditions.add(new ConditionPrecedent(
                UUID.randomUUID(), "Executed credit agreement and all ancillary documents",
                ConditionCategory.LEGAL, ConditionStatus.PENDING, dueDate,
                Optional.empty(), Optional.empty(), Optional.empty()
        ));

        conditions.add(new ConditionPrecedent(
                UUID.randomUUID(), "Satisfactory legal opinion from borrower's counsel",
                ConditionCategory.LEGAL, ConditionStatus.PENDING, dueDate,
                Optional.empty(), Optional.empty(), Optional.empty()
        ));

        conditions.add(new ConditionPrecedent(
                UUID.randomUUID(), "Current audited financial statements (no older than 120 days)",
                ConditionCategory.FINANCIAL, ConditionStatus.PENDING, dueDate,
                Optional.empty(), Optional.empty(), Optional.empty()
        ));

        conditions.add(new ConditionPrecedent(
                UUID.randomUUID(), "Evidence of required insurance coverage",
                ConditionCategory.INSURANCE, ConditionStatus.PENDING, dueDate,
                Optional.empty(), Optional.empty(), Optional.empty()
        ));

        conditions.add(new ConditionPrecedent(
                UUID.randomUUID(), "Compliance certificate from borrower's CFO",
                ConditionCategory.FINANCIAL, ConditionStatus.PENDING, dueDate,
                Optional.empty(), Optional.empty(), Optional.empty()
        ));

        // Larger loans require additional conditions
        if (loanAmount.amount().compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
            conditions.add(new ConditionPrecedent(
                    UUID.randomUUID(), "Environmental site assessment (Phase I minimum)",
                    ConditionCategory.ENVIRONMENTAL, ConditionStatus.PENDING, dueDate,
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));

            conditions.add(new ConditionPrecedent(
                    UUID.randomUUID(), "Perfected security interest in all collateral",
                    ConditionCategory.COLLATERAL, ConditionStatus.PENDING, dueDate,
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));

            conditions.add(new ConditionPrecedent(
                    UUID.randomUUID(), "KYC/AML verification completed for all guarantors",
                    ConditionCategory.REGULATORY, ConditionStatus.PENDING, dueDate,
                    Optional.empty(), Optional.empty(), Optional.empty()
            ));
        }

        return Collections.unmodifiableList(conditions);
    }
}
