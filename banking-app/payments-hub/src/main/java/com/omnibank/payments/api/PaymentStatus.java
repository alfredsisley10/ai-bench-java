package com.omnibank.payments.api;

public enum PaymentStatus {
    RECEIVED,     // Request accepted, not yet validated
    VALIDATED,    // Passes domain checks
    SUBMITTED,    // Sent to network / rail
    SETTLED,      // Network confirms completion
    REJECTED,     // Rail rejected
    RETURNED,     // Counterparty returned (ACH)
    CANCELED      // Canceled before submission
}
