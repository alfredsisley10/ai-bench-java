package com.omnibank.legacy.fraud.legacychargebackprocessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event listener for the retired Legacy Chargeback Processor.
 *
 * <p>// DO NOT MODIFY — retired 2020 under MIG-4015 (replaced by CardDisputeManager).
 *
 * <p>Used to subscribe to the {@code legacy.fraud.events} JMS
 * topic. The subscription was severed during the 2020
 * cutover; this class only counts inbound events for any
 * ad-hoc tool that still pushes onto it directly.
 */
@Deprecated(since = "2020-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyChargebackProcessorEventListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyChargebackProcessorEventListener.class);
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @deprecated Replaced by {@code CardDisputeManager}'s event handler.
     *             Kept so that ad-hoc replay tools have a sink.
     */
    @Deprecated(since = "2020-01-01")
    public void onEvent(String eventType, String payload) {
        if (eventType == null || payload == null) {
            rejected.incrementAndGet();
            log.debug("legacy listener rejected null event");
            return;
        }
        handled.incrementAndGet();
        log.trace("legacy LegacyChargebackProcessor absorbed event={} bytes={}",
                eventType, payload.length());
    }

    public long handledCount() { return handled.get(); }
    public long rejectedCount() { return rejected.get(); }
}
