package com.omnibank.shared.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Sealed message envelope hierarchy that wraps every message flowing through the
 * OmniBank messaging infrastructure. Three message intents exist: domain events
 * (something happened), commands (do something), and queries (ask something).
 *
 * <p>All variants carry a correlation ID for distributed tracing, a causation ID
 * linking to the message that caused this one, a source module tag, a payload type
 * discriminator, and arbitrary headers for cross-cutting concerns (tenant, auth
 * context, priority, etc.).</p>
 *
 * <p>Java 21 sealed types guarantee exhaustive switch coverage at compile time,
 * making it impossible to introduce a new intent without updating every handler.</p>
 */
public sealed interface MessageEnvelope<T> {

    UUID messageId();

    UUID correlationId();

    UUID causationId();

    Instant timestamp();

    String sourceModule();

    String payloadType();

    T payload();

    Map<String, String> headers();

    // -----------------------------------------------------------------------
    //  Intent 1 — Domain Event: something that already happened
    // -----------------------------------------------------------------------

    record DomainEventEnvelope<T>(
            UUID messageId,
            UUID correlationId,
            UUID causationId,
            Instant timestamp,
            String sourceModule,
            String payloadType,
            T payload,
            Map<String, String> headers,
            String aggregateType,
            String aggregateId,
            long sequenceNumber
    ) implements MessageEnvelope<T> {

        public DomainEventEnvelope {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(payloadType, "payloadType");
            Objects.requireNonNull(payload, "payload");
            headers = headers != null ? Map.copyOf(headers) : Map.of();
            if (sequenceNumber < 0) {
                throw new IllegalArgumentException("sequenceNumber must be non-negative");
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Intent 2 — Command: a request to perform an action
    // -----------------------------------------------------------------------

    record CommandEnvelope<T>(
            UUID messageId,
            UUID correlationId,
            UUID causationId,
            Instant timestamp,
            String sourceModule,
            String payloadType,
            T payload,
            Map<String, String> headers,
            String targetModule,
            Instant replyBy,
            int priority
    ) implements MessageEnvelope<T> {

        public static final int PRIORITY_LOW = 1;
        public static final int PRIORITY_NORMAL = 5;
        public static final int PRIORITY_HIGH = 9;
        public static final int PRIORITY_CRITICAL = 10;

        public CommandEnvelope {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(payloadType, "payloadType");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(targetModule, "targetModule");
            headers = headers != null ? Map.copyOf(headers) : Map.of();
            if (priority < 1 || priority > 10) {
                throw new IllegalArgumentException("priority must be 1-10, got " + priority);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Intent 3 — Query: a request for information with expected response
    // -----------------------------------------------------------------------

    record QueryEnvelope<T>(
            UUID messageId,
            UUID correlationId,
            UUID causationId,
            Instant timestamp,
            String sourceModule,
            String payloadType,
            T payload,
            Map<String, String> headers,
            String replyToDestination,
            Instant timeoutAt,
            Class<?> expectedResponseType
    ) implements MessageEnvelope<T> {

        public QueryEnvelope {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(payloadType, "payloadType");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(replyToDestination, "replyToDestination");
            Objects.requireNonNull(expectedResponseType, "expectedResponseType");
            headers = headers != null ? Map.copyOf(headers) : Map.of();
        }
    }

    // -----------------------------------------------------------------------
    //  Builder for fluent construction
    // -----------------------------------------------------------------------

    static <T> EnvelopeBuilder<T> builder(String sourceModule, T payload) {
        return new EnvelopeBuilder<>(sourceModule, payload);
    }

    final class EnvelopeBuilder<T> {

        private final String sourceModule;
        private final T payload;

        private UUID messageId = UUID.randomUUID();
        private UUID correlationId = UUID.randomUUID();
        private UUID causationId;
        private Instant timestamp = Instant.now();
        private String payloadType;
        private Map<String, String> headers = Map.of();

        // domain-event specific
        private String aggregateType;
        private String aggregateId;
        private long sequenceNumber;

        // command specific
        private String targetModule;
        private Instant replyBy;
        private int priority = CommandEnvelope.PRIORITY_NORMAL;

        // query specific
        private String replyToDestination;
        private Instant timeoutAt;
        private Class<?> expectedResponseType;

        EnvelopeBuilder(String sourceModule, T payload) {
            this.sourceModule = Objects.requireNonNull(sourceModule);
            this.payload = Objects.requireNonNull(payload);
            this.payloadType = payload.getClass().getSimpleName();
            this.causationId = this.messageId;
        }

        public EnvelopeBuilder<T> messageId(UUID id) { this.messageId = id; return this; }
        public EnvelopeBuilder<T> correlationId(UUID id) { this.correlationId = id; return this; }
        public EnvelopeBuilder<T> causationId(UUID id) { this.causationId = id; return this; }
        public EnvelopeBuilder<T> timestamp(Instant ts) { this.timestamp = ts; return this; }
        public EnvelopeBuilder<T> payloadType(String pt) { this.payloadType = pt; return this; }
        public EnvelopeBuilder<T> headers(Map<String, String> h) { this.headers = h; return this; }
        public EnvelopeBuilder<T> aggregateType(String at) { this.aggregateType = at; return this; }
        public EnvelopeBuilder<T> aggregateId(String aid) { this.aggregateId = aid; return this; }
        public EnvelopeBuilder<T> sequenceNumber(long sn) { this.sequenceNumber = sn; return this; }
        public EnvelopeBuilder<T> targetModule(String tm) { this.targetModule = tm; return this; }
        public EnvelopeBuilder<T> replyBy(Instant rb) { this.replyBy = rb; return this; }
        public EnvelopeBuilder<T> priority(int p) { this.priority = p; return this; }
        public EnvelopeBuilder<T> replyToDestination(String d) { this.replyToDestination = d; return this; }
        public EnvelopeBuilder<T> timeoutAt(Instant t) { this.timeoutAt = t; return this; }
        public EnvelopeBuilder<T> expectedResponseType(Class<?> c) { this.expectedResponseType = c; return this; }

        public DomainEventEnvelope<T> asDomainEvent() {
            return new DomainEventEnvelope<>(
                    messageId, correlationId, causationId, timestamp,
                    sourceModule, payloadType, payload, headers,
                    aggregateType, aggregateId, sequenceNumber
            );
        }

        public CommandEnvelope<T> asCommand() {
            return new CommandEnvelope<>(
                    messageId, correlationId, causationId, timestamp,
                    sourceModule, payloadType, payload, headers,
                    targetModule, replyBy, priority
            );
        }

        public QueryEnvelope<T> asQuery() {
            return new QueryEnvelope<>(
                    messageId, correlationId, causationId, timestamp,
                    sourceModule, payloadType, payload, headers,
                    replyToDestination, timeoutAt, expectedResponseType
            );
        }
    }
}
