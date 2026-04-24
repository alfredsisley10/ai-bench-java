package com.omnibank.shared.messaging;

/**
 * Internal pub/sub facade. Implementation wraps Spring's ApplicationEventPublisher
 * today; the interface exists so we can swap in Kafka or another broker without
 * touching callers.
 */
public interface EventBus {

    void publish(DomainEvent event);
}
