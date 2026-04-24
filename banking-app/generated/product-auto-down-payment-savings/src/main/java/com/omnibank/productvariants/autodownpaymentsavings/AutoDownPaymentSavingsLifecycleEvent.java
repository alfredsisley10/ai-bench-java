package com.omnibank.productvariants.autodownpaymentsavings;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Auto Down Payment Savings product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface AutoDownPaymentSavingsLifecycleEvent permits
        AutoDownPaymentSavingsLifecycleEvent.Opened,
        AutoDownPaymentSavingsLifecycleEvent.Funded,
        AutoDownPaymentSavingsLifecycleEvent.InterestAccrued,
        AutoDownPaymentSavingsLifecycleEvent.FeeAssessed,
        AutoDownPaymentSavingsLifecycleEvent.FeeWaived,
        AutoDownPaymentSavingsLifecycleEvent.RateChanged,
        AutoDownPaymentSavingsLifecycleEvent.Matured,
        AutoDownPaymentSavingsLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements AutoDownPaymentSavingsLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements AutoDownPaymentSavingsLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements AutoDownPaymentSavingsLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements AutoDownPaymentSavingsLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements AutoDownPaymentSavingsLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements AutoDownPaymentSavingsLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements AutoDownPaymentSavingsLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements AutoDownPaymentSavingsLifecycleEvent {}
}
