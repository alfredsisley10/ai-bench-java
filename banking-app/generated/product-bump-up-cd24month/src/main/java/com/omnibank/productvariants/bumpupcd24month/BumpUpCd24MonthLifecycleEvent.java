package com.omnibank.productvariants.bumpupcd24month;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Bump-Up 24 Month CD product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface BumpUpCd24MonthLifecycleEvent permits
        BumpUpCd24MonthLifecycleEvent.Opened,
        BumpUpCd24MonthLifecycleEvent.Funded,
        BumpUpCd24MonthLifecycleEvent.InterestAccrued,
        BumpUpCd24MonthLifecycleEvent.FeeAssessed,
        BumpUpCd24MonthLifecycleEvent.FeeWaived,
        BumpUpCd24MonthLifecycleEvent.RateChanged,
        BumpUpCd24MonthLifecycleEvent.Matured,
        BumpUpCd24MonthLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements BumpUpCd24MonthLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements BumpUpCd24MonthLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements BumpUpCd24MonthLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements BumpUpCd24MonthLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements BumpUpCd24MonthLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements BumpUpCd24MonthLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements BumpUpCd24MonthLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements BumpUpCd24MonthLifecycleEvent {}
}
