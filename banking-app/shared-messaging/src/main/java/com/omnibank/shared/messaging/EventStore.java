package com.omnibank.shared.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only event store — the authoritative ledger of every domain event in
 * OmniBank. All events are persisted with sequence numbers, aggregate IDs,
 * event types, JSON payloads, and timestamps.
 *
 * <p>Supports event replay per aggregate, global replay (for read-side
 * projections), snapshot storage and retrieval, and stream queries by event
 * type or time range. This forms the backbone of the event-sourcing
 * infrastructure.</p>
 */
public interface EventStore {

    /**
     * Appends a single event to the store within the current transaction.
     *
     * @param aggregateType  e.g. "Payment", "Account", "Loan"
     * @param aggregateId    the business identity of the aggregate
     * @param eventType      the dotted type key ("payment.submitted")
     * @param payload        the event payload object (serialized to JSON)
     * @param correlationId  distributed tracing correlation ID
     * @param causationId    the message that caused this event
     * @param sourceModule   originating module name
     * @param expectedVersion optimistic concurrency — must match current max
     *                        sequence for this aggregate, or -1 to skip check
     * @return the persisted entity with its global sequence assigned
     */
    EventStoreEntity append(String aggregateType,
                            String aggregateId,
                            String eventType,
                            Object payload,
                            UUID correlationId,
                            UUID causationId,
                            String sourceModule,
                            long expectedVersion);

    /**
     * Stores a snapshot of the current aggregate state. On next replay the
     * store will start from this snapshot rather than re-applying all events.
     */
    EventStoreEntity storeSnapshot(String aggregateType,
                                   String aggregateId,
                                   long atSequence,
                                   Object snapshotPayload,
                                   String sourceModule);

    /**
     * Loads all events for an aggregate, starting from the latest snapshot
     * (if any) through the current head. Events are ordered by sequence number.
     */
    List<EventStoreEntity> loadStream(String aggregateType, String aggregateId);

    /**
     * Loads events for an aggregate starting from a specific sequence number.
     */
    List<EventStoreEntity> loadStreamFrom(String aggregateType,
                                          String aggregateId,
                                          long fromSequence);

    /**
     * Returns the latest snapshot for an aggregate, if one exists.
     */
    Optional<EventStoreEntity> latestSnapshot(String aggregateType, String aggregateId);

    /**
     * Queries events by type across all aggregates, ordered by global sequence.
     * Used for building cross-aggregate read-side projections.
     */
    List<EventStoreEntity> queryByEventType(String eventType, long afterGlobalSequence, int limit);

    /**
     * Queries events across all aggregates in a time range.
     */
    List<EventStoreEntity> queryByTimeRange(Instant from, Instant to, int limit);

    /**
     * Returns the current global head sequence (the highest assigned global
     * sequence number). Useful for checkpoint tracking in projections.
     */
    long currentGlobalSequence();

    /**
     * Returns all events after a given global sequence, up to a limit. Used
     * for catch-up subscriptions and projection rebuilding.
     */
    List<EventStoreEntity> readFromGlobalSequence(long afterGlobalSequence, int limit);

    // -----------------------------------------------------------------------
    //  JPA implementation
    // -----------------------------------------------------------------------

    @Repository
    class JpaEventStore implements EventStore {

        private static final Logger log = LoggerFactory.getLogger(JpaEventStore.class);

        @PersistenceContext
        private EntityManager em;

        private final ObjectMapper objectMapper;

