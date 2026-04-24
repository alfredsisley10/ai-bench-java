package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.PostingDirection;
import com.omnibank.ledger.api.ReconciliationReport;
import com.omnibank.ledger.api.ReconciliationReport.AccountReconciliation;
import com.omnibank.ledger.api.ReconciliationReport.ExceptionStatus;
import com.omnibank.ledger.api.ReconciliationReport.ExceptionWorkItem;
import com.omnibank.ledger.api.ReconciliationReport.MatchStatus;
import com.omnibank.ledger.api.ReconciliationReport.ReconciliationOutcome;
import com.omnibank.ledger.api.ReconciliationReport.SourcePair;
import com.omnibank.ledger.api.ReconciliationReport.Summary;
import com.omnibank.ledger.api.ReconciliationReport.VarianceDetail;
import com.omnibank.ledger.api.ReconciliationReport.VarianceType;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.security.PrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconciles general ledger balances against subledger feeds and external
 * system extracts. Produces variance reports at the account level and
 * manages the exception workflow for unmatched items.
 *
 * <p>Reconciliation runs in three phases:
 * <ol>
 *   <li><strong>Extract</strong> — pull balances from GL and the comparison source</li>
 *   <li><strong>Match</strong> — compare balances within configured tolerances</li>
 *   <li><strong>Report</strong> — generate the immutable reconciliation report</li>
 * </ol>
 *
 * <p>Variance items that exceed the materiality threshold are escalated as
 * exception work items, assigned to controllers for investigation.
 */
