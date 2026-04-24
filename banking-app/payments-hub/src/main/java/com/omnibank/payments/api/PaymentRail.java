package com.omnibank.payments.api;

/**
 * The US payment rails Omnibank originates on. Each rail has distinct cutoff,
 * settlement, reversibility, and fee semantics — bugs cluster at the seams.
 */
public enum PaymentRail {
    ACH,      // Same-day and next-day batched, reversible within window
    WIRE,     // Fedwire: real-time gross settlement, irrevocable
    RTP,      // The Clearing House RTP: 24/7, instant, irrevocable
    FEDNOW,   // FedNow: 24/7, instant, irrevocable
    BOOK      // Internal between-Omnibank-accounts, real-time, irrevocable
}
