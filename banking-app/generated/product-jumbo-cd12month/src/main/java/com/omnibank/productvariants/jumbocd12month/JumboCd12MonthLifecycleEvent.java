package com.omnibank.productvariants.jumbocd12month;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Jumbo 12 Month CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface JumboCd12MonthLifecycleEvent permits
        JumboCd12MonthLifecycleEvent.Opened,
        JumboCd12MonthLifecycleEvent.Funded,
        JumboCd12MonthLifecycleEvent.InterestAccrued,
        JumboCd12MonthLifecycleEvent.FeeAssessed,
        JumboCd12MonthLifecycleEvent.FeeWaived,
        JumboCd12MonthLifecycleEvent.RateChanged,
        JumboCd12MonthLifecycleEvent.Matured,
        JumboCd12MonthLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements JumboCd12MonthLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements JumboCd12MonthLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements JumboCd12MonthLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements JumboCd12MonthLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements JumboCd12MonthLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements JumboCd12MonthLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements JumboCd12MonthLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements JumboCd12MonthLifecycleEvent {}
}