public class LedgerReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(LedgerReconciliationService.class);

    /** Default tolerance for immaterial rounding differences. */
    private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.05");

    /** Percentage variance threshold above which an exception is raised. */
    private static final BigDecimal MATERIAL_VARIANCE_PCT = new BigDecimal("0.01");

    private final GlAccountRepository accounts;
    private final JournalEntryRepository journals;

    /** In-memory store for exception work items (in production, this would be a JPA entity). */
    private final ConcurrentHashMap<UUID, ExceptionWorkItem> exceptionStore = new ConcurrentHashMap<>();

    /** In-memory store for generated reports. */
    private final ConcurrentHashMap<UUID, ReconciliationReport> reportStore = new ConcurrentHashMap<>();

    public LedgerReconciliationService(GlAccountRepository accounts,
                                       JournalEntryRepository journals) {
        this.accounts = accounts;
        this.journals = journals;
    }

    // ── Reconciliation Execution ────────────────────────────────────────

    /**
     * Run a full reconciliation between the GL and the provided subledger
     * balances. Each entry in the subledger map is keyed by GL account code
     * with the subledger's computed balance as value.
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(
            String entityCode,
            LocalDate reconciliationDate,
            String subledgerName,
            Map<String, Money> subledgerBalances) {

        Objects.requireNonNull(entityCode, "entityCode");
        Objects.requireNonNull(reconciliationDate, "reconciliationDate");
        Objects.requireNonNull(subledgerName, "subledgerName");
        Objects.requireNonNull(subledgerBalances, "subledgerBalances");

        log.info("Starting reconciliation for entity={} date={} source={}",
                entityCode, reconciliationDate, subledgerName);
        long startNanos = System.nanoTime();

        // Phase 1: Extract GL balances
        Map<String, Money> glBalances = extractGlBalances(reconciliationDate);

        // Phase 2: Match and compare
        List<AccountReconciliation> results = new ArrayList<>();
        Map<CurrencyCode, Money> totalVariance = new EnumMap<>(CurrencyCode.class);
        Map<CurrencyCode, Money> totalAbsVariance = new EnumMap<>(CurrencyCode.class);
        Map<VarianceType, Integer> varianceCounts = new EnumMap<>(VarianceType.class);
        int matched = 0;
        int exceptions = 0;
        int missing = 0;

        // Compare GL accounts against subledger
        for (var glEntry : glBalances.entrySet()) {
            String accountCode = glEntry.getKey();
            Money glBalance = glEntry.getValue();
            Money subBalance = subledgerBalances.get(accountCode);

            AccountReconciliation recon;
            if (subBalance == null) {
                recon = buildMissingInSecondary(accountCode, glBalance);
                missing++;
            } else {
                recon = compareBalances(accountCode, glBalance, subBalance);
                if (recon.matchStatus() == MatchStatus.EXACT_MATCH
                        || recon.matchStatus() == MatchStatus.WITHIN_TOLERANCE) {
                    matched++;
                } else {
                    exceptions++;
                }
            }

            results.add(recon);
            accumulateVariance(recon, totalVariance, totalAbsVariance, varianceCounts);
        }

        // Check for accounts in subledger but not in GL
        for (var subEntry : subledgerBalances.entrySet()) {
            if (!glBalances.containsKey(subEntry.getKey())) {
                AccountReconciliation recon = buildMissingInPrimary(
                        subEntry.getKey(), subEntry.getValue());
                results.add(recon);
                missing++;
                accumulateVariance(recon, totalVariance, totalAbsVariance, varianceCounts);
            }
        }

        // Phase 3: Build report
        int total = results.size();
        BigDecimal matchRate = total > 0
                ? BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        ReconciliationOutcome outcome = determineOutcome(matched, exceptions, missing, total);

        Summary summary = new Summary(
                total, matched, exceptions, missing,
                totalVariance, totalAbsVariance, varianceCounts, matchRate);

        UUID reportId = UUID.randomUUID();
        ReconciliationReport report = new ReconciliationReport(
                reportId, entityCode, reconciliationDate, Instant.now(),
                PrincipalContext.current().getName(),
                SourcePair.glVsSubledger(subledgerName),
                outcome, results, summary);

        reportStore.put(reportId, report);

        // Generate exception work items for material variances
        generateExceptionWorkItems(report);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("Reconciliation complete: entity={} date={} accounts={} matched={} exceptions={} in {}ms",
                entityCode, reconciliationDate, total, matched, exceptions, elapsedMs);

        return report;
    }

    // ── Exception Workflow ──────────────────────────────────────────────

    /**
     * Retrieve all open exception work items for a given report.
     */
    public List<ExceptionWorkItem> openExceptions(UUID reportId) {
        return exceptionStore.values().stream()
                .filter(e -> e.reportId().equals(reportId))
                .filter(e -> e.status() == ExceptionStatus.OPEN
                        || e.status() == ExceptionStatus.ASSIGNED
                        || e.status() == ExceptionStatus.INVESTIGATING)
                .toList();
    }

    /**
     * Assign an exception work item to an investigator.
     */
    public ExceptionWorkItem assignException(UUID workItemId, String assignee) {
        ExceptionWorkItem existing = exceptionStore.get(workItemId);
        if (existing == null) {
            throw new IllegalArgumentException("Exception work item not found: " + workItemId);
        }
        ExceptionWorkItem updated = new ExceptionWorkItem(
                existing.workItemId(), existing.reportId(), existing.account(),
                existing.varianceAmount(), ExceptionStatus.ASSIGNED,
                assignee, existing.createdAt(), null, null, null);
        exceptionStore.put(workItemId, updated);
        log.info("Assigned exception {} to {}", workItemId, assignee);
        return updated;
    }

    /**
     * Resolve an exception with an action and explanation.
     */
    public ExceptionWorkItem resolveException(UUID workItemId, ExceptionStatus resolution,
                                              String action, String note) {
        ExceptionWorkItem existing = exceptionStore.get(workItemId);
        if (existing == null) {
            throw new IllegalArgumentException("Exception work item not found: " + workItemId);
        }
        if (!isResolutionStatus(resolution)) {
            throw new IllegalArgumentException("Invalid resolution status: " + resolution);
        }
        ExceptionWorkItem resolved = new ExceptionWorkItem(
                existing.workItemId(), existing.reportId(), existing.account(),
                existing.varianceAmount(), resolution,
                existing.assignedTo(), existing.createdAt(), Instant.now(),
                action, note);
        exceptionStore.put(workItemId, resolved);
        log.info("Resolved exception {} with status {} action={}",
                workItemId, resolution, action);
        return resolved;
    }

    /**
     * Retrieve a previously generated reconciliation report by ID.
     */
    public ReconciliationReport getReport(UUID reportId) {
        ReconciliationReport report = reportStore.get(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        return report;
    }

    // ── Internals ───────────────────────────────────────────────────────

    private Map<String, Money> extractGlBalances(LocalDate asOf) {
        Map<String, Money> balances = new HashMap<>();
        LocalDate inception = LocalDate.of(1900, 1, 1);

        for (GlAccountEntity account : accounts.findAll()) {
            if (account.isClosed()) continue;

            List<JournalEntryEntity> affecting = journals.findJournalsForAccount(
                    account.code(), inception, asOf);

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

            BigDecimal net = debits.subtract(credits);
            balances.put(account.code(), Money.of(net, account.currency()));
        }
        return balances;
    }

    private AccountReconciliation compareBalances(String accountCode, Money glBalance,
                                                  Money subBalance) {
        GlAccountEntity account = accounts.findById(accountCode).orElse(null);
        String displayName = account != null ? account.displayName() : accountCode;
        CurrencyCode currency = glBalance.currency();

        Money variance = glBalance.minus(subBalance);
        Money absVariance = variance.abs();
        BigDecimal pct = BigDecimal.ZERO;

        if (!subBalance.isZero()) {
            pct = absVariance.amount()
                    .divide(subBalance.amount().abs(), 6, RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(100));
        }

        Money tolerance = Money.of(DEFAULT_TOLERANCE, currency);
        MatchStatus status;
        List<VarianceDetail> details = new ArrayList<>();

        if (variance.isZero()) {
            status = MatchStatus.EXACT_MATCH;
        } else if (absVariance.compareTo(tolerance) <= 0) {
            status = MatchStatus.WITHIN_TOLERANCE;
            details.add(new VarianceDetail(VarianceType.ROUNDING_DIFFERENCE,
                    variance, null, null, "Within tolerance of " + tolerance));
        } else {
            status = MatchStatus.EXCEPTION;
            details.add(classifyVariance(variance, glBalance, subBalance));
        }

        return new AccountReconciliation(
                new GlAccountCode(accountCode), displayName, currency,
                glBalance, subBalance, variance, absVariance, pct,
                status, details, null);
    }

    private AccountReconciliation buildMissingInSecondary(String accountCode, Money glBalance) {
        CurrencyCode ccy = glBalance.currency();
        return new AccountReconciliation(
                new GlAccountCode(accountCode), accountCode, ccy,
                glBalance, Money.zero(ccy), glBalance, glBalance.abs(),
                BigDecimal.valueOf(100),
                MatchStatus.MISSING_IN_SECONDARY,
                List.of(new VarianceDetail(VarianceType.MISSING_TRANSACTION,
                        glBalance, null, null, "Account not found in subledger")),
                null);
    }

    private AccountReconciliation buildMissingInPrimary(String accountCode, Money subBalance) {
        CurrencyCode ccy = subBalance.currency();
        return new AccountReconciliation(
                new GlAccountCode(accountCode), accountCode, ccy,
                Money.zero(ccy), subBalance, subBalance.negate(), subBalance.abs(),
                BigDecimal.valueOf(100),
                MatchStatus.MISSING_IN_PRIMARY,
                List.of(new VarianceDetail(VarianceType.MISSING_TRANSACTION,
                        subBalance.negate(), null, null, "Account not found in GL")),
                null);
    }

    private VarianceDetail classifyVariance(Money variance, Money glBalance, Money subBalance) {
        // Heuristic classification — in production this would use transaction-level matching
        if (variance.isPositive()) {
            return new VarianceDetail(VarianceType.TIMING_GL_AHEAD, variance,
                    null, null, "GL balance exceeds subledger — possible timing difference");
        } else {
            return new VarianceDetail(VarianceType.TIMING_SUBLEDGER_AHEAD, variance,
                    null, null, "Subledger balance exceeds GL — possible timing difference");
        }
    }

    private void accumulateVariance(AccountReconciliation recon,
                                    Map<CurrencyCode, Money> totalVariance,
                                    Map<CurrencyCode, Money> totalAbsVariance,
                                    Map<VarianceType, Integer> varianceCounts) {
        CurrencyCode ccy = recon.currency();
        totalVariance.merge(ccy, recon.variance(),
                (a, b) -> a.currency() == b.currency() ? a.plus(b) : a);
        totalAbsVariance.merge(ccy, recon.absoluteVariance(),
                (a, b) -> a.currency() == b.currency() ? a.plus(b) : a);

        for (VarianceDetail detail : recon.varianceDetails()) {
            varianceCounts.merge(detail.type(), 1, Integer::sum);
        }
    }

    private ReconciliationOutcome determineOutcome(int matched, int exceptions,
                                                    int missing, int total) {
        if (total == 0) return ReconciliationOutcome.INCOMPLETE;
        if (exceptions == 0 && missing == 0) return ReconciliationOutcome.FULLY_RECONCILED;
        if (exceptions == 0) return ReconciliationOutcome.RECONCILED_WITH_MINOR_VARIANCES;
        return ReconciliationOutcome.EXCEPTIONS_FOUND;
    }

    private void generateExceptionWorkItems(ReconciliationReport report) {
        for (AccountReconciliation recon : report.accountResults()) {
            if (recon.matchStatus() == MatchStatus.EXCEPTION
                    || recon.matchStatus() == MatchStatus.MISSING_IN_PRIMARY
                    || recon.matchStatus() == MatchStatus.MISSING_IN_SECONDARY) {

                ExceptionWorkItem item = new ExceptionWorkItem(
                        UUID.randomUUID(), report.reportId(), recon.account(),
                        recon.absoluteVariance(), ExceptionStatus.OPEN,
                        null, Instant.now(), null, null, null);
                exceptionStore.put(item.workItemId(), item);
            }
        }
    }

    private boolean isResolutionStatus(ExceptionStatus status) {
        return status == ExceptionStatus.RESOLVED_ADJUSTED
                || status == ExceptionStatus.RESOLVED_EXPLAINED
                || status == ExceptionStatus.RESOLVED_AUTO_CLEARED;
    }
}
