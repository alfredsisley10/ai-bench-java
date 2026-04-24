package com.omnibank.audit.api;

import java.time.Instant;
import java.util.List;

public interface AuditLog {

    void record(AuditEntry entry);

    List<AuditEntry> read(String subject, Instant fromInclusive, Instant toExclusive);

    boolean verifyTamperEvidence(Instant fromInclusive, Instant toExclusive);
}
