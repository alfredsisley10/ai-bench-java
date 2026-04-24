package com.omnibank.shared.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker for events flowing across the internal bus. Every event carries its
 * own id + timestamp + a type key for routing and observability.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    /**
     * Short dotted type key — {@code "ledger.posted"}, {@code "payment.ach.submitted"}.
     * Stable across versions; routing policy indexes on this.
     */
    String eventType();
}
