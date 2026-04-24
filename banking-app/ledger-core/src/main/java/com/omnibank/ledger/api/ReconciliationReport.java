package com.omnibank.ledger.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Output of a ledger reconciliation run. Compares GL balances against
 * subledger (or external system) balances and produces item-level
 * variance details for each account that does not match within tolerance.
 *
 * <p>Reconciliation reports are immutable snapshots — once generated, they
 * are stored for audit trail and cannot be modified. Resolution of
 * exceptions is tracked separately in the reconciliation workflow.
 */
public record ReconciliationReport(
        UUID reportId,
        String entityCode,
        LocalDate reconciliationDate,
        Instant generatedAt,
        String generatedBy,
        SourcePair sources,
        ReconciliationOutcome outcome,
        List<AccountReconciliation> accountResults,
        Summary summary
) {

    public ReconciliationReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(entityCode, "entityCode");
        Objects.requireNonNull(reconciliationDate, "reconciliationDate");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(generatedBy, "generatedBy");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(outcome, "outcome");
        accountResults = List.copyOf(accountResults);
        Objects.requireNonNull(summary, "summary");
    }

    /**
     * The two systems being reconciled.
     */
    public record SourcePair(String primarySource, String secondarySource) {
        public SourcePair {
            Objects.requireNonNull(primarySource, "primarySource");
            Objects.requireNonNull(secondarySource, "secondarySource");
        }

        public static SourcePair glVsSubledger(String subledgerName) {
            return new SourcePair("GENERAL_LEDGER", subledgerName);
        }

        public static SourcePair glVsExternal(String externalSystem) {
            return new SourcePair("GENERAL_LEDGER", externalSystem);
        }
    }

    /**
     * High-level outcome of the reconciliation run.
     */
    public enum ReconciliationOutcome {
        /** All accounts matched within tolerance. */
        FULLY_RECONCILED,
        /** Some accounts have variances but all within acceptable thresholds. */
        RECONCILED_WITH_MINOR_VARIANCES,
        /** Material variances detected requiring investigation. */
        EXCEPTIONS_FOUND,
        /** Reconciliation could not complete due to missing data. */
        INCOMPLETE,
        /** Reconciliation failed due to a system error. */
        FAILED
    }

    /**
     * Per-account reconciliation result with variance breakdown.
     */
    public record AccountReconciliation(
            GlAccountCode account,
            String accountName,
            CurrencyCode currency,
            Money primaryBalance,
            Money secondaryBalance,
            Money variance,
            Money absoluteVariance,
            BigDecimal variancePercentage,
            MatchStatus matchStatus,
            List<VarianceDetail> varianceDetails,
            String resolutionNote
    ) {
        public AccountReconciliation {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(primaryBalance, "primaryBalance");
            Objects.requireNonNull(secondaryBalance, "secondaryBalance");
            Objects.requireNonNull(variance, "variance");
            Objects.requireNonNull(absoluteVariance, "absoluteVariance");
            Objects.requireNonNull(matchStatus, "matchStatus");
            varianceDetails = varianceDetails == null ? List.of() : List.copyOf(varianceDetails);
        }

        public boolean hasVariance() {
            return !variance.isZero();
        }

        public boolean isWithinTolerance(Money tolerance) {
            return absoluteVariance.compareTo(tolerance) <= 0;
        }
    }

    /**
     * Match status for an individual account in the reconciliation.
     */
    public enum MatchStatus {
        /** Balances match exactly. */
        EXACT_MATCH,
        /** Balances differ but within configured tolerance. */
        WITHIN_TOLERANCE,
        /** Material variance — requires investigation. */
        EXCEPTION,
        /** Account exists in primary but not secondary. */
        MISSING_IN_SECONDARY,
        /** Account exists in secondary but not primary. */
        MISSING_IN_PRIMARY,
        /** Stale data — secondary balance is from a prior period. */
        STALE_DATA
    }

    /**
     * Granular detail of a variance, tracing it to specific transactions
     * or timing differences.
     */
    public record VarianceDetail(
            VarianceType type,
            Money amount,
            String reference,
            LocalDate transactionDate,
            String explanation
    ) {
        public VarianceDetail {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * Classification of variance causes. Operators use this to decide
     * resolution strategy (auto-clear vs. manual investigation).
     */
    public enum VarianceType {
        /** Transaction posted in GL but not yet in subledger. */
        TIMING_GL_AHEAD,
        /** Transaction posted in subledger but not yet in GL. */
        TIMING_SUBLEDGER_AHEAD,
        /** Genuine amount difference on matched transactions. */
        AMOUNT_MISMATCH,
        /** Transaction present in one system but absent in the other. */
        MISSING_TRANSACTION,
        /** Rounding difference due to different precision. */
        ROUNDING_DIFFERENCE,
        /** FX conversion rate difference between systems. */
        FX_RATE_DIFFERENCE,
        /** Duplicate posting in one system. */
        DUPLICATE_ENTRY,
        /** Classification difference (e.g. different GL account mapping). */
        CLASSIFICATION_DIFFERENCE,
        /** Unexplained — requires manual investigation. */
        UNKNOWN
    }

    /**
     * Aggregate statistics for the entire reconciliation run.
     */
    public record Summary(
            int totalAccounts,
            int matchedAccounts,
            int exceptionAccounts,
            int missingAccounts,
            Map<CurrencyCode, Money> totalVarianceByCurrency,
            Map<CurrencyCode, Money> totalAbsoluteVarianceByCurrency,
            Map<VarianceType, Integer> varianceCountByType,
            BigDecimal matchRatePercentage
    ) {
        public Summary {
            Objects.requireNonNull(totalVarianceByCurrency, "totalVarianceByCurrency");
            totalVarianceByCurrency = Map.copyOf(totalVarianceByCurrency);
            totalAbsoluteVarianceByCurrency = totalAbsoluteVarianceByCurrency == null
                    ? Map.of() : Map.copyOf(totalAbsoluteVarianceByCurrency);
            varianceCountByType = varianceCountByType == null
                    ? Map.of() : Map.copyOf(varianceCountByType);
        }

        public boolean isClean() {
            return exceptionAccounts == 0 && missingAccounts == 0;
        }
    }

    /**
     * Reconciliation workflow item — tracks the resolution lifecycle
     * of an individual exception from a reconciliation report.
     */
    public record ExceptionWorkItem(
            UUID workItemId,
            UUID reportId,
            GlAccountCode account,
            Money varianceAmount,
            ExceptionStatus status,
            String assignedTo,
            Instant createdAt,
            Instant resolvedAt,
            String resolutionAction,
            String resolutionNote
    ) {
        public ExceptionWorkItem {
            Objects.requireNonNull(workItemId, "workItemId");
            Objects.requireNonNull(reportId, "reportId");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(varianceAmount, "varianceAmount");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Lifecycle status of a reconciliation exception work item.
     */
    public enum ExceptionStatus {
        OPEN,
        ASSIGNED,
        INVESTIGATING,
        PENDING_APPROVAL,
        RESOLVED_ADJUSTED,
        RESOLVED_EXPLAINED,
        RESOLVED_AUTO_CLEARED,
        ESCALATED
    }
}
