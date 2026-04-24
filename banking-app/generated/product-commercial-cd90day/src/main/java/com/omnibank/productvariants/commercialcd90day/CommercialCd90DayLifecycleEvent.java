package com.omnibank.productvariants.commercialcd90day;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Commercial 90-Day CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface CommercialCd90DayLifecycleEvent permits
        CommercialCd90DayLifecycleEvent.Opened,
        CommercialCd90DayLifecycleEvent.Funded,
        CommercialCd90DayLifecycleEvent.InterestAccrued,
        CommercialCd90DayLifecycleEvent.FeeAssessed,
        CommercialCd90DayLifecycleEvent.FeeWaived,
        CommercialCd90DayLifecycleEvent.RateChanged,
        CommercialCd90DayLifecycleEvent.Matured,
        CommercialCd90DayLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements CommercialCd90DayLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements CommercialCd90DayLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements CommercialCd90DayLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements CommercialCd90DayLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements CommercialCd90DayLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements CommercialCd90DayLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements CommercialCd90DayLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements CommercialCd90DayLifecycleEvent {}
}
