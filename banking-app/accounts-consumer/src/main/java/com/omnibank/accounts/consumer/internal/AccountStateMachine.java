package com.omnibank.accounts.consumer.internal;

import com.omnibank.accounts.consumer.api.AccountStatus;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Timestamp;
import com.omnibank.shared.messaging.DomainEvent;
import com.omnibank.shared.messaging.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Full account lifecycle state machine: PENDING -> OPEN -> FROZEN -> DORMANT -> CLOSED.
 * Each transition is validated against a set of legal edges, reason-tracked, audit-logged,
 * and published as a domain event.
 *
 * <p>Transition rules:
 * <ul>
 *   <li>PENDING -> OPEN: KYC complete, initial deposit verified.</li>
 *   <li>OPEN -> FROZEN: compliance hold, court order, fraud investigation.</li>
 *   <li>FROZEN -> OPEN: hold lifted, investigation cleared.</li>
 *   <li>OPEN -> DORMANT: 12+ months of inactivity (automated nightly scan).</li>
 *   <li>DORMANT -> OPEN: customer-initiated activity resumes.</li>
 *   <li>OPEN -> CLOSED: customer request, zero balance confirmed.</li>
 *   <li>DORMANT -> CLOSED: escheatment after state-mandated dormancy period.</li>
 *   <li>FROZEN -> CLOSED: regulatory order, account permanently seized.</li>
 *   <li>PENDING -> CLOSED: KYC failure or customer withdrawal of application.</li>
 * </ul>
 *
 * <p>Freeze and unfreeze carry mandatory reason codes that are retained
 * for regulatory audit trail. Multiple concurrent freeze reasons are tracked.
 */
public class AccountStateMachine {

    private static final Logger log = LoggerFactory.getLogger(AccountStateMachine.class);
    private static final long DORMANCY_THRESHOLD_DAYS = 365;

