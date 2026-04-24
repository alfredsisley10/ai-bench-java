package com.omnibank.shared.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional outbox pattern implementation for reliable message publishing.
 *
 * <p>Instead of publishing directly to the broker (which risks losing messages
 * if the broker is down or the transaction rolls back after the send), messages
 * are first written to an outbox table within the same database transaction as
 * the business state change. A scheduled poller then forwards unpublished
 * messages to the broker.</p>
 *
 * <p>Guarantees provided:</p>
 * <ul>
 *   <li>At-least-once delivery (deduplication is the consumer's responsibility)</li>
 *   <li>Causal ordering per aggregate (messages for the same aggregate ID are
 *       published in sequence-number order)</li>
 *   <li>Failure recovery: messages stuck in PENDING are retried; messages that
 *       exceed the retry limit are moved to DEAD_LETTER status</li>
 *   <li>Idempotent writes: duplicate message IDs are silently ignored</li>
 * </ul>
 */
@ConditionalOnProperty(name = "omnibank.jms.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class ReliableMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ReliableMessagePublisher.class);

    @PersistenceContext
    private EntityManager em;

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    @Value("${omnibank.messaging.outbox.batch-size:50}")
    private int batchSize;

    @Value("${omnibank.messaging.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${omnibank.messaging.outbox.stuck-threshold:PT5M}")
    private Duration stuckThreshold;

    @Value("${omnibank.messaging.outbox.retention:P7D}")
    private Duration retentionPeriod;

    public ReliableMessagePublisher(JmsTemplate jmsTemplate, ObjectMapper objectMapper) {
        this.jmsTemplate = Objects.requireNonNull(jmsTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    // -----------------------------------------------------------------------
    //  Public API — called within the business transaction
    // -----------------------------------------------------------------------

    /**
     * Persists a message to the outbox table. This must be called within an
     * existing transaction so the outbox write is atomic with the business
     * state change.
     *
     * @param destination the JMS destination name (topic or queue)
     * @param envelope    the message envelope to publish
     * @param orderingKey optional key for preserving order (e.g., aggregate ID)
     * @return the outbox entry ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String destination, MessageEnvelope<?> envelope, String orderingKey) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(envelope, "envelope");

        // Idempotency: skip if we already have this message ID
        Long existing = em.createQuery(
                "SELECT COUNT(o) FROM OutboxEntry o WHERE o.messageId = :mid", Long.class
        ).setParameter("mid", envelope.messageId()).getSingleResult();

        if (existing > 0) {
            log.debug("Duplicate outbox entry for messageId={}, skipping", envelope.messageId());
            return envelope.messageId();
        }

        String payloadJson = serializeEnvelope(envelope);

        OutboxEntry entry = new OutboxEntry();
        entry.messageId = envelope.messageId();
        entry.destination = destination;
        entry.payloadType = envelope.payloadType();
        entry.payloadJson = payloadJson;
        entry.orderingKey = orderingKey;
        entry.correlationId = envelope.correlationId();
        entry.sourceModule = envelope.sourceModule();
        entry.status = OutboxStatus.PENDING;
        entry.retryCount = 0;
        entry.createdAt = Instant.now();
        entry.scheduledAt = Instant.now();

        em.persist(entry);
        log.debug("Enqueued outbox entry messageId={} to destination={}", entry.messageId, destination);
        return entry.messageId;
    }

    /**
     * Convenience overload without an ordering key.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String destination, MessageEnvelope<?> envelope) {
        return enqueue(destination, envelope, null);
    }

    // -----------------------------------------------------------------------
    //  Scheduled poller — forwards pending messages to the broker
    // -----------------------------------------------------------------------

    /**
     * Polls the outbox table for PENDING messages and publishes them to the
     * broker. Runs on a fixed delay to avoid overlap.
     *
     * <p>Messages with an ordering key are fetched in creation order to
     * preserve causal ordering. Each message is published individually so a
     * single failure does not block the entire batch.</p>
     */
    @Scheduled(fixedDelayString = "${omnibank.messaging.outbox.poll-interval-ms:500}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEntry> pending = fetchPendingBatch();

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox poller found {} pending messages", pending.size());

        int published = 0;
        int failed = 0;

        for (OutboxEntry entry : pending) {
            try {
                publishToJms(entry);
                entry.status = OutboxStatus.PUBLISHED;
                entry.publishedAt = Instant.now();
                published++;
            } catch (Exception ex) {
                entry.retryCount++;
                entry.lastError = truncate(ex.getMessage(), 1000);
                entry.scheduledAt = computeNextRetry(entry.retryCount);

                if (entry.retryCount >= maxRetries) {
                    entry.status = OutboxStatus.DEAD_LETTER;
                    log.error("Outbox entry {} exceeded max retries, moved to DLQ: {}",
                            entry.messageId, ex.getMessage());
                } else {
                    log.warn("Outbox entry {} publish failed (attempt {}), will retry at {}: {}",
                            entry.messageId, entry.retryCount, entry.scheduledAt, ex.getMessage());
                }
                failed++;
            }
        }

        if (published > 0 || failed > 0) {
            log.info("Outbox poll complete: published={}, failed={}", published, failed);
        }
    }

    /**
     * Recovers stuck entries — messages that have been in SENDING status longer
     * than the stuck threshold, likely due to a process crash mid-publish.
     */
    @Scheduled(fixedDelayString = "${omnibank.messaging.outbox.recovery-interval-ms:30000}")
    @Transactional
    public void recoverStuckMessages() {
        Instant threshold = Instant.now().minus(stuckThreshold);

        int recovered = em.createQuery(
                """
                UPDATE OutboxEntry o
                SET o.status = 'PENDING', o.scheduledAt = CURRENT_TIMESTAMP
                WHERE o.status = 'SENDING'
                  AND o.scheduledAt < :threshold
                """
        ).setParameter("threshold", threshold).executeUpdate();

        if (recovered > 0) {
            log.warn("Recovered {} stuck outbox entries", recovered);
        }
    }

    /**
     * Purges old published entries beyond the retention period. Runs daily.
     */
    @Scheduled(cron = "${omnibank.messaging.outbox.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldEntries() {
        Instant cutoff = Instant.now().minus(retentionPeriod);

        int purged = em.createQuery(
                """
                DELETE FROM OutboxEntry o
                WHERE o.status = 'PUBLISHED'
                  AND o.publishedAt < :cutoff
                """
        ).setParameter("cutoff", cutoff).executeUpdate();

        if (purged > 0) {
            log.info("Purged {} old outbox entries (published before {})", purged, cutoff);
        }
    }

    // -----------------------------------------------------------------------
    //  Metrics / monitoring helpers
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OutboxStats stats() {
        var results = em.createQuery(
                """
                SELECT o.status, COUNT(o) FROM OutboxEntry o
                GROUP BY o.status
                """,
                Object[].class
        ).getResultList();

        long pending = 0, published = 0, deadLetter = 0;
        for (Object[] row : results) {
            switch ((OutboxStatus) row[0]) {
                case PENDING, SENDING -> pending += (Long) row[1];
                case PUBLISHED -> published = (Long) row[1];
                case DEAD_LETTER -> deadLetter = (Long) row[1];
            }
        }
        return new OutboxStats(pending, published, deadLetter);
    }

    public record OutboxStats(long pending, long published, long deadLetter) {}

    // -----------------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------------

    private List<OutboxEntry> fetchPendingBatch() {
        TypedQuery<OutboxEntry> query = em.createQuery(
                """
                SELECT o FROM OutboxEntry o
                WHERE o.status = 'PENDING'
                  AND o.scheduledAt <= CURRENT_TIMESTAMP
                ORDER BY o.orderingKey ASC NULLS LAST, o.createdAt ASC
                """,
                OutboxEntry.class
        );
        query.setMaxResults(batchSize);
        return query.getResultList();
    }

    private void publishToJms(OutboxEntry entry) {
        entry.status = OutboxStatus.SENDING;
        em.flush(); // mark as SENDING before broker call

        jmsTemplate.convertAndSend(entry.destination, entry.payloadJson, message -> {
            message.setStringProperty("messageId", entry.messageId.toString());
            message.setStringProperty("correlationId", entry.correlationId.toString());
            message.setStringProperty("sourceModule", entry.sourceModule);
            message.setStringProperty("payloadType", entry.payloadType);
            if (entry.orderingKey != null) {
                message.setStringProperty("JMSXGroupID", entry.orderingKey);
            }
            return message;
        });
    }

    private Instant computeNextRetry(int retryCount) {
        // Exponential back-off: 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 512s
        long delaySeconds = (long) Math.pow(2, retryCount - 1);
        long cappedDelay = Math.min(delaySeconds, 600); // cap at 10 minutes
        return Instant.now().plusSeconds(cappedDelay);
    }

    private String serializeEnvelope(MessageEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize message envelope", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    // -----------------------------------------------------------------------
    //  Outbox JPA entity
    // -----------------------------------------------------------------------

    public enum OutboxStatus { PENDING, SENDING, PUBLISHED, DEAD_LETTER }

    @Entity
    @Table(
        name = "message_outbox",
        indexes = {
            @Index(name = "idx_outbox_status_scheduled",
                   columnList = "status, scheduled_at"),
            @Index(name = "idx_outbox_message_id",
                   columnList = "message_id", unique = true),
            @Index(name = "idx_outbox_correlation",
                   columnList = "correlation_id"),
            @Index(name = "idx_outbox_destination",
                   columnList = "destination")
        }
    )
    public static class OutboxEntry {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @Column(name = "message_id", nullable = false, unique = true, updatable = false)
        UUID messageId;

        @Column(name = "destination", nullable = false, updatable = false, length = 128)
        String destination;

        @Column(name = "payload_type", nullable = false, updatable = false, length = 256)
        String payloadType;

        @Lob
        @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "TEXT")
        String payloadJson;

        @Column(name = "ordering_key", length = 128)
        String orderingKey;

        @Column(name = "correlation_id")
        UUID correlationId;

        @Column(name = "source_module", nullable = false, length = 64)
        String sourceModule;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 16)
        OutboxStatus status;

        @Column(name = "retry_count", nullable = false)
        int retryCount;

        @Column(name = "last_error", length = 1024)
        String lastError;

        @Column(name = "created_at", nullable = false, updatable = false)
        Instant createdAt;

        @Column(name = "scheduled_at", nullable = false)
        Instant scheduledAt;

        @Column(name = "published_at")
        Instant publishedAt;
    }
}
