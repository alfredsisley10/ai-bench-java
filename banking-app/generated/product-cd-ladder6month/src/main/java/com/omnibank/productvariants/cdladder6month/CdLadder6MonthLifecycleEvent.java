package com.omnibank.productvariants.cdladder6month;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the 6 Month CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface CdLadder6MonthLifecycleEvent permits
        CdLadder6MonthLifecycleEvent.Opened,
        CdLadder6MonthLifecycleEvent.Funded,
        CdLadder6MonthLifecycleEvent.InterestAccrued,
        CdLadder6MonthLifecycleEvent.FeeAssessed,
        CdLadder6MonthLifecycleEvent.FeeWaived,
        CdLadder6MonthLifecycleEvent.RateChanged,
        CdLadder6MonthLifecycleEvent.Matured,
        CdLadder6MonthLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements CdLadder6MonthLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements CdLadder6MonthLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements CdLadder6MonthLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements CdLadder6MonthLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements CdLadder6MonthLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements CdLadder6MonthLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements CdLadder6MonthLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements CdLadder6MonthLifecycleEvent {}
}
