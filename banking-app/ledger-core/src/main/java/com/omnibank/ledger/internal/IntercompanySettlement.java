package com.omnibank.ledger.internal;

import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.IntercompanyTransaction;
import com.omnibank.ledger.api.IntercompanyTransaction.AllocationLine;
import com.omnibank.ledger.api.IntercompanyTransaction.IcValidationError;
import com.omnibank.ledger.api.IntercompanyTransaction.NetDirection;
import com.omnibank.ledger.api.IntercompanyTransaction.NettingResult;
import com.omnibank.ledger.api.IntercompanyTransaction.PositionSummary;
import com.omnibank.ledger.api.IntercompanyTransaction.Status;
import com.omnibank.ledger.api.IntercompanyTransaction.TransactionType;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostedJournal;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Result;
import com.omnibank.shared.security.PrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages intercompany transactions between legal entities within the
 * Omnibank group. IC transactions require dual-entity bookkeeping:
 * a receivable in the source entity and a payable in the target entity
 * (or vice versa depending on the flow direction).
 *
 * <p>The settlement process supports:
 * <ul>
 *   <li>Bilateral netting — aggregating offsetting IC balances between
 *       entity pairs to minimize cash movements</li>
 *   <li>Multilateral netting — optimizing across multiple entity pairs
 *       via a central treasury entity</li>
 *   <li>Elimination entry generation — for consolidated financial reporting
 *       (eliminates IC receivables/payables so they don't inflate the
 *       consolidated balance sheet)</li>
 * </ul>
 */
public class IntercompanySettlement {

    private static final Logger log = LoggerFactory.getLogger(IntercompanySettlement.class);

    /** Known legal entities within the group. */
    private static final Set<String> KNOWN_ENTITIES = Set.of(
            "OMNI-US", "OMNI-UK", "OMNI-EU", "OMNI-SG", "OMNI-HK", "OMNI-JP");

    /** IC receivable account pattern per entity. */
    private static final String IC_RECEIVABLE_TEMPLATE = "ASS-1800-%s";
    /** IC payable account pattern per entity. */
    private static final String IC_PAYABLE_TEMPLATE = "LIA-2800-%s";

    private final PostingService postingService;
    private final Clock clock;

    /** Transaction store (in production, this would be a JPA entity). */
    private final ConcurrentHashMap<UUID, IntercompanyTransaction> transactions = new ConcurrentHashMap<>();

    /** Netting results store. */
    private final ConcurrentHashMap<UUID, NettingResult> nettingResults = new ConcurrentHashMap<>();

    public IntercompanySettlement(PostingService postingService, Clock clock) {
        this.postingService = postingService;
        this.clock = clock;
    }

    // ── Transaction Management ──────────────────────────────────────────

    /**
     * Submit a new intercompany transaction for processing. The transaction
     * is validated and stored in PENDING_APPROVAL status.
     */
    @Transactional
    public Result<IntercompanyTransaction, IcValidationError> submit(
            String sourceEntity, String targetEntity, Money amount,
            TransactionType type, LocalDate valueDate,
            String description, List<AllocationLine> allocations) {

        // Validate entities
        if (!KNOWN_ENTITIES.contains(sourceEntity)) {
            return Result.err(new IcValidationError.UnknownEntity(sourceEntity));
        }
        if (!KNOWN_ENTITIES.contains(targetEntity)) {
            return Result.err(new IcValidationError.UnknownEntity(targetEntity));
        }
        if (sourceEntity.equals(targetEntity)) {
            return Result.err(new IcValidationError.SameEntity(sourceEntity));
        }

        // Validate allocations sum to 100%
        if (allocations != null && !allocations.isEmpty()) {
            BigDecimal totalPct = allocations.stream()
                    .map(AllocationLine::percentage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalPct.compareTo(BigDecimal.valueOf(100)) != 0) {
                return Result.err(new IcValidationError.InvalidAllocations(
                        "Allocation percentages sum to " + totalPct + ", must be 100"));
            }
        }

        String businessKey = "IC:%s:%s:%s:%s".formatted(
                sourceEntity, targetEntity, type, UUID.randomUUID().toString().substring(0, 8));

        IntercompanyTransaction tx = new IntercompanyTransaction(
                UUID.randomUUID(), sourceEntity, targetEntity, amount,
                amount.currency(), type, Status.PENDING_APPROVAL,
                valueDate, Instant.now(clock),
                PrincipalContext.current().getName(),
                businessKey, description, allocations);

        transactions.put(tx.transactionId(), tx);
        log.info("Submitted IC transaction {} from {} to {} for {}",
                tx.transactionId(), sourceEntity, targetEntity, amount);

        return Result.ok(tx);
    }

