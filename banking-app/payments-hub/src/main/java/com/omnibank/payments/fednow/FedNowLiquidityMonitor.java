package com.omnibank.payments.fednow;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.RoutingNumber;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitors the FedNow settlement account liquidity position in real time.
 *
 * <p>FedNow settlement occurs through the Federal Reserve master account. Unlike
 * traditional batch-based payment systems, FedNow processes payments 24/7/365,
 * requiring continuous liquidity monitoring. This service:
 * <ul>
 *   <li>Tracks the real-time balance of the FedNow settlement account</li>
 *   <li>Generates tiered alerts when balance drops below configured thresholds</li>
 *   <li>Manages automated and manual funding requests to replenish the account</li>
 *   <li>Provides liquidity forecasting based on historical payment patterns</li>
 *   <li>Enforces circuit-breaker logic to pause FedNow origination when balance is critically low</li>
 * </ul>
 *
 * <p>Alert thresholds are expressed as absolute amounts, not percentages, because
 * the Fed master account balance fluctuates with all settlement activity (not just
 * FedNow). Threshold values are typically set by Treasury operations.
 */
public class FedNowLiquidityMonitor {

    private static final Logger log = LoggerFactory.getLogger(FedNowLiquidityMonitor.class);

    public enum AlertSeverity {
        INFO,
        WARNING,
        CRITICAL,
        CIRCUIT_BREAKER
    }

    public enum FundingRequestStatus {
        PENDING,
        SUBMITTED,
        CONFIRMED,
        FAILED,
        CANCELLED
    }

    public record LiquidityThreshold(
            String name,
            Money thresholdAmount,
            AlertSeverity severity,
            boolean autoFundEnabled,
            Money autoFundAmount
    ) {
        public LiquidityThreshold {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(thresholdAmount, "thresholdAmount");
            Objects.requireNonNull(severity, "severity");
            if (autoFundEnabled && autoFundAmount == null) {
                throw new IllegalArgumentException("Auto-fund amount required when auto-fund is enabled");
            }
        }
    }

    public record LiquidityAlert(
            String alertId,
            AlertSeverity severity,
            Money currentBalance,
            Money thresholdAmount,
            String message,
            Instant triggeredAt,
            boolean acknowledged
    ) {}

    public record FundingRequest(
            String requestId,
            Money amount,
            String sourceAccountRef,
            String targetMasterAccountRef,
            FundingRequestStatus status,
            Instant requestedAt,
            Instant completedAt,
            String failureReason
    ) {}

    public record LiquiditySnapshot(
            Money currentBalance,
            Money availableBalance,
            Money pendingOutflows,
            Money pendingInflows,
            int activeAlertsCount,
            boolean circuitBreakerOpen,
            Instant snapshotTime
    ) {}

    private final Clock clock;
    private final RoutingNumber bankRoutingNumber;
    private final String masterAccountRef;
    private final AtomicReference<Money> currentBalance;
    private final AtomicReference<Money> pendingOutflows;
    private final AtomicReference<Money> pendingInflows;
    private final List<LiquidityThreshold> thresholds = new ArrayList<>();
    private final Map<String, LiquidityAlert> activeAlerts = new ConcurrentHashMap<>();
    private final Map<String, FundingRequest> fundingRequests = new ConcurrentHashMap<>();
    private volatile boolean circuitBreakerOpen = false;

    public FedNowLiquidityMonitor(Clock clock, RoutingNumber bankRoutingNumber, String masterAccountRef) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bankRoutingNumber = Objects.requireNonNull(bankRoutingNumber, "bankRoutingNumber");
        this.masterAccountRef = Objects.requireNonNull(masterAccountRef, "masterAccountRef");
        this.currentBalance = new AtomicReference<>(Money.zero(CurrencyCode.USD));
        this.pendingOutflows = new AtomicReference<>(Money.zero(CurrencyCode.USD));
        this.pendingInflows = new AtomicReference<>(Money.zero(CurrencyCode.USD));

