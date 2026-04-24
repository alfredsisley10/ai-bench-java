package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Enforces transaction limits for consumer accounts. Implements multiple
 * constraint dimensions:
 *
 * <ul>
 *   <li><b>Regulation D:</b> Federal Reserve Reg D limits savings and money market
 *       accounts to 6 convenient withdrawals per statement cycle. Although the Fed
 *       suspended Reg D in 2020, Omnibank retains the limit as a product feature
 *       for standard savings (waived for high-yield).</li>
 *   <li><b>Daily debit limits:</b> Per-product daily aggregate debit caps to limit
 *       exposure from compromised accounts.</li>
 *   <li><b>Daily transaction count:</b> Maximum number of transactions per day
 *       to detect automated abuse patterns.</li>
 *   <li><b>Monthly aggregate limits:</b> Per-product monthly spending caps for
 *       certain account types.</li>
 *   <li><b>Velocity checks:</b> Detects rapid-fire transactions within short
 *       windows (e.g., 3+ transactions in 60 seconds).</li>
 * </ul>
 *
 * <p>The enforcer is called synchronously in the transaction authorization path.
 * It must be fast — all lookups use in-memory tracking with periodic persistence.
 */
public class TransactionLimitEnforcer {

    private static final Logger log = LoggerFactory.getLogger(TransactionLimitEnforcer.class);

    /** Reg D withdrawal limit per statement cycle (monthly). */
    private static final int REG_D_WITHDRAWAL_LIMIT = 6;

    /** Short-window velocity: max transactions within 60 seconds. */
    private static final int VELOCITY_WINDOW_SECONDS = 60;
    private static final int VELOCITY_MAX_IN_WINDOW = 3;

    /** Per-product daily and monthly limits. */
    private static final Map<ConsumerProduct, ProductLimits> PRODUCT_LIMITS;

    static {
        PRODUCT_LIMITS = new EnumMap<>(ConsumerProduct.class);
        PRODUCT_LIMITS.put(ConsumerProduct.CHECKING_BASIC, new ProductLimits(
                Money.of("5000.00", CurrencyCode.USD), 50,
                Money.of("25000.00", CurrencyCode.USD), true));
        PRODUCT_LIMITS.put(ConsumerProduct.CHECKING_PREMIUM, new ProductLimits(
                Money.of("25000.00", CurrencyCode.USD), 200,
                Money.of("100000.00", CurrencyCode.USD), true));
        PRODUCT_LIMITS.put(ConsumerProduct.SAVINGS_STANDARD, new ProductLimits(
                Money.of("10000.00", CurrencyCode.USD), 20,
                Money.of("50000.00", CurrencyCode.USD), false));
        PRODUCT_LIMITS.put(ConsumerProduct.SAVINGS_HIGH_YIELD, new ProductLimits(
                Money.of("50000.00", CurrencyCode.USD), 100,
                Money.of("250000.00", CurrencyCode.USD), true));
    }

    record ProductLimits(Money dailyDebitMax, int dailyTxnCountMax,
                         Money monthlyDebitMax, boolean regDExempt) {}

    /** Transaction types that count toward Reg D limits. */
    enum TransactionType {
        ACH_DEBIT(true), WIRE_OUT(true), ONLINE_TRANSFER(true),
        ATM_WITHDRAWAL(false), IN_PERSON_WITHDRAWAL(false),
        CHECK(true), DEBIT_CARD(true), INTERNAL_TRANSFER(true);

        private final boolean regDCountable;

        TransactionType(boolean regDCountable) {
            this.regDCountable = regDCountable;
        }

        /** Whether this transaction type counts toward the Reg D 6-per-cycle limit. */
        public boolean isRegDCountable() { return regDCountable; }
    }

    sealed interface LimitCheckResult permits
            LimitCheckResult.Approved,
            LimitCheckResult.Denied {

        record Approved(AccountNumber account, List<String> warnings) implements LimitCheckResult {}
        record Denied(AccountNumber account, String limitType,
                      String reason, Money requestedAmount) implements LimitCheckResult {}
    }

    record TransactionRecord(AccountNumber account, Money amount, TransactionType type,
                              Instant timestamp) {}

    /** In-memory transaction tracking for velocity and daily/monthly aggregation. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TransactionRecord>> recentTransactions
            = new ConcurrentHashMap<>();

    private final ConsumerAccountRepository accounts;
    private final Clock clock;

    public TransactionLimitEnforcer(ConsumerAccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Check whether a proposed transaction would violate any limits. Must be
     * called in the authorization path before the transaction is approved.
     *
     * @return Approved with optional warnings, or Denied with the specific limit violated
     */
    @Transactional(readOnly = true)
    public LimitCheckResult check(AccountNumber accountNumber, Money amount,
                                   TransactionType transactionType) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(transactionType, "transactionType");

