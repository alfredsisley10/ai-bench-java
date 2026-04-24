package com.omnibank.cards.internal;

import com.omnibank.ledger.api.GlAccountCode;
import com.omnibank.ledger.api.JournalEntry;
import com.omnibank.ledger.api.PostingLine;
import com.omnibank.ledger.api.PostingService;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chargeback and dispute workflow engine. Drives every dispute from intake
 * through arbitration, enforcing Reg E (debit cards) and Reg Z (credit
 * cards) timelines while tracking the back-and-forth between cardholder,
 * issuer, and acquirer / merchant.
 *
 * <p>State machine:
 * <pre>
 *   SUBMITTED -> UNDER_REVIEW -> PROVISIONAL_CREDIT_ISSUED -> MERCHANT_REPRESENTED
 *                    \                                          /         \
 *                     \-> RESOLVED_CARDHOLDER                  v           \-> PRE_ARBITRATION
 *                                                              \                   \
 *                                                               \-> RESOLVED_MERCHANT \
 *                                                                                      \
 *                                                                                       \-> ARBITRATION -> FINAL_RESOLVED
 * </pre>
 *
 * <p>Key deadlines enforced:
 * <ul>
 *   <li><b>Reg E:</b> 10 business days to provisional credit on debit disputes.</li>
 *   <li><b>Reg Z:</b> 30 days to acknowledge credit card disputes, 2 billing
 *       cycles to resolve (up to 90 days).</li>
 *   <li><b>Network chargeback window:</b> 120 days from transaction date for
 *       most reason codes.</li>
 * </ul>
 */
@Service
public class CardDisputeManager {

    private static final Logger log = LoggerFactory.getLogger(CardDisputeManager.class);

    /** High-level dispute state. */
    public enum DisputeStatus {
        SUBMITTED,
        UNDER_REVIEW,
        PROVISIONAL_CREDIT_ISSUED,
        MERCHANT_REPRESENTED,
        PRE_ARBITRATION,
        ARBITRATION,
        RESOLVED_CARDHOLDER,
        RESOLVED_MERCHANT,
        FINAL_RESOLVED,
        WITHDRAWN
    }

