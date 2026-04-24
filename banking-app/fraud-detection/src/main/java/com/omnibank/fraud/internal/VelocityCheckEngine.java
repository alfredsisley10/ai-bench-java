package com.omnibank.fraud.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Tracks transaction velocity across multiple time windows and fires alerts
 * when configurable thresholds are breached. Maintains sliding-window counters
 * for both per-account and per-customer aggregation.
 *
 * <p>Time windows monitored:
 * <ul>
 *   <li><b>1 minute:</b> Burst detection (card testing, bot attacks)</li>
 *   <li><b>1 hour:</b> Short-term velocity (rapid spending spree)</li>
 *   <li><b>24 hours:</b> Daily pattern anomaly detection</li>
 *   <li><b>7 days:</b> Weekly pattern analysis (slow-burn compromise)</li>
 * </ul>
 *
 * <p>Thresholds are configurable per-product and per-customer risk segment.
 * The engine uses in-memory ring buffers for the 1-min and 1-hr windows,
 * with periodic snapshots persisted for the 24-hr and 7-day windows.
 *
 * <p>Thread-safe: all structures use concurrent collections. Designed for
 * high-throughput authorization paths (10K+ TPS target).
 */
public class VelocityCheckEngine {

    private static final Logger log = LoggerFactory.getLogger(VelocityCheckEngine.class);

    /** Time window definitions. */
    enum TimeWindow {
        ONE_MINUTE(Duration.ofMinutes(1), "1min"),
        ONE_HOUR(Duration.ofHours(1), "1hr"),
        TWENTY_FOUR_HOURS(Duration.ofHours(24), "24hr"),
        SEVEN_DAYS(Duration.ofDays(7), "7d");

        private final Duration duration;
        private final String label;

        TimeWindow(Duration duration, String label) {
            this.duration = duration;
            this.label = label;
        }

        public Duration duration() { return duration; }
        public String label() { return label; }
    }

    /** Threshold configuration for a velocity rule. */
    record VelocityThreshold(TimeWindow window, int maxCount, Money maxAmount,
                              String ruleId, String description) {
        public VelocityThreshold {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(ruleId, "ruleId");
            if (maxCount <= 0 && (maxAmount == null || maxAmount.isZero())) {
                throw new IllegalArgumentException(
                        "Threshold must specify maxCount > 0 or maxAmount > 0");
            }
        }
    }

    /** Result of a velocity check — aggregated across all windows. */
    public sealed interface VelocityResult permits VelocityResult.Clear, VelocityResult.Breach {

        record Clear(AccountNumber account, Map<TimeWindow, WindowSnapshot> snapshots)
                implements VelocityResult {}

        record Breach(AccountNumber account, List<VelocityViolation> violations,
                      Map<TimeWindow, WindowSnapshot> snapshots) implements VelocityResult {}
    }

    record VelocityViolation(String ruleId, TimeWindow window, String description,
                              int actualCount, int limitCount,
                              Money actualAmount, Money limitAmount) {}

    record WindowSnapshot(TimeWindow window, int transactionCount, Money totalAmount,
                          Instant windowStart, Instant windowEnd) {}

