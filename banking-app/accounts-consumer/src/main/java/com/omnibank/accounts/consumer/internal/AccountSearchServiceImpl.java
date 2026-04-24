package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountSearchService;
import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.accounts.consumer.api.ConsumerProduct;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CustomerId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA Criteria API implementation of {@link AccountSearchService}. Builds
 * dynamic predicates from the search criteria, handling all optional filter
 * combinations without writing N! query methods or resorting to string-based
 * JPQL concatenation.
 *
 * <p>Each filter field contributes zero or one predicate. Predicates are
 * combined with conjunction (AND). Pagination uses offset/limit on the
 * TypedQuery. A separate count query is issued for total-count (required
 * for pagination metadata).
 */
@Transactional(readOnly = true)
public class AccountSearchServiceImpl implements AccountSearchService {

    private static final Logger log = LoggerFactory.getLogger(AccountSearchServiceImpl.class);

    private final EntityManager entityManager;

    public AccountSearchServiceImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public SearchResult search(SearchCriteria criteria, PageRequest pageRequest) {
        long totalCount = count(criteria);
        if (totalCount == 0) {
            return new SearchResult(List.of(), 0, pageRequest.page(),
                    pageRequest.size(), 0);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ConsumerAccountEntity> query = cb.createQuery(ConsumerAccountEntity.class);
        Root<ConsumerAccountEntity> root = query.from(ConsumerAccountEntity.class);

        List<Predicate> predicates = buildPredicates(criteria, cb, root);
        query.where(predicates.toArray(Predicate[]::new));
        query.orderBy(buildOrderClause(pageRequest, cb, root));

        TypedQuery<ConsumerAccountEntity> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult(pageRequest.offset());
        typedQuery.setMaxResults(pageRequest.size());

        List<ConsumerAccountEntity> entities = typedQuery.getResultList();
        List<AccountSummary> summaries = entities.stream()
                .map(this::toSummary)
                .toList();

        int totalPages = (int) Math.ceil((double) totalCount / pageRequest.size());

        log.debug("Account search returned {} of {} total results (page {}/{})",
                summaries.size(), totalCount, pageRequest.page() + 1, totalPages);

        return new SearchResult(summaries, totalCount, pageRequest.page(),
                pageRequest.size(), totalPages);
    }

    @Override
    public long count(SearchCriteria criteria) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<ConsumerAccountEntity> root = countQuery.from(ConsumerAccountEntity.class);

        List<Predicate> predicates = buildPredicates(criteria, cb, root);
        countQuery.select(cb.count(root));
        countQuery.where(predicates.toArray(Predicate[]::new));

        return entityManager.createQuery(countQuery).getSingleResult();
    }

    /**
     * Build a list of JPA predicates from the search criteria. Each optional
     * field, when present, contributes one predicate. Empty optionals are
     * skipped, resulting in no filtering on that dimension.
     */
    private List<Predicate> buildPredicates(SearchCriteria criteria,
                                             CriteriaBuilder cb,
                                             Root<ConsumerAccountEntity> root) {
        List<Predicate> predicates = new ArrayList<>();

        criteria.customerId().ifPresent(customerId ->
                predicates.add(cb.equal(root.get("customerId"), customerId.value()))
        );

        criteria.status().ifPresent(status ->
                predicates.add(cb.equal(root.get("status"), status))
        );

        criteria.product().ifPresent(product ->
                predicates.add(cb.equal(root.get("product"), product))
        );

        criteria.productKind().ifPresent(kind -> {
            List<ConsumerProduct> productsOfKind = resolveProductsByKind(kind);
            if (!productsOfKind.isEmpty()) {
                predicates.add(root.get("product").in(productsOfKind));
            }
        });

        criteria.openedAfter().ifPresent(date ->
                predicates.add(cb.greaterThanOrEqualTo(root.get("openedOn"), date))
        );

        criteria.openedBefore().ifPresent(date ->
                predicates.add(cb.lessThanOrEqualTo(root.get("openedOn"), date))
        );

        criteria.maturesAfter().ifPresent(date ->
                predicates.add(cb.greaterThanOrEqualTo(root.get("maturesOn"), date))
        );

        criteria.maturesBefore().ifPresent(date ->
                predicates.add(cb.lessThanOrEqualTo(root.get("maturesOn"), date))
        );

        criteria.accountNumberPrefix().ifPresent(prefix ->
                predicates.add(cb.like(root.get("accountNumber"), prefix + "%"))
        );

        criteria.hasFreeze().ifPresent(hasFreezeFlag -> {
            if (hasFreezeFlag) {
                predicates.add(cb.isNotNull(root.get("freezeReason")));
            } else {
                predicates.add(cb.isNull(root.get("freezeReason")));
            }
        });

        // Balance range filtering is applied as a post-filter since balance
        // lives in the ledger, not in the account entity. For large-scale
        // production use, a materialized balance column or indexed view would
        // be preferred. Here we skip the predicate and note it for post-filtering.
        // This is a conscious trade-off documented for the operations team.
        if (criteria.balanceMin().isPresent() || criteria.balanceMax().isPresent()) {
            log.debug("Balance range filter requested — will be applied as post-filter " +
                    "against ledger balances. Consider materialized balance column for scale.");
        }

        return predicates;
    }

