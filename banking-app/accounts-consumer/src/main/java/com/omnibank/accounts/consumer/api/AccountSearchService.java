package com.omnibank.accounts.consumer.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Search interface for consumer accounts. Supports multi-criteria filtering,
 * pagination, and sort ordering. Designed for internal operations dashboards,
 * customer service lookup, and batch processing queries.
 *
 * <p>All search criteria are optional and combined with AND semantics.
 * An empty criteria returns all accounts (subject to pagination limits).
 */
public interface AccountSearchService {

    /**
     * Search for accounts matching the given criteria, with pagination and sorting.
     */
    SearchResult search(SearchCriteria criteria, PageRequest pageRequest);

    /**
     * Count accounts matching the given criteria without returning results.
     * Useful for dashboard metrics and pre-flight pagination.
     */
    long count(SearchCriteria criteria);

    /**
     * Multi-criteria search filter. All fields are optional; non-null fields
     * are combined with AND. Balance range filters operate on the GL ledger
     * balance (not available balance, which requires hold computation).
     */
    record SearchCriteria(
            Optional<CustomerId> customerId,
            Optional<AccountStatus> status,
            Optional<ConsumerProduct> product,
            Optional<ConsumerProduct.Kind> productKind,
            Optional<Money> balanceMin,
            Optional<Money> balanceMax,
            Optional<LocalDate> openedAfter,
            Optional<LocalDate> openedBefore,
            Optional<LocalDate> maturesAfter,
            Optional<LocalDate> maturesBefore,
            Optional<String> accountNumberPrefix,
            Optional<Boolean> hasFreeze
    ) {
        public SearchCriteria {
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(productKind, "productKind");
            Objects.requireNonNull(balanceMin, "balanceMin");
            Objects.requireNonNull(balanceMax, "balanceMax");
            Objects.requireNonNull(openedAfter, "openedAfter");
            Objects.requireNonNull(openedBefore, "openedBefore");
            Objects.requireNonNull(maturesAfter, "maturesAfter");
            Objects.requireNonNull(maturesBefore, "maturesBefore");
            Objects.requireNonNull(accountNumberPrefix, "accountNumberPrefix");
            Objects.requireNonNull(hasFreeze, "hasFreeze");
        }

        /**
         * Builder-style factory for constructing criteria incrementally.
         */
        public static SearchCriteria empty() {
            return new SearchCriteria(
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()
            );
        }

        public SearchCriteria withCustomer(CustomerId id) {
            return new SearchCriteria(Optional.of(id), status, product, productKind,
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    maturesAfter, maturesBefore, accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withStatus(AccountStatus s) {
            return new SearchCriteria(customerId, Optional.of(s), product, productKind,
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    maturesAfter, maturesBefore, accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withProduct(ConsumerProduct p) {
            return new SearchCriteria(customerId, status, Optional.of(p), productKind,
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    maturesAfter, maturesBefore, accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withProductKind(ConsumerProduct.Kind k) {
            return new SearchCriteria(customerId, status, product, Optional.of(k),
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    maturesAfter, maturesBefore, accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withBalanceRange(Money min, Money max) {
            return new SearchCriteria(customerId, status, product, productKind,
                    Optional.ofNullable(min), Optional.ofNullable(max), openedAfter,
                    openedBefore, maturesAfter, maturesBefore, accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withOpenedDateRange(LocalDate after, LocalDate before) {
            return new SearchCriteria(customerId, status, product, productKind,
                    balanceMin, balanceMax, Optional.ofNullable(after),
                    Optional.ofNullable(before), maturesAfter, maturesBefore,
                    accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withMaturityDateRange(LocalDate after, LocalDate before) {
            return new SearchCriteria(customerId, status, product, productKind,
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    Optional.ofNullable(after), Optional.ofNullable(before),
                    accountNumberPrefix, hasFreeze);
        }

        public SearchCriteria withAccountNumberPrefix(String prefix) {
            return new SearchCriteria(customerId, status, product, productKind,
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    maturesAfter, maturesBefore, Optional.of(prefix), hasFreeze);
        }

        public SearchCriteria withHasFreeze(boolean frozen) {
            return new SearchCriteria(customerId, status, product, productKind,
                    balanceMin, balanceMax, openedAfter, openedBefore,
                    maturesAfter, maturesBefore, accountNumberPrefix, Optional.of(frozen));
        }
    }

    /**
     * Pagination and sort configuration.
     */
    record PageRequest(int page, int size, SortField sortBy, SortDirection direction) {
        public PageRequest {
            if (page < 0) throw new IllegalArgumentException("Page must be >= 0");
            if (size < 1 || size > 500) throw new IllegalArgumentException("Size must be 1-500");
            Objects.requireNonNull(sortBy, "sortBy");
            Objects.requireNonNull(direction, "direction");
        }

        public static PageRequest defaultPage() {
            return new PageRequest(0, 25, SortField.ACCOUNT_NUMBER, SortDirection.ASC);
        }

        public int offset() {
            return page * size;
        }
    }

    enum SortField {
        ACCOUNT_NUMBER("accountNumber"),
        CUSTOMER_ID("customerId"),
        PRODUCT("product"),
        STATUS("status"),
        OPENED_ON("openedOn"),
        MATURES_ON("maturesOn");

        private final String entityField;

        SortField(String entityField) {
            this.entityField = entityField;
        }

        public String entityField() { return entityField; }
    }

    enum SortDirection { ASC, DESC }

    /**
     * Paginated search result containing matched accounts and total count.
     */
    record SearchResult(
            List<AccountSummary> accounts,
            long totalCount,
            int page,
            int pageSize,
            int totalPages
    ) {
        public SearchResult {
            Objects.requireNonNull(accounts, "accounts");
            accounts = List.copyOf(accounts);
        }

        public boolean hasNext() {
            return page < totalPages - 1;
        }

        public boolean hasPrevious() {
            return page > 0;
        }
    }

    /**
     * Lightweight account projection for search results. Does not include
     * full balance computation (which requires hold aggregation) — only
     * the core attributes.
     */
    record AccountSummary(
            AccountNumber accountNumber,
            CustomerId customerId,
            ConsumerProduct product,
            AccountStatus status,
            LocalDate openedOn,
            LocalDate maturesOn,
            String freezeReason
    ) {}
}