        public JpaEventStore(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper);
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public EventStoreEntity append(String aggregateType,
                                       String aggregateId,
                                       String eventType,
                                       Object payload,
                                       UUID correlationId,
                                       UUID causationId,
                                       String sourceModule,
                                       long expectedVersion) {

            long currentMax = currentSequenceFor(aggregateType, aggregateId);

            if (expectedVersion >= 0 && currentMax != expectedVersion) {
                throw new OptimisticConcurrencyException(
                        "Expected version %d for %s/%s but current is %d"
                                .formatted(expectedVersion, aggregateType, aggregateId, currentMax));
            }

            long nextSeq = currentMax + 1;
            String json = serialize(payload);

            EventStoreEntity entity = new EventStoreEntity(
                    UUID.randomUUID(),
                    aggregateType,
                    aggregateId,
                    nextSeq,
                    eventType,
                    payload.getClass().getName(),
                    json,
                    null,
                    correlationId,
                    causationId,
                    sourceModule,
                    Instant.now(),
                    false,
                    1
            );

            em.persist(entity);
            em.flush(); // force global_sequence assignment

            log.debug("Appended event {} seq={} global={} for {}/{}",
                    eventType, nextSeq, entity.globalSequence(), aggregateType, aggregateId);
            return entity;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public EventStoreEntity storeSnapshot(String aggregateType,
                                              String aggregateId,
                                              long atSequence,
                                              Object snapshotPayload,
                                              String sourceModule) {

            String json = serialize(snapshotPayload);

            EventStoreEntity entity = new EventStoreEntity(
                    UUID.randomUUID(),
                    aggregateType,
                    aggregateId,
                    atSequence,
                    "snapshot",
                    snapshotPayload.getClass().getName(),
                    json,
                    null,
                    null,
                    null,
                    sourceModule,
                    Instant.now(),
                    true,
                    1
            );

            em.persist(entity);
            log.info("Stored snapshot for {}/{} at sequence {}", aggregateType, aggregateId, atSequence);
            return entity;
        }

        @Override
        @Transactional(readOnly = true)
        public List<EventStoreEntity> loadStream(String aggregateType, String aggregateId) {
            Optional<EventStoreEntity> snapshot = latestSnapshot(aggregateType, aggregateId);

            long fromSeq = snapshot.map(s -> s.sequenceNumber() + 1).orElse(0L);

            List<EventStoreEntity> events = loadStreamFrom(aggregateType, aggregateId, fromSeq);

            if (snapshot.isPresent()) {
                var combined = new java.util.ArrayList<EventStoreEntity>(events.size() + 1);
                combined.add(snapshot.get());
                combined.addAll(events);
                return List.copyOf(combined);
            }
            return events;
        }

        @Override
        @Transactional(readOnly = true)
        public List<EventStoreEntity> loadStreamFrom(String aggregateType,
                                                     String aggregateId,
                                                     long fromSequence) {
            TypedQuery<EventStoreEntity> query = em.createQuery(
                    """
                    SELECT e FROM EventStoreEntity e
                    WHERE e.aggregateType = :aggregateType
                      AND e.aggregateId = :aggregateId
                      AND e.sequenceNumber >= :fromSeq
                      AND e.snapshot = false
                    ORDER BY e.sequenceNumber ASC
                    """,
                    EventStoreEntity.class
            );
            query.setParameter("aggregateType", aggregateType);
            query.setParameter("aggregateId", aggregateId);
            query.setParameter("fromSeq", fromSequence);
            return query.getResultList();
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<EventStoreEntity> latestSnapshot(String aggregateType, String aggregateId) {
            TypedQuery<EventStoreEntity> query = em.createQuery(
                    """
                    SELECT e FROM EventStoreEntity e
                    WHERE e.aggregateType = :aggregateType
                      AND e.aggregateId = :aggregateId
                      AND e.snapshot = true
                    ORDER BY e.sequenceNumber DESC
                    """,
                    EventStoreEntity.class
            );
            query.setParameter("aggregateType", aggregateType);
            query.setParameter("aggregateId", aggregateId);
            query.setMaxResults(1);
            return query.getResultList().stream().findFirst();
        }

        @Override
        @Transactional(readOnly = true)
        public List<EventStoreEntity> queryByEventType(String eventType,
                                                       long afterGlobalSequence,
                                                       int limit) {
            TypedQuery<EventStoreEntity> query = em.createQuery(
                    """
                    SELECT e FROM EventStoreEntity e
                    WHERE e.eventType = :eventType
                      AND e.globalSequence > :afterSeq
                      AND e.snapshot = false
                    ORDER BY e.globalSequence ASC
                    """,
                    EventStoreEntity.class
            );
            query.setParameter("eventType", eventType);
            query.setParameter("afterSeq", afterGlobalSequence);
            query.setMaxResults(limit);
            return query.getResultList();
        }

        @Override
        @Transactional(readOnly = true)
        public List<EventStoreEntity> queryByTimeRange(Instant from, Instant to, int limit) {
            TypedQuery<EventStoreEntity> query = em.createQuery(
                    """
                    SELECT e FROM EventStoreEntity e
                    WHERE e.occurredAt >= :from
                      AND e.occurredAt < :to
                      AND e.snapshot = false
                    ORDER BY e.globalSequence ASC
                    """,
                    EventStoreEntity.class
            );
            query.setParameter("from", from);
            query.setParameter("to", to);
            query.setMaxResults(limit);
            return query.getResultList();
        }

        @Override
        @Transactional(readOnly = true)
        public long currentGlobalSequence() {
            Long max = em.createQuery(
                    "SELECT MAX(e.globalSequence) FROM EventStoreEntity e", Long.class
            ).getSingleResult();
            return max != null ? max : 0L;
        }

        @Override
        @Transactional(readOnly = true)
        public List<EventStoreEntity> readFromGlobalSequence(long afterGlobalSequence, int limit) {
            TypedQuery<EventStoreEntity> query = em.createQuery(
                    """
                    SELECT e FROM EventStoreEntity e
                    WHERE e.globalSequence > :afterSeq
                    ORDER BY e.globalSequence ASC
                    """,
                    EventStoreEntity.class
            );
            query.setParameter("afterSeq", afterGlobalSequence);
            query.setMaxResults(limit);
            return query.getResultList();
        }

        // -----------------------------------------------------------------------
        //  Internal helpers
        // -----------------------------------------------------------------------

        private long currentSequenceFor(String aggregateType, String aggregateId) {
            Long max = em.createQuery(
                    """
                    SELECT MAX(e.sequenceNumber) FROM EventStoreEntity e
                    WHERE e.aggregateType = :aggregateType
                      AND e.aggregateId = :aggregateId
                      AND e.snapshot = false
                    """,
                    Long.class
            ).setParameter("aggregateType", aggregateType)
             .setParameter("aggregateId", aggregateId)
             .getSingleResult();
            return max != null ? max : 0L;
        }

        private String serialize(Object payload) {
            try {
                return objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                throw new EventSerializationException(
                        "Failed to serialize event payload of type " + payload.getClass().getName(), e);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Custom exceptions
    // -----------------------------------------------------------------------

    class OptimisticConcurrencyException extends RuntimeException {
        public OptimisticConcurrencyException(String message) {
            super(message);
        }
    }

    class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
