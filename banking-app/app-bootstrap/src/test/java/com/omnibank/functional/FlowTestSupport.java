package com.omnibank.functional;

import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for the {@code com.omnibank.functional.*} cross-module flow
 * tests. These tests deliberately compose many stateless engines from many
 * banking-app modules so the resulting AppMap traces traverse a wide call
 * graph — the wider the graph, the harder it is for downstream automation to
 * reverse-engineer the trace into an architectural understanding.
 *
 * <p>Two facades are provided:
 * <ul>
 *   <li>{@link RecordingEventPublisher} — a Spring
 *       {@link ApplicationEventPublisher} that captures every event in order;
 *       used by services that take the publisher directly
 *       (e.g. {@code PaymentLifecycleManager}).</li>
 *   <li>{@link RecordingEventBus} — the {@code EventBus} facade used by every
 *       module that publishes through {@code shared-messaging}.</li>
 * </ul>
 *
 * <p>Both implementations are intentionally simple: append-to-list, no async,
 * no failure injection. Tests assert on the captured streams.
 */
final class FlowTestSupport {

    private FlowTestSupport() {}

    static final class RecordingEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override public void publishEvent(Object event)            { events.add(event); }
        @Override public void publishEvent(ApplicationEvent event)  { events.add(event); }
    }

    static final class RecordingEventBus implements EventBus {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }

        @SuppressWarnings("unchecked")
        <T extends DomainEvent> List<T> ofType(Class<T> type) {
            var out = new ArrayList<T>();
            for (var e : events) if (type.isInstance(e)) out.add((T) e);
            return out;
        }
    }
}
