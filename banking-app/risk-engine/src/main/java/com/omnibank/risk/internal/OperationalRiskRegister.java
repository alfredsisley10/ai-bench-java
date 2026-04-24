package com.omnibank.risk.internal;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store + analytics facade for operational-risk loss events.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Accept new {@link OperationalRiskLossEvent} records and publish a
 *       domain event to downstream consumers (GL posting, KRI feeds).</li>
 *   <li>Maintain cumulative totals by event type and business line for the
 *       Basel II/III loss-component calculation.</li>
 *   <li>Provide the 10-year look-back window aggregation required by the
 *       2023 SA for operational risk.</li>
 *   <li>Track frequency (events per period) and severity percentiles used in
 *       internal LDA-style capital models.</li>
 * </ul>
 */
public class OperationalRiskRegister {

    private static final Logger log = LoggerFactory.getLogger(OperationalRiskRegister.class);

    /** Internal materiality threshold for alerting (configurable). */
    public static final Money DEFAULT_MATERIALITY_THRESHOLD =
            Money.of("10000.00", CurrencyCode.USD);

    public record OperationalLossRecordedEvent(
            UUID eventId, Instant occurredAt,
            UUID lossEventId,
            OperationalRiskLossEvent.LossEventType type,
            Money netLoss)
            implements DomainEvent {
        @Override public String eventType() { return "risk.operational.loss_recorded"; }
    }

    public record CategoryStatistics(
            OperationalRiskLossEvent.LossEventType type,
            int count,
            Money grossTotal,
            Money recoveryTotal,
            Money netTotal,
            Money maxSingleLoss) {}

    public record BusinessLineStatistics(
            RiskWeightedAssetsEngine.BusinessLine businessLine,
            int count,
            Money netTotal) {}

    public record AggregateSnapshot(
            int totalEvents,
            Money totalGross,
            Money totalRecoveries,
            Money totalNet,
            Map<OperationalRiskLossEvent.LossEventType, CategoryStatistics> byType,
            Map<RiskWeightedAssetsEngine.BusinessLine, BusinessLineStatistics> byBusinessLine
    ) {}

    private final Clock clock;
    private final EventBus events;
    private final CurrencyCode baseCurrency;
    private final Money materialityThreshold;

    /** Event store keyed by event id. */
    private final Map<UUID, OperationalRiskLossEvent> store = new ConcurrentHashMap<>();

    public OperationalRiskRegister(Clock clock, EventBus events, CurrencyCode baseCurrency) {
        this(clock, events, baseCurrency,
                baseCurrency == CurrencyCode.USD
                        ? DEFAULT_MATERIALITY_THRESHOLD
                        : Money.of("10000.00", baseCurrency));
    }

