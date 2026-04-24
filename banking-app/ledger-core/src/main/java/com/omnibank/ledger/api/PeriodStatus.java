package com.omnibank.ledger.api;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * Accounting period lifecycle state. A period progresses strictly through
 * {@code FUTURE -> OPEN -> SOFT_CLOSE -> HARD_CLOSE -> ARCHIVED}. Backwards
 * transitions are forbidden outside of regulatory remediation (which routes
 * through a separate approval workflow, not through enum transitions).
 *
 * <p>Sealed so exhaustive pattern-matching is enforced wherever period state
 * drives branching logic — if someone adds a new state, every switch must
 * be updated or the compiler screams.
 */
public enum PeriodStatus {

    /**
     * Period exists in the calendar but is not yet open for posting.
     * Typically the next month or quarter that has been pre-defined.
     */
    FUTURE(false, false, false),

    /**
     * Active period accepting journal entries. Only one period per entity
     * may be OPEN at any time (enforced by {@code PeriodCloseManager}).
     */
    OPEN(true, false, false),

    /**
     * Soft-close: no new business transactions accepted, but adjusting
     * entries (accruals, reclasses, corrections) are still permitted.
     * Controllers use this window to finalize month-end adjustments.
     */
    SOFT_CLOSE(false, true, false),

    /**
     * Hard-close: period is sealed. No further postings of any kind.
     * Trial balance is frozen and audit snapshots have been taken.
     */
    HARD_CLOSE(false, false, true),

    /**
     * Archived: period data has been moved to long-term storage.
     * Queries still work (via archive tables) but writes are impossible.
     */
    ARCHIVED(false, false, true);

    private final boolean acceptsBusinessPostings;
    private final boolean acceptsAdjustments;
    private final boolean frozen;

    PeriodStatus(boolean acceptsBusinessPostings, boolean acceptsAdjustments, boolean frozen) {
        this.acceptsBusinessPostings = acceptsBusinessPostings;
        this.acceptsAdjustments = acceptsAdjustments;
        this.frozen = frozen;
    }

    public boolean acceptsBusinessPostings() {
        return acceptsBusinessPostings;
    }

    public boolean acceptsAdjustments() {
        return acceptsAdjustments;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * Returns the next valid status in the lifecycle, or empty if the
     * period is already in a terminal state.
     */
    public Optional<PeriodStatus> nextStatus() {
        return switch (this) {
            case FUTURE -> Optional.of(OPEN);
            case OPEN -> Optional.of(SOFT_CLOSE);
            case SOFT_CLOSE -> Optional.of(HARD_CLOSE);
            case HARD_CLOSE -> Optional.of(ARCHIVED);
            case ARCHIVED -> Optional.empty();
        };
    }

    /**
     * Whether a transition from this status to the target is legal under
     * normal operations (i.e. not a regulatory override).
     */
    public boolean canTransitionTo(PeriodStatus target) {
        return nextStatus().map(target::equals).orElse(false);
    }

    /**
     * Snapshot of a period's metadata — immutable view returned by queries.
     *
     * @param entityCode   the legal entity this period belongs to (e.g. "OMNI-US")
     * @param period       the calendar month
     * @param status       current lifecycle status
     * @param openedAt     when the period was first opened (null if FUTURE)
     * @param closedAt     when the period reached HARD_CLOSE (null if not yet closed)
     * @param closedBy     the principal who performed the close (null if not yet closed)
     * @param journalCount total journal entries booked in this period
     */
    public record PeriodInfo(
            String entityCode,
            YearMonth period,
            PeriodStatus status,
            Instant openedAt,
            Instant closedAt,
            String closedBy,
            long journalCount
    ) {
        public PeriodInfo {
            Objects.requireNonNull(entityCode, "entityCode");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(status, "status");
        }

        public boolean isOpenForBusiness() {
            return status.acceptsBusinessPostings();
        }

        public boolean isOpenForAdjustments() {
            return status.acceptsAdjustments();
        }
    }

    /**
     * Validation error details for period lifecycle operations.
     */
    public sealed interface PeriodValidationError {

        record InvalidTransition(PeriodStatus from, PeriodStatus to) implements PeriodValidationError {}
        record OutstandingReconciliations(int count) implements PeriodValidationError {}
        record UnpostedAccruals(int count) implements PeriodValidationError {}
        record TrialBalanceImbalance(String details) implements PeriodValidationError {}
        record MissingApproval(String requiredRole) implements PeriodValidationError {}
        record PeriodNotFound(String entityCode, YearMonth period) implements PeriodValidationError {}
        record ConcurrentModification(String entityCode, YearMonth period) implements PeriodValidationError {}
    }

    /**
     * Event raised when a period transitions state. Downstream listeners
     * can trigger subledger closes, report generation, regulatory filings, etc.
     */
    public record PeriodTransitionEvent(
            String entityCode,
            YearMonth period,
            PeriodStatus fromStatus,
            PeriodStatus toStatus,
            Instant transitionedAt,
            String transitionedBy
    ) {
        public PeriodTransitionEvent {
            Objects.requireNonNull(entityCode, "entityCode");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(fromStatus, "fromStatus");
            Objects.requireNonNull(toStatus, "toStatus");
            Objects.requireNonNull(transitionedAt, "transitionedAt");
            Objects.requireNonNull(transitionedBy, "transitionedBy");
        }
    }

    /**
     * Calendar of defined periods for an entity, used by period-close
     * orchestration to determine which period to close/open next.
     */
    public record PeriodCalendar(
            String entityCode,
            YearMonth currentOpenPeriod,
            YearMonth earliestUnclosedPeriod,
            YearMonth latestDefinedPeriod,
            int totalDefinedPeriods
    ) {
        public PeriodCalendar {
            Objects.requireNonNull(entityCode, "entityCode");
            Objects.requireNonNull(latestDefinedPeriod, "latestDefinedPeriod");
        }

        public boolean hasOpenPeriod() {
            return currentOpenPeriod != null;
        }

        public boolean isFullyClosedUpTo(YearMonth inclusive) {
            return earliestUnclosedPeriod == null
                    || earliestUnclosedPeriod.isAfter(inclusive);
        }
    }
}