    /**
     * Approve a pending IC transaction. In production, this would require
     * approval from both the source and target entity controllers.
     */
    @Transactional
    public Result<IntercompanyTransaction, IcValidationError> approve(UUID transactionId) {
        IntercompanyTransaction tx = transactions.get(transactionId);
        if (tx == null) {
            return Result.err(new IcValidationError.UnknownEntity(transactionId.toString()));
        }

        IntercompanyTransaction approved = new IntercompanyTransaction(
                tx.transactionId(), tx.sourceEntity(), tx.targetEntity(),
                tx.amount(), tx.settlementCurrency(), tx.type(),
                Status.APPROVED, tx.valueDate(), tx.createdAt(),
                tx.createdBy(), tx.businessKey(), tx.description(),
                tx.allocations());

        transactions.put(transactionId, approved);
        log.info("Approved IC transaction {}", transactionId);
        return Result.ok(approved);
    }

    /**
     * Post the journal entries for an approved IC transaction. Creates
     * mirror entries in both entities' ledgers.
     */
    @Transactional
    public List<PostedJournal> postTransaction(UUID transactionId) {
        IntercompanyTransaction tx = transactions.get(transactionId);
        Objects.requireNonNull(tx, "Transaction not found: " + transactionId);

        if (tx.status() != Status.APPROVED) {
            throw new IllegalStateException(
                    "Cannot post IC transaction in status " + tx.status());
        }

        List<PostedJournal> posted = new ArrayList<>();

        // Source entity entry: Debit IC Receivable, Credit Revenue/Expense
        GlAccountCode sourceReceivable = icReceivableAccount(tx.targetEntity());
        GlAccountCode sourceRevenue = resolveIcRevenueAccount(tx.type(), tx.sourceEntity());

        JournalEntry sourceEntry = new JournalEntry(
                UUID.randomUUID(), tx.valueDate(),
                tx.businessKey() + ":SOURCE",
                "IC %s to %s: %s".formatted(tx.sourceEntity(), tx.targetEntity(), tx.description()),
                List.of(
                        PostingLine.debit(sourceReceivable, tx.amount(),
                                "IC receivable from " + tx.targetEntity()),
                        PostingLine.credit(sourceRevenue, tx.amount(),
                                "IC " + tx.type() + " to " + tx.targetEntity())));
        posted.add(postingService.post(sourceEntry));

        // Target entity entry: Debit Expense/Asset, Credit IC Payable
        GlAccountCode targetPayable = icPayableAccount(tx.sourceEntity());
        GlAccountCode targetExpense = resolveIcExpenseAccount(tx.type(), tx.targetEntity());

        JournalEntry targetEntry = new JournalEntry(
                UUID.randomUUID(), tx.valueDate(),
                tx.businessKey() + ":TARGET",
                "IC from %s to %s: %s".formatted(tx.sourceEntity(), tx.targetEntity(), tx.description()),
                List.of(
                        PostingLine.debit(targetExpense, tx.amount(),
                                "IC " + tx.type() + " from " + tx.sourceEntity()),
                        PostingLine.credit(targetPayable, tx.amount(),
                                "IC payable to " + tx.sourceEntity())));
        posted.add(postingService.post(targetEntry));

        // Update status
        IntercompanyTransaction updated = new IntercompanyTransaction(
                tx.transactionId(), tx.sourceEntity(), tx.targetEntity(),
                tx.amount(), tx.settlementCurrency(), tx.type(),
                Status.POSTED, tx.valueDate(), tx.createdAt(),
                tx.createdBy(), tx.businessKey(), tx.description(),
                tx.allocations());
        transactions.put(transactionId, updated);

        log.info("Posted IC transaction {} with {} journal entries", transactionId, posted.size());
        return posted;
    }

    // ── Netting ─────────────────────────────────────────────────────────

