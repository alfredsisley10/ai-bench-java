package com.omnibank.productvariants.rothira;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Roth IRA product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface RothIraLifecycleEvent permits
        RothIraLifecycleEvent.Opened,
        RothIraLifecycleEvent.Funded,
        RothIraLifecycleEvent.InterestAccrued,
        RothIraLifecycleEvent.FeeAssessed,
        RothIraLifecycleEvent.FeeWaived,
        RothIraLifecycleEvent.RateChanged,
        RothIraLifecycleEvent.Matured,
        RothIraLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements RothIraLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements RothIraLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements RothIraLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements RothIraLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements RothIraLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements RothIraLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements RothIraLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements RothIraLifecycleEvent {}
}
