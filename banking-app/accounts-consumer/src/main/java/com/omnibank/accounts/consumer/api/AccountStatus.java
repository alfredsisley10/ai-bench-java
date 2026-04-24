package com.omnibank.accounts.consumer.api;

public enum AccountStatus {
    PENDING,    // Opening in progress (KYC outstanding)
    OPEN,       // Active
    FROZEN,     // Compliance hold
    DORMANT,    // 12+ months inactive
    CLOSED      // Terminal
}
