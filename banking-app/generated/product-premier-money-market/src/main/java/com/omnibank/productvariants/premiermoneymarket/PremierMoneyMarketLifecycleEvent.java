package com.omnibank.productvariants.premiermoneymarket;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Premier Money Market product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface PremierMoneyMarketLifecycleEvent permits
        PremierMoneyMarketLifecycleEvent.Opened,
        PremierMoneyMarketLifecycleEvent.Funded,
        PremierMoneyMarketLifecycleEvent.InterestAccrued,
        PremierMoneyMarketLifecycleEvent.FeeAssessed,
        PremierMoneyMarketLifecycleEvent.FeeWaived,
        PremierMoneyMarketLifecycleEvent.RateChanged,
        PremierMoneyMarketLifecycleEvent.Matured,
        PremierMoneyMarketLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements PremierMoneyMarketLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements PremierMoneyMarketLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements PremierMoneyMarketLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements PremierMoneyMarketLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements PremierMoneyMarketLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements PremierMoneyMarketLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements PremierMoneyMarketLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements PremierMoneyMarketLifecycleEvent {}
}
