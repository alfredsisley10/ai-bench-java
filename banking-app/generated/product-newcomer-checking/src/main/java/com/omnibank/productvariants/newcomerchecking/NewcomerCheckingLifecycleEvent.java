package com.omnibank.productvariants.newcomerchecking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Newcomer to US Checking product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface NewcomerCheckingLifecycleEvent permits
        NewcomerCheckingLifecycleEvent.Opened,
        NewcomerCheckingLifecycleEvent.Funded,
        NewcomerCheckingLifecycleEvent.InterestAccrued,
        NewcomerCheckingLifecycleEvent.FeeAssessed,
        NewcomerCheckingLifecycleEvent.FeeWaived,
        NewcomerCheckingLifecycleEvent.RateChanged,
        NewcomerCheckingLifecycleEvent.Matured,
        NewcomerCheckingLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements NewcomerCheckingLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements NewcomerCheckingLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements NewcomerCheckingLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements NewcomerCheckingLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements NewcomerCheckingLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements NewcomerCheckingLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements NewcomerCheckingLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements NewcomerCheckingLifecycleEvent {}
}
