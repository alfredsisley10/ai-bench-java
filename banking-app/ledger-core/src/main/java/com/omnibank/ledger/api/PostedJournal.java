package com.omnibank.ledger.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable record of a posted journal entry — after the engine has booked it.
 * Carries the ledger-assigned sequence number (strictly monotonic per day).
 */
public record PostedJournal(
        long sequence,
        UUID proposalId,
        LocalDate postingDate,
        Instant postedAt,
        String businessKey,
        String description,
        List<PostingLine> lines,
        String postedBy
) {
}
