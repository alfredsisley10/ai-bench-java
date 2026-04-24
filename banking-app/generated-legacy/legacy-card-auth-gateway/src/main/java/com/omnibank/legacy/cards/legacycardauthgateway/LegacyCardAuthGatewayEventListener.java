package com.omnibank.legacy.cards.legacycardauthgateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event listener for the retired Legacy Card Auth Gateway.
 *
 * <p>// DO NOT MODIFY — retired 2018 under MIG-7001 (replaced by CardAuthorizationEngine).
 *
 * <p>Used to subscribe to the {@code legacy.cards.events} JMS
 * topic. The subscription was severed during the 2018
 * cutover; this class only counts inbound events for any
 * ad-hoc tool that still pushes onto it directly.
 */
@Deprecated(since = "2018-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyCardAuthGatewayEventListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyCardAuthGatewayEventListener.class);
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @deprecated Replaced by {@code CardAuthorizationEngine}'s event handler.
     *             Kept so that ad-hoc replay tools have a sink.
     */
    @Deprecated(since = "2018-01-01")
    public void onEvent(String eventType, String payload) {
        if (eventType == null || payload == null) {
            rejected.incrementAndGet();
            log.debug("legacy listener rejected null event");
            return;
        }
        handled.incrementAndGet();
        log.trace("legacy LegacyCardAuthGateway absorbed event={} bytes={}",
                eventType, payload.length());
    }

    public long handledCount() { return handled.get(); }
    public long rejectedCount() { return rejected.get(); }
}
