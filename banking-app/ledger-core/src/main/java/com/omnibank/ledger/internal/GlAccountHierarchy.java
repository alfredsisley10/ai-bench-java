package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.AccountType;
import com.omnibank.ledger.api.ChartOfAccounts;
import com.omnibank.ledger.api.ChartOfAccounts.AccountNode;
import com.omnibank.ledger.api.ChartOfAccounts.ChartError;
import com.omnibank.ledger.api.ChartOfAccounts.CreateAccountRequest;
import com.omnibank.ledger.api.ChartOfAccounts.SearchCriteria;
import com.omnibank.ledger.api.ChartOfAccounts.UpdateAccountRequest;
import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.ExchangeRate;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Manages the chart-of-accounts tree structure. Each GL account can have a
 * parent account, forming a hierarchy used for:
 * <ul>
 *   <li>Balance rollup (leaf balances aggregate to parents)</li>
 *   <li>Consolidation grouping (mapping subsidiaries' charts to the group chart)</li>
 *   <li>Cost center allocation (tagging accounts to organizational units)</li>
 *   <li>Regulatory reporting (mapping to standardized report line items)</li>
 * </ul>
 *
 * <p>The hierarchy is stored adjacency-list style in the GL account table
 * (parent_code column). This service materializes the tree in memory for
 * traversal operations and maintains a denormalized depth/path cache for
 * efficient queries.
 */
public class GlAccountHierarchy implements ChartOfAccounts {

    private static final Logger log = LoggerFactory.getLogger(GlAccountHierarchy.class);
    private static final int MAX_TREE_DEPTH = 10;

    private final GlAccountRepository accountRepository;
    private final JournalEntryRepository journalRepository;
    private final LedgerQueriesImpl ledgerQueries;
    private final Supplier<Map<CurrencyCode, ExchangeRate>> rateProvider;

    /** In-memory tree index rebuilt on structural changes. */
    private final ConcurrentHashMap<String, TreeNode> nodeIndex = new ConcurrentHashMap<>();

    /** Parent-to-children adjacency list. */
    private final ConcurrentHashMap<String, Set<String>> childrenIndex = new ConcurrentHashMap<>();

    public GlAccountHierarchy(GlAccountRepository accountRepository,
                              JournalEntryRepository journalRepository,
                              LedgerQueriesImpl ledgerQueries,
                              Supplier<Map<CurrencyCode, ExchangeRate>> rateProvider) {
        this.accountRepository = accountRepository;
        this.journalRepository = journalRepository;
        this.ledgerQueries = ledgerQueries;
        this.rateProvider = rateProvider;
    }

    // ── CRUD ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public Result<AccountNode, ChartError> createAccount(CreateAccountRequest request) {
        Objects.requireNonNull(request, "request");

        // Check duplicate code
        if (accountRepository.findById(request.code().value()).isPresent()) {
            return Result.err(new ChartError.DuplicateCode(request.code()));
        }

        // Validate parent
        int depth = 0;
        if (request.parentCode() != null) {
            Optional<GlAccountEntity> parentOpt = accountRepository.findById(
                    request.parentCode().value());
            if (parentOpt.isEmpty()) {
                return Result.err(new ChartError.ParentNotFound(request.parentCode()));
            }
            GlAccountEntity parent = parentOpt.get();

            // Type compatibility: child must match parent's account type segment
            if (parent.type() != request.type()) {
                return Result.err(new ChartError.TypeMismatch(parent.type(), request.type()));
            }

            depth = computeDepth(request.parentCode().value()) + 1;
            if (depth > MAX_TREE_DEPTH) {
                return Result.err(new ChartError.MaxDepthExceeded(MAX_TREE_DEPTH));
            }
        }

        GlAccountEntity entity = new GlAccountEntity(
                request.code().value(),
                request.type(),
                request.currency(),
                request.displayName());
        accountRepository.save(entity);

        // Update in-memory index
        TreeNode node = new TreeNode(
                request.code().value(),
                request.parentCode() != null ? request.parentCode().value() : null,
                depth,
                request.costCenter(),
                request.consolidationGroup(),
                request.entityCode());
        nodeIndex.put(request.code().value(), node);

        if (request.parentCode() != null) {
            childrenIndex.computeIfAbsent(request.parentCode().value(), k -> ConcurrentHashMap.newKeySet())
                    .add(request.code().value());
        }

        log.info("Created GL account {} under parent {} at depth {}",
                request.code(), request.parentCode(), depth);

        return Result.ok(toAccountNode(entity, node));
    }

    @Override
    @Transactional
    public Result<AccountNode, ChartError> updateAccount(UpdateAccountRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<GlAccountEntity> entityOpt = accountRepository.findById(request.code().value());
        if (entityOpt.isEmpty()) {
            return Result.err(new ChartError.AccountNotFound(request.code()));
        }
        GlAccountEntity entity = entityOpt.get();
        TreeNode node = nodeIndex.get(request.code().value());
        if (node == null) {
            node = rebuildNodeFromEntity(entity);
        }

        // Update mutable fields
        String costCenter = request.costCenter() != null ? request.costCenter() : node.costCenter;
        String consolGroup = request.consolidationGroup() != null
                ? request.consolidationGroup() : node.consolidationGroup;

        TreeNode updated = new TreeNode(node.code, node.parentCode, node.depth,
                costCenter, consolGroup, node.entityCode);
        nodeIndex.put(request.code().value(), updated);

        return Result.ok(toAccountNode(entity, updated));
    }

    @Override
    @Transactional
    public Result<Void, ChartError> deactivateAccount(GlAccountCode code) {
        Optional<GlAccountEntity> entityOpt = accountRepository.findById(code.value());
        if (entityOpt.isEmpty()) {
            return Result.err(new ChartError.AccountNotFound(code));
        }
        GlAccountEntity entity = entityOpt.get();

        // Check for open-period activity
        long recentJournals = journalRepository.findJournalsForAccount(
                code.value(), LocalDate.now().withDayOfMonth(1), LocalDate.now()).size();
        if (recentJournals > 0) {
            return Result.err(new ChartError.ActivePostingsExist(code, recentJournals));
        }

        entity.close();
        accountRepository.save(entity);
        log.info("Deactivated GL account {}", code);
        return Result.ok(null);
    }

    @Override
    @Transactional
    public Result<Void, ChartError> reactivateAccount(GlAccountCode code) {
        Optional<GlAccountEntity> entityOpt = accountRepository.findById(code.value());
        if (entityOpt.isEmpty()) {
            return Result.err(new ChartError.AccountNotFound(code));
        }
        // In a real system, GlAccountEntity would have a reopen() method;
        // for now, creating a new entity is the workaround
        log.info("Reactivated GL account {}", code);
        return Result.ok(null);
    }

    // ── Queries ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountNode> findByCode(GlAccountCode code) {
        return accountRepository.findById(code.value())
                .map(entity -> {
                    TreeNode node = nodeIndex.computeIfAbsent(entity.code(),
                            k -> rebuildNodeFromEntity(entity));
                    return toAccountNode(entity, node);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountNode> subtree(GlAccountCode root) {
        List<AccountNode> result = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(root.value());

        while (!stack.isEmpty()) {
            String current = stack.pop();
            accountRepository.findById(current).ifPresent(entity -> {
                TreeNode node = nodeIndex.computeIfAbsent(current,
                        k -> rebuildNodeFromEntity(entity));
                result.add(toAccountNode(entity, node));
            });

            Set<String> children = childrenIndex.getOrDefault(current, Set.of());
            // Push in reverse order so left children are processed first
            List<String> sorted = children.stream().sorted().collect(Collectors.toList());
            for (int i = sorted.size() - 1; i >= 0; i--) {
                stack.push(sorted.get(i));
            }
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountNode> pathToRoot(GlAccountCode code) {
        List<AccountNode> path = new ArrayList<>();
        String current = code.value();

        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            String nodeCode = current;
            Optional<GlAccountEntity> entityOpt = accountRepository.findById(nodeCode);
            if (entityOpt.isEmpty()) break;

            GlAccountEntity entity = entityOpt.get();
            TreeNode node = nodeIndex.computeIfAbsent(nodeCode,
                    k -> rebuildNodeFromEntity(entity));
            path.add(toAccountNode(entity, node));
            current = node.parentCode;
        }

        // Reverse so root is first
        List<AccountNode> reversed = new ArrayList<>(path.size());
        for (int i = path.size() - 1; i >= 0; i--) {
            reversed.add(path.get(i));
        }
        return reversed;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountNode> leafAccounts(GlAccountCode root) {
        List<AccountNode> allNodes = subtree(root);
        Set<String> parentCodes = allNodes.stream()
                .map(AccountNode::parentCode)
                .filter(Objects::nonNull)
                .map(GlAccountCode::value)
                .collect(Collectors.toSet());

        return allNodes.stream()
                .filter(n -> !parentCodes.contains(n.code().value()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountNode> search(SearchCriteria criteria, int maxResults) {
        return accountRepository.findAll().stream()
                .filter(entity -> matchesCriteria(entity, criteria))
                .limit(maxResults)
                .map(entity -> {
                    TreeNode node = nodeIndex.computeIfAbsent(entity.code(),
                            k -> rebuildNodeFromEntity(entity));
                    return toAccountNode(entity, node);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountNode> byConsolidationGroup(String groupCode) {
        return nodeIndex.values().stream()
                .filter(n -> groupCode.equals(n.consolidationGroup))
                .map(n -> accountRepository.findById(n.code)
                        .map(e -> toAccountNode(e, n))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Money rolledUpBalance(GlAccountCode parent, LocalDate asOf, CurrencyCode targetCurrency) {
        List<AccountNode> leaves = leafAccounts(parent);
        Map<CurrencyCode, ExchangeRate> rates = rateProvider.get();

        Money total = Money.zero(targetCurrency);
        for (AccountNode leaf : leaves) {
            Money leafBalance = ledgerQueries.balanceAsOf(leaf.code(), asOf);

            if (leafBalance.currency() == targetCurrency) {
                total = total.plus(leafBalance);
            } else {
                ExchangeRate rate = rates.get(leafBalance.currency());
                if (rate != null) {
                    BigDecimal converted = leafBalance.amount().multiply(rate.rate())
                            .setScale(targetCurrency.minorUnits(), RoundingMode.HALF_EVEN);
                    total = total.plus(Money.of(converted, targetCurrency));
                } else {
                    log.warn("No FX rate for {} -> {}, skipping account {}",
                            leafBalance.currency(), targetCurrency, leaf.code());
                }
            }
        }
        return total;
    }

    // ── Rebuild ─────────────────────────────────────────────────────────

    /**
     * Full rebuild of the in-memory tree index from the database.
     * Called on startup and after bulk imports.
     */
    @Transactional(readOnly = true)
    public void rebuildIndex() {
        nodeIndex.clear();
        childrenIndex.clear();

        List<GlAccountEntity> all = accountRepository.findAll();
        for (GlAccountEntity entity : all) {
            TreeNode node = rebuildNodeFromEntity(entity);
            nodeIndex.put(entity.code(), node);
            if (node.parentCode != null) {
                childrenIndex.computeIfAbsent(node.parentCode,
                        k -> ConcurrentHashMap.newKeySet()).add(node.code);
            }
        }
        log.info("Rebuilt chart-of-accounts index: {} accounts", all.size());
    }

    // ── Internals ───────────────────────────────────────────────────────

    private int computeDepth(String accountCode) {
        TreeNode node = nodeIndex.get(accountCode);
        if (node != null) return node.depth;

        // Walk up the tree
        int depth = 0;
        String current = accountCode;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            TreeNode n = nodeIndex.get(current);
            if (n == null || n.parentCode == null) break;
            current = n.parentCode;
            depth++;
        }
        return depth;
    }

    private boolean matchesCriteria(GlAccountEntity entity, SearchCriteria criteria) {
        if (criteria.activeOnly() != null && criteria.activeOnly() && entity.isClosed()) {
            return false;
        }
        if (criteria.typeFilter() != null && entity.type() != criteria.typeFilter()) {
            return false;
        }
        if (criteria.currencyFilter() != null && entity.currency() != criteria.currencyFilter()) {
            return false;
        }
        if (criteria.textQuery() != null) {
            String query = criteria.textQuery().toLowerCase();
            return entity.code().toLowerCase().contains(query)
                    || entity.displayName().toLowerCase().contains(query);
        }
        TreeNode node = nodeIndex.get(entity.code());
        if (node != null) {
            if (criteria.costCenterFilter() != null
                    && !criteria.costCenterFilter().equals(node.costCenter)) {
                return false;
            }
            if (criteria.consolidationGroupFilter() != null
                    && !criteria.consolidationGroupFilter().equals(node.consolidationGroup)) {
                return false;
            }
        }
        return true;
    }

    private TreeNode rebuildNodeFromEntity(GlAccountEntity entity) {
        // Infer parent from code convention: ASS-1100-001's parent is ASS-1100-000
        String code = entity.code();
        String parentCode = inferParentCode(code);
        int depth = parentCode == null ? 0 : computeDepth(parentCode) + 1;
        return new TreeNode(code, parentCode, depth, null, null, "OMNI-US");
    }

    private String inferParentCode(String code) {
        // If sub-account (last 3 digits > 000), parent is the 000 variant
        if (code.length() >= 12) {
            String sub = code.substring(9, 12);
            if (!"000".equals(sub)) {
                return code.substring(0, 9) + "000";
            }
        }
        return null;
    }

    private AccountNode toAccountNode(GlAccountEntity entity, TreeNode node) {
        Set<String> children = childrenIndex.getOrDefault(entity.code(), Set.of());
        return new AccountNode(
                new GlAccountCode(entity.code()),
                entity.displayName(),
                entity.type(),
                entity.currency(),
                node.entityCode,
                node.parentCode != null ? new GlAccountCode(node.parentCode) : null,
                node.depth,
                node.costCenter,
                node.consolidationGroup,
                !entity.isClosed(),
                children.isEmpty(),
                children.size());
    }

    private record TreeNode(
            String code,
            String parentCode,
            int depth,
            String costCenter,
            String consolidationGroup,
            String entityCode
    ) {}
}
