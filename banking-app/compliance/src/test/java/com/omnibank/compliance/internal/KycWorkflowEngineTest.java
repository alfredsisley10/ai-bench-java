package com.omnibank.compliance.internal;

import com.omnibank.compliance.api.KycStatus;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycWorkflowEngineTest {

    private RecordingEventBus eventBus;
    private Clock clock;
    private KycWorkflowEngine engine;
    private CustomerId customer;

    @BeforeEach
    void setUp() {
        eventBus = new RecordingEventBus();
        clock = Clock.fixed(Instant.parse("2026-04-19T12:00:00Z"), ZoneId.of("UTC"));
        engine = new KycWorkflowEngine(eventBus, clock);
        customer = new CustomerId(UUID.randomUUID());
    }

    @Test
    void initiate_with_valid_identity_advances_workflow_in_progress() {
        var workflow = engine.initiateKyc(customer, validIdentity());

        assertThat(workflow.overallStatus()).isEqualTo(KycStatus.IN_PROGRESS);
        var idResult = workflow.stageResults().get(KycWorkflowEngine.KycStage.IDENTITY_VERIFICATION);
        assertThat(idResult.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.PASS);
        assertThat(eventBus.events).hasSize(1);
    }

    @Test
    void initiate_with_invalid_ssn_marks_workflow_failed() {
        var bad = new KycWorkflowEngine.IdentityData("Jane", "Doe",
                LocalDate.of(1990, 1, 1), "abc-12-3456",
                "1 Main St", "Anytown", "NY", "10001", "US");

        var workflow = engine.initiateKyc(customer, bad);

        assertThat(workflow.overallStatus()).isEqualTo(KycStatus.IN_PROGRESS);
        // identity verification stage fails outright
        var idResult = workflow.stageResults().get(KycWorkflowEngine.KycStage.IDENTITY_VERIFICATION);
        assertThat(idResult.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.FAIL);
    }

    @Test
    void minor_customer_fails_identity_verification() {
        var minor = new KycWorkflowEngine.IdentityData("Tim", "Young",
                LocalDate.of(2020, 1, 1), "123-45-6789",
                "1 Main St", "Anytown", "NY", "10001", "US");

        var workflow = engine.initiateKyc(customer, minor);
        var idResult = workflow.stageResults().get(KycWorkflowEngine.KycStage.IDENTITY_VERIFICATION);
        assertThat(idResult.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.FAIL);
        assertThat(idResult.findings()).anyMatch(f -> f.contains("18"));
    }

    @Test
    void empty_document_submission_fails_stage() {
        engine.initiateKyc(customer, validIdentity());
        var workflow = engine.submitDocuments(customer, List.of());

        var docResult = workflow.stageResults().get(KycWorkflowEngine.KycStage.DOCUMENT_COLLECTION);
        assertThat(docResult.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.FAIL);
        assertThat(docResult.findings()).anyMatch(f -> f.contains("No documents"));
    }

    @Test
    void expired_document_fails_stage() {
        engine.initiateKyc(customer, validIdentity());
        var expired = new KycWorkflowEngine.DocumentSubmission(
                "PASSPORT", "X1234567", "US",
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2025, 1, 1), // expired before today (2026-04-19)
                new byte[]{1, 2, 3});

        var workflow = engine.submitDocuments(customer, List.of(expired));

        var docResult = workflow.stageResults().get(KycWorkflowEngine.KycStage.DOCUMENT_COLLECTION);
        assertThat(docResult.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.FAIL);
    }

    @Test
    void valid_documents_advance_stage_to_pass() {
        engine.initiateKyc(customer, validIdentity());
        var goodDoc = new KycWorkflowEngine.DocumentSubmission(
                "PASSPORT", "X1234567", "US",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2030, 1, 1),
                new byte[]{1, 2, 3});

        var workflow = engine.submitDocuments(customer, List.of(goodDoc));
        var docResult = workflow.stageResults().get(KycWorkflowEngine.KycStage.DOCUMENT_COLLECTION);
        assertThat(docResult.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.PASS);
    }

    @Test
    void beneficial_owner_missing_ssn_fails_stage() {
        engine.initiateKyc(customer, validIdentity());
        var owner = new KycWorkflowEngine.BeneficialOwner(
                "Alice Owner", LocalDate.of(1980, 1, 1), "", 30.0, "CEO");

        var workflow = engine.submitBeneficialOwnership(customer, List.of(owner));

        var bo = workflow.stageResults().get(KycWorkflowEngine.KycStage.BENEFICIAL_OWNERSHIP);
        assertThat(bo.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.FAIL);
        assertThat(bo.findings()).anyMatch(f -> f.contains("SSN"));
    }

    @Test
    void low_total_ownership_routes_to_manual_review() {
        engine.initiateKyc(customer, validIdentity());
        var owner = new KycWorkflowEngine.BeneficialOwner(
                "Alice Owner", LocalDate.of(1980, 1, 1), "111-22-3333", 10.0, "CEO");

        var workflow = engine.submitBeneficialOwnership(customer, List.of(owner));

        var bo = workflow.stageResults().get(KycWorkflowEngine.KycStage.BENEFICIAL_OWNERSHIP);
        assertThat(bo.verdict()).isEqualTo(KycWorkflowEngine.StageVerdict.MANUAL_REVIEW);
    }

    @Test
    void full_happy_path_assigns_risk_tier_and_review_date() {
        engine.initiateKyc(customer, validIdentity());
        var goodDoc = new KycWorkflowEngine.DocumentSubmission(
                "PASSPORT", "X1234567", "US",
                LocalDate.of(2024, 1, 1), LocalDate.of(2030, 1, 1), new byte[]{1});
        engine.submitDocuments(customer, List.of(goodDoc));
        var owner = new KycWorkflowEngine.BeneficialOwner(
                "Alice", LocalDate.of(1980, 1, 1), "111-22-3333", 60.0, "CEO");
        engine.submitBeneficialOwnership(customer, List.of(owner));
        var workflow = engine.runScreenings(customer, "Alice", "US");

        assertThat(workflow.overallStatus()).isEqualTo(KycStatus.PASSED);
        assertThat(workflow.riskTier()).isEqualTo(KycWorkflowEngine.CustomerRiskTier.LOW);
        // LOW risk tier → 3-year re-review.
        assertThat(workflow.nextReviewDate()).isEqualTo(LocalDate.of(2029, 4, 18));
        assertThat(workflow.completedAt()).isNotNull();
    }

    @Test
    void get_workflow_for_unknown_customer_is_empty() {
        assertThat(engine.getWorkflow(new CustomerId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void submitting_documents_for_unknown_customer_throws() {
        assertThatThrownBy(() -> engine.submitDocuments(
                new CustomerId(UUID.randomUUID()), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private KycWorkflowEngine.IdentityData validIdentity() {
        return new KycWorkflowEngine.IdentityData("Jane", "Doe",
                LocalDate.of(1990, 1, 1), "123-45-6789",
                "1 Main St", "Anytown", "NY", "10001", "US");
    }

    private static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();

        @Override public void publish(DomainEvent event) { events.add(event); }
    }
}
