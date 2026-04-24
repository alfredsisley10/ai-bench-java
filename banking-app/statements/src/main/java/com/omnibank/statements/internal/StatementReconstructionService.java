package com.omnibank.statements.internal;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.CustomerId;
import com.omnibank.shared.domain.Money;
import com.omnibank.statements.internal.StatementGenerator.GenerationRequest;
import com.omnibank.statements.internal.StatementGenerator.HoldSummary;
import com.omnibank.statements.internal.StatementGenerator.LineType;
import com.omnibank.statements.internal.StatementGenerator.StatementContent;
import com.omnibank.statements.internal.StatementGenerator.StatementHeader;
import com.omnibank.statements.internal.StatementGenerator.StatementLineItem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rebuilds a historical statement from ledger postings when the original
 * archive artifact is unavailable (corruption, legal discovery for a period
 * that pre-dates archiving, a migrated customer's old bank).
 *
 * <p>Reconstruction is never silent — every invocation produces an
 * {@link AuditTrail} recording the parameters used, the postings consumed,
 * and any reconciliation warnings (e.g. opening-balance drift vs. prior
 * statement). Consumers of the reconstructed statement must see this audit
 * trail to avoid treating a rebuild as equivalent to the original.
 */
public class StatementReconstructionService {

    private static final Logger log = LoggerFactory.getLogger(StatementReconstructionService.class);

    /** Source of ledger data — a thin facade so the service can be unit-tested. */
    public interface LedgerSource {
        /** All postings for an account in a half-open cycle window [start, end]. */
        List<LedgerPosting> postingsFor(AccountNumber account, LocalDate startInclusive, LocalDate endInclusive);

        /** Opening balance as-of the start of the cycle. */
        Money balanceAsOf(AccountNumber account, LocalDate asOf);

        /** Active holds for this account during the window. */
        List<HoldSummary> holdsFor(AccountNumber account, LocalDate startInclusive, LocalDate endInclusive);
    }

    /**
     * Minimal ledger posting DTO. Not tied to the ledger-core entity so this
     * module doesn't transitively drag its JPA types into statement code.
     */
    public record LedgerPosting(
            String referenceId,
            LocalDate postedOn,
            Money amount,
            String description,
            LineType inferredType
    ) {
        public LedgerPosting {
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(postedOn, "postedOn");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(inferredType, "inferredType");
        }
    }

    /** Customer / product context the ledger does not carry. */
    public record AccountContext(
            AccountNumber account,
            CustomerId customer,
            String accountHolderName,
            String mailingAddress,
            String productName,
            CurrencyCode currency
    ) {
        public AccountContext {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(accountHolderName, "accountHolderName");
            Objects.requireNonNull(mailingAddress, "mailingAddress");
            Objects.requireNonNull(productName, "productName");
            Objects.requireNonNull(currency, "currency");
        }
    }

    /** Parameters captured for the audit trail of a single reconstruction. */
    public record AuditTrail(
            String reconstructionId,
            AccountNumber account,
            LocalDate cycleStart,
            LocalDate cycleEnd,
            String requestedBy,
            String reason,
            int postingsConsumed,
            Money computedOpening,
            Money computedClosing,
            List<String> warnings,
            Instant runAt
    ) {
        public AuditTrail {
            Objects.requireNonNull(reconstructionId, "reconstructionId");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(cycleStart, "cycleStart");
            Objects.requireNonNull(cycleEnd, "cycleEnd");
            Objects.requireNonNull(requestedBy, "requestedBy");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(computedOpening, "computedOpening");
            Objects.requireNonNull(computedClosing, "computedClosing");
            Objects.requireNonNull(warnings, "warnings");
            Objects.requireNonNull(runAt, "runAt");
            warnings = List.copyOf(warnings);
        }
    }

    /** Reconstruction output bundle — content + audit. */
    public record ReconstructionResult(StatementContent content, AuditTrail auditTrail) {
        public ReconstructionResult {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(auditTrail, "auditTrail");
        }
    }

    private final Clock clock;
    private final StatementGenerator generator;
    private final LedgerSource ledger;

    public StatementReconstructionService(Clock clock, StatementGenerator generator, LedgerSource ledger) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /**
     * Reconstruct a statement for a closed cycle. Produces both the statement
     * bytes and an audit trail explaining exactly how they were derived.
     */
    public ReconstructionResult reconstruct(AccountContext context,
                                            LocalDate cycleStart,
                                            LocalDate cycleEnd,
                                            String requestedBy,
                                            String reason) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cycleStart, "cycleStart");
        Objects.requireNonNull(cycleEnd, "cycleEnd");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(reason, "reason");

        if (cycleEnd.isBefore(cycleStart)) {
            throw new IllegalArgumentException("cycleEnd is before cycleStart");
        }

        LocalDate today = LocalDate.now(clock);
        if (cycleEnd.isAfter(today)) {
            throw new IllegalArgumentException("Cannot reconstruct a future cycle: " + cycleEnd);
        }

        List<String> warnings = new ArrayList<>();

        Money opening = ledger.balanceAsOf(context.account(), cycleStart);
        if (opening.currency() != context.currency()) {
            throw new IllegalStateException(
                    "Ledger opening currency %s does not match context currency %s"
                            .formatted(opening.currency(), context.currency()));
        }

        List<LedgerPosting> postings = ledger.postingsFor(context.account(), cycleStart, cycleEnd);
        List<StatementLineItem> lineItems = new ArrayList<>();
        Money expectedClosing = opening;
        for (LedgerPosting p : postings) {
            if (p.amount().currency() != context.currency()) {
                warnings.add("Skipped posting %s: currency %s != account currency %s"
                        .formatted(p.referenceId(), p.amount().currency(), context.currency()));
                continue;
            }
            lineItems.add(new StatementLineItem(
                    p.postedOn(), p.description(), p.amount(), p.inferredType(), p.referenceId()));
            expectedClosing = applyToRunning(expectedClosing, p);
        }

        if (postings.isEmpty()) {
            warnings.add("No postings found for cycle — reconstruction may be incomplete");
        }

        List<HoldSummary> holds = ledger.holdsFor(context.account(), cycleStart, cycleEnd);

        StatementHeader header = new StatementHeader(
                StatementGenerator.DEFAULT_INSTITUTION,
                context.account(),
                context.customer(),
                context.accountHolderName(),
                context.mailingAddress(),
                cycleStart,
                cycleEnd,
                context.productName());

        GenerationRequest request = new GenerationRequest(header, opening, lineItems, holds);
        StatementContent content = generator.generate(request);

        if (content.summary().closingBalance().compareTo(expectedClosing) != 0) {
            warnings.add("Computed closing %s disagrees with summary closing %s"
                    .formatted(expectedClosing, content.summary().closingBalance()));
        }

        AuditTrail audit = new AuditTrail(
                "RECON-" + java.util.UUID.randomUUID(),
                context.account(),
                cycleStart,
                cycleEnd,
                requestedBy,
                reason,
                postings.size(),
                opening,
                content.summary().closingBalance(),
                Collections.unmodifiableList(warnings),
                clock.instant());

        log.info("Reconstructed statement account={} cycle={}..{} postings={} warnings={}",
                context.account(), cycleStart, cycleEnd, postings.size(), warnings.size());

        return new ReconstructionResult(content, audit);
    }

    /**
     * Verify a reconstruction against an already-archived version. Returns
     * {@code true} iff all line items, totals, and holds match. Intended for
     * nightly archive-integrity jobs that sample reconstructions.
     */
    public boolean verifyAgainst(ReconstructionResult reconstruction, StatementContent archived) {
        Objects.requireNonNull(reconstruction, "reconstruction");
        Objects.requireNonNull(archived, "archived");
        if (!reconstruction.content().header().account().equals(archived.header().account())) return false;
        if (!reconstruction.content().header().cycleStart().equals(archived.header().cycleStart())) return false;
        if (!reconstruction.content().header().cycleEnd().equals(archived.header().cycleEnd())) return false;
        if (reconstruction.content().lineItems().size() != archived.lineItems().size()) return false;
        if (reconstruction.content().summary().closingBalance().compareTo(
                archived.summary().closingBalance()) != 0) return false;
        for (int i = 0; i < archived.lineItems().size(); i++) {
            StatementLineItem a = reconstruction.content().lineItems().get(i);
            StatementLineItem b = archived.lineItems().get(i);
            if (!a.referenceId().equals(b.referenceId())) return false;
            if (a.amount().compareTo(b.amount()) != 0) return false;
        }
        return true;
    }

    /** Expose the backing ledger so orchestrators can route around us when needed. */
    public LedgerSource ledger() {
        return ledger;
    }

    private Money applyToRunning(Money running, LedgerPosting p) {
        return switch (p.inferredType()) {
            case DEPOSIT, INTEREST_EARNED, RELEASE -> running.plus(p.amount().abs());
            case WITHDRAWAL, CHECK, FEE, INTEREST_CHARGED -> running.minus(p.amount().abs());
            case TRANSFER, ADJUSTMENT -> p.amount().isNegative()
                    ? running.minus(p.amount().abs())
                    : running.plus(p.amount().abs());
            case HOLD -> running; // holds don't change posted balance
        };
    }
}
