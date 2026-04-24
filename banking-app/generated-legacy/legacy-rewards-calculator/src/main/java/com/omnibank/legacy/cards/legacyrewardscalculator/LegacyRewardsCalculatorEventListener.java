package com.omnibank.legacy.cards.legacyrewardscalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event listener for the retired Legacy Rewards Calculator.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-7022 (replaced by CardRewardsCalculator).
 *
 * <p>Used to subscribe to the {@code legacy.cards.events} JMS
 * topic. The subscription was severed during the 2021
 * cutover; this class only counts inbound events for any
 * ad-hoc tool that still pushes onto it directly.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyRewardsCalculatorEventListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyRewardsCalculatorEventListener.class);
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @deprecated Replaced by {@code CardRewardsCalculator}'s event handler.
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
        log.trace("legacy LegacyRewardsCalculator absorbed event={} bytes={}",
                eventType, payload.length());
    }

    public long handledCount() { return handled.get(); }
    public long rejectedCount() { return rejected.get(); }
}
