package com.omnibank.shared.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing a single stored domain event in the append-only event
 * store. Each row captures exactly one event with its full serialized payload,
 * aggregate identity, and ordering metadata.
 *
 * <p>The composite index on (aggregate_type, aggregate_id, sequence_number)
 * supports efficient stream reconstruction per aggregate, while the global
 * sequence provides total ordering for cross-aggregate projections.</p>
 *
 * <p>Snapshot markers are stored in the same table with
 * {@code is_snapshot = true}. When replaying, the store first seeks the latest
 * snapshot for an aggregate and then replays only subsequent events.</p>
 */
@Entity
@Table(
    name = "event_store",
    indexes = {
        @Index(name = "idx_es_aggregate",
               columnList = "aggregate_type, aggregate_id, sequence_number"),
        @Index(name = "idx_es_event_type",
               columnList = "event_type"),
        @Index(name = "idx_es_correlation",
               columnList = "correlation_id"),
        @Index(name = "idx_es_occurred_at",
               columnList = "occurred_at"),
        @Index(name = "idx_es_global_seq",
               columnList = "global_sequence")
    }
)
public class EventStoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "global_sequence")
    private Long globalSequence;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    private String aggregateId;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "event_type", nullable = false, updatable = false, length = 256)
    private String eventType;

    @Column(name = "payload_type", nullable = false, updatable = false, length = 512)
    private String payloadType;

    @Lob
    @Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "metadata_json", updatable = false, columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "causation_id", updatable = false)
    private UUID causationId;

    @Column(name = "source_module", nullable = false, updatable = false, length = 64)
    private String sourceModule;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "stored_at", nullable = false, updatable = false)
    private Instant storedAt;

    @Column(name = "is_snapshot", nullable = false, updatable = false)
    private boolean snapshot;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Version
    @Column(name = "row_version")
    private int rowVersion;

    protected EventStoreEntity() {
        // JPA
    }

    /**
     * Constructs a fully populated event store entry. Typically called only
     * from {@link EventStore} implementations.
     */
    public EventStoreEntity(UUID eventId,
                            String aggregateType,
                            String aggregateId,
                            long sequenceNumber,
                            String eventType,
                            String payloadType,
                            String payloadJson,
                            String metadataJson,
                            UUID correlationId,
                            UUID causationId,
                            String sourceModule,
                            Instant occurredAt,
                            boolean snapshot,
                            int schemaVersion) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.sequenceNumber = sequenceNumber;
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
        this.metadataJson = metadataJson;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.sourceModule = Objects.requireNonNull(sourceModule, "sourceModule");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.storedAt = Instant.now();
        this.snapshot = snapshot;
        this.schemaVersion = schemaVersion;
    }

    // -----------------------------------------------------------------------
    //  Accessors — immutable-style (no setters; events are append-only)
    // -----------------------------------------------------------------------

    public Long globalSequence()   { return globalSequence; }
    public UUID eventId()          { return eventId; }
    public String aggregateType()  { return aggregateType; }
    public String aggregateId()    { return aggregateId; }
    public long sequenceNumber()   { return sequenceNumber; }
    public String eventType()      { return eventType; }
    public String payloadType()    { return payloadType; }
    public String payloadJson()    { return payloadJson; }
    public String metadataJson()   { return metadataJson; }
    public UUID correlationId()    { return correlationId; }
    public UUID causationId()      { return causationId; }
    public String sourceModule()   { return sourceModule; }
    public Instant occurredAt()    { return occurredAt; }
    public Instant storedAt()      { return storedAt; }
    public boolean isSnapshot()    { return snapshot; }
    public int schemaVersion()     { return schemaVersion; }

    // -----------------------------------------------------------------------
    //  Equality by event ID (business key)
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventStoreEntity that)) return false;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    @Override
    public String toString() {
        return "EventStoreEntity{" +
                "globalSequence=" + globalSequence +
                ", eventId=" + eventId +
                ", aggregateType='" + aggregateType + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", sequenceNumber=" + sequenceNumber +
                ", eventType='" + eventType + '\'' +
                ", occurredAt=" + occurredAt +
                ", snapshot=" + snapshot +
                '}';
    }
}