        initializeDefaultThresholds();
    }

    private void initializeDefaultThresholds() {
        thresholds.add(new LiquidityThreshold(
                "INFO_LOW", Money.of("10000000.00", CurrencyCode.USD),
                AlertSeverity.INFO, false, null));
        thresholds.add(new LiquidityThreshold(
                "WARNING_LOW", Money.of("5000000.00", CurrencyCode.USD),
                AlertSeverity.WARNING, true, Money.of("10000000.00", CurrencyCode.USD)));
        thresholds.add(new LiquidityThreshold(
                "CRITICAL_LOW", Money.of("2000000.00", CurrencyCode.USD),
                AlertSeverity.CRITICAL, true, Money.of("20000000.00", CurrencyCode.USD)));
        thresholds.add(new LiquidityThreshold(
                "CIRCUIT_BREAKER", Money.of("500000.00", CurrencyCode.USD),
                AlertSeverity.CIRCUIT_BREAKER, true, Money.of("30000000.00", CurrencyCode.USD)));
    }

    /**
     * Updates the known balance of the FedNow settlement account.
     * Called when we receive a balance notification from the Fed or after reconciliation.
     */
    public void updateBalance(Money newBalance) {
        Objects.requireNonNull(newBalance, "newBalance");
        var previousBalance = currentBalance.getAndSet(newBalance);

        log.info("FedNow settlement account balance updated: previous={}, current={}",
                previousBalance, newBalance);

        evaluateThresholds(newBalance);
    }

    /**
     * Records a pending outflow (payment being originated via FedNow).
     */
    public void recordPendingOutflow(Money amount) {
        pendingOutflows.updateAndGet(current -> current.plus(amount));
        log.debug("Pending outflow recorded: amount={}, totalPending={}", amount, pendingOutflows.get());

        // Re-evaluate thresholds with projected balance
        var projectedBalance = currentBalance.get().minus(pendingOutflows.get()).plus(pendingInflows.get());
        evaluateThresholds(projectedBalance);
    }

    /**
     * Records a pending inflow (payment being received via FedNow).
     */
    public void recordPendingInflow(Money amount) {
        pendingInflows.updateAndGet(current -> current.plus(amount));
        log.debug("Pending inflow recorded: amount={}, totalPending={}", amount, pendingInflows.get());
    }

    /**
     * Settles a previously pending outflow (deducted from pending, confirmed from balance).
     */
    public void settleOutflow(Money amount) {
        pendingOutflows.updateAndGet(current -> {
            var updated = current.minus(amount);
            return updated.isNegative() ? Money.zero(CurrencyCode.USD) : updated;
        });
        currentBalance.updateAndGet(current -> current.minus(amount));
        evaluateThresholds(currentBalance.get());
    }

    /**
     * Settles a previously pending inflow (deducted from pending, added to balance).
     */
    public void settleInflow(Money amount) {
        pendingInflows.updateAndGet(current -> {
            var updated = current.minus(amount);
            return updated.isNegative() ? Money.zero(CurrencyCode.USD) : updated;
        });
        currentBalance.updateAndGet(current -> current.plus(amount));
        evaluateThresholds(currentBalance.get());
    }

    /**
     * Checks if the circuit breaker is open (FedNow origination should be paused).
     */
    public boolean isCircuitBreakerOpen() {
        return circuitBreakerOpen;
    }

    /**
     * Returns whether a payment of the given amount can be originated given current liquidity.
     */
    public boolean canOriginate(Money amount) {
        if (circuitBreakerOpen) {
            log.warn("FedNow origination blocked by circuit breaker: requested={}", amount);
            return false;
        }
        var availableBalance = currentBalance.get().minus(pendingOutflows.get());
        return availableBalance.compareTo(amount) >= 0;
    }

    /**
     * Submits a manual funding request to replenish the FedNow settlement account.
     */
    public FundingRequest requestFunding(Money amount, String sourceAccountRef) {
        var requestId = "FUND-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var now = Timestamp.now(clock);

        var request = new FundingRequest(
                requestId, amount, sourceAccountRef, masterAccountRef,
                FundingRequestStatus.PENDING, now, null, null);

        fundingRequests.put(requestId, request);
        log.info("Funding request submitted: id={}, amount={}, source={}", requestId, amount, sourceAccountRef);
        return request;
    }

    /**
     * Confirms a funding request has been processed by the Fed.
     */
    public void confirmFunding(String requestId) {
        var existing = fundingRequests.get(requestId);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown funding request: " + requestId);
        }

        var confirmed = new FundingRequest(
                existing.requestId(), existing.amount(), existing.sourceAccountRef(),
                existing.targetMasterAccountRef(), FundingRequestStatus.CONFIRMED,
                existing.requestedAt(), Timestamp.now(clock), null);

        fundingRequests.put(requestId, confirmed);
        currentBalance.updateAndGet(current -> current.plus(existing.amount()));

        log.info("Funding confirmed: id={}, amount={}, newBalance={}",
                requestId, existing.amount(), currentBalance.get());
        evaluateThresholds(currentBalance.get());
    }

    /**
     * Returns the current liquidity snapshot for monitoring dashboards.
     */
    public LiquiditySnapshot snapshot() {
        var balance = currentBalance.get();
        var outflows = pendingOutflows.get();
        var inflows = pendingInflows.get();
        var available = balance.minus(outflows);

        return new LiquiditySnapshot(
                balance, available.isNegative() ? Money.zero(CurrencyCode.USD) : available,
                outflows, inflows,
                activeAlerts.size(), circuitBreakerOpen,
                Timestamp.now(clock));
    }

    public List<LiquidityAlert> getActiveAlerts() {
        return activeAlerts.values().stream()
                .sorted(Comparator.comparing(LiquidityAlert::triggeredAt).reversed())
                .toList();
    }

    public void acknowledgeAlert(String alertId) {
        var alert = activeAlerts.get(alertId);
        if (alert != null) {
            activeAlerts.put(alertId, new LiquidityAlert(
                    alert.alertId(), alert.severity(), alert.currentBalance(),
                    alert.thresholdAmount(), alert.message(), alert.triggeredAt(), true));
        }
    }

    public void configureThreshold(LiquidityThreshold threshold) {
        thresholds.removeIf(t -> t.name().equals(threshold.name()));
        thresholds.add(threshold);
        thresholds.sort(Comparator.comparing(t -> t.thresholdAmount().amount()));
        log.info("Threshold configured: name={}, amount={}, severity={}",
                threshold.name(), threshold.thresholdAmount(), threshold.severity());
    }

    private void evaluateThresholds(Money balanceToCheck) {
        for (var threshold : thresholds) {
            if (balanceToCheck.compareTo(threshold.thresholdAmount()) < 0) {
                triggerAlert(threshold, balanceToCheck);

                if (threshold.severity() == AlertSeverity.CIRCUIT_BREAKER && !circuitBreakerOpen) {
                    circuitBreakerOpen = true;
                    log.error("FedNow CIRCUIT BREAKER OPEN: balance={} below threshold={}",
                            balanceToCheck, threshold.thresholdAmount());
                }

                if (threshold.autoFundEnabled()) {
                    initiateAutoFunding(threshold);
                }
            } else if (threshold.severity() == AlertSeverity.CIRCUIT_BREAKER && circuitBreakerOpen) {
                circuitBreakerOpen = false;
                log.info("FedNow circuit breaker CLOSED: balance={} above threshold={}",
                        balanceToCheck, threshold.thresholdAmount());
            }
        }
    }

    private void triggerAlert(LiquidityThreshold threshold, Money currentBal) {
        var alertId = "LQALRT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var message = "FedNow settlement account balance %s is below %s threshold of %s"
                .formatted(currentBal, threshold.name(), threshold.thresholdAmount());

        var alert = new LiquidityAlert(
                alertId, threshold.severity(), currentBal, threshold.thresholdAmount(),
                message, Timestamp.now(clock), false);

        activeAlerts.put(alertId, alert);

        switch (threshold.severity()) {
            case INFO -> log.info("Liquidity alert: {}", message);
            case WARNING -> log.warn("Liquidity alert: {}", message);
            case CRITICAL, CIRCUIT_BREAKER -> log.error("Liquidity alert: {}", message);
        }
    }

    private void initiateAutoFunding(LiquidityThreshold threshold) {
        // Check if there's already a pending funding request to avoid duplicates
        boolean hasPendingFunding = fundingRequests.values().stream()
                .anyMatch(r -> r.status() == FundingRequestStatus.PENDING
                        || r.status() == FundingRequestStatus.SUBMITTED);

        if (hasPendingFunding) {
            log.info("Auto-funding skipped — pending funding request already exists");
            return;
        }

        log.info("Initiating auto-funding: threshold={}, amount={}",
                threshold.name(), threshold.autoFundAmount());
        requestFunding(threshold.autoFundAmount(), "MASTER-PRIMARY");
    }
}
