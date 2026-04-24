package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Performs unrealized gain/loss calculations for foreign currency denominated
 * positions. At each revaluation date (typically month-end), the engine:
 *
 * <ol>
 *   <li>Identifies all GL accounts denominated in a foreign currency
 *       (i.e., not the entity's functional/reporting currency)</li>
 *   <li>Retrieves the current spot rate for each foreign currency</li>
 *   <li>Computes the functional-currency equivalent of each account's
 *       foreign-currency balance at the new spot rate</li>
 *   <li>Compares this to the previously booked functional-currency amount
 *       (the "historical rate equivalent")</li>
 *   <li>Posts the difference as an unrealized FX gain or loss</li>
 * </ol>
 *
 * <p>Revaluation entries are auto-reversible: they reverse on the first day
 * of the next period so that realized gains/losses are computed cleanly
 * when the position actually settles.
 *
 * <p>The engine handles the subtlety that asset accounts with a debit-normal
 * balance produce an unrealized gain when the foreign currency strengthens,
 * while liability accounts (credit-normal) produce an unrealized loss in
 * the same scenario.
 */
public class CurrencyRevaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(CurrencyRevaluationEngine.class);

    private static final int RATE_SCALE = 12;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    /** GL account for unrealized FX gains (credit-normal). */
    private static final GlAccountCode FX_GAIN_ACCOUNT = new GlAccountCode("REV-4900-001");

    /** GL account for unrealized FX losses (debit-normal). */
    private static final GlAccountCode FX_LOSS_ACCOUNT = new GlAccountCode("EXP-7900-001");

    /** GL account for the FX revaluation reserve (balance sheet). */
    private static final GlAccountCode FX_REVAL_RESERVE = new GlAccountCode("EQU-3500-001");

    private final PostingService postingService;
    private final GlAccountRepository accounts;
    private final JournalEntryRepository journals;
    private final Supplier<Map<CurrencyCode, ExchangeRate>> rateProvider;
    private final Clock clock;

    /** Tracks the last revaluation rate used per account for delta computation. */
    private final ConcurrentHashMap<String, RateSnapshot> lastRevalRates = new ConcurrentHashMap<>();

    /** Stores revaluation run results for audit/reporting. */
    private final ConcurrentHashMap<String, RevaluationRunResult> runHistory = new ConcurrentHashMap<>();

    public CurrencyRevaluationEngine(PostingService postingService,
                                     GlAccountRepository accounts,
                                     JournalEntryRepository journals,
                                     Supplier<Map<CurrencyCode, ExchangeRate>> rateProvider,
                                     Clock clock) {
        this.postingService = postingService;
        this.accounts = accounts;
        this.journals = journals;
        this.rateProvider = rateProvider;
        this.clock = clock;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Run the full revaluation for an entity as of the given date.
     * Returns the list of revaluation journal entries posted.
     */
    @Transactional
    public RevaluationRunResult runRevaluation(
            String entityCode, LocalDate revalDate, CurrencyCode functionalCurrency) {

        Objects.requireNonNull(entityCode, "entityCode");
        Objects.requireNonNull(revalDate, "revalDate");
        Objects.requireNonNull(functionalCurrency, "functionalCurrency");

        log.info("Starting FX revaluation for entity={} date={} functional={}",
                entityCode, revalDate, functionalCurrency);
        long startNanos = System.nanoTime();

        Map<CurrencyCode, ExchangeRate> spotRates = rateProvider.get();
        List<AccountRevaluation> accountResults = new ArrayList<>();
        List<PostedJournal> postedJournals = new ArrayList<>();
        Money totalGain = Money.zero(functionalCurrency);
        Money totalLoss = Money.zero(functionalCurrency);

        // Find all foreign-currency accounts
        List<GlAccountEntity> foreignAccounts = accounts.findAll().stream()
                .filter(a -> !a.isClosed())
                .filter(a -> a.currency() != functionalCurrency)
                .toList();

        for (GlAccountEntity account : foreignAccounts) {
            CurrencyCode foreignCcy = account.currency();
            ExchangeRate spotRate = spotRates.get(foreignCcy);

            if (spotRate == null) {
                log.warn("No spot rate available for {} — skipping account {}",
                        foreignCcy, account.code());
                continue;
            }

            // Compute the foreign-currency balance
            Money foreignBalance = computeBalance(account, revalDate);
            if (foreignBalance.isZero()) continue;

            // Compute functional-currency equivalent at current spot rate
            Money currentFunctional = convertAtRate(foreignBalance, spotRate, functionalCurrency);

            // Determine the historical functional-currency equivalent
            Money historicalFunctional = computeHistoricalFunctional(
                    account, foreignBalance, functionalCurrency);

            // The revaluation P&L impact
            Money revalPnl = currentFunctional.minus(historicalFunctional);

            if (revalPnl.isZero()) {
                accountResults.add(new AccountRevaluation(
                        new GlAccountCode(account.code()), account.type(),
                        foreignCcy, foreignBalance, historicalFunctional,
                        currentFunctional, revalPnl, spotRate.rate(), false));
                continue;
            }

            // Adjust for normal balance direction:
            // Asset with debit-normal: FX strengthening = gain
            // Liability with credit-normal: FX strengthening = loss
            boolean isGain = isUnrealizedGain(account.type(), revalPnl);

            // Post the revaluation journal entry
            Optional<PostedJournal> posted = postRevaluationEntry(
                    account, revalPnl, isGain, revalDate, entityCode, functionalCurrency);

            posted.ifPresent(postedJournals::add);

            if (isGain) {
                totalGain = totalGain.plus(revalPnl.abs());
            } else {
                totalLoss = totalLoss.plus(revalPnl.abs());
            }

            // Update last reval rate
            lastRevalRates.put(account.code(),
                    new RateSnapshot(spotRate.rate(), revalDate, Instant.now(clock)));

            accountResults.add(new AccountRevaluation(
                    new GlAccountCode(account.code()), account.type(),
                    foreignCcy, foreignBalance, historicalFunctional,
                    currentFunctional, revalPnl, spotRate.rate(), true));
        }

        Money netPnl = totalGain.minus(totalLoss);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        RevaluationRunResult result = new RevaluationRunResult(
                entityCode, revalDate, functionalCurrency,
                accountResults, postedJournals,
                totalGain, totalLoss, netPnl,
                foreignAccounts.size(), postedJournals.size(),
                elapsedMs);

        String runKey = "%s:%s".formatted(entityCode, revalDate);
        runHistory.put(runKey, result);

        log.info("FX revaluation complete: entity={} date={} accounts={} journals={} " +
                        "gain={} loss={} net={} in {}ms",
                entityCode, revalDate, foreignAccounts.size(), postedJournals.size(),
                totalGain, totalLoss, netPnl, elapsedMs);

        return result;
    }

    /**
     * Retrieve the result of a previous revaluation run.
     */
    public Optional<RevaluationRunResult> getRunResult(String entityCode, LocalDate revalDate) {
        return Optional.ofNullable(runHistory.get("%s:%s".formatted(entityCode, revalDate)));
    }

    /**
     * Preview the revaluation without posting. Useful for controllers
     * to review the P&L impact before committing.
     */
    @Transactional(readOnly = true)
    public List<AccountRevaluation> preview(
            String entityCode, LocalDate revalDate, CurrencyCode functionalCurrency) {

        Map<CurrencyCode, ExchangeRate> spotRates = rateProvider.get();
        List<AccountRevaluation> previews = new ArrayList<>();

        List<GlAccountEntity> foreignAccounts = accounts.findAll().stream()
                .filter(a -> !a.isClosed())
                .filter(a -> a.currency() != functionalCurrency)
                .toList();

        for (GlAccountEntity account : foreignAccounts) {
            ExchangeRate spotRate = spotRates.get(account.currency());
            if (spotRate == null) continue;

            Money foreignBalance = computeBalance(account, revalDate);
            if (foreignBalance.isZero()) continue;

            Money currentFunctional = convertAtRate(foreignBalance, spotRate, functionalCurrency);
            Money historicalFunctional = computeHistoricalFunctional(
                    account, foreignBalance, functionalCurrency);
            Money revalPnl = currentFunctional.minus(historicalFunctional);

            previews.add(new AccountRevaluation(
                    new GlAccountCode(account.code()), account.type(),
                    account.currency(), foreignBalance, historicalFunctional,
                    currentFunctional, revalPnl, spotRate.rate(), false));
        }

        return previews;
    }

    // ── Internals ───────────────────────────────────────────────────────

    private Money computeBalance(GlAccountEntity account, LocalDate asOf) {
        List<JournalEntryEntity> affecting = journals.findJournalsForAccount(
                account.code(), LocalDate.of(1900, 1, 1), asOf);

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;

        for (JournalEntryEntity je : affecting) {
            for (PostingLineEntity line : je.lines()) {
                if (!line.glAccount().equals(account.code())) continue;
                if (line.direction() == PostingDirection.DEBIT) {
                    debits = debits.add(line.amount());
                } else {
                    credits = credits.add(line.amount());
                }
            }
        }

        BigDecimal net = (account.type().normalBalance() == AccountType.NormalBalance.DEBIT)
                ? debits.subtract(credits)
                : credits.subtract(debits);

        return Money.of(net, account.currency());
    }

    private Money convertAtRate(Money amount, ExchangeRate rate, CurrencyCode targetCcy) {
        BigDecimal converted = amount.amount().multiply(rate.rate())
                .setScale(targetCcy.minorUnits(), ROUNDING);
        return Money.of(converted, targetCcy);
    }

    private Money computeHistoricalFunctional(GlAccountEntity account,
                                               Money foreignBalance,
                                               CurrencyCode functionalCurrency) {
        // Use the last revaluation rate if available, otherwise use 1:1
        // (which means the first revaluation captures the full translation effect)
        RateSnapshot lastRate = lastRevalRates.get(account.code());
        if (lastRate != null) {
            BigDecimal historical = foreignBalance.amount()
                    .multiply(lastRate.rate)
                    .setScale(functionalCurrency.minorUnits(), ROUNDING);
            return Money.of(historical, functionalCurrency);
        }

        // First revaluation — assume initial booking was at par
        // In production, this would use the weighted-average booking rate
        return Money.of(foreignBalance.amount(), functionalCurrency);
    }

    private boolean isUnrealizedGain(AccountType type, Money revalPnl) {
        // For debit-normal accounts (assets, expenses):
        //   positive revalPnl = gain (foreign currency strengthened)
        // For credit-normal accounts (liabilities, equity, revenue):
        //   positive revalPnl = loss (we owe more in functional terms)
        if (type.normalBalance() == AccountType.NormalBalance.DEBIT) {
            return revalPnl.isPositive();
        } else {
            return revalPnl.isNegative();
        }
    }

    private Optional<PostedJournal> postRevaluationEntry(
            GlAccountEntity account, Money revalPnl, boolean isGain,
            LocalDate revalDate, String entityCode, CurrencyCode functionalCurrency) {

        String businessKey = "REVAL:%s:%s:%s".formatted(
                entityCode, account.code(), revalDate);

        // Check for duplicate
        if (journals.findByBusinessKey(businessKey).isPresent()) {
            log.debug("Revaluation entry already exists for {}", businessKey);
            return Optional.empty();
        }

        Money absAmount = revalPnl.abs();
        // We need the entry in the functional currency, not the foreign currency
        Money functionalAmount = Money.of(absAmount.amount(), functionalCurrency);

        List<PostingLine> lines;
        if (isGain) {
            // Debit the account's revaluation adjustment, credit FX gain
            lines = List.of(
                    PostingLine.debit(FX_REVAL_RESERVE, functionalAmount,
                            "FX reval adjustment " + account.code()),
                    PostingLine.credit(FX_GAIN_ACCOUNT, functionalAmount,
                            "Unrealized FX gain on " + account.code()));
        } else {
            // Debit FX loss, credit the account's revaluation adjustment
            lines = List.of(
                    PostingLine.debit(FX_LOSS_ACCOUNT, functionalAmount,
                            "Unrealized FX loss on " + account.code()),
                    PostingLine.credit(FX_REVAL_RESERVE, functionalAmount,
                            "FX reval adjustment " + account.code()));
        }

        JournalEntry entry = new JournalEntry(
                UUID.randomUUID(), revalDate, businessKey,
                "FX revaluation %s %s rate=%s".formatted(
                        account.code(), account.currency(),
                        lastRevalRates.containsKey(account.code())
                                ? lastRevalRates.get(account.code()).rate : "initial"),
                lines);

        PostedJournal posted = postingService.post(entry);
        log.debug("Posted revaluation entry seq={} for account {} amount={} gain={}",
                posted.sequence(), account.code(), absAmount, isGain);
        return Optional.of(posted);
    }

    // ── Types ───────────────────────────────────────────────────────────

    private record RateSnapshot(BigDecimal rate, LocalDate asOf, Instant capturedAt) {}

    /**
     * Per-account revaluation detail.
     */
    public record AccountRevaluation(
            GlAccountCode account,
            AccountType accountType,
            CurrencyCode foreignCurrency,
            Money foreignBalance,
            Money historicalFunctional,
            Money currentFunctional,
            Money revaluationPnl,
            BigDecimal spotRate,
            boolean entryPosted
    ) {
        public boolean isGain() {
            return revaluationPnl.isPositive()
                    && accountType.normalBalance() == AccountType.NormalBalance.DEBIT
                    || revaluationPnl.isNegative()
                    && accountType.normalBalance() == AccountType.NormalBalance.CREDIT;
        }
    }

    /**
     * Summary of a complete revaluation run.
     */
    public record RevaluationRunResult(
            String entityCode,
            LocalDate revaluationDate,
            CurrencyCode functionalCurrency,
            List<AccountRevaluation> accountResults,
            List<PostedJournal> postedJournals,
            Money totalUnrealizedGain,
            Money totalUnrealizedLoss,
            Money netPnl,
            int accountsEvaluated,
            int entriesPosted,
            long executionTimeMs
    ) {
        public RevaluationRunResult {
            Objects.requireNonNull(entityCode, "entityCode");
            Objects.requireNonNull(revaluationDate, "revaluationDate");
            Objects.requireNonNull(functionalCurrency, "functionalCurrency");
            accountResults = List.copyOf(accountResults);
            postedJournals = List.copyOf(postedJournals);
        }
    }
}
