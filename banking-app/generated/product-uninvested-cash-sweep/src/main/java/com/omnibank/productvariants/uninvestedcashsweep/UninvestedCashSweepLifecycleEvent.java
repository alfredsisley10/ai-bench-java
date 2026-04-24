package com.omnibank.productvariants.uninvestedcashsweep;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Uninvested Cash Sweep product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface UninvestedCashSweepLifecycleEvent permits
        UninvestedCashSweepLifecycleEvent.Opened,
        UninvestedCashSweepLifecycleEvent.Funded,
        UninvestedCashSweepLifecycleEvent.InterestAccrued,
        UninvestedCashSweepLifecycleEvent.FeeAssessed,
        UninvestedCashSweepLifecycleEvent.FeeWaived,
        UninvestedCashSweepLifecycleEvent.RateChanged,
        UninvestedCashSweepLifecycleEvent.Matured,
        UninvestedCashSweepLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements UninvestedCashSweepLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements UninvestedCashSweepLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements UninvestedCashSweepLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements UninvestedCashSweepLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements UninvestedCashSweepLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements UninvestedCashSweepLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements UninvestedCashSweepLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements UninvestedCashSweepLifecycleEvent {}
}
