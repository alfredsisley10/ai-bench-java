package com.omnibank.channel.rewardschecking.kiosk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Channel-scoped telemetry tracker for RewardsChecking on In-Branch Kiosk.
 * Captures screen transitions, latency, and abandonments for
 * the analytics pipeline.
 */
public final class RewardsCheckingKioskEventTracker {

    public record Event(String name, Instant at, Map<String, String> attributes) {}

    private final List<Event> events = new ArrayList<>();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void track(String name) {
        track(name, Map.of());
    }

    public void track(String name, Map<String, String> attributes) {
        Objects.requireNonNull(name, "name");
        synchronized (events) {
            events.add(new Event(name, Instant.now(), Map.copyOf(attributes)));
        }
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public long count(String name) {
        var adder = counters.get(name);
        return adder == null ? 0L : adder.sum();
    }

    public List<Event> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public int totalEvents() {
        synchronized (events) {
            return events.size();
        }
    }

    public String channelCode() {
        return "KIOSK";
    }

    public String telemetryNamespace() {
        return "omnibank." + "rewardschecking" + "." + "KIOSK".toLowerCase();
    }

    public void reset() {
        synchronized (events) {
            events.clear();
        }
        counters.clear();
    }
}
