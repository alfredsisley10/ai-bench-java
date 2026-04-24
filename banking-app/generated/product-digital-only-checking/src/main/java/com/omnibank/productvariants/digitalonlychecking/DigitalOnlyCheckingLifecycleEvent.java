package com.omnibank.productvariants.digitalonlychecking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Digital-Only Checking product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface DigitalOnlyCheckingLifecycleEvent permits
        DigitalOnlyCheckingLifecycleEvent.Opened,
        DigitalOnlyCheckingLifecycleEvent.Funded,
        DigitalOnlyCheckingLifecycleEvent.InterestAccrued,
        DigitalOnlyCheckingLifecycleEvent.FeeAssessed,
        DigitalOnlyCheckingLifecycleEvent.FeeWaived,
        DigitalOnlyCheckingLifecycleEvent.RateChanged,
        DigitalOnlyCheckingLifecycleEvent.Matured,
        DigitalOnlyCheckingLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements DigitalOnlyCheckingLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements DigitalOnlyCheckingLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements DigitalOnlyCheckingLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements DigitalOnlyCheckingLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements DigitalOnlyCheckingLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements DigitalOnlyCheckingLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements DigitalOnlyCheckingLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements DigitalOnlyCheckingLifecycleEvent {}
}
