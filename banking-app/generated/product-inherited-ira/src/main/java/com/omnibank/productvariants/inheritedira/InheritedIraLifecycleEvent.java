package com.omnibank.productvariants.inheritedira;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Inherited IRA product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface InheritedIraLifecycleEvent permits
        InheritedIraLifecycleEvent.Opened,
        InheritedIraLifecycleEvent.Funded,
        InheritedIraLifecycleEvent.InterestAccrued,
        InheritedIraLifecycleEvent.FeeAssessed,
        InheritedIraLifecycleEvent.FeeWaived,
        InheritedIraLifecycleEvent.RateChanged,
        InheritedIraLifecycleEvent.Matured,
        InheritedIraLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements InheritedIraLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements InheritedIraLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements InheritedIraLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements InheritedIraLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements InheritedIraLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements InheritedIraLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements InheritedIraLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements InheritedIraLifecycleEvent {}
}
