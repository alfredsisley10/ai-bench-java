package com.omnibank.productvariants.spanishlanguagechecking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain events emitted across the Spanish-Language Basic Checking product lifecycle.
 * Downstream modules subscribe to these for audit, statements,
 * notifications, and analytics.
 */
public sealed interface SpanishLanguageCheckingLifecycleEvent permits
        SpanishLanguageCheckingLifecycleEvent.Opened,
        SpanishLanguageCheckingLifecycleEvent.Funded,
        SpanishLanguageCheckingLifecycleEvent.InterestAccrued,
        SpanishLanguageCheckingLifecycleEvent.FeeAssessed,
        SpanishLanguageCheckingLifecycleEvent.FeeWaived,
        SpanishLanguageCheckingLifecycleEvent.RateChanged,
        SpanishLanguageCheckingLifecycleEvent.Matured,
        SpanishLanguageCheckingLifecycleEvent.Closed {

    UUID eventId();
    Instant occurredAt();
    UUID productId();

    record Opened(UUID eventId, Instant occurredAt, UUID productId,
                   String customerReference, String channelId)
            implements SpanishLanguageCheckingLifecycleEvent {
        public Opened {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(occurredAt);
        }
    }

    record Funded(UUID eventId, Instant occurredAt, UUID productId,
                   java.math.BigDecimal amount, String source)
            implements SpanishLanguageCheckingLifecycleEvent {}

    record InterestAccrued(UUID eventId, Instant occurredAt, UUID productId,
                             java.math.BigDecimal dailyAmount,
                             java.math.BigDecimal runningTotal)
            implements SpanishLanguageCheckingLifecycleEvent {}

    record FeeAssessed(UUID eventId, Instant occurredAt, UUID productId,
                        String feeCode, java.math.BigDecimal amount)
            implements SpanishLanguageCheckingLifecycleEvent {}

    record FeeWaived(UUID eventId, Instant occurredAt, UUID productId,
                      String feeCode, String reason)
            implements SpanishLanguageCheckingLifecycleEvent {}

    record RateChanged(UUID eventId, Instant occurredAt, UUID productId,
                        java.math.BigDecimal previousRate,
                        java.math.BigDecimal newRate, String reason)
            implements SpanishLanguageCheckingLifecycleEvent {}

    record Matured(UUID eventId, Instant occurredAt, UUID productId,
                    String dispositionAction, java.math.BigDecimal finalBalance)
            implements SpanishLanguageCheckingLifecycleEvent {}

    record Closed(UUID eventId, Instant occurredAt, UUID productId,
                   String reason, String closedBy)
            implements SpanishLanguageCheckingLifecycleEvent {}
}
