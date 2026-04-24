package com.omnibank.nacha.iat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end processor for IAT ACH batches. Takes a list of
 * entries, runs authorization checks, emits processable entries,
 * and accumulates counters for reporting.
 */
public final class IATProcessor {

    public record BatchResult(
            List<IATEntry> accepted,
            List<IATEntry> rejected,
            List<String> rejectionReasons
    ) {
        public BatchResult {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
            rejectionReasons = List.copyOf(rejectionReasons);
        }
    }

    private final IATAuthorizationCheck authCheck = new IATAuthorizationCheck();
    private final AtomicLong batchesProcessed = new AtomicLong();
    private final AtomicLong entriesAccepted = new AtomicLong();
    private final AtomicLong entriesRejected = new AtomicLong();

    public BatchResult processBatch(
            List<IATEntry> entries,
            java.util.Map<String, IATAuthorizationCheck.Authorization> authByEntryId) {
        Objects.requireNonNull(entries, "entries");
        batchesProcessed.incrementAndGet();

        List<IATEntry> accepted = new ArrayList<>();
        List<IATEntry> rejected = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (IATEntry entry : entries) {
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
                reasons.add("IAT entry " + entry.traceNumber() + ": "
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

    public String secCode() { return "IAT"; }
    public String classification() { return "CORPORATE"; }
}
