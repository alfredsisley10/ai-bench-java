package com.omnibank.lending.corporate.api;

/**
 * Commercial loan lifecycle. Transitions are restricted by {@link #canTransitionTo}
 * — the state machine is checked on every update to the underlying aggregate.
 * Skipping states is a rich bug surface.
 */
public enum LoanStatus {
    APPLICATION,
    UNDERWRITING,
    APPROVED,
    DECLINED,
    FUNDED,
    ACTIVE,        // Drawing + repayment in progress
    DELINQUENT,
    NON_ACCRUAL,
    RESTRUCTURED,
    PAID_OFF,
    CHARGED_OFF;

    public boolean canTransitionTo(LoanStatus next) {
        return switch (this) {
            case APPLICATION -> next == UNDERWRITING || next == DECLINED;
            case UNDERWRITING -> next == APPROVED || next == DECLINED;
            case APPROVED -> next == FUNDED || next == DECLINED;
            case FUNDED -> next == ACTIVE;
            case ACTIVE -> next == DELINQUENT || next == PAID_OFF || next == RESTRUCTURED;
            case DELINQUENT -> next == ACTIVE || next == NON_ACCRUAL || next == RESTRUCTURED;
            case NON_ACCRUAL -> next == RESTRUCTURED || next == CHARGED_OFF;
            case RESTRUCTURED -> next == ACTIVE;
            case PAID_OFF, CHARGED_OFF, DECLINED -> false;
        };
    }
}
