package com.omnibank.productvariants.nopenaltycd11month;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the No-Penalty 11 Month CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface NoPenaltyCd11MonthLifecycleEvent permits
        NoPenaltyCd11MonthLifecycleEvent.Opened,
        NoPenaltyCd11MonthLifecycleEvent.Funded,
        NoPenaltyCd11MonthLifecycleEvent.InterestAccrued,
        NoPenaltyCd11MonthLifecycleEvent.FeeAssessed,
        NoPenaltyCd11MonthLifecycleEvent.FeeWaived,
        NoPenaltyCd11MonthLifecycleEvent.RateChanged,
        NoPenaltyCd11MonthLifecycleEvent.Matured,
        NoPenaltyCd11MonthLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements NoPenaltyCd11MonthLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements NoPenaltyCd11MonthLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements NoPenaltyCd11MonthLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements NoPenaltyCd11MonthLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements NoPenaltyCd11MonthLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements NoPenaltyCd11MonthLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements NoPenaltyCd11MonthLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements NoPenaltyCd11MonthLifecycleEvent {}
}
