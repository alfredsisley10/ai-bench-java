package com.omnibank.ledger.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.TrialBalance;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Computes trial balance from GL accounts with support for period-end
 * adjustments, multi-currency aggregation, and multi-entity consolidation.
 *
 * <p>The engine caches per-account balance computations using Caffeine to
 * avoid repeated full-table scans when multiple reporting views need the
 * same underlying data within a short window (e.g. during period-close when
 * controllers pull the TB repeatedly while reviewing adjustments).
 *
 * <p>Cache invalidation is event-driven: any {@code JournalPostedEvent}
 * evicts the affected accounts from the cache. The TTL acts as a safety net
 * for missed events.
 */
public class TrialBalanceEngine {

    private static final Logger log = LoggerFactory.getLogger(TrialBalanceEngine.class);

    private static final int BALANCE_SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private final JournalEntryRepository journals;
    private final GlAccountRepository accounts;
    private final Supplier<Map<CurrencyCode, ExchangeRate>> rateProvider;

    /** Per-account balance cache keyed by (accountCode, asOfDate). */
    private final Cache<BalanceCacheKey, AccountBalance> balanceCache;

    /** Tracks the last invalidation timestamp per account for diagnostics. */
    private final ConcurrentHashMap<String, Instant> lastInvalidation = new ConcurrentHashMap<>();

