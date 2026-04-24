package com.omnibank.risk.internal;

import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;

import java.util.ArrayList;
import java.util.List;

/** In-memory event bus for tests — records what was published, nothing else. */
final class RecordingEventBus implements EventBus {
    final List<DomainEvent> events = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
        events.add(event);
    }

    void clear() { events.clear(); }
}