    /**
     * Perform bilateral netting between two entities. Aggregates all posted
     * IC transactions between the pair and produces a single net settlement.
     */
    @Transactional
    public NettingResult performBilateralNetting(
            String entityA, String entityB, LocalDate settlementDate) {

        Objects.requireNonNull(entityA, "entityA");
        Objects.requireNonNull(entityB, "entityB");

        // Find all posted (not yet netted) transactions between the pair
        List<IntercompanyTransaction> eligible = transactions.values().stream()
                .filter(tx -> tx.status() == Status.POSTED)
                .filter(tx -> (tx.sourceEntity().equals(entityA) && tx.targetEntity().equals(entityB))
                        || (tx.sourceEntity().equals(entityB) && tx.targetEntity().equals(entityA)))
                .toList();

        CurrencyCode ccy = eligible.isEmpty()
                ? CurrencyCode.USD
                : eligible.get(0).settlementCurrency();

        Money aToB = Money.zero(ccy);
        Money bToA = Money.zero(ccy);
        List<UUID> includedIds = new ArrayList<>();

        for (IntercompanyTransaction tx : eligible) {
            includedIds.add(tx.transactionId());
            if (tx.sourceEntity().equals(entityA)) {
                aToB = aToB.plus(tx.amount());
            } else {
                bToA = bToA.plus(tx.amount());
            }
        }

        Money netAmount = aToB.minus(bToA);
        NetDirection direction;
        if (netAmount.isPositive()) {
            direction = NetDirection.B_TO_A;
        } else if (netAmount.isNegative()) {
            direction = NetDirection.A_TO_B;
            netAmount = netAmount.abs();
        } else {
            direction = NetDirection.ZERO;
        }

        NettingResult result = new NettingResult(
                UUID.randomUUID(), entityA, entityB,
                aToB, bToA, netAmount, direction,
                settlementDate, includedIds);

        nettingResults.put(result.nettingId(), result);

        // Update transaction statuses to NETTED
        for (UUID txId : includedIds) {
            IntercompanyTransaction tx = transactions.get(txId);
            IntercompanyTransaction netted = new IntercompanyTransaction(
                    tx.transactionId(), tx.sourceEntity(), tx.targetEntity(),
                    tx.amount(), tx.settlementCurrency(), tx.type(),
                    Status.NETTED, tx.valueDate(), tx.createdAt(),
                    tx.createdBy(), tx.businessKey(), tx.description(),
                    tx.allocations());
            transactions.put(txId, netted);
        }

        log.info("Netting complete: {} <-> {}, gross={}/{}, net={} direction={}",
                entityA, entityB, aToB, bToA, netAmount, direction);
        return result;
    }

    // ── Elimination Entries ─────────────────────────────────────────────

    /**
     * Generate elimination entries for consolidated reporting. These entries
     * zero out IC receivables and payables so they do not appear on the
     * consolidated balance sheet.
     */
    @Transactional
    public List<PostedJournal> generateEliminationEntries(
            String entityA, String entityB, LocalDate asOf) {

        List<PostedJournal> eliminations = new ArrayList<>();

        // Find netted transactions between the pair
        List<IntercompanyTransaction> netted = transactions.values().stream()
                .filter(tx -> tx.status() == Status.NETTED || tx.status() == Status.SETTLED)
                .filter(tx -> (tx.sourceEntity().equals(entityA) && tx.targetEntity().equals(entityB))
                        || (tx.sourceEntity().equals(entityB) && tx.targetEntity().equals(entityA)))
                .toList();

        Map<CurrencyCode, Money> receivableBySource = new HashMap<>();
        for (IntercompanyTransaction tx : netted) {
            receivableBySource.merge(tx.settlementCurrency(), tx.amount(), Money::plus);
        }

        for (var entry : receivableBySource.entrySet()) {
            CurrencyCode ccy = entry.getKey();
            Money amount = entry.getValue();

            if (amount.isZero()) continue;

            String elimKey = "ELIM:%s:%s:%s:%s".formatted(entityA, entityB, asOf, ccy);
            GlAccountCode receivable = icReceivableAccount(entityB);
            GlAccountCode payable = icPayableAccount(entityA);

            JournalEntry elimEntry = new JournalEntry(
                    UUID.randomUUID(), asOf, elimKey,
                    "IC elimination %s <-> %s".formatted(entityA, entityB),
                    List.of(
                            PostingLine.debit(payable, amount,
                                    "Eliminate IC payable"),
                            PostingLine.credit(receivable, amount,
                                    "Eliminate IC receivable")));

            PostedJournal posted = postingService.post(elimEntry);
            eliminations.add(posted);
        }

        log.info("Generated {} elimination entries for {} <-> {}",
                eliminations.size(), entityA, entityB);
        return eliminations;
    }

