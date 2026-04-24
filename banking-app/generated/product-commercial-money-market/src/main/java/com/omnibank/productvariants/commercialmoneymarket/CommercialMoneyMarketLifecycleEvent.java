package com.omnibank.productvariants.commercialmoneymarket;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Commercial Money Market Sweep product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface CommercialMoneyMarketLifecycleEvent permits
        CommercialMoneyMarketLifecycleEvent.Opened,
        CommercialMoneyMarketLifecycleEvent.Funded,
        CommercialMoneyMarketLifecycleEvent.InterestAccrued,
        CommercialMoneyMarketLifecycleEvent.FeeAssessed,
        CommercialMoneyMarketLifecycleEvent.FeeWaived,
        CommercialMoneyMarketLifecycleEvent.RateChanged,
        CommercialMoneyMarketLifecycleEvent.Matured,
        CommercialMoneyMarketLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements CommercialMoneyMarketLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements CommercialMoneyMarketLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements CommercialMoneyMarketLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements CommercialMoneyMarketLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements CommercialMoneyMarketLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements CommercialMoneyMarketLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements CommercialMoneyMarketLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements CommercialMoneyMarketLifecycleEvent {}
}
