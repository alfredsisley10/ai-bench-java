package com.omnibank.productvariants.simpleira;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the SIMPLE IRA product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface SimpleIraLifecycleEvent permits
        SimpleIraLifecycleEvent.Opened,
        SimpleIraLifecycleEvent.Funded,
        SimpleIraLifecycleEvent.InterestAccrued,
        SimpleIraLifecycleEvent.FeeAssessed,
        SimpleIraLifecycleEvent.FeeWaived,
        SimpleIraLifecycleEvent.RateChanged,
        SimpleIraLifecycleEvent.Matured,
        SimpleIraLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements SimpleIraLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements SimpleIraLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements SimpleIraLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements SimpleIraLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements SimpleIraLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements SimpleIraLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements SimpleIraLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements SimpleIraLifecycleEvent {}
}
