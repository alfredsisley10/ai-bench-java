package com.omnibank.productvariants.cdladder36month;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the 36 Month CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface CdLadder36MonthLifecycleEvent permits
        CdLadder36MonthLifecycleEvent.Opened,
        CdLadder36MonthLifecycleEvent.Funded,
        CdLadder36MonthLifecycleEvent.InterestAccrued,
        CdLadder36MonthLifecycleEvent.FeeAssessed,
        CdLadder36MonthLifecycleEvent.FeeWaived,
        CdLadder36MonthLifecycleEvent.RateChanged,
        CdLadder36MonthLifecycleEvent.Matured,
        CdLadder36MonthLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements CdLadder36MonthLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements CdLadder36MonthLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements CdLadder36MonthLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements CdLadder36MonthLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements CdLadder36MonthLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements CdLadder36MonthLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements CdLadder36MonthLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements CdLadder36MonthLifecycleEvent {}
}
