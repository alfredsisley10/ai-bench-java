package com.omnibank.ledger.api;

import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Result;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service interface for chart-of-accounts management. The chart is a
 * hierarchical tree of GL accounts grouped by consolidation segments,
 * cost centers, and reporting categories. All mutations go through this
 * facade to enforce structural integrity (no orphans, no cycles, no
 * duplicate codes) and trigger downstream reindexing.
 *
 * <p>Implementations must ensure:
 * <ul>
 *   <li>Account codes are globally unique across the bank group</li>
 *   <li>Parent accounts always precede children in the natural sort order</li>
 *   <li>Deleting a parent cascades only if all children are also inactive</li>
 *   <li>Active accounts referenced in open-period journals cannot be deactivated</li>
 * </ul>
 */
public interface ChartOfAccounts {

    // ── CRUD ────────────────────────────────────────────────────────────

    /**
     * Create a new GL account under the given parent. Returns an error if
     * the code is already taken, the parent does not exist, or the account
     * type is incompatible with its parent's segment.
     */
    Result<AccountNode, ChartError> createAccount(CreateAccountRequest request);

    /**
     * Update mutable attributes of an existing account (display name,
     * cost center assignment, active flag). The account code, type, and
     * currency are immutable after creation.
     */
    Result<AccountNode, ChartError> updateAccount(UpdateAccountRequest request);

    /**
     * Deactivate an account. Deactivated accounts reject new postings
     * but retain their balance for reporting. Returns an error if the
     * account has activity in the current open period.
     */
    Result<Void, ChartError> deactivateAccount(GlAccountCode code);

    /**
     * Reactivate a previously deactivated account.
     */
    Result<Void, ChartError> reactivateAccount(GlAccountCode code);

    // ── Queries ─────────────────────────────────────────────────────────

    /**
     * Look up a single account by code. Returns empty if not found.
     */
    Optional<AccountNode> findByCode(GlAccountCode code);

    /**
     * Return the full subtree rooted at the given account, including
     * the root itself. Nodes are returned in pre-order (parent before
     * children) for deterministic rendering.
     */
    List<AccountNode> subtree(GlAccountCode root);

    /**
     * Return the path from root to the given account (inclusive of both
     * endpoints). Useful for breadcrumb navigation and rollup audits.
     */
    List<AccountNode> pathToRoot(GlAccountCode code);

    /**
     * Return all leaf accounts (accounts with no children) under the
     * given root. Posting is only allowed to leaf accounts.
     */
    List<AccountNode> leafAccounts(GlAccountCode root);

    /**
     * Search accounts by partial name, code prefix, or cost center.
     * Results are ranked by relevance and capped at {@code maxResults}.
     */
    List<AccountNode> search(SearchCriteria criteria, int maxResults);

    /**
     * Return all accounts in a specific consolidation group, across
     * all entities. Used by the consolidation engine for elimination
     * and rollup.
     */
    List<AccountNode> byConsolidationGroup(String groupCode);

    /**
     * Roll up balances from leaf accounts to a parent, as of the given date.
     * The rollup respects the tree hierarchy and performs currency conversion
     * where children are in different currencies than the parent.
     */
    Money rolledUpBalance(GlAccountCode parent, LocalDate asOf, CurrencyCode targetCurrency);

    // ── Nested types ────────────────────────────────────────────────────

    /**
     * A node in the chart-of-accounts tree. Carries the account metadata
     * plus structural information (parent, depth, path).
     */
    record AccountNode(
            GlAccountCode code,
            String displayName,
            AccountType type,
            CurrencyCode currency,
            String entityCode,
            GlAccountCode parentCode,
            int depth,
            String costCenter,
            String consolidationGroup,
            boolean active,
            boolean leaf,
            int childCount
    ) {
        public AccountNode {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(entityCode, "entityCode");
        }

        public boolean isRoot() {
            return parentCode == null;
        }
    }

    record CreateAccountRequest(
            GlAccountCode code,
            String displayName,
            AccountType type,
            CurrencyCode currency,
            String entityCode,
            GlAccountCode parentCode,
            String costCenter,
            String consolidationGroup
    ) {
        public CreateAccountRequest {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(entityCode, "entityCode");
        }
    }

    record UpdateAccountRequest(
            GlAccountCode code,
            String displayName,
            String costCenter,
            String consolidationGroup,
            Boolean active
    ) {
        public UpdateAccountRequest {
            Objects.requireNonNull(code, "code");
        }
    }

    record SearchCriteria(
            String textQuery,
            AccountType typeFilter,
            CurrencyCode currencyFilter,
            String entityFilter,
            String costCenterFilter,
            String consolidationGroupFilter,
            Boolean activeOnly
    ) {
        public SearchCriteria {
            if (textQuery != null && textQuery.isBlank()) {
                textQuery = null;
            }
        }

        public static SearchCriteria byText(String query) {
            return new SearchCriteria(query, null, null, null, null, null, true);
        }

        public static SearchCriteria byType(AccountType type) {
            return new SearchCriteria(null, type, null, null, null, null, true);
        }

        public static SearchCriteria byCostCenter(String costCenter) {
            return new SearchCriteria(null, null, null, null, costCenter, null, true);
        }
    }

    /**
     * Errors that can occur during chart-of-accounts operations.
     */
    sealed interface ChartError {
        record AccountNotFound(GlAccountCode code) implements ChartError {}
        record DuplicateCode(GlAccountCode code) implements ChartError {}
        record ParentNotFound(GlAccountCode parentCode) implements ChartError {}
        record TypeMismatch(AccountType parentType, AccountType childType) implements ChartError {}
        record CyclicReference(GlAccountCode code) implements ChartError {}
        record ActivePostingsExist(GlAccountCode code, long journalCount) implements ChartError {}
        record EntityMismatch(String parentEntity, String childEntity) implements ChartError {}
        record MaxDepthExceeded(int maxDepth) implements ChartError {}
        record ConcurrentModification(GlAccountCode code) implements ChartError {}
    }
}