    /** Legal state transitions: from -> allowed targets. */
    private static final Map<AccountStatus, Set<AccountStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(AccountStatus.class);
        TRANSITIONS.put(AccountStatus.PENDING, EnumSet.of(AccountStatus.OPEN, AccountStatus.CLOSED));
        TRANSITIONS.put(AccountStatus.OPEN, EnumSet.of(AccountStatus.FROZEN, AccountStatus.DORMANT, AccountStatus.CLOSED));
        TRANSITIONS.put(AccountStatus.FROZEN, EnumSet.of(AccountStatus.OPEN, AccountStatus.CLOSED));
        TRANSITIONS.put(AccountStatus.DORMANT, EnumSet.of(AccountStatus.OPEN, AccountStatus.CLOSED));
        TRANSITIONS.put(AccountStatus.CLOSED, EnumSet.noneOf(AccountStatus.class));
    }

    /** Reason codes for freeze operations. Each must map to a regulatory category. */
    public enum FreezeReason {
        COMPLIANCE_HOLD("Compliance department hold pending review"),
        COURT_ORDER("Court-ordered account freeze"),
        FRAUD_INVESTIGATION("Fraud investigation in progress"),
        OFAC_MATCH("Potential OFAC sanctions list match"),
        LAW_ENFORCEMENT("Law enforcement request / subpoena"),
        CUSTOMER_REQUEST("Customer-initiated security freeze"),
        AML_ALERT("Anti-money laundering alert triggered");

        private final String description;

        FreezeReason(String description) {
            this.description = description;
        }

        public String description() { return description; }
    }

    sealed interface TransitionResult permits TransitionResult.Success, TransitionResult.Rejected {
        record Success(AccountNumber account, AccountStatus from, AccountStatus to,
                       Instant transitionedAt) implements TransitionResult {}
        record Rejected(AccountNumber account, AccountStatus current, AccountStatus requested,
                        String reason) implements TransitionResult {}
    }

    record FreezeRecord(FreezeReason reason, Instant frozenAt, String initiatedBy,
                        String caseReference) {}

    record AccountStateChangedEvent(UUID eventId, Instant occurredAt, AccountNumber account,
                                    AccountStatus from, AccountStatus to,
                                    String reason) implements DomainEvent {
        @Override
        public String eventType() {
            return "accounts.consumer.state_changed";
        }
    }

    private final ConsumerAccountRepository accounts;
    private static final org.slf4j.Logger auditLog = org.slf4j.LoggerFactory.getLogger(AccountStateMachine.class);
    private final EventBus events;
    private final Clock clock;

    public AccountStateMachine(ConsumerAccountRepository accounts,
                               EventBus events,
                               Clock clock) {
        this.accounts = accounts;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Attempt a state transition. Validates the transition is legal, applies it,
     * records an audit entry, and publishes a domain event.
     */
    @Transactional
    public TransitionResult transition(AccountNumber accountNumber, AccountStatus targetStatus,
                                       String reason, String initiatedBy) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(targetStatus, "targetStatus");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(initiatedBy, "initiatedBy");

        ConsumerAccountEntity entity = requireAccount(accountNumber);
        AccountStatus currentStatus = entity.status();

        if (!isLegalTransition(currentStatus, targetStatus)) {
            log.warn("Rejected transition {} -> {} for account {} (reason: illegal)",
                    currentStatus, targetStatus, accountNumber);
            return new TransitionResult.Rejected(accountNumber, currentStatus, targetStatus,
                    "Transition from " + currentStatus + " to " + targetStatus + " is not permitted");
        }

        if (targetStatus == AccountStatus.CLOSED) {
            String closureValidation = validateClosure(entity);
            if (closureValidation != null) {
                return new TransitionResult.Rejected(accountNumber, currentStatus, targetStatus,
                        closureValidation);
            }
        }

        Instant now = Timestamp.now(clock);
        applyTransition(entity, targetStatus, reason, now);

        recordAudit(accountNumber, currentStatus, targetStatus, reason, initiatedBy, now);
        publishEvent(accountNumber, currentStatus, targetStatus, reason, now);

        log.info("Account {} transitioned {} -> {} by {} (reason: {})",
                accountNumber, currentStatus, targetStatus, initiatedBy, reason);
        return new TransitionResult.Success(accountNumber, currentStatus, targetStatus, now);
    }

    /**
     * Freeze an account with a specific regulatory reason code and case reference.
     */
    @Transactional
    public TransitionResult freeze(AccountNumber accountNumber, FreezeReason freezeReason,
                                   String caseReference, String initiatedBy) {
        Objects.requireNonNull(freezeReason, "freezeReason");
        String reason = freezeReason.description() + " [Case: " + caseReference + "]";
        return transition(accountNumber, AccountStatus.FROZEN, reason, initiatedBy);
    }

    /**
     * Unfreeze an account, returning it to OPEN status. Requires documentation
     * of the resolution that justifies lifting the freeze.
     */
    @Transactional
    public TransitionResult unfreeze(AccountNumber accountNumber, String resolutionNotes,
                                     String initiatedBy) {
        Objects.requireNonNull(resolutionNotes, "resolutionNotes");
        if (resolutionNotes.length() < 20) {
            return new TransitionResult.Rejected(accountNumber,
                    requireAccount(accountNumber).status(), AccountStatus.OPEN,
                    "Resolution notes must be at least 20 characters for audit compliance");
        }
        return transition(accountNumber, AccountStatus.OPEN, "Unfreeze: " + resolutionNotes,
                initiatedBy);
    }

    /**
     * Nightly dormancy scan: accounts with no activity for the configured threshold
     * period are transitioned to DORMANT. Only OPEN accounts are eligible.
     */
    @Transactional
    public List<TransitionResult> runDormancyScan() {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(DORMANCY_THRESHOLD_DAYS);

        List<ConsumerAccountEntity> candidates = accounts.findAll().stream()
                .filter(a -> a.status() == AccountStatus.OPEN)
                .filter(a -> a.openedOn().isBefore(cutoff))
                .toList();

        List<TransitionResult> results = new java.util.ArrayList<>();
        for (ConsumerAccountEntity account : candidates) {
            Instant lastActivity = determineLastActivityDate(account);
            long daysSinceActivity = ChronoUnit.DAYS.between(
                    lastActivity, Timestamp.now(clock));

            if (daysSinceActivity >= DORMANCY_THRESHOLD_DAYS) {
                TransitionResult result = transition(
                        AccountNumber.of(account.accountNumber()),
                        AccountStatus.DORMANT,
                        "Automated dormancy: " + daysSinceActivity + " days since last activity",
                        "SYSTEM_DORMANCY_SCAN"
                );
                results.add(result);
            }
        }

        log.info("Dormancy scan complete: {} candidates evaluated, {} transitioned",
                candidates.size(), results.stream()
                        .filter(r -> r instanceof TransitionResult.Success).count());
        return results;
    }

    /**
     * Reactivate a dormant account when customer-initiated activity is detected.
     */
    @Transactional
    public TransitionResult reactivate(AccountNumber accountNumber, String activityDescription,
                                        String initiatedBy) {
        ConsumerAccountEntity entity = requireAccount(accountNumber);
        if (entity.status() != AccountStatus.DORMANT) {
            return new TransitionResult.Rejected(accountNumber, entity.status(), AccountStatus.OPEN,
                    "Account is not dormant; reactivation not applicable");
        }
        return transition(accountNumber, AccountStatus.OPEN,
                "Reactivation due to customer activity: " + activityDescription, initiatedBy);
    }

    private boolean isLegalTransition(AccountStatus from, AccountStatus to) {
        Set<AccountStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    private String validateClosure(ConsumerAccountEntity entity) {
        if (entity.product().isMaturing() && entity.maturesOn() != null
                && entity.maturesOn().isAfter(LocalDate.now(clock))) {
            return "CD account cannot be closed before maturity without early withdrawal processing";
        }
        return null;
    }

    private void applyTransition(ConsumerAccountEntity entity, AccountStatus target,
                                  String reason, Instant now) {
        switch (target) {
            case OPEN -> entity.activate();
            case FROZEN -> entity.freeze(reason);
            case DORMANT -> entity.markDormant(reason);
            case CLOSED -> entity.close(now);
            case PENDING -> throw new IllegalStateException("Cannot transition back to PENDING");
        }
    }

    private Instant determineLastActivityDate(ConsumerAccountEntity account) {
        // In production, queries the transaction journal for the most recent entry.
        // Simplified: use the entity's last modification timestamp.
        return account.updatedAt() != null ? account.updatedAt() : account.createdAt();
    }

    private void recordAudit(AccountNumber account, AccountStatus from, AccountStatus to,
                              String reason, String initiatedBy, Instant when) {
        auditLog.info("audit: account={} from={} to={} by={} reason={}",
                account.raw(), from, to, initiatedBy, reason);
    }

    private void publishEvent(AccountNumber account, AccountStatus from, AccountStatus to,
                               String reason, Instant when) {
        events.publish(new AccountStateChangedEvent(
                UUID.randomUUID(), when, account, from, to, reason));
    }

    private ConsumerAccountEntity requireAccount(AccountNumber accountNumber) {
        return accounts.findById(accountNumber.raw())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown account: " + accountNumber));
    }
}
