package com.omnibank.ledger.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an intercompany transaction between two legal entities within
 * the Omnibank group. These transactions must ultimately net to zero in
 * consolidation — each IC transaction generates mirror entries in both
 * entities' ledgers and corresponding elimination entries for reporting.
 *
 * <p>Sealed hierarchy covers the lifecycle from proposal through settlement.
 */
public record IntercompanyTransaction(
        UUID transactionId,
        String sourceEntity,
        String targetEntity,
        Money amount,
        CurrencyCode settlementCurrency,
        TransactionType type,
        Status status,
        LocalDate valueDate,
        Instant createdAt,
        String createdBy,
        String businessKey,
        String description,
        List<AllocationLine> allocations
) {

    public IntercompanyTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(sourceEntity, "sourceEntity");
        Objects.requireNonNull(targetEntity, "targetEntity");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(settlementCurrency, "settlementCurrency");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(businessKey, "businessKey");
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
        if (sourceEntity.equals(targetEntity)) {
            throw new IllegalArgumentException(
                    "Source and target entity must differ: " + sourceEntity);
        }
    }

    /**
     * Classification of intercompany transaction types. Drives the posting
     * rule engine's selection of GL accounts and elimination templates.
     */
    public enum TransactionType {
        /** Cost allocation from shared-services entity to operating entity. */
        COST_ALLOCATION,
        /** Revenue share from operating entity back to IP-holding entity. */
        REVENUE_SHARE,
        /** Capital injection from parent to subsidiary. */
        CAPITAL_INJECTION,
        /** Dividend upstream from subsidiary to parent. */
        DIVIDEND_UPSTREAM,
        /** Interest on intercompany loans. */
        INTERCOMPANY_INTEREST,
        /** Management or service fees. */
        MANAGEMENT_FEE,
        /** Transfer pricing adjustment. */
        TRANSFER_PRICING,
        /** Generic funding movement (e.g. treasury sweep). */
        FUNDING_TRANSFER
    }

    /**
     * Lifecycle status of the intercompany transaction.
     */
    public enum Status {
        /** Created but not yet approved by both entities. */
        PENDING_APPROVAL,
        /** Approved by both source and target entity controllers. */
        APPROVED,
        /** Journal entries posted in both entities' ledgers. */
        POSTED,
        /** Included in a netting batch for settlement. */
        NETTED,
        /** Cash settlement completed. */
        SETTLED,
        /** Elimination entries generated for consolidated reporting. */
        ELIMINATED,
        /** Cancelled before settlement. */
        CANCELLED,
        /** Disputed — requires manual resolution. */
        DISPUTED
    }

    /**
     * Sub-allocation line for cost allocations that break a single IC
     * transaction across multiple cost centers or departments.
     */
    public record AllocationLine(
            String costCenter,
            GlAccountCode account,
            BigDecimal percentage,
            Money allocatedAmount,
            String memo
    ) {
        public AllocationLine {
            Objects.requireNonNull(costCenter, "costCenter");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(percentage, "percentage");
            Objects.requireNonNull(allocatedAmount, "allocatedAmount");
            if (percentage.signum() <= 0 || percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException(
                        "Allocation percentage must be between 0 exclusive and 100 inclusive: " + percentage);
            }
        }
    }

    /**
     * Result of a netting run — aggregates multiple IC transactions between
     * the same pair of entities into a single net settlement amount.
     */
    public record NettingResult(
            UUID nettingId,
            String entityA,
            String entityB,
            Money grossPayable,
            Money grossReceivable,
            Money netAmount,
            NetDirection direction,
            LocalDate settlementDate,
            List<UUID> includedTransactions
    ) {
        public NettingResult {
            Objects.requireNonNull(nettingId, "nettingId");
            Objects.requireNonNull(entityA, "entityA");
            Objects.requireNonNull(entityB, "entityB");
            Objects.requireNonNull(grossPayable, "grossPayable");
            Objects.requireNonNull(grossReceivable, "grossReceivable");
            Objects.requireNonNull(netAmount, "netAmount");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(settlementDate, "settlementDate");
            includedTransactions = List.copyOf(includedTransactions);
        }
    }

    public enum NetDirection {
        /** Entity A owes entity B. */
        A_TO_B,
        /** Entity B owes entity A. */
        B_TO_A,
        /** Perfectly netted — no cash movement required. */
        ZERO
    }

    /**
     * Summary view for intercompany position reporting. Shows outstanding
     * balances between entity pairs across all transaction types.
     */
    public record PositionSummary(
            String entityCode,
            String counterpartyEntity,
            CurrencyCode currency,
            Money receivableBalance,
            Money payableBalance,
            Money netPosition,
            int openTransactionCount,
            LocalDate oldestOpenDate
    ) {
        public PositionSummary {
            Objects.requireNonNull(entityCode, "entityCode");
            Objects.requireNonNull(counterpartyEntity, "counterpartyEntity");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(receivableBalance, "receivableBalance");
            Objects.requireNonNull(payableBalance, "payableBalance");
            Objects.requireNonNull(netPosition, "netPosition");
        }

        public boolean isNetReceivable() {
            return netPosition.isPositive();
        }

        public boolean isNetPayable() {
            return netPosition.isNegative();
        }
    }

    /**
     * Validation error specific to intercompany transactions.
     */
    public sealed interface IcValidationError {
        record UnknownEntity(String entityCode) implements IcValidationError {}
        record SameEntity(String entityCode) implements IcValidationError {}
        record AmountBelowThreshold(Money amount, Money threshold) implements IcValidationError {}
        record MissingApproval(String entityCode, String requiredRole) implements IcValidationError {}
        record PeriodClosed(String entityCode, LocalDate valueDate) implements IcValidationError {}
        record DuplicateBusinessKey(String businessKey) implements IcValidationError {}
        record InvalidAllocations(String reason) implements IcValidationError {}
        record CurrencyMismatch(CurrencyCode expected, CurrencyCode actual) implements IcValidationError {}
    }
}
