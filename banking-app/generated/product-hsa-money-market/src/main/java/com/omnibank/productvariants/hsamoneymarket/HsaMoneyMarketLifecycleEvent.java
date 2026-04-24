package com.omnibank.productvariants.hsamoneymarket;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the HSA Money Market product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface HsaMoneyMarketLifecycleEvent permits
        HsaMoneyMarketLifecycleEvent.Opened,
        HsaMoneyMarketLifecycleEvent.Funded,
        HsaMoneyMarketLifecycleEvent.InterestAccrued,
        HsaMoneyMarketLifecycleEvent.FeeAssessed,
        HsaMoneyMarketLifecycleEvent.FeeWaived,
        HsaMoneyMarketLifecycleEvent.RateChanged,
        HsaMoneyMarketLifecycleEvent.Matured,
        HsaMoneyMarketLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements HsaMoneyMarketLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements HsaMoneyMarketLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements HsaMoneyMarketLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements HsaMoneyMarketLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements HsaMoneyMarketLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements HsaMoneyMarketLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements HsaMoneyMarketLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements HsaMoneyMarketLifecycleEvent {}
}
