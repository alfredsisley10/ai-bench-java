package com.omnibank.compliance.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Anti-Money Laundering (AML) transaction monitoring engine. Analyzes
 * transaction patterns in real-time and batch to detect suspicious activity
 * typologies required by the Bank Secrecy Act (BSA) and FinCEN regulations.
 *
 * <p>Monitored AML typologies:
 * <ul>
 *   <li><b>Structuring:</b> Multiple cash transactions just below $10,000 CTR
 *       threshold within a rolling window (31 USC 5324)</li>
 *   <li><b>Layering:</b> Rapid movement of funds through multiple accounts to
 *       obscure the origin (wire-to-wire, account-to-account chains)</li>
 *   <li><b>Round-trip:</b> Funds sent to an external party and returned within
 *       a short window with no apparent business purpose</li>
 *   <li><b>Rapid movement:</b> Large deposits quickly followed by withdrawals
 *       or outbound transfers (pass-through pattern)</li>
 *   <li><b>High-risk jurisdiction:</b> Transactions involving FATF-listed
 *       countries or jurisdictions with weak AML controls</li>
 * </ul>
 *
 * <p>When a typology pattern is detected, the engine generates an alert for
 * BSA analyst review. Confirmed alerts may result in Suspicious Activity
 * Report (SAR) filing with FinCEN.
 */
public class AmlTransactionMonitor {

    private static final Logger log = LoggerFactory.getLogger(AmlTransactionMonitor.class);

    /** CTR reporting threshold. */
    private static final Money CTR_THRESHOLD = Money.of("10000.00", CurrencyCode.USD);

    /** Structuring detection: individual transaction threshold just below CTR. */
    private static final Money STRUCTURING_LOWER = Money.of("3000.00", CurrencyCode.USD);
    private static final Money STRUCTURING_UPPER = Money.of("9999.99", CurrencyCode.USD);
    private static final int STRUCTURING_COUNT_THRESHOLD = 3;
    private static final Duration STRUCTURING_WINDOW = Duration.ofHours(48);

    /** Rapid movement: deposit-then-withdraw within this window. */
    private static final Duration RAPID_MOVEMENT_WINDOW = Duration.ofHours(72);
    private static final BigDecimal RAPID_MOVEMENT_RATIO = new BigDecimal("0.80");

    /** Round-trip detection window. */
    private static final Duration ROUND_TRIP_WINDOW = Duration.ofDays(14);

    /** FATF high-risk jurisdictions. */
    private static final Set<String> HIGH_RISK_JURISDICTIONS = Set.of(
            "IR", "KP", "MM", "SY", "YE", "AF", "AL", "BF",
            "CF", "CD", "HT", "ML", "MZ", "NI", "PK", "SO", "SS", "TZ"
    );

    public enum TypologyCode {
        STRUCTURING("Potential structuring to evade CTR reporting"),
        LAYERING("Potential layering through multiple accounts"),
        ROUND_TRIP("Potential round-trip transaction"),
        RAPID_MOVEMENT("Rapid movement of funds / pass-through"),
        HIGH_RISK_JURISDICTION("Transaction involving high-risk jurisdiction"),
        UNUSUAL_VOLUME("Unusual transaction volume for account profile"),
        CASH_INTENSIVE("Cash-intensive activity inconsistent with business type");

        private final String description;

        TypologyCode(String description) {
            this.description = description;
        }

        public String description() { return description; }
    }

    public enum AlertStatus { OPEN, UNDER_REVIEW, ESCALATED, SAR_FILED, CLOSED_NO_ACTION }

    public record MonitoredTransaction(
            UUID transactionId,
            AccountNumber account,
            CustomerId customer,
            Money amount,
            String direction,    // INBOUND or OUTBOUND
            String channel,      // CASH, WIRE, ACH, INTERNAL
            String counterpartyCountry,
            String counterpartyName,
            Instant timestamp
    ) {
        public MonitoredTransaction {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(timestamp, "timestamp");
        }
    }

    public record AmlAlert(
            UUID alertId,
            CustomerId customer,
            AccountNumber primaryAccount,
            TypologyCode typology,
            AlertStatus status,
            int riskScore,
            String narrative,
            List<UUID> relatedTransactions,
            Instant createdAt,
            String assignedAnalyst,
            UUID sarFilingId
    ) {}

    record AmlAlertEvent(UUID eventId, Instant occurredAt, UUID alertId,
                          CustomerId customer, TypologyCode typology,
                          int riskScore) implements DomainEvent {
        @Override
        public String eventType() {
            return "compliance.aml.alert_generated";
        }
    }

