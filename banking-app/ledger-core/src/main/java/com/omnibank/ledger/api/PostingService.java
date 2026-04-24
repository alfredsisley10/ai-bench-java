package com.omnibank.ledger.api;

/**
 * Public entry point for booking into the general ledger. Every other module
 * posts through this — there is no other sanctioned path to GL.
 */
public interface PostingService {

    /**
     * Validate and book a journal entry. Atomic: either the full set of lines
     * hits the ledger or none do.
     *
     * @throws PostingException if unbalanced, cross-currency, or refers to
     *                          unknown / closed accounts, or duplicates an
     *                          already-posted business key.
     */
    PostedJournal post(JournalEntry entry);
}
