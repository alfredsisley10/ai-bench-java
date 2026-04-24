package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.messaging.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record BalanceChangedEvent(
        UUID eventId,
        Instant occurredAt,
        AccountNumber account,
        Money priorLedger,
        Money newLedger,
        String reason
) implements DomainEvent {

    @Override
    public String eventType() {
        return "accounts.consumer.balance_changed";
    }
}