    // ── Position Reporting ──────────────────────────────────────────────

    /**
     * Compute the outstanding IC position for an entity against all
     * counterparties.
     */
    public List<PositionSummary> positionSummary(String entityCode) {
        Map<String, PositionAccumulator> byCounterparty = new HashMap<>();

        for (IntercompanyTransaction tx : transactions.values()) {
            if (tx.status() == Status.CANCELLED || tx.status() == Status.DISPUTED) continue;

            if (tx.sourceEntity().equals(entityCode)) {
                byCounterparty.computeIfAbsent(tx.targetEntity(),
                        k -> new PositionAccumulator(tx.settlementCurrency()))
                        .addReceivable(tx.amount());
            } else if (tx.targetEntity().equals(entityCode)) {
                byCounterparty.computeIfAbsent(tx.sourceEntity(),
                        k -> new PositionAccumulator(tx.settlementCurrency()))
                        .addPayable(tx.amount());
            }
        }

        List<PositionSummary> summaries = new ArrayList<>();
        for (var entry : byCounterparty.entrySet()) {
            PositionAccumulator acc = entry.getValue();
            Money net = acc.receivable.minus(acc.payable);
            int openCount = (int) transactions.values().stream()
                    .filter(tx -> tx.status() != Status.SETTLED
                            && tx.status() != Status.ELIMINATED
                            && tx.status() != Status.CANCELLED)
                    .filter(tx -> tx.sourceEntity().equals(entityCode)
                            && tx.targetEntity().equals(entry.getKey())
                            || tx.targetEntity().equals(entityCode)
                            && tx.sourceEntity().equals(entry.getKey()))
                    .count();

            summaries.add(new PositionSummary(
                    entityCode, entry.getKey(), acc.currency,
                    acc.receivable, acc.payable, net,
                    openCount, null));
        }
        return summaries;
    }

    // ── Internals ───────────────────────────────────────────────────────

    private GlAccountCode icReceivableAccount(String counterpartyEntity) {
        String suffix = entityToSuffix(counterpartyEntity);
        return new GlAccountCode(IC_RECEIVABLE_TEMPLATE.formatted(suffix));
    }

    private GlAccountCode icPayableAccount(String counterpartyEntity) {
        String suffix = entityToSuffix(counterpartyEntity);
        return new GlAccountCode(IC_PAYABLE_TEMPLATE.formatted(suffix));
    }

    private String entityToSuffix(String entityCode) {
        return switch (entityCode) {
            case "OMNI-US" -> "001";
            case "OMNI-UK" -> "002";
            case "OMNI-EU" -> "003";
            case "OMNI-SG" -> "004";
            case "OMNI-HK" -> "005";
            case "OMNI-JP" -> "006";
            default -> "099";
        };
    }

    private GlAccountCode resolveIcRevenueAccount(TransactionType type, String entity) {
        return switch (type) {
            case MANAGEMENT_FEE -> new GlAccountCode("REV-4800-001");
            case REVENUE_SHARE -> new GlAccountCode("REV-4800-002");
            case INTERCOMPANY_INTEREST -> new GlAccountCode("REV-4500-001");
            default -> new GlAccountCode("REV-4800-000");
        };
    }

    private GlAccountCode resolveIcExpenseAccount(TransactionType type, String entity) {
        return switch (type) {
            case MANAGEMENT_FEE -> new GlAccountCode("EXP-6800-001");
            case COST_ALLOCATION -> new GlAccountCode("EXP-6800-002");
            case INTERCOMPANY_INTEREST -> new GlAccountCode("EXP-7500-001");
            default -> new GlAccountCode("EXP-6800-000");
        };
    }

    private static final class PositionAccumulator {
        final CurrencyCode currency;
        Money receivable;
        Money payable;

        PositionAccumulator(CurrencyCode currency) {
            this.currency = currency;
            this.receivable = Money.zero(currency);
            this.payable = Money.zero(currency);
        }

        void addReceivable(Money amount) {
            if (amount.currency() == currency) {
                receivable = receivable.plus(amount);
            }
        }

        void addPayable(Money amount) {
            if (amount.currency() == currency) {
                payable = payable.plus(amount);
            }
        }
    }
}
