package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Configurable posting rule engine that generates automated journal entries
 * based on business events and schedules. Rules are evaluated in priority
 * order and can generate:
 * <ul>
 *   <li>Auto-reversals for accrual entries on the first day of the next period</li>
 *   <li>Accrual entries at period-end for recognized but unbilled revenue/expenses</li>
 *   <li>Period-end closing entries (revenue/expense to retained earnings)</li>
 *   <li>Foreign currency revaluation entries</li>
 *   <li>Intercompany elimination entries for consolidated reporting</li>
 * </ul>
 *
 * <p>Each rule is a pure function: given the current ledger state and a trigger
 * context, it produces zero or more journal entries. The engine orchestrates
 * rule evaluation, conflict detection, and batch posting.
 */
public class PostingRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(PostingRuleEngine.class);

    private final PostingService postingService;
    private final GlAccountRepository accounts;
    private final JournalEntryRepository journals;
    private final Clock clock;

    /** Registered rules, sorted by priority on access. */
    private final CopyOnWriteArrayList<PostingRule> rules = new CopyOnWriteArrayList<>();

    public PostingRuleEngine(PostingService postingService,
                             GlAccountRepository accounts,
                             JournalEntryRepository journals,
                             Clock clock) {
        this.postingService = postingService;
        this.accounts = accounts;
        this.journals = journals;
        this.clock = clock;

        registerBuiltInRules();
    }

    // ── Rule Registration ───────────────────────────────────────────────

    /**
     * Register a custom posting rule. Rules with lower priority numbers
     * execute first. Duplicate rule IDs are rejected.
     */
    public void registerRule(PostingRule rule) {
        Objects.requireNonNull(rule, "rule");
        boolean duplicate = rules.stream().anyMatch(r -> r.ruleId().equals(rule.ruleId()));
        if (duplicate) {
            throw new IllegalArgumentException("Duplicate rule ID: " + rule.ruleId());
        }
        rules.add(rule);
        log.info("Registered posting rule: {} (priority={})", rule.ruleId(), rule.priority());
    }

    /**
     * Unregister a rule by ID. Returns true if the rule was found and removed.
     */
    public boolean unregisterRule(String ruleId) {
        return rules.removeIf(r -> r.ruleId().equals(ruleId));
    }

    // ── Execution ───────────────────────────────────────────────────────

    /**
     * Evaluate all applicable rules for the given trigger and post the
     * resulting journal entries. Returns the list of posted journals.
     * Rules that produce no entries are silently skipped.
     */
    @Transactional
    public List<PostedJournal> evaluateAndPost(RuleTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");

        List<PostingRule> sorted = rules.stream()
                .sorted(Comparator.comparingInt(PostingRule::priority))
                .toList();

        List<PostedJournal> posted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (PostingRule rule : sorted) {
            if (!rule.appliesTo(trigger)) {
                continue;
            }

            try {
                List<JournalEntry> entries = rule.evaluate(trigger);
                for (JournalEntry entry : entries) {
                    // Guard against duplicate postings from re-triggered rules
                    if (journals.findByBusinessKey(entry.businessKey()).isPresent()) {
                        log.debug("Skipping duplicate entry from rule {}: {}",
                                rule.ruleId(), entry.businessKey());
                        skipped.add(entry.businessKey());
                        continue;
                    }
                    PostedJournal result = postingService.post(entry);
                    posted.add(result);
                    log.debug("Rule {} posted journal seq={} key={}",
                            rule.ruleId(), result.sequence(), result.businessKey());
                }
            } catch (Exception e) {
                log.error("Rule {} failed for trigger {}: {}",
                        rule.ruleId(), trigger.triggerType(), e.getMessage(), e);
                // Continue with remaining rules — partial failure is acceptable
                // for independent rules, and the failed entries can be retried
            }
        }

        log.info("Rule evaluation complete: trigger={}, posted={}, skipped={}",
                trigger.triggerType(), posted.size(), skipped.size());
        return posted;
    }

    /**
     * Generate auto-reversal entries for all accruals posted in the given
     * period. The reversals are dated the first day of the next period.
     */
    @Transactional
    public List<PostedJournal> generateAutoReversals(YearMonth period, String entityCode) {
        LocalDate periodEnd = period.atEndOfMonth();
        LocalDate reversalDate = period.plusMonths(1).atDay(1);

        List<JournalEntryEntity> accruals = journals.findJournalsForAccount(
                "EXP-9800-000", period.atDay(1), periodEnd);

        List<PostedJournal> reversals = new ArrayList<>();
        for (JournalEntryEntity accrual : accruals) {
            String reversalKey = "AUTO-REV:" + accrual.businessKey();
            if (journals.findByBusinessKey(reversalKey).isPresent()) {
                continue; // Already reversed
            }

            List<PostingLine> reversedLines = new ArrayList<>();
            for (PostingLineEntity line : accrual.lines()) {
                reversedLines.add(new PostingLine(
                        new GlAccountCode(line.glAccount()),
                        line.direction().opposite(),
                        Money.of(line.amount(), line.currency()),
                        "Auto-reversal of " + accrual.businessKey()));
            }

            if (reversedLines.size() >= 2) {
                JournalEntry reversal = new JournalEntry(
                        UUID.randomUUID(),
                        reversalDate,
                        reversalKey,
                        "Auto-reversal of accrual " + accrual.businessKey(),
                        reversedLines);
                PostedJournal posted = postingService.post(reversal);
                reversals.add(posted);
            }
        }

        log.info("Generated {} auto-reversals for period {} entity {}",
                reversals.size(), period, entityCode);
        return reversals;
    }

    /**
     * Generate period-end closing entries that transfer revenue and expense
     * account balances to retained earnings.
     */
    @Transactional
    public Optional<PostedJournal> generateClosingEntries(
            YearMonth period, String entityCode, CurrencyCode currency) {

        LocalDate periodEnd = period.atEndOfMonth();
        GlAccountCode retainedEarnings = new GlAccountCode("EQU-9900-000");

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        List<GlAccountEntity> allAccounts = accounts.findAll();

        for (GlAccountEntity account : allAccounts) {
            if (account.currency() != currency || account.isClosed()) continue;

            BigDecimal balance = computePeriodBalance(account.code(), period);

            // computePeriodBalance returns debits − credits. Revenue accounts
            // have a natural credit balance, so flip the sign before
            // accumulating; expense accounts already use the debit-natural
            // convention and need no adjustment.
            if (account.type() == AccountType.REVENUE) {
                totalRevenue = totalRevenue.subtract(balance);
            } else if (account.type() == AccountType.EXPENSE) {
                totalExpense = totalExpense.add(balance);
            }
        }

        BigDecimal netIncome = totalRevenue.subtract(totalExpense);
        if (netIncome.signum() == 0) {
            log.info("No net income for period {} entity {} — no closing entry needed",
                    period, entityCode);
            return Optional.empty();
        }

        String businessKey = "CLOSE:%s:%s:%s".formatted(entityCode, period, currency);
        if (journals.findByBusinessKey(businessKey).isPresent()) {
            log.info("Closing entry already exists for {}", businessKey);
            return Optional.empty();
        }

        List<PostingLine> lines = new ArrayList<>();

        // Close revenue accounts (debit to zero them)
        for (GlAccountEntity account : allAccounts) {
            if (account.type() != AccountType.REVENUE || account.currency() != currency
                    || account.isClosed()) continue;
            BigDecimal balance = computePeriodBalance(account.code(), period);
            if (balance.signum() != 0) {
                lines.add(PostingLine.debit(
                        new GlAccountCode(account.code()),
                        Money.of(balance.abs(), currency),
                        "Period close - revenue"));
            }
        }

        // Close expense accounts (credit to zero them)
        for (GlAccountEntity account : allAccounts) {
            if (account.type() != AccountType.EXPENSE || account.currency() != currency
                    || account.isClosed()) continue;
            BigDecimal balance = computePeriodBalance(account.code(), period);
            if (balance.signum() != 0) {
                lines.add(PostingLine.credit(
                        new GlAccountCode(account.code()),
                        Money.of(balance.abs(), currency),
                        "Period close - expense"));
            }
        }

        // Net income to retained earnings
        if (netIncome.signum() > 0) {
            lines.add(PostingLine.credit(retainedEarnings,
                    Money.of(netIncome, currency),
                    "Net income to retained earnings"));
        } else {
            lines.add(PostingLine.debit(retainedEarnings,
                    Money.of(netIncome.abs(), currency),
                    "Net loss to retained earnings"));
        }

        if (lines.size() < 2) {
            return Optional.empty();
        }

        JournalEntry closingEntry = new JournalEntry(
                UUID.randomUUID(), periodEnd, businessKey,
                "Period close %s %s %s".formatted(entityCode, period, currency),
                lines);

        PostedJournal posted = postingService.post(closingEntry);
        log.info("Posted closing entry seq={} for period {} entity {} net={}",
                posted.sequence(), period, entityCode, netIncome);
        return Optional.of(posted);
    }

    // ── Built-in rules ──────────────────────────────────────────────────

    private void registerBuiltInRules() {
        // Intercompany elimination rule — generates elimination entries when
        // IC transactions are posted
        rules.add(new PostingRule() {
            @Override public String ruleId() { return "BUILTIN:IC_ELIMINATION"; }
            @Override public int priority() { return 100; }
            @Override public boolean appliesTo(RuleTrigger trigger) {
                return trigger.triggerType() == TriggerType.INTERCOMPANY_POSTING;
            }
            @Override public List<JournalEntry> evaluate(RuleTrigger trigger) {
                // Elimination logic delegated to IntercompanySettlement
                return List.of();
            }
        });

        // Accrual recognition rule — generates accrual entries at period-end
        rules.add(new PostingRule() {
            @Override public String ruleId() { return "BUILTIN:ACCRUAL_RECOGNITION"; }
            @Override public int priority() { return 200; }
            @Override public boolean appliesTo(RuleTrigger trigger) {
                return trigger.triggerType() == TriggerType.PERIOD_END;
            }
            @Override public List<JournalEntry> evaluate(RuleTrigger trigger) {
                // In production, this would scan accrual schedules and generate entries
                log.debug("Accrual recognition triggered for {}", trigger.effectiveDate());
                return List.of();
            }
        });

        // Depreciation rule
        rules.add(new PostingRule() {
            @Override public String ruleId() { return "BUILTIN:DEPRECIATION"; }
            @Override public int priority() { return 300; }
            @Override public boolean appliesTo(RuleTrigger trigger) {
                return trigger.triggerType() == TriggerType.PERIOD_END;
            }
            @Override public List<JournalEntry> evaluate(RuleTrigger trigger) {
                log.debug("Depreciation triggered for {}", trigger.effectiveDate());
                return List.of();
            }
        });
    }

    private BigDecimal computePeriodBalance(String accountCode, YearMonth period) {
        List<JournalEntryEntity> entries = journals.findJournalsForAccount(
                accountCode, period.atDay(1), period.atEndOfMonth());

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JournalEntryEntity je : entries) {
            for (PostingLineEntity line : je.lines()) {
                if (!line.glAccount().equals(accountCode)) continue;
                if (line.direction() == PostingDirection.DEBIT) {
                    debits = debits.add(line.amount());
                } else {
                    credits = credits.add(line.amount());
                }
            }
        }
        return debits.subtract(credits);
    }

    // ── Types ───────────────────────────────────────────────────────────

    /**
     * A posting rule that can generate journal entries in response to
     * business triggers.
     */
    public interface PostingRule {
        String ruleId();
        int priority();
        boolean appliesTo(RuleTrigger trigger);
        List<JournalEntry> evaluate(RuleTrigger trigger);
    }

    public enum TriggerType {
        PERIOD_END,
        PERIOD_OPEN,
        JOURNAL_POSTED,
        INTERCOMPANY_POSTING,
        FX_REVALUATION,
        MANUAL
    }

    public record RuleTrigger(
            TriggerType triggerType,
            String entityCode,
            LocalDate effectiveDate,
            CurrencyCode currency,
            String sourceReference
    ) {
        public RuleTrigger {
            Objects.requireNonNull(triggerType, "triggerType");
            Objects.requireNonNull(entityCode, "entityCode");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
        }

        public static RuleTrigger periodEnd(String entityCode, LocalDate date) {
            return new RuleTrigger(TriggerType.PERIOD_END, entityCode, date, null, null);
        }

        public static RuleTrigger manual(String entityCode, LocalDate date, String reference) {
            return new RuleTrigger(TriggerType.MANUAL, entityCode, date, null, reference);
        }
    }
}