    public OperationalRiskRegister(Clock clock, EventBus events,
                                    CurrencyCode baseCurrency, Money materialityThreshold) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
        this.materialityThreshold = Objects.requireNonNull(materialityThreshold, "materialityThreshold");
    }

    /** Record a new loss event and emit the domain event. */
    public OperationalRiskLossEvent record(OperationalRiskLossEvent event) {
        Objects.requireNonNull(event, "event");
        if (!event.grossLoss().currency().equals(baseCurrency)) {
            throw new IllegalArgumentException("event currency does not match register base currency");
        }

        store.put(event.eventId(), event);

        events.publish(new OperationalLossRecordedEvent(
                UUID.randomUUID(), Timestamp.now(clock),
                event.eventId(), event.type(), event.netLoss()));

        if (event.isMaterial(materialityThreshold)) {
            log.warn("Material operational loss recorded: id={} type={} netLoss={} bl={}",
                    event.eventId(), event.type(), event.netLoss(), event.businessLine());
        }
        return event;
    }

    /** Update the status of an existing event (ACCOUNTED, IN_RECOVERY, CLOSED …). */
    public OperationalRiskLossEvent updateStatus(UUID eventId,
                                                  OperationalRiskLossEvent.EventStatus newStatus) {
        OperationalRiskLossEvent existing = store.get(eventId);
        if (existing == null) throw new IllegalArgumentException("event not found: " + eventId);
        OperationalRiskLossEvent updated = existing.withStatus(newStatus);
        store.put(eventId, updated);
        return updated;
    }

    /** Add an incremental recovery amount against an existing event. */
    public OperationalRiskLossEvent recordRecovery(UUID eventId, Money additional) {
        OperationalRiskLossEvent existing = store.get(eventId);
        if (existing == null) throw new IllegalArgumentException("event not found: " + eventId);
        OperationalRiskLossEvent updated = existing.addRecovery(additional);
        store.put(eventId, updated);
        return updated;
    }

    /** Full list of events in insertion order. */
    public List<OperationalRiskLossEvent> allEvents() {
        return List.copyOf(store.values());
    }

    /** Only events whose occurrence date falls inside the Basel 10-year window. */
    public List<OperationalRiskLossEvent> eventsInRegulatoryWindow(LocalDate asOf) {
        return store.values().stream()
                .filter(e -> e.isInLookbackWindow(asOf))
                .toList();
    }

    /**
     * Compute the averaged annual loss for the 2023 SA loss component:
     * arithmetic mean of annual net losses over the supplied window.
     */
    public Money averageAnnualLoss(LocalDate asOf, int years) {
        if (years <= 0) throw new IllegalArgumentException("years must be > 0");
        LocalDate start = asOf.minusYears(years);
        Money total = Money.zero(baseCurrency);
        int actualYears = 0;
        for (int y = 0; y < years; y++) {
            LocalDate yearStart = start.plusYears(y);
            LocalDate yearEnd = yearStart.plusYears(1);
            Money yearly = store.values().stream()
                    .filter(e -> !e.occurredOn().isBefore(yearStart)
                            && e.occurredOn().isBefore(yearEnd))
                    .map(OperationalRiskLossEvent::netLoss)
                    .reduce(Money.zero(baseCurrency), Money::plus);
            total = total.plus(yearly);
            actualYears++;
        }
        if (actualYears == 0) return Money.zero(baseCurrency);
        return total.dividedBy(java.math.BigDecimal.valueOf(actualYears));
    }

    /** Produce a consolidated statistics snapshot for dashboards. */
    public AggregateSnapshot snapshot() {
        int total = store.size();
        Money gross = Money.zero(baseCurrency);
        Money rec = Money.zero(baseCurrency);
        Money net = Money.zero(baseCurrency);

        Map<OperationalRiskLossEvent.LossEventType, List<OperationalRiskLossEvent>> byType =
                new EnumMap<>(OperationalRiskLossEvent.LossEventType.class);
        Map<RiskWeightedAssetsEngine.BusinessLine, List<OperationalRiskLossEvent>> byBl =
                new EnumMap<>(RiskWeightedAssetsEngine.BusinessLine.class);

        for (OperationalRiskLossEvent e : store.values()) {
            gross = gross.plus(e.grossLoss());
            rec = rec.plus(e.recoveries());
            net = net.plus(e.netLoss());
            byType.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
            byBl.computeIfAbsent(e.businessLine(), k -> new ArrayList<>()).add(e);
        }

        Map<OperationalRiskLossEvent.LossEventType, CategoryStatistics> typeStats =
                new EnumMap<>(OperationalRiskLossEvent.LossEventType.class);
        for (var entry : byType.entrySet()) {
            typeStats.put(entry.getKey(), categoryStats(entry.getKey(), entry.getValue()));
        }

        Map<RiskWeightedAssetsEngine.BusinessLine, BusinessLineStatistics> blStats =
                new EnumMap<>(RiskWeightedAssetsEngine.BusinessLine.class);
        for (var entry : byBl.entrySet()) {
            Money sum = entry.getValue().stream()
                    .map(OperationalRiskLossEvent::netLoss)
                    .reduce(Money.zero(baseCurrency), Money::plus);
            blStats.put(entry.getKey(),
                    new BusinessLineStatistics(entry.getKey(), entry.getValue().size(), sum));
        }

        return new AggregateSnapshot(total, gross, rec, net,
                new LinkedHashMap<>(typeStats), new LinkedHashMap<>(blStats));
    }

    /** Events per month for the last {@code months} months — KRI input. */
    public Map<String, Integer> monthlyFrequency(LocalDate asOf, int months) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (int i = months - 1; i >= 0; i--) {
            LocalDate monthStart = asOf.minusMonths(i).withDayOfMonth(1);
            LocalDate nextMonth = monthStart.plusMonths(1);
            int count = (int) store.values().stream()
                    .filter(e -> !e.occurredOn().isBefore(monthStart)
                            && e.occurredOn().isBefore(nextMonth))
                    .count();
            freq.put(monthStart.toString().substring(0, 7), count);
        }
        return freq;
    }

    /** Average days between occurrence and discovery — KRI. */
    public double averageDiscoveryLagDays() {
        if (store.isEmpty()) return 0.0;
        long totalDays = store.values().stream()
                .mapToLong(OperationalRiskLossEvent::discoveryLagDays)
                .sum();
        return totalDays / (double) store.size();
    }

    /** Count of still-open (non-closed) events. */
    public int openEventCount() {
        return (int) store.values().stream()
                .filter(e -> e.status() != OperationalRiskLossEvent.EventStatus.CLOSED
                        && e.status() != OperationalRiskLossEvent.EventStatus.WRITTEN_OFF)
                .count();
    }

    /** Sort events newest-first. */
    public List<OperationalRiskLossEvent> recentEvents(int limit) {
        return store.values().stream()
                .sorted((a, b) -> b.occurredOn().compareTo(a.occurredOn()))
                .limit(limit)
                .toList();
    }

    private CategoryStatistics categoryStats(OperationalRiskLossEvent.LossEventType type,
                                               List<OperationalRiskLossEvent> events) {
        Money gross = Money.zero(baseCurrency);
        Money rec = Money.zero(baseCurrency);
        Money net = Money.zero(baseCurrency);
        Money max = Money.zero(baseCurrency);
        for (OperationalRiskLossEvent e : events) {
            gross = gross.plus(e.grossLoss());
            rec = rec.plus(e.recoveries());
            net = net.plus(e.netLoss());
            if (e.netLoss().compareTo(max) > 0) max = e.netLoss();
        }
        return new CategoryStatistics(type, events.size(), gross, rec, net, max);
    }

    /**
     * Days since the most recent event — simple diagnostic "days since incident"
     * counter you see on shop-floor posters.
     */
    public long daysSinceLastEvent(LocalDate asOf) {
        return store.values().stream()
                .map(OperationalRiskLossEvent::occurredOn)
                .max(LocalDate::compareTo)
                .map(d -> ChronoUnit.DAYS.between(d, asOf))
                .orElse(Long.MAX_VALUE);
    }
}