        ConsumerAccountEntity entity = requireAccount(accountNumber);
        if (entity.status() != AccountStatus.OPEN) {
            return new LimitCheckResult.Denied(accountNumber, "ACCOUNT_STATUS",
                    "Account is not open (status: " + entity.status() + ")", amount);
        }

        List<String> warnings = new ArrayList<>();
        Instant now = Timestamp.now(clock);
        List<TransactionRecord> history = getRecentHistory(accountNumber);

        // Check 1: Velocity (rapid-fire detection)
        LimitCheckResult velocityResult = checkVelocity(accountNumber, history, now);
        if (velocityResult instanceof LimitCheckResult.Denied) {
            return velocityResult;
        }

        // Check 2: Reg D limit (savings accounts)
        if (isSubjectToRegD(entity) && transactionType.isRegDCountable()) {
            LimitCheckResult regDResult = checkRegDLimit(accountNumber, history, now);
            if (regDResult instanceof LimitCheckResult.Denied) {
                return regDResult;
            }
            if (regDResult instanceof LimitCheckResult.Approved approved
                    && !approved.warnings().isEmpty()) {
                warnings.addAll(approved.warnings());
            }
        }

        // Check 3: Daily debit limit
        ProductLimits limits = PRODUCT_LIMITS.get(entity.product());
        if (limits != null) {
            LimitCheckResult dailyResult = checkDailyDebitLimit(
                    accountNumber, amount, limits, history, now);
            if (dailyResult instanceof LimitCheckResult.Denied) {
                return dailyResult;
            }

            // Check 4: Daily transaction count
            LimitCheckResult countResult = checkDailyTransactionCount(
                    accountNumber, limits, history, now);
            if (countResult instanceof LimitCheckResult.Denied) {
                return countResult;
            }

            // Check 5: Monthly debit limit
            LimitCheckResult monthlyResult = checkMonthlyDebitLimit(
                    accountNumber, amount, limits, history, now);
            if (monthlyResult instanceof LimitCheckResult.Denied) {
                return monthlyResult;
            }
        }