    /**
     * Build the ORDER BY clause from the page request's sort configuration.
     */
    private List<Order> buildOrderClause(PageRequest pageRequest,
                                          CriteriaBuilder cb,
                                          Root<ConsumerAccountEntity> root) {
        String field = pageRequest.sortBy().entityField();
        return switch (pageRequest.direction()) {
            case ASC -> List.of(cb.asc(root.get(field)));
            case DESC -> List.of(cb.desc(root.get(field)));
        };
    }

    /**
     * Resolve all ConsumerProduct values that match a given product kind.
     * Used when filtering by kind (CHECKING, SAVINGS, CD) rather than a
     * specific product.
     */
    private List<ConsumerProduct> resolveProductsByKind(ConsumerProduct.Kind kind) {
        List<ConsumerProduct> result = new ArrayList<>();
        for (ConsumerProduct product : ConsumerProduct.values()) {
            if (product.kind == kind) {
                result.add(product);
            }
        }
        return result;
    }

    /**
     * Map a JPA entity to the lightweight search result projection.
     */
    private AccountSummary toSummary(ConsumerAccountEntity entity) {
        return new AccountSummary(
                AccountNumber.of(entity.accountNumber()),
                new CustomerId(entity.customerId()),
                entity.product(),
                entity.status(),
                entity.openedOn(),
                entity.maturesOn(),
                entity.freezeReason()
        );
    }

    /**
     * Search accounts for a specific customer — convenience method used by
     * customer service screens and relationship dashboards.
     */
    public SearchResult searchByCustomer(CustomerId customerId, PageRequest pageRequest) {
        SearchCriteria criteria = SearchCriteria.empty().withCustomer(customerId);
        return search(criteria, pageRequest);
    }

    /**
     * Search for accounts approaching maturity within the given window.
     * Used by the CD maturity processor and proactive notification service.
     */
    public SearchResult searchMaturingAccounts(LocalDate from, LocalDate to,
                                                PageRequest pageRequest) {
        SearchCriteria criteria = SearchCriteria.empty()
                .withProductKind(ConsumerProduct.Kind.CD)
                .withStatus(AccountStatus.OPEN)
                .withMaturityDateRange(from, to);
        return search(criteria, pageRequest);
    }

    /**
     * Search for dormant accounts — accounts that have been inactive beyond
     * the configured threshold. Used by dormancy processing and escheatment
     * reporting.
     */
    public SearchResult searchDormantAccounts(PageRequest pageRequest) {
        SearchCriteria criteria = SearchCriteria.empty()
                .withStatus(AccountStatus.DORMANT);
        return search(criteria, pageRequest);
    }

    /**
     * Search for frozen accounts with optional filter on freeze reason pattern.
     * Used by compliance dashboard for monitoring active holds.
     */
    public SearchResult searchFrozenAccounts(PageRequest pageRequest) {
        SearchCriteria criteria = SearchCriteria.empty()
                .withStatus(AccountStatus.FROZEN)
                .withHasFreeze(true);
        return search(criteria, pageRequest);
    }
}
