package com.omnibank.productvariants.standardsavings;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Standard Savings product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface StandardSavingsLifecycleEvent permits
        StandardSavingsLifecycleEvent.Opened,
        StandardSavingsLifecycleEvent.Funded,
        StandardSavingsLifecycleEvent.InterestAccrued,
        StandardSavingsLifecycleEvent.FeeAssessed,
        StandardSavingsLifecycleEvent.FeeWaived,
        StandardSavingsLifecycleEvent.RateChanged,
        StandardSavingsLifecycleEvent.Matured,
        StandardSavingsLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements StandardSavingsLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements StandardSavingsLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements StandardSavingsLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements StandardSavingsLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements StandardSavingsLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements StandardSavingsLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements StandardSavingsLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements StandardSavingsLifecycleEvent {}
}