        return new LimitCheckResult.Approved(accountNumber, List.copyOf(warnings));
    }

    /**
     * Record a transaction after it has been approved and settled. Updates
     * the in-memory tracking for future limit checks.
     */
    public void recordTransaction(AccountNumber accountNumber, Money amount,
                                   TransactionType type) {
        TransactionRecord record = new TransactionRecord(
                accountNumber, amount, type, Timestamp.now(clock));
        recentTransactions
                .computeIfAbsent(accountNumber.raw(), k -> new CopyOnWriteArrayList<>())
                .add(record);
    }

    private LimitCheckResult checkVelocity(AccountNumber account,
                                            List<TransactionRecord> history,
                                            Instant now) {
        Instant windowStart = now.minus(VELOCITY_WINDOW_SECONDS, ChronoUnit.SECONDS);
        long countInWindow = history.stream()
                .filter(r -> r.timestamp().isAfter(windowStart))
                .count();

        if (countInWindow >= VELOCITY_MAX_IN_WINDOW) {
            log.warn("Velocity limit exceeded for account {}: {} txns in {}s window",
                    account, countInWindow, VELOCITY_WINDOW_SECONDS);
            return new LimitCheckResult.Denied(account, "VELOCITY",
                    "Transaction velocity exceeded: %d transactions in %d seconds"
                            .formatted(countInWindow, VELOCITY_WINDOW_SECONDS),
                    Money.zero(CurrencyCode.USD));
        }
        return new LimitCheckResult.Approved(account, List.of());
    }

    private LimitCheckResult checkRegDLimit(AccountNumber account,
                                             List<TransactionRecord> history,
                                             Instant now) {
        YearMonth currentMonth = YearMonth.from(LocalDate.now(clock));
        long regDCount = history.stream()
                .filter(r -> r.type().isRegDCountable())
                .filter(r -> YearMonth.from(r.timestamp().atZone(Timestamp.BANK_ZONE)).equals(currentMonth))
                .count();

        if (regDCount >= REG_D_WITHDRAWAL_LIMIT) {
            return new LimitCheckResult.Denied(account, "REG_D",
                    "Regulation D limit reached: %d of %d withdrawals used this cycle"
                            .formatted(regDCount, REG_D_WITHDRAWAL_LIMIT),
                    Money.zero(CurrencyCode.USD));
        }

        if (regDCount >= REG_D_WITHDRAWAL_LIMIT - 1) {
            return new LimitCheckResult.Approved(account,
                    List.of("Reg D warning: this is the last withdrawal allowed this cycle"));
        }
        return new LimitCheckResult.Approved(account, List.of());
    }

    private LimitCheckResult checkDailyDebitLimit(AccountNumber account, Money amount,
                                                   ProductLimits limits,
                                                   List<TransactionRecord> history,
                                                   Instant now) {
        LocalDate today = LocalDate.now(clock);
        Money dailyTotal = aggregateDebitsForDay(history, today, amount.currency());
        Money projectedTotal = dailyTotal.plus(amount);

        if (projectedTotal.compareTo(limits.dailyDebitMax()) > 0) {
            return new LimitCheckResult.Denied(account, "DAILY_DEBIT",
                    "Daily debit limit exceeded: projected %s exceeds limit %s"
                            .formatted(projectedTotal, limits.dailyDebitMax()),
                    amount);
        }
        return new LimitCheckResult.Approved(account, List.of());
    }

    private LimitCheckResult checkDailyTransactionCount(AccountNumber account,
                                                         ProductLimits limits,
                                                         List<TransactionRecord> history,
                                                         Instant now) {
        LocalDate today = LocalDate.now(clock);
        long todayCount = history.stream()
                .filter(r -> LocalDate.ofInstant(r.timestamp(), Timestamp.BANK_ZONE).equals(today))
                .count();

        if (todayCount >= limits.dailyTxnCountMax()) {
            return new LimitCheckResult.Denied(account, "DAILY_COUNT",
                    "Daily transaction count limit reached: %d of %d"
                            .formatted(todayCount, limits.dailyTxnCountMax()),
                    Money.zero(CurrencyCode.USD));
        }
        return new LimitCheckResult.Approved(account, List.of());
    }

    private LimitCheckResult checkMonthlyDebitLimit(AccountNumber account, Money amount,
                                                     ProductLimits limits,
                                                     List<TransactionRecord> history,
                                                     Instant now) {
        YearMonth currentMonth = YearMonth.from(LocalDate.now(clock));
        Money monthlyTotal = aggregateDebitsForMonth(history, currentMonth, amount.currency());
        Money projectedTotal = monthlyTotal.plus(amount);

        if (projectedTotal.compareTo(limits.monthlyDebitMax()) > 0) {
            return new LimitCheckResult.Denied(account, "MONTHLY_DEBIT",
                    "Monthly debit limit exceeded: projected %s exceeds limit %s"
                            .formatted(projectedTotal, limits.monthlyDebitMax()),
                    amount);
        }
        return new LimitCheckResult.Approved(account, List.of());
    }

    private boolean isSubjectToRegD(ConsumerAccountEntity entity) {
        if (entity.product().kind != ConsumerProduct.Kind.SAVINGS) return false;
        ProductLimits limits = PRODUCT_LIMITS.get(entity.product());
        return limits != null && !limits.regDExempt();
    }

    private Money aggregateDebitsForDay(List<TransactionRecord> history,
                                         LocalDate day, CurrencyCode currency) {
        return history.stream()
                .filter(r -> LocalDate.ofInstant(r.timestamp(), Timestamp.BANK_ZONE).equals(day))
                .map(TransactionRecord::amount)
                .reduce(Money.zero(currency), Money::plus);
    }

    private Money aggregateDebitsForMonth(List<TransactionRecord> history,
                                           YearMonth month, CurrencyCode currency) {
        return history.stream()
                .filter(r -> YearMonth.from(r.timestamp().atZone(Timestamp.BANK_ZONE)).equals(month))
                .map(TransactionRecord::amount)
                .reduce(Money.zero(currency), Money::plus);
    }

    private List<TransactionRecord> getRecentHistory(AccountNumber account) {
        CopyOnWriteArrayList<TransactionRecord> list = recentTransactions.get(account.raw());
        return list != null ? List.copyOf(list) : List.of();
    }

    /**
     * Evict stale transaction records older than 35 days. Called periodically
     * to prevent unbounded memory growth.
     */
    public void evictStaleRecords() {
        Instant cutoff = Timestamp.now(clock).minus(35, ChronoUnit.DAYS);
        recentTransactions.forEach((key, records) -> {
            records.removeIf(r -> r.timestamp().isBefore(cutoff));
            if (records.isEmpty()) {
                recentTransactions.remove(key);
            }
        });
    }

    private ConsumerAccountEntity requireAccount(AccountNumber accountNumber) {
        return accounts.findById(accountNumber.raw())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown account: " + accountNumber));
    }
}
