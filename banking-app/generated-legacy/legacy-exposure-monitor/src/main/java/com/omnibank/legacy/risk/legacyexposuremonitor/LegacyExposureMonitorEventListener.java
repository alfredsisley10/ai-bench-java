package com.omnibank.legacy.risk.legacyexposuremonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event listener for the retired Legacy Exposure Monitor.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-8015 (replaced by ExposureLimitEnforcer).
 *
 * <p>Used to subscribe to the {@code legacy.risk.events} JMS
 * topic. The subscription was severed during the 2021
 * cutover; this class only counts inbound events for any
 * ad-hoc tool that still pushes onto it directly.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyExposureMonitorEventListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyExposureMonitorEventListener.class);
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @deprecated Replaced by {@code ExposureLimitEnforcer}'s event handler.
     *             Kept so that ad-hoc replay tools have a sink.
     */
    @Deprecated(since = "2021-01-01")
    public void onEvent(String eventType, String payload) {
        if (eventType == null || payload == null) {
            rejected.incrementAndGet();
            log.debug("legacy listener rejected null event");
            return;
        }
        handled.incrementAndGet();
        log.trace("legacy LegacyExposureMonitor absorbed event={} bytes={}",
                eventType, payload.length());
    }

    public long handledCount() { return handled.get(); }
    public long rejectedCount() { return rejected.get(); }
}
