package com.omnibank.nacha.rck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end processor for RCK ACH batches. Takes a list of
 * entries, runs authorization checks, emits processable entries,
 * and accumulates counters for reporting.
 */
public final class RCKProcessor {

    public record BatchResult(
            List<RCKEntry> accepted,
            List<RCKEntry> rejected,
            List<String> rejectionReasons
    ) {
        public BatchResult {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
            rejectionReasons = List.copyOf(rejectionReasons);
        }
    }

    private final RCKAuthorizationCheck authCheck = new RCKAuthorizationCheck();
    private final AtomicLong batchesProcessed = new AtomicLong();
    private final AtomicLong entriesAccepted = new AtomicLong();
    private final AtomicLong entriesRejected = new AtomicLong();

    public BatchResult processBatch(
            List<RCKEntry> entries,
            java.util.Map<String, RCKAuthorizationCheck.Authorization> authByEntryId) {
        Objects.requireNonNull(entries, "entries");
        batchesProcessed.incrementAndGet();

        List<RCKEntry> accepted = new ArrayList<>();
        List<RCKEntry> rejected = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (RCKEntry entry : entries) {
            var auth = authByEntryId != null
                    ? authByEntryId.get(entry.identificationNumber())
                    : null;
            var outcome = authCheck.verify(entry, auth);
            if (outcome.ok()) {
                accepted.add(entry);
                entriesAccepted.incrementAndGet();
            } else {
                rejected.add(entry);
                entriesRejected.incrementAndGet();
                reasons.add("RCK entry " + entry.traceNumber() + ": "
                        + String.join(", ", outcome.violations()));
            }
        }

        return new BatchResult(
                Collections.unmodifiableList(accepted),
                Collections.unmodifiableList(rejected),
                Collections.unmodifiableList(reasons));
    }

    public long batchesProcessed() { return batchesProcessed.get(); }
    public long entriesAccepted() { return entriesAccepted.get(); }
    public long entriesRejected() { return entriesRejected.get(); }

    public String secCode() { return "RCK"; }
    public String classification() { return "CONSUMER"; }
}