    /** Valid transitions — anything else throws. */
    private static final Map<DisputeStatus, Set<DisputeStatus>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = new EnumMap<>(DisputeStatus.class);
        ALLOWED_TRANSITIONS.put(DisputeStatus.SUBMITTED,
                EnumSet.of(DisputeStatus.UNDER_REVIEW, DisputeStatus.WITHDRAWN));
        ALLOWED_TRANSITIONS.put(DisputeStatus.UNDER_REVIEW,
                EnumSet.of(DisputeStatus.PROVISIONAL_CREDIT_ISSUED,
                        DisputeStatus.RESOLVED_CARDHOLDER,
                        DisputeStatus.RESOLVED_MERCHANT,
                        DisputeStatus.WITHDRAWN));
        ALLOWED_TRANSITIONS.put(DisputeStatus.PROVISIONAL_CREDIT_ISSUED,
                EnumSet.of(DisputeStatus.MERCHANT_REPRESENTED,
                        DisputeStatus.RESOLVED_CARDHOLDER,
                        DisputeStatus.RESOLVED_MERCHANT));
        ALLOWED_TRANSITIONS.put(DisputeStatus.MERCHANT_REPRESENTED,
                EnumSet.of(DisputeStatus.PRE_ARBITRATION,
                        DisputeStatus.RESOLVED_CARDHOLDER,
                        DisputeStatus.RESOLVED_MERCHANT));
        ALLOWED_TRANSITIONS.put(DisputeStatus.PRE_ARBITRATION,
                EnumSet.of(DisputeStatus.ARBITRATION,
                        DisputeStatus.RESOLVED_CARDHOLDER,
                        DisputeStatus.RESOLVED_MERCHANT));
        ALLOWED_TRANSITIONS.put(DisputeStatus.ARBITRATION,
                EnumSet.of(DisputeStatus.FINAL_RESOLVED));
        ALLOWED_TRANSITIONS.put(DisputeStatus.RESOLVED_CARDHOLDER, EnumSet.noneOf(DisputeStatus.class));
        ALLOWED_TRANSITIONS.put(DisputeStatus.RESOLVED_MERCHANT, EnumSet.noneOf(DisputeStatus.class));
        ALLOWED_TRANSITIONS.put(DisputeStatus.FINAL_RESOLVED, EnumSet.noneOf(DisputeStatus.class));
        ALLOWED_TRANSITIONS.put(DisputeStatus.WITHDRAWN, EnumSet.noneOf(DisputeStatus.class));
    }

    /** Canonical network dispute reason codes we accept at intake. */
    public enum DisputeReason {
        FRAUD,                  // 10.4 / 83
        UNAUTHORIZED,           // Reg E unauthorized use
        SERVICE_NOT_PROVIDED,   // 13.1
        DEFECTIVE_MERCHANDISE,  // 13.3
        NOT_AS_DESCRIBED,       // 13.5
        DUPLICATE_CHARGE,       // 12.6.1
        INCORRECT_AMOUNT,       // 12.5
        CREDIT_NOT_PROCESSED,   // 13.6
        CANCELED_RECURRING      // 13.2
    }

    /** In-memory dispute record — production would be a JPA entity. */
    public record Dispute(
            UUID disputeId,
            UUID authorizationId,
            UUID cardId,
            Money disputedAmount,
            DisputeReason reason,
            DisputeStatus status,
            boolean creditCardDispute,
            Instant filedAt,
            LocalDate transactionDate,
            LocalDate regEDeadline,
            LocalDate regZAcknowledgeDeadline,
            LocalDate regZResolveDeadline,
            boolean provisionalCreditIssued,
            Money provisionalCreditAmount,
            String currentNotes,
            int transitionCount
    ) {
        public Dispute withStatus(DisputeStatus next, String note) {
            return new Dispute(disputeId, authorizationId, cardId, disputedAmount, reason,
                    next, creditCardDispute, filedAt, transactionDate,
                    regEDeadline, regZAcknowledgeDeadline, regZResolveDeadline,
                    provisionalCreditIssued, provisionalCreditAmount, note,
                    transitionCount + 1);
        }

        public Dispute withProvisionalCredit(Money amount) {
            return new Dispute(disputeId, authorizationId, cardId, disputedAmount, reason,
                    DisputeStatus.PROVISIONAL_CREDIT_ISSUED, creditCardDispute, filedAt,
                    transactionDate, regEDeadline, regZAcknowledgeDeadline, regZResolveDeadline,
                    true, amount, currentNotes, transitionCount + 1);
        }
    }

    /** Domain event published on every workflow transition. */
    public record DisputeEvent(
            UUID eventId,
            Instant occurredAt,
            UUID disputeId,
            UUID cardId,
            DisputeStatus previousStatus,
            DisputeStatus newStatus,
            String note) implements DomainEvent {

        @Override
        public String eventType() {
            return "cards.dispute_" + newStatus.name().toLowerCase();
        }
    }

    /** Result of a deadline sweep — helper struct for the scheduled task. */
    public record DeadlineSweepResult(
            List<UUID> regEProvisionalCreditIssued,
            List<UUID> regZAcknowledgementsDue,
            List<UUID> resolutionsPastDue
    ) {}

    private static final GlAccountCode CARDHOLDER_RECEIVABLE = new GlAccountCode("ASS-1400-001");
    private static final GlAccountCode CHARGEBACK_SUSPENSE = new GlAccountCode("ASS-1400-005");
    private static final GlAccountCode CHARGEBACK_WRITEOFF = new GlAccountCode("EXP-5100-010");

    private final Map<UUID, Dispute> store = new ConcurrentHashMap<>();
    private final PostingService posting;
    private final EventBus events;
    private final Clock clock;

    public CardDisputeManager(PostingService posting, EventBus events, Clock clock) {
        this.posting = Objects.requireNonNull(posting, "posting");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Intake: create a new dispute in the SUBMITTED state. Computes the
     * Reg E / Reg Z deadlines at intake.
     */
    @Transactional
    public Dispute fileDispute(UUID authorizationId,
                               UUID cardId,
                               Money amount,
                               DisputeReason reason,
                               boolean isCreditCard,
                               LocalDate transactionDate) {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(transactionDate, "transactionDate");

        var now = Timestamp.now(clock);
        var filedDate = now.atZone(Timestamp.BANK_ZONE).toLocalDate();
        var chargebackWindow = filedDate.minusDays(120);
        if (transactionDate.isBefore(chargebackWindow)) {
            throw new IllegalArgumentException(
                    "Transaction date " + transactionDate
                            + " is outside chargeback window (120 days)");
        }

        var dispute = new Dispute(
                UUID.randomUUID(), authorizationId, cardId, amount, reason,
                DisputeStatus.SUBMITTED, isCreditCard, now, transactionDate,
                filedDate.plusDays(10),             // Reg E provisional credit deadline
                filedDate.plusDays(30),             // Reg Z acknowledgement deadline
                filedDate.plusDays(90),             // Reg Z resolve deadline (2 cycles)
                false, null,
                "Filed by cardholder", 0);
        store.put(dispute.disputeId(), dispute);

        log.info("Dispute filed: id={}, card={}, amount={}, reason={}, creditCard={}",
                dispute.disputeId(), cardId, amount, reason, isCreditCard);
        publishEvent(null, dispute, "Dispute filed");
        return dispute;
    }

    /** Move a dispute into review status — assigns a case manager implicitly. */
    @Transactional
    public Dispute beginReview(UUID disputeId, String investigatorId) {
        Objects.requireNonNull(investigatorId, "investigatorId");
        return transition(disputeId, DisputeStatus.UNDER_REVIEW,
                "Review started by " + investigatorId);
    }

    /**
     * Issue provisional credit back to the cardholder — mandatory within
     * 10 business days for Reg E claims. Posts to suspense until the
     * merchant rep / arbitration phase closes.
     */
    @Transactional
    public Dispute issueProvisionalCredit(UUID disputeId, Money amount) {
        var dispute = requireDispute(disputeId);
        if (dispute.provisionalCreditIssued()) {
            log.warn("Provisional credit already issued for dispute {}", disputeId);
            return dispute;
        }
        if (dispute.status() != DisputeStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "Provisional credit requires UNDER_REVIEW, was " + dispute.status());
        }

        var je = new JournalEntry(
                UUID.randomUUID(), LocalDate.now(clock),
                "DISP-PROV-CR-" + disputeId,
                "Provisional credit for dispute " + disputeId,
                List.of(
                        PostingLine.debit(CHARGEBACK_SUSPENSE, amount,
                                "Chargeback suspense reserve"),
                        PostingLine.credit(CARDHOLDER_RECEIVABLE, amount,
                                "Provisional credit to cardholder")
                ));
        posting.post(je);

        var updated = dispute.withProvisionalCredit(amount);
        store.put(disputeId, updated);

        log.info("Provisional credit issued: dispute={}, amount={}", disputeId, amount);
        publishEvent(dispute.status(), updated, "Provisional credit issued");
        return updated;
    }

    /** Record the merchant's representment — re-presenting the charge back to issuer. */
    @Transactional
    public Dispute merchantRepresent(UUID disputeId, String evidenceSummary) {
        Objects.requireNonNull(evidenceSummary, "evidenceSummary");
        return transition(disputeId, DisputeStatus.MERCHANT_REPRESENTED,
                "Merchant represented: " + evidenceSummary);
    }

    /** Escalate to pre-arbitration (issuer rebuts the representment). */
    @Transactional
    public Dispute preArbitration(UUID disputeId, String issuerRebuttal) {
        Objects.requireNonNull(issuerRebuttal, "issuerRebuttal");
        return transition(disputeId, DisputeStatus.PRE_ARBITRATION,
                "Pre-arbitration filed: " + issuerRebuttal);
    }

    /** Escalate to arbitration — binding network ruling. */
    @Transactional
    public Dispute arbitration(UUID disputeId, String arbitrationFilingRef) {
        Objects.requireNonNull(arbitrationFilingRef, "arbitrationFilingRef");
        return transition(disputeId, DisputeStatus.ARBITRATION,
                "Arbitration filed: " + arbitrationFilingRef);
    }

    /**
     * Resolve in favor of the cardholder — makes the provisional credit
     * permanent and writes off the chargeback suspense against expense.
     */
    @Transactional
    public Dispute resolveForCardholder(UUID disputeId, String rationale) {
        var dispute = requireDispute(disputeId);
        if (dispute.provisionalCreditIssued()) {
            var je = new JournalEntry(
                    UUID.randomUUID(), LocalDate.now(clock),
                    "DISP-WRITEOFF-" + disputeId,
                    "Chargeback writeoff for " + disputeId,
                    List.of(
                            PostingLine.debit(CHARGEBACK_WRITEOFF,
                                    dispute.provisionalCreditAmount(),
                                    "Chargeback expense"),
                            PostingLine.credit(CHARGEBACK_SUSPENSE,
                                    dispute.provisionalCreditAmount(),
                                    "Release suspense")
                    ));
            posting.post(je);
        }
        return transitionTo(dispute,
                dispute.status() == DisputeStatus.ARBITRATION
                        ? DisputeStatus.FINAL_RESOLVED
                        : DisputeStatus.RESOLVED_CARDHOLDER,
                "Resolved for cardholder: " + rationale);
    }

    /**
     * Resolve in favor of the merchant — reverses any provisional credit
     * back against the cardholder.
     */
    @Transactional
    public Dispute resolveForMerchant(UUID disputeId, String rationale) {
        var dispute = requireDispute(disputeId);
        if (dispute.provisionalCreditIssued()) {
            var je = new JournalEntry(
                    UUID.randomUUID(), LocalDate.now(clock),
                    "DISP-REVPROV-" + disputeId,
                    "Reverse provisional credit for " + disputeId,
                    List.of(
                            PostingLine.debit(CARDHOLDER_RECEIVABLE,
                                    dispute.provisionalCreditAmount(),
                                    "Reverse provisional credit"),
                            PostingLine.credit(CHARGEBACK_SUSPENSE,
                                    dispute.provisionalCreditAmount(),
                                    "Release suspense")
                    ));
            posting.post(je);
        }
        return transitionTo(dispute,
                dispute.status() == DisputeStatus.ARBITRATION
                        ? DisputeStatus.FINAL_RESOLVED
                        : DisputeStatus.RESOLVED_MERCHANT,
                "Resolved for merchant: " + rationale);
    }

    /** Cardholder withdraws the dispute — terminal state. */
    @Transactional
    public Dispute withdraw(UUID disputeId, String reason) {
        return transition(disputeId, DisputeStatus.WITHDRAWN, "Withdrawn: " + reason);
    }

    /**
     * Scan all disputes for missed deadlines. Used by a scheduled sweeper
     * — in production a @Scheduled cron task every hour.
     */
    public DeadlineSweepResult sweepDeadlines() {
        var today = LocalDate.now(clock);
        List<UUID> regEDue = new ArrayList<>();
        List<UUID> regZAck = new ArrayList<>();
        List<UUID> resolveDue = new ArrayList<>();

        for (var dispute : store.values()) {
            if (dispute.status() == DisputeStatus.RESOLVED_CARDHOLDER
                    || dispute.status() == DisputeStatus.RESOLVED_MERCHANT
                    || dispute.status() == DisputeStatus.FINAL_RESOLVED
                    || dispute.status() == DisputeStatus.WITHDRAWN) {
                continue;
            }
            if (!dispute.creditCardDispute() && !dispute.provisionalCreditIssued()
                    && !today.isBefore(dispute.regEDeadline())) {
                regEDue.add(dispute.disputeId());
            }
            if (dispute.creditCardDispute() && dispute.status() == DisputeStatus.SUBMITTED
                    && !today.isBefore(dispute.regZAcknowledgeDeadline())) {
                regZAck.add(dispute.disputeId());
            }
            if (dispute.creditCardDispute()
                    && !today.isBefore(dispute.regZResolveDeadline())) {
                resolveDue.add(dispute.disputeId());
            }
        }
        return new DeadlineSweepResult(regEDue, regZAck, resolveDue);
    }

    /** Return how many days remain until the applicable resolution deadline. */
    public long daysToResolution(UUID disputeId) {
        var dispute = requireDispute(disputeId);
        var today = LocalDate.now(clock);
        var deadline = dispute.creditCardDispute()
                ? dispute.regZResolveDeadline()
                : dispute.regEDeadline();
        return Duration.between(today.atStartOfDay(), deadline.atStartOfDay()).toDays();
    }

    /** Lookup a dispute by id. */
    public Optional<Dispute> find(UUID disputeId) {
        return Optional.ofNullable(store.get(disputeId));
    }

    public List<Dispute> findByCard(UUID cardId) {
        List<Dispute> out = new ArrayList<>();
        for (var d : store.values()) {
            if (d.cardId().equals(cardId)) out.add(d);
        }
        return out;
    }

    public int activeCount() {
        int count = 0;
        for (var d : store.values()) {
            if (d.status() != DisputeStatus.RESOLVED_CARDHOLDER
                    && d.status() != DisputeStatus.RESOLVED_MERCHANT
                    && d.status() != DisputeStatus.FINAL_RESOLVED
                    && d.status() != DisputeStatus.WITHDRAWN) {
                count++;
            }
        }
        return count;
    }

    // --- State-machine helpers ----------------------------------------------

    private Dispute transition(UUID disputeId, DisputeStatus target, String note) {
        var dispute = requireDispute(disputeId);
        return transitionTo(dispute, target, note);
    }

    private Dispute transitionTo(Dispute dispute, DisputeStatus target, String note) {
        var allowed = ALLOWED_TRANSITIONS.getOrDefault(dispute.status(),
                EnumSet.noneOf(DisputeStatus.class));
        if (!allowed.contains(target)) {
            throw new IllegalStateException(
                    "Illegal dispute transition: %s -> %s for %s"
                            .formatted(dispute.status(), target, dispute.disputeId()));
        }
        var previous = dispute.status();
        var updated = dispute.withStatus(target, note);
        store.put(dispute.disputeId(), updated);
        log.info("Dispute transition: id={}, {} -> {}, note={}",
                dispute.disputeId(), previous, target, note);
        publishEvent(previous, updated, note);
        return updated;
    }

    private Dispute requireDispute(UUID disputeId) {
        var dispute = store.get(disputeId);
        if (dispute == null) {
            throw new IllegalArgumentException("Unknown dispute: " + disputeId);
        }
        return dispute;
    }

    private void publishEvent(DisputeStatus previous, Dispute dispute, String note) {
        try {
            events.publish(new DisputeEvent(
                    UUID.randomUUID(), Timestamp.now(clock),
                    dispute.disputeId(), dispute.cardId(),
                    previous, dispute.status(), note));
        } catch (Exception e) {
            log.warn("Failed to publish dispute event for {}: {}",
                    dispute.disputeId(), e.getMessage());
        }
    }
}
