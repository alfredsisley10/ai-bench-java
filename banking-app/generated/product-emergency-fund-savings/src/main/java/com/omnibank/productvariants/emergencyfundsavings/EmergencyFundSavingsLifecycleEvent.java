package com.omnibank.productvariants.emergencyfundsavings;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Emergency Fund Savings product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface EmergencyFundSavingsLifecycleEvent permits
        EmergencyFundSavingsLifecycleEvent.Opened,
        EmergencyFundSavingsLifecycleEvent.Funded,
        EmergencyFundSavingsLifecycleEvent.InterestAccrued,
        EmergencyFundSavingsLifecycleEvent.FeeAssessed,
        EmergencyFundSavingsLifecycleEvent.FeeWaived,
        EmergencyFundSavingsLifecycleEvent.RateChanged,
        EmergencyFundSavingsLifecycleEvent.Matured,
        EmergencyFundSavingsLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements EmergencyFundSavingsLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements EmergencyFundSavingsLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements EmergencyFundSavingsLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements EmergencyFundSavingsLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements EmergencyFundSavingsLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements EmergencyFundSavingsLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements EmergencyFundSavingsLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements EmergencyFundSavingsLifecycleEvent {}
}
