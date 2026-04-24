package com.omnibank.productvariants.escrowmoneymarket;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Escrow Money Market product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface EscrowMoneyMarketLifecycleEvent permits
        EscrowMoneyMarketLifecycleEvent.Opened,
        EscrowMoneyMarketLifecycleEvent.Funded,
        EscrowMoneyMarketLifecycleEvent.InterestAccrued,
        EscrowMoneyMarketLifecycleEvent.FeeAssessed,
        EscrowMoneyMarketLifecycleEvent.FeeWaived,
        EscrowMoneyMarketLifecycleEvent.RateChanged,
        EscrowMoneyMarketLifecycleEvent.Matured,
        EscrowMoneyMarketLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements EscrowMoneyMarketLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements EscrowMoneyMarketLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements EscrowMoneyMarketLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements EscrowMoneyMarketLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements EscrowMoneyMarketLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements EscrowMoneyMarketLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements EscrowMoneyMarketLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements EscrowMoneyMarketLifecycleEvent {}
}
