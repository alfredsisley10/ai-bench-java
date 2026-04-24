package com.omnibank.legacy.ledger.legacyfxrevaluator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event listener for the retired Legacy FX Revaluator.
 *
 * <p>// DO NOT MODIFY — retired 2022 under MIG-1124 (replaced by CurrencyRevaluationEngine).
 *
 * <p>Used to subscribe to the {@code legacy.ledger.events} JMS
 * topic. The subscription was severed during the 2022
 * cutover; this class only counts inbound events for any
 * ad-hoc tool that still pushes onto it directly.
 */
@Deprecated(since = "2022-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyFxRevaluatorEventListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyFxRevaluatorEventListener.class);
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @deprecated Replaced by {@code CurrencyRevaluationEngine}'s event handler.
     *             Kept so that ad-hoc replay tools have a sink.
     */
    @Deprecated(since = "2022-01-01")
    public void onEvent(String eventType, String payload) {
        if (eventType == null || payload == null) {
            rejected.incrementAndGet();
            log.debug("legacy listener rejected null event");
            return;
        }
        handled.incrementAndGet();
        log.trace("legacy LegacyFxRevaluator absorbed event={} bytes={}",
                eventType, payload.length());
    }

    public long handledCount() { return handled.get(); }
    public long rejectedCount() { return rejected.get(); }
}
