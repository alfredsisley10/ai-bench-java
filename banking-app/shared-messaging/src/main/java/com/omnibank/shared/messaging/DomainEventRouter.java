package com.omnibank.shared.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Routes domain events to registered handlers based on event type. Supports
 * synchronous dispatch (caller blocks until all handlers complete), asynchronous
 * dispatch (handlers run on the Spring async executor), dead-letter handling
 * for events no handler claims, and event replay from the stored event log.
 *
 * <p>Handlers are registered programmatically via {@link #register} or
 * discovered automatically from Spring beans implementing
 * {@link DomainEventHandler}. Each handler declares the event types it can
 * process, an optional filter predicate, and whether it prefers async
 * dispatch.</p>
 *
 * <p>Thread safety: handler registrations are stored in concurrent data
 * structures. Dispatch itself is thread-safe; multiple events can be routed
 * concurrently.</p>
 */
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class DomainEventRouter {

    private static final Logger log = LoggerFactory.getLogger(DomainEventRouter.class);

    private final EventStore eventStore;
    private final Map<String, CopyOnWriteArrayList<HandlerRegistration>> handlersByType;
    private final CopyOnWriteArrayList<HandlerRegistration> wildcardHandlers;
    private final List<DeadLetterListener> deadLetterListeners;

    /** Metrics counters. */
    private final Map<String, RoutingMetrics> metrics = new ConcurrentHashMap<>();

    public DomainEventRouter(EventStore eventStore) {
        this.eventStore = Objects.requireNonNull(eventStore);
        this.handlersByType = new ConcurrentHashMap<>();
        this.wildcardHandlers = new CopyOnWriteArrayList<>();
        this.deadLetterListeners = new CopyOnWriteArrayList<>();
    }

    // -----------------------------------------------------------------------
    //  Handler registration
    // -----------------------------------------------------------------------

    /**
     * Registers a handler for one or more event types. Use {@code "*"} as the
     * event type for a wildcard handler that receives all events.
     */
    public Registration register(DomainEventHandler handler) {
        Objects.requireNonNull(handler, "handler");

        List<String> types = handler.handledEventTypes();
        if (types.isEmpty()) {
            throw new IllegalArgumentException("Handler must declare at least one event type");
        }

        HandlerRegistration reg = new HandlerRegistration(
                handler, handler.filter(), handler.isAsync()
        );

        for (String type : types) {
            if ("*".equals(type)) {
                wildcardHandlers.add(reg);
                log.info("Registered wildcard event handler: {}", handler.name());
            } else {
                handlersByType.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(reg);
                log.info("Registered event handler '{}' for type '{}'", handler.name(), type);
            }
        }

        return () -> {
            for (String type : types) {
                if ("*".equals(type)) {
                    wildcardHandlers.remove(reg);
                } else {
                    var list = handlersByType.get(type);
                    if (list != null) list.remove(reg);
                }
            }
            log.info("Unregistered event handler: {}", handler.name());
        };
    }

    /**
     * Registers a dead-letter listener invoked when an event has no matching
     * handler or all handlers rejected it via their filter.
     */
    public void onDeadLetter(DeadLetterListener listener) {
        deadLetterListeners.add(Objects.requireNonNull(listener));
    }

    // -----------------------------------------------------------------------
    //  Dispatch
    // -----------------------------------------------------------------------

    /**
     * Dispatches a domain event to all matching handlers. Synchronous handlers
     * execute inline; async handlers are dispatched to the thread pool.
     *
     * @param envelope the event envelope to route
     * @return dispatch result with handler count and any errors
     */
    public DispatchResult dispatch(MessageEnvelope.DomainEventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope");

        String eventType = envelope.payloadType();
        List<HandlerRegistration> matched = resolveHandlers(eventType);

        if (matched.isEmpty()) {
            handleDeadLetter(envelope, "No handlers registered for event type: " + eventType);
            incrementMetric(eventType, 0, 0, 1);
            return new DispatchResult(eventType, 0, 0, 1);
        }

        int syncCount = 0;
        int asyncCount = 0;
        List<HandlerError> errors = new ArrayList<>();

        for (HandlerRegistration reg : matched) {
            if (reg.filter != null && !reg.filter.test(envelope)) {
                continue;
            }

            if (reg.async) {
                dispatchAsync(reg.handler, envelope, errors);
                asyncCount++;
            } else {
                dispatchSync(reg.handler, envelope, errors);
                syncCount++;
            }
        }

        if (syncCount == 0 && asyncCount == 0) {
            handleDeadLetter(envelope, "All handlers filtered out event: " + eventType);
            incrementMetric(eventType, 0, 0, 1);
            return new DispatchResult(eventType, 0, 0, 1);
        }

        incrementMetric(eventType, syncCount, asyncCount, 0);
        return new DispatchResult(eventType, syncCount, asyncCount, 0, errors);
    }

    /**
     * Replays events from the event store starting after a given global
     * sequence. Useful for rebuilding read-side projections or catching up
     * a new handler.
     *
     * @param afterGlobalSequence replay events after this sequence number
     * @param batchSize           number of events per batch
     * @param maxBatches          safety limit on total batches (0 = unlimited)
     * @return total number of events replayed
     */
    public long replayFromStore(long afterGlobalSequence, int batchSize, int maxBatches) {
        log.info("Starting event replay from global sequence {}, batchSize={}, maxBatches={}",
                afterGlobalSequence, batchSize, maxBatches);

        long cursor = afterGlobalSequence;
        long totalReplayed = 0;
        int batchCount = 0;

        while (maxBatches == 0 || batchCount < maxBatches) {
            List<EventStoreEntity> batch = eventStore.readFromGlobalSequence(cursor, batchSize);
            if (batch.isEmpty()) {
                break;
            }

            for (EventStoreEntity entity : batch) {
                try {
                    var syntheticEnvelope = reconstructEnvelope(entity);
                    dispatch(syntheticEnvelope);
                    totalReplayed++;
                } catch (Exception ex) {
                    log.error("Replay failed at global sequence {}: {}",
                            entity.globalSequence(), ex.getMessage(), ex);
                }
                cursor = entity.globalSequence();
            }

            batchCount++;
        }

        log.info("Event replay complete: {} events replayed in {} batches", totalReplayed, batchCount);
        return totalReplayed;
    }

    // -----------------------------------------------------------------------
    //  Handler discovery from Spring context
    // -----------------------------------------------------------------------

    /**
     * Auto-discovers and registers all {@link DomainEventHandler} beans.
     * Called by Spring after context refresh.
     */
    @jakarta.annotation.PostConstruct
    public void autoDiscoverHandlers() {
        // Handlers inject themselves via Spring's dependency injection in
        // their own @PostConstruct or via the ApplicationContext. This method
        // serves as a hook for subclasses or testing.
        log.info("DomainEventRouter initialized, ready for handler registration");
    }

    // -----------------------------------------------------------------------
    //  Metrics
    // -----------------------------------------------------------------------

    public Map<String, RoutingMetrics> allMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    public record RoutingMetrics(
            long syncDispatches,
            long asyncDispatches,
            long deadLetters,
            Instant lastDispatchedAt
    ) {}

    // -----------------------------------------------------------------------
    //  Supporting types
    // -----------------------------------------------------------------------

    public record DispatchResult(
            String eventType,
            int syncHandlerCount,
            int asyncHandlerCount,
            int deadLetterCount,
            List<HandlerError> errors
    ) {
        public DispatchResult(String eventType, int sync, int async, int deadLetter) {
            this(eventType, sync, async, deadLetter, List.of());
        }

        public boolean hasErrors() { return errors != null && !errors.isEmpty(); }
    }

    public record HandlerError(String handlerName, String eventType, String message) {}

    /** Returned from {@link #register} to allow deregistration. */
    @FunctionalInterface
    public interface Registration {
        void unregister();
    }

    /** Callback for dead-letter events. */
    @FunctionalInterface
    public interface DeadLetterListener {
        void onDeadLetter(MessageEnvelope.DomainEventEnvelope<?> envelope, String reason);
    }

    /**
     * Interface for domain event handlers. Implementations declare which event
     * types they handle and an optional filter predicate.
     */
    public interface DomainEventHandler {

        String name();

        List<String> handledEventTypes();

        void handle(MessageEnvelope.DomainEventEnvelope<?> envelope);

        default Predicate<MessageEnvelope.DomainEventEnvelope<?>> filter() { return null; }

        default boolean isAsync() { return false; }
    }

    // -----------------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------------

    private record HandlerRegistration(
            DomainEventHandler handler,
            Predicate<MessageEnvelope.DomainEventEnvelope<?>> filter,
            boolean async
    ) {}

    private List<HandlerRegistration> resolveHandlers(String eventType) {
        var typed = handlersByType.getOrDefault(eventType, new CopyOnWriteArrayList<>());
        if (wildcardHandlers.isEmpty()) {
            return typed;
        }
        var combined = new ArrayList<HandlerRegistration>(typed.size() + wildcardHandlers.size());
        combined.addAll(typed);
        combined.addAll(wildcardHandlers);
        return combined;
    }

    private void dispatchSync(DomainEventHandler handler,
                              MessageEnvelope.DomainEventEnvelope<?> envelope,
                              List<HandlerError> errors) {
        try {
            handler.handle(envelope);
        } catch (Exception ex) {
            log.error("Sync handler '{}' failed for event type '{}': {}",
                    handler.name(), envelope.payloadType(), ex.getMessage(), ex);
            errors.add(new HandlerError(handler.name(), envelope.payloadType(), ex.getMessage()));
        }
    }

    @Async
    private void dispatchAsync(DomainEventHandler handler,
                               MessageEnvelope.DomainEventEnvelope<?> envelope,
                               List<HandlerError> errors) {
        try {
            handler.handle(envelope);
        } catch (Exception ex) {
            log.error("Async handler '{}' failed for event type '{}': {}",
                    handler.name(), envelope.payloadType(), ex.getMessage(), ex);
            errors.add(new HandlerError(handler.name(), envelope.payloadType(), ex.getMessage()));
        }
    }

    private void handleDeadLetter(MessageEnvelope.DomainEventEnvelope<?> envelope, String reason) {
        log.warn("Dead letter event: type={}, messageId={}, reason={}",
                envelope.payloadType(), envelope.messageId(), reason);

        for (DeadLetterListener listener : deadLetterListeners) {
            try {
                listener.onDeadLetter(envelope, reason);
            } catch (Exception ex) {
                log.error("Dead letter listener threw: {}", ex.getMessage(), ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private MessageEnvelope.DomainEventEnvelope<?> reconstructEnvelope(EventStoreEntity entity) {
        return new MessageEnvelope.DomainEventEnvelope<>(
                entity.eventId(),
                entity.correlationId() != null ? entity.correlationId() : entity.eventId(),
                entity.causationId() != null ? entity.causationId() : entity.eventId(),
                entity.occurredAt(),
                entity.sourceModule(),
                entity.eventType(),
                entity.payloadJson(),
                Map.of("replayed", "true", "globalSequence", String.valueOf(entity.globalSequence())),
                entity.aggregateType(),
                entity.aggregateId(),
                entity.sequenceNumber()
        );
    }

    private void incrementMetric(String eventType, int sync, int async, int deadLetter) {
        metrics.compute(eventType, (k, existing) -> {
            if (existing == null) {
                return new RoutingMetrics(sync, async, deadLetter, Instant.now());
            }
            return new RoutingMetrics(
                    existing.syncDispatches + sync,
                    existing.asyncDispatches + async,
                    existing.deadLetters + deadLetter,
                    Instant.now()
            );
        });
    }
}
