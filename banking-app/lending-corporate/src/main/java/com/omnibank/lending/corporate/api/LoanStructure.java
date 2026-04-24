package com.omnibank.lending.corporate.api;

public enum LoanStructure {
    TERM_LOAN,        // Fixed amortization
    REVOLVING_CREDIT, // Drawn/repaid repeatedly up to a limit
    CONSTRUCTION,     // Progressive draws against milestones
    BRIDGE,           // Short-term gap financing
    SYNDICATED_TERM   // Term loan with participants
}
