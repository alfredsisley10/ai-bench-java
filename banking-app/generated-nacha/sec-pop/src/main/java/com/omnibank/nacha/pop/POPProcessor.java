package com.omnibank.nacha.pop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end processor for POP ACH batches. Takes a list of
 * entries, runs authorization checks, emits processable entries,
 * and accumulates counters for reporting.
 */
public final class POPProcessor {

    public record BatchResult(
            List<POPEntry> accepted,
            List<POPEntry> rejected,
            List<String> rejectionReasons
    ) {
        public BatchResult {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
            rejectionReasons = List.copyOf(rejectionReasons);
        }
    }

    private final POPAuthorizationCheck authCheck = new POPAuthorizationCheck();
    private final AtomicLong batchesProcessed = new AtomicLong();
    private final AtomicLong entriesAccepted = new AtomicLong();
    private final AtomicLong entriesRejected = new AtomicLong();

    public BatchResult processBatch(
            List<POPEntry> entries,
            java.util.Map<String, POPAuthorizationCheck.Authorization> authByEntryId) {
        Objects.requireNonNull(entries, "entries");
        batchesProcessed.incrementAndGet();

        List<POPEntry> accepted = new ArrayList<>();
        List<POPEntry> rejected = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (POPEntry entry : entries) {
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
                reasons.add("POP entry " + entry.traceNumber() + ": "
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

    public String secCode() { return "POP"; }
    public String classification() { return "CONSUMER"; }
}