    public record TransactionEvent(AccountNumber account, CustomerId customer, Money amount,
                             Instant timestamp, String channel) {
        public TransactionEvent {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    /** Default thresholds for per-account velocity. */
    private static final List<VelocityThreshold> DEFAULT_ACCOUNT_THRESHOLDS = List.of(
            new VelocityThreshold(TimeWindow.ONE_MINUTE, 3, null,
                    "VEL-ACC-1M-CNT", "Max 3 txns per account per minute"),
            new VelocityThreshold(TimeWindow.ONE_MINUTE, 0,
                    Money.of("2000.00", CurrencyCode.USD),
                    "VEL-ACC-1M-AMT", "Max $2,000 per account per minute"),
            new VelocityThreshold(TimeWindow.ONE_HOUR, 15, null,
                    "VEL-ACC-1H-CNT", "Max 15 txns per account per hour"),
            new VelocityThreshold(TimeWindow.ONE_HOUR, 0,
                    Money.of("10000.00", CurrencyCode.USD),
                    "VEL-ACC-1H-AMT", "Max $10,000 per account per hour"),
            new VelocityThreshold(TimeWindow.TWENTY_FOUR_HOURS, 50, null,
                    "VEL-ACC-24H-CNT", "Max 50 txns per account per 24hr"),
            new VelocityThreshold(TimeWindow.TWENTY_FOUR_HOURS, 0,
                    Money.of("25000.00", CurrencyCode.USD),
                    "VEL-ACC-24H-AMT", "Max $25,000 per account per 24hr"),
            new VelocityThreshold(TimeWindow.SEVEN_DAYS, 200, null,
                    "VEL-ACC-7D-CNT", "Max 200 txns per account per 7 days"),
            new VelocityThreshold(TimeWindow.SEVEN_DAYS, 0,
                    Money.of("75000.00", CurrencyCode.USD),
                    "VEL-ACC-7D-AMT", "Max $75,000 per account per 7 days")
    );

    /** Default thresholds for per-customer velocity (aggregated across all accounts). */
    private static final List<VelocityThreshold> DEFAULT_CUSTOMER_THRESHOLDS = List.of(
            new VelocityThreshold(TimeWindow.ONE_HOUR, 25, null,
                    "VEL-CUS-1H-CNT", "Max 25 txns per customer per hour"),
            new VelocityThreshold(TimeWindow.TWENTY_FOUR_HOURS, 100, null,
                    "VEL-CUS-24H-CNT", "Max 100 txns per customer per 24hr"),
            new VelocityThreshold(TimeWindow.SEVEN_DAYS, 0,
                    Money.of("150000.00", CurrencyCode.USD),
                    "VEL-CUS-7D-AMT", "Max $150,000 per customer per 7 days")
    );

    /** In-memory event stores keyed by account number and customer ID. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TransactionEvent>> accountEvents
            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TransactionEvent>> customerEvents
            = new ConcurrentHashMap<>();

    private final Clock clock;

    public VelocityCheckEngine(Clock clock) {
        this.clock = clock;
    }

    /**
     * Record a transaction and check velocity thresholds. Returns the result
     * immediately — this is called in the authorization hot path.
     */
    public VelocityResult recordAndCheck(TransactionEvent event) {
        record_(event);
        return checkAccount(event.account());
    }

    /**
     * Record a transaction event for velocity tracking without performing a check.
     * Used for transactions that have already been authorized (e.g., settlement).
     */
    public void record_(TransactionEvent event) {
        accountEvents
                .computeIfAbsent(event.account().raw(), k -> new CopyOnWriteArrayList<>())
                .add(event);

        if (event.customer() != null) {
            customerEvents
                    .computeIfAbsent(event.customer().toString(), k -> new CopyOnWriteArrayList<>())
                    .add(event);
        }
    }

    /**
     * Check per-account velocity against all configured thresholds.
     */
    public VelocityResult checkAccount(AccountNumber account) {
        Instant now = Timestamp.now(clock);
        List<TransactionEvent> events = getAccountEvents(account);
        Map<TimeWindow, WindowSnapshot> snapshots = computeSnapshots(events, now, account);
        List<VelocityViolation> violations = evaluateThresholds(
                DEFAULT_ACCOUNT_THRESHOLDS, snapshots);

        if (violations.isEmpty()) {
            return new VelocityResult.Clear(account, snapshots);
        }

        log.warn("Velocity breach for account {}: {} violations", account, violations.size());
        return new VelocityResult.Breach(account, List.copyOf(violations), snapshots);
    }

    /**
     * Check per-customer velocity across all their accounts.
     */
    public VelocityResult checkCustomer(CustomerId customer, AccountNumber reportingAccount) {
        Instant now = Timestamp.now(clock);
        List<TransactionEvent> events = getCustomerEvents(customer);
        Map<TimeWindow, WindowSnapshot> snapshots = computeSnapshots(
                events, now, reportingAccount);
        List<VelocityViolation> violations = evaluateThresholds(
                DEFAULT_CUSTOMER_THRESHOLDS, snapshots);

        if (violations.isEmpty()) {
            return new VelocityResult.Clear(reportingAccount, snapshots);
        }

        log.warn("Velocity breach for customer {}: {} violations", customer, violations.size());
        return new VelocityResult.Breach(reportingAccount, List.copyOf(violations), snapshots);
    }

    /**
     * Get current window snapshots for an account (used by dashboards / investigation).
     */
    public Map<TimeWindow, WindowSnapshot> getAccountVelocitySnapshot(AccountNumber account) {
        Instant now = Timestamp.now(clock);
        List<TransactionEvent> events = getAccountEvents(account);
        return computeSnapshots(events, now, account);
    }

    private Map<TimeWindow, WindowSnapshot> computeSnapshots(List<TransactionEvent> events,
                                                               Instant now,
                                                               AccountNumber account) {
        return java.util.Arrays.stream(TimeWindow.values())
                .collect(Collectors.toMap(
                        window -> window,
                        window -> {
                            Instant windowStart = now.minus(window.duration());
                            List<TransactionEvent> windowEvents = events.stream()
                                    .filter(e -> e.timestamp().isAfter(windowStart))
                                    .toList();
                            Money total = windowEvents.stream()
                                    .map(TransactionEvent::amount)
                                    .reduce(Money.zero(CurrencyCode.USD), Money::plus);
                            return new WindowSnapshot(window, windowEvents.size(),
                                    total, windowStart, now);
                        }
                ));
    }

    private List<VelocityViolation> evaluateThresholds(List<VelocityThreshold> thresholds,
                                                         Map<TimeWindow, WindowSnapshot> snapshots) {
        List<VelocityViolation> violations = new ArrayList<>();

        for (VelocityThreshold threshold : thresholds) {
            WindowSnapshot snapshot = snapshots.get(threshold.window());
            if (snapshot == null) continue;

            boolean countBreached = threshold.maxCount() > 0
                    && snapshot.transactionCount() > threshold.maxCount();
            boolean amountBreached = threshold.maxAmount() != null
                    && !threshold.maxAmount().isZero()
                    && snapshot.totalAmount().compareTo(threshold.maxAmount()) > 0;

            if (countBreached || amountBreached) {
                violations.add(new VelocityViolation(
                        threshold.ruleId(), threshold.window(), threshold.description(),
                        snapshot.transactionCount(), threshold.maxCount(),
                        snapshot.totalAmount(), threshold.maxAmount()
                ));
            }
        }

        return violations;
    }

    private List<TransactionEvent> getAccountEvents(AccountNumber account) {
        CopyOnWriteArrayList<TransactionEvent> events = accountEvents.get(account.raw());
        return events != null ? List.copyOf(events) : List.of();
    }

    private List<TransactionEvent> getCustomerEvents(CustomerId customer) {
        CopyOnWriteArrayList<TransactionEvent> events = customerEvents.get(customer.toString());
        return events != null ? List.copyOf(events) : List.of();
    }

    /**
     * Periodic eviction of events older than the maximum window (7 days + 1 day buffer).
     * Runs every 4 hours to keep memory bounded.
     */
    @Scheduled(fixedRate = 4 * 60 * 60 * 1000)
    public void evictStaleEvents() {
        Instant cutoff = Timestamp.now(clock).minus(Duration.ofDays(8));
        int evicted = 0;

        for (var entry : accountEvents.entrySet()) {
            int before = entry.getValue().size();
            entry.getValue().removeIf(e -> e.timestamp().isBefore(cutoff));
            evicted += before - entry.getValue().size();
            if (entry.getValue().isEmpty()) {
                accountEvents.remove(entry.getKey());
            }
        }

        for (var entry : customerEvents.entrySet()) {
            int before = entry.getValue().size();
            entry.getValue().removeIf(e -> e.timestamp().isBefore(cutoff));
            evicted += before - entry.getValue().size();
            if (entry.getValue().isEmpty()) {
                customerEvents.remove(entry.getKey());
            }
        }

        if (evicted > 0) {
            log.info("Velocity engine evicted {} stale events. Active accounts: {}, customers: {}",
                    evicted, accountEvents.size(), customerEvents.size());
        }
    }

    /**
     * Get summary statistics for monitoring. Used by the operations dashboard.
     */
    public VelocityEngineStats getStats() {
        int totalAccountEvents = accountEvents.values().stream()
                .mapToInt(CopyOnWriteArrayList::size).sum();
        int totalCustomerEvents = customerEvents.values().stream()
                .mapToInt(CopyOnWriteArrayList::size).sum();
        return new VelocityEngineStats(accountEvents.size(), customerEvents.size(),
                totalAccountEvents, totalCustomerEvents);
    }

    record VelocityEngineStats(int trackedAccounts, int trackedCustomers,
                                int totalAccountEvents, int totalCustomerEvents) {}
}