    public TrialBalanceEngine(JournalEntryRepository journals,
                              GlAccountRepository accounts,
                              Supplier<Map<CurrencyCode, ExchangeRate>> rateProvider) {
        this.journals = journals;
        this.accounts = accounts;
        this.rateProvider = rateProvider;
        this.balanceCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Compute the trial balance for a single entity as of the given date.
     * Each currency appears as a separate section; debits and credits are
     * summed independently so the TB consumer can verify the invariant.
     */
    @Transactional(readOnly = true)
    public TrialBalance computeTrialBalance(String entityCode, LocalDate asOf) {
        Objects.requireNonNull(entityCode, "entityCode");
        Objects.requireNonNull(asOf, "asOf");

        log.debug("Computing trial balance for entity={} asOf={}", entityCode, asOf);
        long start = System.nanoTime();

        List<GlAccountEntity> entityAccounts = accounts.findAll().stream()
                .filter(a -> !a.isClosed())
                .toList();

        Map<CurrencyCode, List<TrialBalance.Row>> byCurrency = new EnumMap<>(CurrencyCode.class);

        for (GlAccountEntity account : entityAccounts) {
            AccountBalance balance = balanceCache.get(
                    new BalanceCacheKey(account.code(), asOf),
                    key -> computeAccountBalance(account, asOf));

            TrialBalance.Row row = new TrialBalance.Row(
                    new GlAccountCode(account.code()),
                    account.type(),
                    balance.totalDebits(),
                    balance.totalCredits());

            byCurrency.computeIfAbsent(account.currency(), c -> new ArrayList<>()).add(row);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("Trial balance computed for entity={} asOf={} accounts={} in {}ms",
                entityCode, asOf, entityAccounts.size(), elapsedMs);

        return new TrialBalance(asOf, byCurrency);
    }

    /**
     * Compute a consolidated trial balance across multiple entities,
     * converting all amounts to the target reporting currency.
     */
    @Transactional(readOnly = true)
    public TrialBalance computeConsolidatedTrialBalance(
            List<String> entityCodes, LocalDate asOf, CurrencyCode reportingCurrency) {

        Objects.requireNonNull(entityCodes, "entityCodes");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(reportingCurrency, "reportingCurrency");

        Map<CurrencyCode, ExchangeRate> rates = rateProvider.get();
        Map<String, ConsolidatedRow> consolidated = new HashMap<>();

        for (String entityCode : entityCodes) {
            TrialBalance entityTb = computeTrialBalance(entityCode, asOf);

            for (var entry : entityTb.byCurrency().entrySet()) {
                CurrencyCode sourceCcy = entry.getKey();
                for (TrialBalance.Row row : entry.getValue()) {
                    Money convertedDebit = convertToReporting(
                            row.debit(), sourceCcy, reportingCurrency, rates);
                    Money convertedCredit = convertToReporting(
                            row.credit(), sourceCcy, reportingCurrency, rates);

                    consolidated.merge(
                            row.account().value(),
                            new ConsolidatedRow(row.account(), row.type(), convertedDebit, convertedCredit),
                            (existing, incoming) -> new ConsolidatedRow(
                                    existing.code, existing.type,
                                    existing.debit.plus(incoming.debit),
                                    existing.credit.plus(incoming.credit)));
                }
            }
        }

        List<TrialBalance.Row> rows = consolidated.values().stream()
                .map(cr -> new TrialBalance.Row(cr.code, cr.type, cr.debit, cr.credit))
                .toList();

        return new TrialBalance(asOf, Map.of(reportingCurrency, rows));
    }

    /**
     * Apply period-end adjustments to a trial balance. Adjustments include
     * accruals, deferrals, depreciation, and reclassifications that have
     * been staged but not yet posted.
     */
    public TrialBalance applyPeriodEndAdjustments(
            TrialBalance baseTb, List<PeriodEndAdjustment> adjustments) {

        Objects.requireNonNull(baseTb, "baseTb");
        Objects.requireNonNull(adjustments, "adjustments");

        Map<CurrencyCode, List<TrialBalance.Row>> adjusted = new EnumMap<>(CurrencyCode.class);

        // Copy base rows into mutable map keyed by account code
        Map<CurrencyCode, Map<String, MutableRow>> workspace = new EnumMap<>(CurrencyCode.class);
        for (var entry : baseTb.byCurrency().entrySet()) {
            Map<String, MutableRow> accountMap = new HashMap<>();
            for (TrialBalance.Row row : entry.getValue()) {
                accountMap.put(row.account().value(),
                        new MutableRow(row.account(), row.type(),
                                row.debit().amount(), row.credit().amount(), entry.getKey()));
            }
            workspace.put(entry.getKey(), accountMap);
        }

        // Apply each adjustment
        for (PeriodEndAdjustment adj : adjustments) {
            Map<String, MutableRow> ccyWorkspace = workspace.computeIfAbsent(
                    adj.currency(), c -> new HashMap<>());

            applyAdjustmentToAccount(ccyWorkspace, adj.debitAccount(), adj.currency(),
                    adj.amount(), BigDecimal.ZERO, adj.debitAccountType());
            applyAdjustmentToAccount(ccyWorkspace, adj.creditAccount(), adj.currency(),
                    BigDecimal.ZERO, adj.amount(), adj.creditAccountType());
        }

        // Convert back to immutable rows
        for (var entry : workspace.entrySet()) {
            List<TrialBalance.Row> rows = entry.getValue().values().stream()
                    .map(mr -> new TrialBalance.Row(
                            mr.code,
                            mr.type,
                            Money.of(mr.debits, entry.getKey()),
                            Money.of(mr.credits, entry.getKey())))
                    .toList();
            adjusted.put(entry.getKey(), rows);
        }

        return new TrialBalance(baseTb.asOf(), adjusted);
    }

    /**
     * Invalidate cached balances for accounts affected by a new journal posting.
     * Called by the event listener on {@code JournalPostedEvent}.
     */
    public void invalidateAccounts(List<String> accountCodes) {
        Instant now = Instant.now();
        for (String code : accountCodes) {
            // Evict all date variants — cache key includes date
            balanceCache.asMap().keySet().removeIf(k -> k.accountCode.equals(code));
            lastInvalidation.put(code, now);
        }
        log.debug("Invalidated cache for {} accounts", accountCodes.size());
    }

    // ── Internals ───────────────────────────────────────────────────────

    private AccountBalance computeAccountBalance(GlAccountEntity account, LocalDate asOf) {
        List<JournalEntryEntity> affecting = journals.findJournalsForAccount(
                account.code(), LocalDate.of(1900, 1, 1), asOf);

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        int transactionCount = 0;

        for (JournalEntryEntity je : affecting) {
            for (PostingLineEntity line : je.lines()) {
                if (!line.glAccount().equals(account.code())) continue;
                transactionCount++;
                if (line.direction() == PostingDirection.DEBIT) {
                    totalDebits = totalDebits.add(line.amount());
                } else {
                    totalCredits = totalCredits.add(line.amount());
                }
            }
        }

        return new AccountBalance(
                Money.of(totalDebits, account.currency()),
                Money.of(totalCredits, account.currency()),
                transactionCount);
    }

    private Money convertToReporting(Money amount, CurrencyCode sourceCcy,
                                     CurrencyCode targetCcy,
                                     Map<CurrencyCode, ExchangeRate> rates) {
        if (sourceCcy == targetCcy) {
            return amount;
        }
        ExchangeRate rate = rates.get(sourceCcy);
        if (rate == null) {
            throw new IllegalStateException(
                    "No exchange rate available for " + sourceCcy + " -> " + targetCcy);
        }
        BigDecimal converted = amount.amount().multiply(rate.rate())
                .setScale(targetCcy.minorUnits(), ROUNDING);
        return Money.of(converted, targetCcy);
    }

    private void applyAdjustmentToAccount(Map<String, MutableRow> workspace,
                                          GlAccountCode account, CurrencyCode currency,
                                          BigDecimal debitAdj, BigDecimal creditAdj,
                                          AccountType type) {
        workspace.compute(account.value(), (code, existing) -> {
            if (existing == null) {
                return new MutableRow(account, type, debitAdj, creditAdj, currency);
            }
            existing.debits = existing.debits.add(debitAdj);
            existing.credits = existing.credits.add(creditAdj);
            return existing;
        });
    }

    // ── Inner types ─────────────────────────────────────────────────────

    private record BalanceCacheKey(String accountCode, LocalDate asOf) {}

    private record AccountBalance(Money totalDebits, Money totalCredits, int transactionCount) {}

    private record ConsolidatedRow(GlAccountCode code, AccountType type, Money debit, Money credit) {}

    private static final class MutableRow {
        final GlAccountCode code;
        final AccountType type;
        BigDecimal debits;
        BigDecimal credits;
        final CurrencyCode currency;

        MutableRow(GlAccountCode code, AccountType type,
                   BigDecimal debits, BigDecimal credits, CurrencyCode currency) {
            this.code = code;
            this.type = type;
            this.debits = debits;
            this.credits = credits;
            this.currency = currency;
        }
    }

    /**
     * Represents a period-end adjustment that has been staged for
     * application to the trial balance.
     */
    public record PeriodEndAdjustment(
            String adjustmentId,
            GlAccountCode debitAccount,
            AccountType debitAccountType,
            GlAccountCode creditAccount,
            AccountType creditAccountType,
            BigDecimal amount,
            CurrencyCode currency,
            String description
    ) {
        public PeriodEndAdjustment {
            Objects.requireNonNull(adjustmentId, "adjustmentId");
            Objects.requireNonNull(debitAccount, "debitAccount");
            Objects.requireNonNull(creditAccount, "creditAccount");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Adjustment amount must be positive: " + amount);
            }
        }
    }
}
