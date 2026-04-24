package com.omnibank.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntry(
        UUID id,
        Instant at,
        String actor,
        String action,
        String subject,
        Map<String, String> attributes
) {}
