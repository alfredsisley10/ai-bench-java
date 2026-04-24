package com.omnibank.treasury.internal;

import com.omnibank.shared.domain.AccountNumber;
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
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Enterprise cash position management for the bank's own accounts. Tracks
 * real-time positions across the bank's nostro (accounts at other banks),
 * vostro (other banks' accounts at us), Fed reserve, and internal GL accounts.
 *
 * <p>Key functions:
 * <ul>
 *   <li><b>Intraday position tracking:</b> Real-time updates from payment
 *       settlement, FX trades, and interbank transfers</li>
 *   <li><b>Nostro/vostro reconciliation:</b> Expected vs. actual balances at
 *       correspondent banks</li>
 *   <li><b>Liquidity forecasting:</b> Project cash needs for upcoming settlement
 *       windows based on scheduled payments and historical patterns</li>
 *   <li><b>Threshold alerts:</b> Notify treasury when positions breach minimum
 *       or maximum thresholds (regulatory buffers, investment opportunity)</li>
 *   <li><b>Multi-currency consolidation:</b> Aggregate positions across all
 *       currencies into USD-equivalent for enterprise liquidity view</li>
 * </ul>
 *
 * <p>Positions are tracked per-currency and per-account. Intraday updates
 * are applied in real-time; end-of-day reconciliation runs as a batch.
 */
public class CashPositionManager {

    private static final Logger log = LoggerFactory.getLogger(CashPositionManager.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    /** Liquidity buffer: minimum USD position required by internal policy. */
    private static final Money MIN_USD_POSITION = Money.of("50000000.00", CurrencyCode.USD);

    /** Maximum idle cash before sweep to investment portfolio is recommended. */
    private static final Money MAX_IDLE_USD = Money.of("500000000.00", CurrencyCode.USD);

    /** Fed wire cutoff times (ET). */
    private static final LocalTime FED_WIRE_CUTOFF = LocalTime.of(18, 0);
    private static final LocalTime CHIPS_CUTOFF = LocalTime.of(17, 0);

    enum AccountCategory {
        FED_RESERVE,    // Federal Reserve Bank account
        NOSTRO,         // Our account at another bank
        VOSTRO,         // Another bank's account at us
        INTERNAL_GL,    // Internal general ledger
        INVESTMENT      // Short-term investment / money market
    }

    record CashPosition(
            String positionId,
            AccountCategory category,
            CurrencyCode currency,
            String counterpartyBankName,
            Money openingBalance,
            Money currentBalance,
            Money projectedEndOfDay,
            Money pendingInflows,
            Money pendingOutflows,
            LocalDate positionDate,
            Instant lastUpdated
    ) {
        public CashPosition {
            Objects.requireNonNull(positionId, "positionId");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(currentBalance, "currentBalance");
        }

        public Money netPending() {
            Money inflows = pendingInflows != null ? pendingInflows : Money.zero(currency);
            Money outflows = pendingOutflows != null ? pendingOutflows : Money.zero(currency);
            return inflows.minus(outflows);
        }
    }

    record IntradayMovement(
            UUID movementId,
            String positionId,
            Money amount,
            String direction,   // INFLOW or OUTFLOW
            String source,      // WIRE, ACH, FX, INTERNAL
            String reference,
            Instant timestamp
    ) {
        public IntradayMovement {
            Objects.requireNonNull(movementId, "movementId");
            Objects.requireNonNull(positionId, "positionId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(direction, "direction");
        }
    }

    record LiquidityForecast(
            LocalDate forecastDate,
            CurrencyCode currency,
            Money projectedOpening,
            Money expectedInflows,
            Money expectedOutflows,
            Money projectedClosing,
            Money liquidityGap,
            String recommendation
    ) {}

    record ConsolidatedPosition(
            LocalDate positionDate,
            Map<CurrencyCode, Money> positionsByCurrency,
            Money totalUsdEquivalent,
            boolean meetsLiquidityThreshold,
            boolean exceedsIdleCashThreshold,
            Instant computedAt
    ) {}

    sealed interface PositionAlert permits
            PositionAlert.LiquidityWarning,
            PositionAlert.ExcessCash,
            PositionAlert.ReconciliationBreak,
            PositionAlert.SettlementWindowApproaching {

        record LiquidityWarning(CurrencyCode currency, Money currentPosition,
                                 Money minimumRequired, Money shortfall) implements PositionAlert {}
        record ExcessCash(CurrencyCode currency, Money currentPosition,
                          Money threshold, Money excess) implements PositionAlert {}
        record ReconciliationBreak(String positionId, Money expectedBalance,
                                    Money actualBalance, Money difference) implements PositionAlert {}
        record SettlementWindowApproaching(String window, LocalTime cutoff,
                                            Duration timeRemaining,
                                            Money unsettledAmount) implements PositionAlert {}
    }

    record PositionEvent(UUID eventId, Instant occurredAt, String positionId,
                          String eventName, Money amount) implements DomainEvent {
        @Override
        public String eventType() {
            return "treasury.cash_position." + eventName;
        }
    }

    /** Real-time position store. */
    private final ConcurrentHashMap<String, CashPosition> positions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<IntradayMovement>> intradayMovements
            = new ConcurrentHashMap<>();

    private final EventBus events;
    private final Clock clock;

    public CashPositionManager(EventBus events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Initialize today's positions from prior day closing balances.
     * Called at start of business day (06:00 ET).
     */
    @Scheduled(cron = "0 0 6 * * MON-FRI", zone = "America/New_York")
    public void initializeDailyPositions() {
        LocalDate today = LocalDate.now(clock);
        log.info("Initializing cash positions for {}", today);

        for (CashPosition pos : positions.values()) {
            Money opening = pos.currentBalance();
            CashPosition reset = new CashPosition(
                    pos.positionId(), pos.category(), pos.currency(),
                    pos.counterpartyBankName(), opening, opening, opening,
                    Money.zero(pos.currency()), Money.zero(pos.currency()),
                    today, Timestamp.now(clock)
            );
            positions.put(pos.positionId(), reset);
        }

        intradayMovements.clear();
        log.info("Daily positions initialized: {} accounts", positions.size());
    }

    /**
     * Record an intraday cash movement and update the position.
     */
    public CashPosition recordMovement(IntradayMovement movement) {
        Objects.requireNonNull(movement, "movement");

        CashPosition position = positions.get(movement.positionId());
        if (position == null) {
            throw new IllegalArgumentException("Unknown position: " + movement.positionId());
        }

        Money adjustedBalance;
        Money pendingInflows = position.pendingInflows();
        Money pendingOutflows = position.pendingOutflows();

        if ("INFLOW".equalsIgnoreCase(movement.direction())) {
            adjustedBalance = position.currentBalance().plus(movement.amount());
            pendingInflows = pendingInflows.plus(movement.amount());
        } else {
            adjustedBalance = position.currentBalance().minus(movement.amount());
            pendingOutflows = pendingOutflows.plus(movement.amount());
        }

        CashPosition updated = new CashPosition(
                position.positionId(), position.category(), position.currency(),
                position.counterpartyBankName(), position.openingBalance(),
                adjustedBalance, position.projectedEndOfDay(),
                pendingInflows, pendingOutflows,
                position.positionDate(), Timestamp.now(clock)
        );

        positions.put(position.positionId(), updated);
        intradayMovements
                .computeIfAbsent(movement.positionId(), k -> new ArrayList<>())
                .add(movement);

        publishEvent(position.positionId(), "movement_recorded", movement.amount());
        return updated;
    }

    /**
     * Register a new cash position account for tracking.
     */
    public CashPosition registerPosition(String positionId, AccountCategory category,
                                          CurrencyCode currency, String counterpartyBankName,
                                          Money initialBalance) {
        CashPosition position = new CashPosition(
                positionId, category, currency, counterpartyBankName,
                initialBalance, initialBalance, initialBalance,
                Money.zero(currency), Money.zero(currency),
                LocalDate.now(clock), Timestamp.now(clock)
        );
        positions.put(positionId, position);
        log.info("Registered position: id={}, category={}, currency={}, bank={}",
                positionId, category, currency, counterpartyBankName);
        return position;
    }

    /**
     * Compute consolidated position across all currencies. Converts non-USD
     * positions to USD equivalent for enterprise-wide liquidity view.
     */
    public ConsolidatedPosition getConsolidatedPosition(Map<CurrencyCode, BigDecimal> fxRates) {
        Map<CurrencyCode, Money> byCurrency = positions.values().stream()
                .collect(Collectors.groupingBy(
                        CashPosition::currency,
                        () -> new EnumMap<>(CurrencyCode.class),
                        Collectors.reducing(
                                null,
                                CashPosition::currentBalance,
                                (a, b) -> a == null ? b : a.plus(b)
                        )
                ));

        Money totalUsd = Money.zero(CurrencyCode.USD);
        for (var entry : byCurrency.entrySet()) {
            if (entry.getValue() == null) continue;
            if (entry.getKey() == CurrencyCode.USD) {
                totalUsd = totalUsd.plus(entry.getValue());
            } else {
                BigDecimal rate = fxRates.getOrDefault(entry.getKey(), BigDecimal.ONE);
                Money usdEquiv = entry.getValue().times(rate);
                totalUsd = totalUsd.plus(Money.of(usdEquiv.amount(), CurrencyCode.USD));
            }
        }

        boolean meetsLiquidity = totalUsd.compareTo(MIN_USD_POSITION) >= 0;
        boolean excessCash = totalUsd.compareTo(MAX_IDLE_USD) > 0;

        return new ConsolidatedPosition(LocalDate.now(clock), byCurrency,
                totalUsd, meetsLiquidity, excessCash, Timestamp.now(clock));
    }

    /**
     * Generate a 5-day liquidity forecast for a specific currency.
     */
    public List<LiquidityForecast> forecastLiquidity(CurrencyCode currency, int forecastDays) {
        Money currentPosition = positions.values().stream()
                .filter(p -> p.currency() == currency)
                .map(CashPosition::currentBalance)
                .reduce(Money.zero(currency), Money::plus);

        List<LiquidityForecast> forecasts = new ArrayList<>();
        Money runningBalance = currentPosition;

        for (int day = 1; day <= forecastDays; day++) {
            LocalDate forecastDate = LocalDate.now(clock).plusDays(day);

            // Simplified forecasting based on historical averages
            Money expectedInflows = estimateInflows(currency, forecastDate);
            Money expectedOutflows = estimateOutflows(currency, forecastDate);
            Money projectedClosing = runningBalance.plus(expectedInflows).minus(expectedOutflows);
            Money gap = projectedClosing.minus(MIN_USD_POSITION);

            String recommendation;
            if (gap.isNegative()) {
                recommendation = "BORROW: Liquidity shortfall of " + gap.negate()
                        + " projected. Arrange interbank borrowing.";
            } else if (projectedClosing.compareTo(MAX_IDLE_USD) > 0) {
                recommendation = "INVEST: Excess liquidity of "
                        + projectedClosing.minus(MAX_IDLE_USD)
                        + ". Consider overnight repo or T-bill placement.";
            } else {
                recommendation = "MAINTAIN: Position within target range.";
            }

            forecasts.add(new LiquidityForecast(forecastDate, currency,
                    runningBalance, expectedInflows, expectedOutflows,
                    projectedClosing, gap, recommendation));

            runningBalance = projectedClosing;
        }

        return forecasts;
    }

    /**
     * Check all positions against thresholds and generate alerts.
     * Runs every 30 minutes during business hours.
     */
    @Scheduled(cron = "0 0/30 6-18 * * MON-FRI", zone = "America/New_York")
    public List<PositionAlert> checkThresholds() {
        List<PositionAlert> alerts = new ArrayList<>();
        Instant now = Timestamp.now(clock);

        // Liquidity check
        Money totalUsd = positions.values().stream()
                .filter(p -> p.currency() == CurrencyCode.USD)
                .map(CashPosition::currentBalance)
                .reduce(Money.zero(CurrencyCode.USD), Money::plus);

        if (totalUsd.compareTo(MIN_USD_POSITION) < 0) {
            Money shortfall = MIN_USD_POSITION.minus(totalUsd);
            alerts.add(new PositionAlert.LiquidityWarning(
                    CurrencyCode.USD, totalUsd, MIN_USD_POSITION, shortfall));
            log.warn("LIQUIDITY WARNING: USD position {} below minimum {}, shortfall {}",
                    totalUsd, MIN_USD_POSITION, shortfall);
        }

        if (totalUsd.compareTo(MAX_IDLE_USD) > 0) {
            Money excess = totalUsd.minus(MAX_IDLE_USD);
            alerts.add(new PositionAlert.ExcessCash(
                    CurrencyCode.USD, totalUsd, MAX_IDLE_USD, excess));
        }

        // Settlement window check
        ZonedDateTime nowEt = now.atZone(Timestamp.BANK_ZONE);
        Duration toFedCutoff = Duration.between(nowEt.toLocalTime(), FED_WIRE_CUTOFF);
        if (!toFedCutoff.isNegative() && toFedCutoff.toMinutes() <= 60) {
            Money unsettled = computeUnsettledAmount(CurrencyCode.USD);
            if (unsettled.isPositive()) {
                alerts.add(new PositionAlert.SettlementWindowApproaching(
                        "FedWire", FED_WIRE_CUTOFF, toFedCutoff, unsettled));
            }
        }

        return alerts;
    }

    /**
     * Get all positions for a specific currency.
     */
    public List<CashPosition> getPositionsByCurrency(CurrencyCode currency) {
        return positions.values().stream()
                .filter(p -> p.currency() == currency)
                .sorted(Comparator.comparing(CashPosition::category))
                .toList();
    }

    /**
     * Get all nostro positions (our accounts at other banks).
     */
    public List<CashPosition> getNostroPositions() {
        return positions.values().stream()
                .filter(p -> p.category() == AccountCategory.NOSTRO)
                .sorted(Comparator.comparing(CashPosition::counterpartyBankName))
                .toList();
    }

    private Money estimateInflows(CurrencyCode currency, LocalDate date) {
        // Simplified: historical average daily inflow estimate
        return switch (currency) {
            case USD -> Money.of("75000000.00", CurrencyCode.USD);
            case EUR -> Money.of("15000000.00", CurrencyCode.EUR);
            case GBP -> Money.of("8000000.00", CurrencyCode.GBP);
            default -> Money.of("5000000.00", currency);
        };
    }

    private Money estimateOutflows(CurrencyCode currency, LocalDate date) {
        return switch (currency) {
            case USD -> Money.of("70000000.00", CurrencyCode.USD);
            case EUR -> Money.of("14000000.00", CurrencyCode.EUR);
            case GBP -> Money.of("7500000.00", CurrencyCode.GBP);
            default -> Money.of("4800000.00", currency);
        };
    }

    private Money computeUnsettledAmount(CurrencyCode currency) {
        return positions.values().stream()
                .filter(p -> p.currency() == currency)
                .map(CashPosition::netPending)
                .filter(Money::isPositive)
                .reduce(Money.zero(currency), Money::plus);
    }

    private void publishEvent(String positionId, String eventName, Money amount) {
        events.publish(new PositionEvent(UUID.randomUUID(), Timestamp.now(clock),
                positionId, eventName, amount));
    }
}
