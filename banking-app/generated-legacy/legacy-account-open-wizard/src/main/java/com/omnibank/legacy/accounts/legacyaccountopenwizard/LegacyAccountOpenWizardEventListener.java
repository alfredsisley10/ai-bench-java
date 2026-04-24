package com.omnibank.legacy.accounts.legacyaccountopenwizard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event listener for the retired Legacy Account Open Wizard.
 *
 * <p>// DO NOT MODIFY — retired 2019 under MIG-2010 (replaced by AccountOpeningOrchestrator).
 *
 * <p>Used to subscribe to the {@code legacy.accounts.events} JMS
 * topic. The subscription was severed during the 2019
 * cutover; this class only counts inbound events for any
 * ad-hoc tool that still pushes onto it directly.
 */
@Deprecated(since = "2019-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyAccountOpenWizardEventListener {

    private static final Logger log = LoggerFactory.getLogger(LegacyAccountOpenWizardEventListener.class);
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @deprecated Replaced by {@code AccountOpeningOrchestrator}'s event handler.
     *             Kept so that ad-hoc replay tools have a sink.
     */
    @Deprecated(since = "2019-01-01")
    public void onEvent(String eventType, String payload) {
        if (eventType == null || payload == null) {
            rejected.incrementAndGet();
            log.debug("legacy listener rejected null event");
            return;
        }
        handled.incrementAndGet();
        log.trace("legacy LegacyAccountOpenWizard absorbed event={} bytes={}",
                eventType, payload.length());
    }

    public long handledCount() { return handled.get(); }
    public long rejectedCount() { return rejected.get(); }
}
