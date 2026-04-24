package com.omnibank.shared.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates multi-step distributed sagas across OmniBank modules.
 *
 * <p>A saga is a sequence of local transactions where each step is executed by
 * a different module. If any step fails, the orchestrator runs compensation
 * actions for all previously completed steps in reverse order.</p>
 *
 * <p>Example: payment processing saga with steps
 * {@code validate -> screen -> route -> settle -> notify}. If "settle" fails,
 * the orchestrator compensates "route" (cancel routing), then "screen" (log
 * reversal), then "validate" (release hold).</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Step-by-step execution with state persisted after each step</li>
 *   <li>Automatic compensation on failure (reverse order)</li>
 *   <li>Timeout detection via scheduled deadline scanner</li>
 *   <li>Saga-level and step-level metadata for audit and debugging</li>
 *   <li>Pluggable step definitions via {@link SagaDefinition}</li>
 * </ul>
 */
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    @PersistenceContext
    private EntityManager em;

    private final ObjectMapper objectMapper;
    private final ReliableMessagePublisher messagePublisher;
    private final EventStore eventStore;

    private final Map<String, SagaDefinition> registeredSagas = new ConcurrentHashMap<>();

    @Value("${omnibank.saga.default-timeout:PT30M}")
    private Duration defaultTimeout;

    @Value("${omnibank.saga.deadline-scan-batch:50}")
    private int deadlineScanBatch;

    public SagaOrchestrator(ObjectMapper objectMapper,
                            ReliableMessagePublisher messagePublisher,
                            EventStore eventStore) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.messagePublisher = Objects.requireNonNull(messagePublisher);
        this.eventStore = Objects.requireNonNull(eventStore);
    }

    // -----------------------------------------------------------------------
    //  Saga definition registration
    // -----------------------------------------------------------------------

    /**
     * Registers a saga definition. Typically called from module-specific
     * {@code @Configuration} classes at startup.
     */
    public void registerSaga(SagaDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        registeredSagas.put(definition.sagaType(), definition);
        log.info("Registered saga definition '{}' with {} steps",
                definition.sagaType(), definition.steps().size());
    }

    // -----------------------------------------------------------------------
    //  Saga lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initiates a new saga instance.
     *
     * @param sagaType      registered saga type name
     * @param aggregateType the aggregate this saga operates on
     * @param aggregateId   the aggregate instance identity
     * @param correlationId distributed tracing correlation
     * @param initiatedBy   module or user that triggered this saga
     * @param initialData   initial saga context data
     * @return the saga ID
     */
    @Transactional
    public UUID startSaga(String sagaType,
                          String aggregateType,
                          String aggregateId,
                          UUID correlationId,
                          String initiatedBy,
                          Map<String, Object> initialData) {

        SagaDefinition definition = registeredSagas.get(sagaType);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown saga type: " + sagaType);
        }

        UUID sagaId = UUID.randomUUID();
        String dataJson = serialize(initialData);
        Instant deadline = Instant.now().plus(
                definition.timeout() != null ? definition.timeout() : defaultTimeout);

        SagaState state = new SagaState(
                sagaId, sagaType, definition.steps().size(),
                aggregateType, aggregateId, correlationId,
                initiatedBy, dataJson, deadline
        );

        // Initialize step log entries
        for (SagaStep step : definition.steps()) {
            state.addStepLog(new SagaState.StepLogEntry(step.name()));
        }

        em.persist(state);

        log.info("Created saga {} type='{}' aggregate={}/{} deadline={}",
                sagaId, sagaType, aggregateType, aggregateId, deadline);

        // Record saga creation event
        eventStore.append(
                "Saga", sagaId.toString(),
                "saga.created", initialData,
                correlationId, sagaId, "saga-orchestrator", -1
        );

        // Immediately execute first step
        state.start();
        executeCurrentStep(state, definition);

        return sagaId;
    }

    /**
     * Signals the saga that an external step has completed, advancing the
     * saga to the next step.
     *
     * @param sagaId    the saga to advance
     * @param stepName  the step that completed
     * @param output    step output data (persisted in step log)
     */
    @Transactional
    public void completeStep(UUID sagaId, String stepName, Map<String, Object> output) {
        SagaState state = loadSaga(sagaId);
        SagaDefinition definition = getDefinition(state.sagaType());

        List<SagaState.StepLogEntry> steps = new ArrayList<>(state.stepLog());
        SagaState.StepLogEntry currentEntry = steps.get(state.currentStepIndex());

        if (!currentEntry.stepName().equals(stepName)) {
            throw new IllegalStateException(
                    "Step mismatch: expected '%s' but got '%s' for saga %s"
                            .formatted(currentEntry.stepName(), stepName, sagaId));
        }

        currentEntry.markCompleted(serialize(output));
        state.updateSagaData(serialize(mergeData(state, output)));

        log.info("Saga {} step '{}' completed ({}/{})",
                sagaId, stepName, state.currentStepIndex() + 1, state.totalSteps());

        // Check if this was the last step
        if (state.currentStepIndex() + 1 >= state.totalSteps()) {
            state.markCompleted();
            log.info("Saga {} completed successfully", sagaId);

            eventStore.append(
                    "Saga", sagaId.toString(),
                    "saga.completed", output,
                    state.correlationId(), sagaId, "saga-orchestrator", -1
            );
            publishSagaEvent(state, "saga.completed");
        } else {
            state.advanceStep();
            executeCurrentStep(state, definition);
        }
    }

    /**
     * Signals a step failure, triggering compensation.
     */
    @Transactional
    public void failStep(UUID sagaId, String stepName, String reason) {
        SagaState state = loadSaga(sagaId);

        List<SagaState.StepLogEntry> steps = new ArrayList<>(state.stepLog());
        SagaState.StepLogEntry currentEntry = steps.get(state.currentStepIndex());
        currentEntry.markFailed(reason);

        log.warn("Saga {} step '{}' failed: {}", sagaId, stepName, reason);

        state.markCompensating(reason, stepName);
        eventStore.append(
                "Saga", sagaId.toString(),
                "saga.step.failed", Map.of("step", stepName, "reason", reason),
                state.correlationId(), sagaId, "saga-orchestrator", -1
        );

        compensate(state, getDefinition(state.sagaType()));
    }

    /**
     * Returns the current state of a saga.
     */
    @Transactional(readOnly = true)
    public SagaState getSaga(UUID sagaId) {
        return loadSaga(sagaId);
    }

    /**
     * Queries sagas by status for operational monitoring.
     */
    @Transactional(readOnly = true)
    public List<SagaState> findByStatus(SagaState.SagaStatus status, int limit) {
        TypedQuery<SagaState> query = em.createQuery(
                "SELECT s FROM SagaState s WHERE s.status = :status ORDER BY s.updatedAt DESC",
                SagaState.class
        );
        query.setParameter("status", status);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    // -----------------------------------------------------------------------
    //  Timeout detection
    // -----------------------------------------------------------------------

    /**
     * Scans for sagas that have exceeded their deadline and initiates
     * compensation. Runs on a fixed schedule.
     */
    @Scheduled(fixedDelayString = "${omnibank.saga.deadline-scan-interval-ms:60000}")
    @Transactional
    public void scanForTimedOutSagas() {
        TypedQuery<SagaState> query = em.createQuery(
                """
                SELECT s FROM SagaState s
                WHERE s.deadline < CURRENT_TIMESTAMP
                  AND s.status IN ('RUNNING', 'AWAITING', 'CREATED')
                ORDER BY s.deadline ASC
                """,
                SagaState.class
        );
        query.setMaxResults(deadlineScanBatch);
        List<SagaState> expired = query.getResultList();

        if (expired.isEmpty()) return;

        log.warn("Found {} timed-out sagas, initiating compensation", expired.size());

        for (SagaState state : expired) {
            try {
                state.markTimedOut();
                state.markCompensating("Deadline exceeded: " + state.deadline(), "timeout");

                eventStore.append(
                        "Saga", state.sagaId().toString(),
                        "saga.timed_out", Map.of("deadline", state.deadline().toString()),
                        state.correlationId(), state.sagaId(), "saga-orchestrator", -1
                );

                SagaDefinition definition = registeredSagas.get(state.sagaType());
                if (definition != null) {
                    compensate(state, definition);
                } else {
                    log.error("No saga definition for type '{}', cannot compensate saga {}",
                            state.sagaType(), state.sagaId());
                    state.markFailed("No saga definition available for compensation");
                }
            } catch (Exception ex) {
                log.error("Error handling timeout for saga {}: {}", state.sagaId(), ex.getMessage(), ex);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Step execution
    // -----------------------------------------------------------------------

    private void executeCurrentStep(SagaState state, SagaDefinition definition) {
        int idx = state.currentStepIndex();
        SagaStep step = definition.steps().get(idx);

        List<SagaState.StepLogEntry> steps = new ArrayList<>(state.stepLog());
        SagaState.StepLogEntry entry = steps.get(idx);
        entry.markRunning();

        log.info("Saga {} executing step '{}' ({}/{})",
                state.sagaId(), step.name(), idx + 1, state.totalSteps());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> sagaData = objectMapper.readValue(
                    state.sagaDataJson(), Map.class);

            StepContext context = new StepContext(
                    state.sagaId(),
                    state.correlationId(),
                    step.name(),
                    idx,
                    Collections.unmodifiableMap(sagaData)
            );

            StepResult result = step.execute(context);

            switch (result) {
                case StepResult.Completed completed -> {
                    completeStep(state.sagaId(), step.name(), completed.output());
                }
                case StepResult.Async ignored -> {
                    state.markAwaiting();
                    log.debug("Saga {} step '{}' is async, awaiting callback",
                            state.sagaId(), step.name());
                }
                case StepResult.Failed failed -> {
                    failStep(state.sagaId(), step.name(), failed.reason());
                }
            }
        } catch (Exception ex) {
            log.error("Saga {} step '{}' threw: {}", state.sagaId(), step.name(), ex.getMessage(), ex);
            failStep(state.sagaId(), step.name(), ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Compensation
    // -----------------------------------------------------------------------

    private void compensate(SagaState state, SagaDefinition definition) {
        log.info("Starting compensation for saga {} from step index {}",
                state.sagaId(), state.currentStepIndex());

        List<SagaState.StepLogEntry> steps = new ArrayList<>(state.stepLog());
        boolean allCompensated = true;

        // Walk backward from the step before the failed one
        for (int i = state.currentStepIndex() - 1; i >= 0; i--) {
            SagaState.StepLogEntry stepEntry = steps.get(i);
            if (stepEntry.stepStatus() != SagaState.StepLogEntry.StepStatus.COMPLETED) {
                continue; // only compensate completed steps
            }

            SagaStep step = definition.steps().get(i);
            SagaState.CompensationLogEntry compEntry = new SagaState.CompensationLogEntry(step.name());
            compEntry.markRunning();
            state.addCompensationLog(compEntry);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> sagaData = objectMapper.readValue(
                        state.sagaDataJson(), Map.class);

                StepContext context = new StepContext(
                        state.sagaId(), state.correlationId(),
                        step.name(), i, sagaData
                );

                step.compensate(context);
                compEntry.markCompleted();

                log.info("Saga {} compensated step '{}'", state.sagaId(), step.name());

                eventStore.append(
                        "Saga", state.sagaId().toString(),
                        "saga.step.compensated", Map.of("step", step.name()),
                        state.correlationId(), state.sagaId(), "saga-orchestrator", -1
                );

            } catch (Exception ex) {
                compEntry.markFailed(ex.getMessage());
                allCompensated = false;

                log.error("Saga {} compensation failed for step '{}': {}",
                        state.sagaId(), step.name(), ex.getMessage(), ex);

                eventStore.append(
                        "Saga", state.sagaId().toString(),
                        "saga.compensation.failed",
                        Map.of("step", step.name(), "error", ex.getMessage()),
                        state.correlationId(), state.sagaId(), "saga-orchestrator", -1
                );
            }
        }

        if (allCompensated) {
            state.markCompensated();
            log.info("Saga {} fully compensated", state.sagaId());
            publishSagaEvent(state, "saga.compensated");
        } else {
            state.markFailed("One or more compensation steps failed — manual intervention required");
            log.error("Saga {} compensation incomplete — MANUAL INTERVENTION REQUIRED", state.sagaId());
            publishSagaEvent(state, "saga.compensation.incomplete");
        }
    }

    // -----------------------------------------------------------------------
    //  Supporting types
    // -----------------------------------------------------------------------

    /**
     * Definition of a saga type — its steps, timeout, and metadata.
     */
    public interface SagaDefinition {

        String sagaType();

        List<SagaStep> steps();

        default Duration timeout() { return null; }
    }

    /**
     * A single step in a saga.
     */
    public interface SagaStep {

        String name();

        StepResult execute(StepContext context);

        void compensate(StepContext context);
    }

    /**
     * Context passed to each step, providing saga metadata and accumulated data.
     */
    public record StepContext(
            UUID sagaId,
            UUID correlationId,
            String stepName,
            int stepIndex,
            Map<String, Object> sagaData
    ) {}

    /**
     * Result of a step execution. Sealed to enforce exhaustive handling.
     */
    public sealed interface StepResult {

        record Completed(Map<String, Object> output) implements StepResult {
            public Completed() { this(Map.of()); }
        }

        record Async(String callbackDestination) implements StepResult {}

        record Failed(String reason) implements StepResult {}
    }

    // -----------------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------------

    private SagaState loadSaga(UUID sagaId) {
        SagaState state = em.find(SagaState.class, sagaId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown saga: " + sagaId);
        }
        return state;
    }

    private SagaDefinition getDefinition(String sagaType) {
        SagaDefinition definition = registeredSagas.get(sagaType);
        if (definition == null) {
            throw new IllegalStateException("No registered definition for saga type: " + sagaType);
        }
        return definition;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeData(SagaState state, Map<String, Object> stepOutput) {
        try {
            Map<String, Object> existing = objectMapper.readValue(state.sagaDataJson(), Map.class);
            var merged = new java.util.HashMap<>(existing);
            if (stepOutput != null) {
                merged.putAll(stepOutput);
            }
            return merged;
        } catch (JsonProcessingException e) {
            log.warn("Cannot merge saga data for {}: {}", state.sagaId(), e.getMessage());
            return stepOutput != null ? stepOutput : Map.of();
        }
    }

    private void publishSagaEvent(SagaState state, String eventType) {
        try {
            var envelope = MessageEnvelope.builder("saga-orchestrator",
                            Map.of("sagaId", state.sagaId().toString(),
                                    "sagaType", state.sagaType(),
                                    "status", state.status().name()))
                    .correlationId(state.correlationId())
                    .payloadType(eventType)
                    .aggregateType("Saga")
                    .aggregateId(state.sagaId().toString())
                    .asDomainEvent();

            messagePublisher.enqueue(MessageBrokerConfig.TOPIC_PAYMENT_EVENTS, envelope,
                    state.sagaId().toString());
        } catch (Exception ex) {
            log.warn("Failed to publish saga event '{}' for {}: {}",
                    eventType, state.sagaId(), ex.getMessage());
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON serialization failed", e);
        }
    }
}
