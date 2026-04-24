package com.omnibank.productvariants.homedownpaymentsavings;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Home Down Payment Savings product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface HomeDownPaymentSavingsLifecycleEvent permits
        HomeDownPaymentSavingsLifecycleEvent.Opened,
        HomeDownPaymentSavingsLifecycleEvent.Funded,
        HomeDownPaymentSavingsLifecycleEvent.InterestAccrued,
        HomeDownPaymentSavingsLifecycleEvent.FeeAssessed,
        HomeDownPaymentSavingsLifecycleEvent.FeeWaived,
        HomeDownPaymentSavingsLifecycleEvent.RateChanged,
        HomeDownPaymentSavingsLifecycleEvent.Matured,
        HomeDownPaymentSavingsLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements HomeDownPaymentSavingsLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements HomeDownPaymentSavingsLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements HomeDownPaymentSavingsLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements HomeDownPaymentSavingsLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements HomeDownPaymentSavingsLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements HomeDownPaymentSavingsLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements HomeDownPaymentSavingsLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements HomeDownPaymentSavingsLifecycleEvent {}
}
