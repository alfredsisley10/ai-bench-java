package com.omnibank.productvariants.overdraftprotectedchecking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Overdraft-Protected Checking product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface OverdraftProtectedCheckingLifecycleEvent permits
        OverdraftProtectedCheckingLifecycleEvent.Opened,
        OverdraftProtectedCheckingLifecycleEvent.Funded,
        OverdraftProtectedCheckingLifecycleEvent.InterestAccrued,
        OverdraftProtectedCheckingLifecycleEvent.FeeAssessed,
        OverdraftProtectedCheckingLifecycleEvent.FeeWaived,
        OverdraftProtectedCheckingLifecycleEvent.RateChanged,
        OverdraftProtectedCheckingLifecycleEvent.Matured,
        OverdraftProtectedCheckingLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements OverdraftProtectedCheckingLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements OverdraftProtectedCheckingLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements OverdraftProtectedCheckingLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements OverdraftProtectedCheckingLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements OverdraftProtectedCheckingLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements OverdraftProtectedCheckingLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements OverdraftProtectedCheckingLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements OverdraftProtectedCheckingLifecycleEvent {}
}