    /** Transaction history for monitoring. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MonitoredTransaction>>
            accountHistory = new ConcurrentHashMap<>();

    /** Generated alerts. */
    private final ConcurrentHashMap<String, List<AmlAlert>> alerts = new ConcurrentHashMap<>();

    private final EventBus events;
    private final Clock clock;

    public AmlTransactionMonitor(EventBus events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Monitor a new transaction in real-time. Evaluates against all typologies
     * and generates alerts if patterns are detected.
     */
    public List<AmlAlert> monitorTransaction(MonitoredTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");

        recordTransaction(transaction);

        List<AmlAlert> newAlerts = new ArrayList<>();
        List<MonitoredTransaction> history = getAccountHistory(transaction.account());

        // Typology 1: Structuring
        detectStructuring(transaction, history).ifPresent(newAlerts::add);

        // Typology 2: Rapid movement
        detectRapidMovement(transaction, history).ifPresent(newAlerts::add);

        // Typology 3: High-risk jurisdiction
        detectHighRiskJurisdiction(transaction).ifPresent(newAlerts::add);

        // Typology 4: Round-trip
        detectRoundTrip(transaction, history).ifPresent(newAlerts::add);

        for (AmlAlert alert : newAlerts) {
            storeAlert(alert);
            publishAlertEvent(alert);
            log.warn("AML alert generated: id={}, typology={}, customer={}, score={}",
                    alert.alertId(), alert.typology(), alert.customer(), alert.riskScore());
        }

        return newAlerts;
    }

    /**
     * Batch analysis: run all typology detectors against accumulated history.
     * Scheduled nightly for patterns that require broader context.
     */
    @Scheduled(cron = "0 0 3 * * MON-FRI", zone = "America/New_York")
    public List<AmlAlert> runBatchAnalysis() {
        log.info("Starting nightly AML batch analysis");
        List<AmlAlert> batchAlerts = new ArrayList<>();

        for (var entry : accountHistory.entrySet()) {
            List<MonitoredTransaction> history = List.copyOf(entry.getValue());
            if (history.isEmpty()) continue;

            // Unusual volume check
            detectUnusualVolume(history).ifPresent(alert -> {
                batchAlerts.add(alert);
                storeAlert(alert);
                publishAlertEvent(alert);
            });

            // Layering detection (requires multi-account view)
            detectLayering(history).ifPresent(alert -> {
                batchAlerts.add(alert);
                storeAlert(alert);
                publishAlertEvent(alert);
            });
        }

        log.info("AML batch analysis complete: {} alerts generated", batchAlerts.size());
        return batchAlerts;
    }

    /**
     * File a SAR for a confirmed alert. Generates the SAR record and updates
     * alert status. In production, this would submit to FinCEN's BSA E-Filing.
     */
    public AmlAlert fileSar(UUID alertId, String narrative, String analystId) {
        AmlAlert alert = findAlert(alertId);
        if (alert == null) {
            throw new IllegalArgumentException("Alert not found: " + alertId);
        }

        UUID sarId = UUID.randomUUID();
        AmlAlert updated = new AmlAlert(alert.alertId(), alert.customer(),
                alert.primaryAccount(), alert.typology(), AlertStatus.SAR_FILED,
                alert.riskScore(), narrative, alert.relatedTransactions(),
                alert.createdAt(), analystId, sarId);

        replaceAlert(updated);
        log.info("SAR filed: alertId={}, sarId={}, customer={}", alertId, sarId, alert.customer());
        return updated;
    }

    /**
     * Close an alert after review determines no suspicious activity.
     */
    public AmlAlert closeAlert(UUID alertId, String justification, String analystId) {
        AmlAlert alert = findAlert(alertId);
        if (alert == null) {
            throw new IllegalArgumentException("Alert not found: " + alertId);
        }

        AmlAlert closed = new AmlAlert(alert.alertId(), alert.customer(),
                alert.primaryAccount(), alert.typology(), AlertStatus.CLOSED_NO_ACTION,
                alert.riskScore(), justification, alert.relatedTransactions(),
                alert.createdAt(), analystId, null);

        replaceAlert(closed);
        return closed;
    }

    private java.util.Optional<AmlAlert> detectStructuring(MonitoredTransaction txn,
                                                             List<MonitoredTransaction> history) {
        if (!"CASH".equalsIgnoreCase(txn.channel())) return java.util.Optional.empty();

        Instant windowStart = txn.timestamp().minus(STRUCTURING_WINDOW);
        List<MonitoredTransaction> cashTxns = history.stream()
                .filter(t -> "CASH".equalsIgnoreCase(t.channel()))
                .filter(t -> t.timestamp().isAfter(windowStart))
                .filter(t -> t.amount().compareTo(STRUCTURING_LOWER) >= 0)
                .filter(t -> t.amount().compareTo(STRUCTURING_UPPER) <= 0)
                .toList();

        if (cashTxns.size() >= STRUCTURING_COUNT_THRESHOLD) {
            Money totalCash = cashTxns.stream()
                    .map(MonitoredTransaction::amount)
                    .reduce(Money.zero(CurrencyCode.USD), Money::plus);

            if (totalCash.compareTo(CTR_THRESHOLD) >= 0) {
                List<UUID> txnIds = cashTxns.stream()
                        .map(MonitoredTransaction::transactionId).toList();
                return java.util.Optional.of(createAlert(txn.customer(), txn.account(),
                        TypologyCode.STRUCTURING, 85,
                        "%d cash transactions totaling %s in %dhr window"
                                .formatted(cashTxns.size(), totalCash,
                                        STRUCTURING_WINDOW.toHours()),
                        txnIds));
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<AmlAlert> detectRapidMovement(MonitoredTransaction txn,
                                                                List<MonitoredTransaction> history) {
        if (!"INBOUND".equalsIgnoreCase(txn.direction())) return java.util.Optional.empty();

        Instant windowEnd = txn.timestamp().plus(RAPID_MOVEMENT_WINDOW);
        Money inboundTotal = history.stream()
                .filter(t -> "INBOUND".equalsIgnoreCase(t.direction()))
                .filter(t -> t.timestamp().isAfter(txn.timestamp().minus(RAPID_MOVEMENT_WINDOW)))
                .map(MonitoredTransaction::amount)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);

        Money outboundTotal = history.stream()
                .filter(t -> "OUTBOUND".equalsIgnoreCase(t.direction()))
                .filter(t -> t.timestamp().isAfter(txn.timestamp().minus(RAPID_MOVEMENT_WINDOW)))
                .map(MonitoredTransaction::amount)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);

        if (inboundTotal.isPositive() && outboundTotal.isPositive()) {
            BigDecimal ratio = outboundTotal.amount().divide(
                    inboundTotal.amount(), 4, java.math.RoundingMode.HALF_EVEN);
            if (ratio.compareTo(RAPID_MOVEMENT_RATIO) >= 0
                    && inboundTotal.compareTo(Money.of("5000.00", CurrencyCode.USD)) >= 0) {
                return java.util.Optional.of(createAlert(txn.customer(), txn.account(),
                        TypologyCode.RAPID_MOVEMENT, 70,
                        "%.0f%% of inbound funds (%s) moved outbound within %dhr"
                                .formatted(ratio.multiply(BigDecimal.valueOf(100)).doubleValue(),
                                        inboundTotal, RAPID_MOVEMENT_WINDOW.toHours()),
                        List.of(txn.transactionId())));
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<AmlAlert> detectHighRiskJurisdiction(MonitoredTransaction txn) {
        if (txn.counterpartyCountry() != null
                && HIGH_RISK_JURISDICTIONS.contains(txn.counterpartyCountry().toUpperCase())) {
            return java.util.Optional.of(createAlert(txn.customer(), txn.account(),
                    TypologyCode.HIGH_RISK_JURISDICTION, 60,
                    "Transaction with high-risk jurisdiction: " + txn.counterpartyCountry(),
                    List.of(txn.transactionId())));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<AmlAlert> detectRoundTrip(MonitoredTransaction txn,
                                                            List<MonitoredTransaction> history) {
        if (txn.counterpartyName() == null || "OUTBOUND".equalsIgnoreCase(txn.direction())) {
            return java.util.Optional.empty();
        }

        Instant windowStart = txn.timestamp().minus(ROUND_TRIP_WINDOW);
        boolean hasMatchingOutbound = history.stream()
                .filter(t -> "OUTBOUND".equalsIgnoreCase(t.direction()))
                .filter(t -> t.timestamp().isAfter(windowStart))
                .filter(t -> t.counterpartyName() != null)
                .anyMatch(t -> t.counterpartyName().equalsIgnoreCase(txn.counterpartyName())
                        && t.amount().equals(txn.amount()));

        if (hasMatchingOutbound) {
            return java.util.Optional.of(createAlert(txn.customer(), txn.account(),
                    TypologyCode.ROUND_TRIP, 75,
                    "Round-trip detected: %s sent and received from %s within %dd"
                            .formatted(txn.amount(), txn.counterpartyName(),
                                    ROUND_TRIP_WINDOW.toDays()),
                    List.of(txn.transactionId())));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<AmlAlert> detectUnusualVolume(
            List<MonitoredTransaction> history) {
        if (history.isEmpty()) return java.util.Optional.empty();

        Instant last7Days = Timestamp.now(clock).minus(Duration.ofDays(7));
        Instant prior30Days = Timestamp.now(clock).minus(Duration.ofDays(37));

        long recentCount = history.stream()
                .filter(t -> t.timestamp().isAfter(last7Days)).count();
        long baselineCount = history.stream()
                .filter(t -> t.timestamp().isAfter(prior30Days) && t.timestamp().isBefore(last7Days))
                .count();
        double weeklyBaseline = baselineCount / 4.0;

        if (weeklyBaseline > 0 && recentCount > weeklyBaseline * 3) {
            MonitoredTransaction sample = history.get(history.size() - 1);
            return java.util.Optional.of(createAlert(sample.customer(), sample.account(),
                    TypologyCode.UNUSUAL_VOLUME, 55,
                    "Recent 7d volume (%d) is 3x+ baseline weekly average (%.0f)"
                            .formatted(recentCount, weeklyBaseline),
                    List.of()));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<AmlAlert> detectLayering(List<MonitoredTransaction> history) {
        // Simplified layering detection: look for chain patterns (in->out->in->out)
        // within a short window with different counterparties
        Instant last48h = Timestamp.now(clock).minus(Duration.ofHours(48));
        List<MonitoredTransaction> recent = history.stream()
                .filter(t -> t.timestamp().isAfter(last48h))
                .sorted(java.util.Comparator.comparing(MonitoredTransaction::timestamp))
                .toList();

        if (recent.size() < 4) return java.util.Optional.empty();

        int alternations = 0;
        String prevDirection = null;
        Set<String> counterparties = new java.util.HashSet<>();

        for (MonitoredTransaction t : recent) {
            if (prevDirection != null && !prevDirection.equals(t.direction())) {
                alternations++;
            }
            prevDirection = t.direction();
            if (t.counterpartyName() != null) counterparties.add(t.counterpartyName());
        }

        if (alternations >= 3 && counterparties.size() >= 3) {
            MonitoredTransaction sample = recent.get(0);
            return java.util.Optional.of(createAlert(sample.customer(), sample.account(),
                    TypologyCode.LAYERING, 80,
                    "%d direction alternations with %d distinct counterparties in 48hr"
                            .formatted(alternations, counterparties.size()),
                    recent.stream().map(MonitoredTransaction::transactionId).toList()));
        }
        return java.util.Optional.empty();
    }

    private AmlAlert createAlert(CustomerId customer, AccountNumber account,
                                   TypologyCode typology, int riskScore,
                                   String narrative, List<UUID> relatedTxns) {
        return new AmlAlert(UUID.randomUUID(), customer, account, typology,
                AlertStatus.OPEN, riskScore, narrative, relatedTxns,
                Timestamp.now(clock), null, null);
    }

    private void recordTransaction(MonitoredTransaction transaction) {
        accountHistory
                .computeIfAbsent(transaction.account().raw(), k -> new CopyOnWriteArrayList<>())
                .add(transaction);
    }

    private List<MonitoredTransaction> getAccountHistory(AccountNumber account) {
        CopyOnWriteArrayList<MonitoredTransaction> history = accountHistory.get(account.raw());
        return history != null ? List.copyOf(history) : List.of();
    }

    private void storeAlert(AmlAlert alert) {
        String key = alert.customer() != null ? alert.customer().toString() : "UNKNOWN";
        alerts.computeIfAbsent(key, k -> new ArrayList<>()).add(alert);
    }

    private AmlAlert findAlert(UUID alertId) {
        return alerts.values().stream()
                .flatMap(List::stream)
                .filter(a -> a.alertId().equals(alertId))
                .findFirst()
                .orElse(null);
    }

    private void replaceAlert(AmlAlert updated) {
        String key = updated.customer() != null ? updated.customer().toString() : "UNKNOWN";
        List<AmlAlert> customerAlerts = alerts.get(key);
        if (customerAlerts != null) {
            customerAlerts.replaceAll(a -> a.alertId().equals(updated.alertId()) ? updated : a);
        }
    }

    private void publishAlertEvent(AmlAlert alert) {
        events.publish(new AmlAlertEvent(UUID.randomUUID(), Timestamp.now(clock),
                alert.alertId(), alert.customer(), alert.typology(), alert.riskScore()));
    }
}
