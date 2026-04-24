package com.omnibank.cards.api;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status of an issued card. Not all transitions are legal; see
 * {@link #canTransitionTo(CardStatus)}. Terminal states (CLOSED, EXPIRED)
 * cannot be re-opened — a new card must be issued instead.
 */
public enum CardStatus {

    PENDING_ACTIVATION,
    ACTIVE,
    BLOCKED,
    FRAUD_HOLD,
    EXPIRED,
    LOST,
    STOLEN,
    CLOSED;

    private static final Set<CardStatus> TERMINAL = EnumSet.of(EXPIRED, CLOSED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canAuthorize() {
        return this == ACTIVE;
    }

    public boolean canTransitionTo(CardStatus target) {
        if (this == target) {
            return true;
        }
        if (isTerminal()) {
            return false;
        }
        return switch (this) {
            case PENDING_ACTIVATION -> target == ACTIVE || target == CLOSED;
            case ACTIVE -> target != PENDING_ACTIVATION;
            case BLOCKED, FRAUD_HOLD -> target == ACTIVE || target == CLOSED || target == LOST || target == STOLEN;
            case LOST, STOLEN -> target == CLOSED;
            default -> false;
        };
    }
}
