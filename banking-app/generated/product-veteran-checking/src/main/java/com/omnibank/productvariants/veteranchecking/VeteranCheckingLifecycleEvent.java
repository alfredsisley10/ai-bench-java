package com.omnibank.productvariants.veteranchecking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Veteran Rewards Checking product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface VeteranCheckingLifecycleEvent permits
        VeteranCheckingLifecycleEvent.Opened,
        VeteranCheckingLifecycleEvent.Funded,
        VeteranCheckingLifecycleEvent.InterestAccrued,
        VeteranCheckingLifecycleEvent.FeeAssessed,
        VeteranCheckingLifecycleEvent.FeeWaived,
        VeteranCheckingLifecycleEvent.RateChanged,
        VeteranCheckingLifecycleEvent.Matured,
        VeteranCheckingLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements VeteranCheckingLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements VeteranCheckingLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements VeteranCheckingLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements VeteranCheckingLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements VeteranCheckingLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements VeteranCheckingLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements VeteranCheckingLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements VeteranCheckingLifecycleEvent {}
}
