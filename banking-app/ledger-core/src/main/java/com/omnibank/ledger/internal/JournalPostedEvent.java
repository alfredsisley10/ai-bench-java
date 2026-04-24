package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.shared.messaging.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record JournalPostedEvent(PostedJournal journal) implements DomainEvent {

    @Override
    public UUID eventId() {
        return journal.proposalId();
    }

    @Override
    public Instant occurredAt() {
        return journal.postedAt();
    }

    @Override
    public String eventType() {
        return "ledger.posted";
    }
}
