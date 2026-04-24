package com.omnibank.productvariants.indexedcd24month;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Market-Indexed 24 Month CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface IndexedCd24MonthLifecycleEvent permits
        IndexedCd24MonthLifecycleEvent.Opened,
        IndexedCd24MonthLifecycleEvent.Funded,
        IndexedCd24MonthLifecycleEvent.InterestAccrued,
        IndexedCd24MonthLifecycleEvent.FeeAssessed,
        IndexedCd24MonthLifecycleEvent.FeeWaived,
        IndexedCd24MonthLifecycleEvent.RateChanged,
        IndexedCd24MonthLifecycleEvent.Matured,
        IndexedCd24MonthLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements IndexedCd24MonthLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements IndexedCd24MonthLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements IndexedCd24MonthLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements IndexedCd24MonthLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements IndexedCd24MonthLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements IndexedCd24MonthLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements IndexedCd24MonthLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements IndexedCd24MonthLifecycleEvent {}
}
