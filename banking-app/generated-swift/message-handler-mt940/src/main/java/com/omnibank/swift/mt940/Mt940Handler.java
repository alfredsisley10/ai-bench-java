package com.omnibank.swift.mt940;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end handler for SWIFT MT940. Coordinates parse →
 * validate → route → forward pipeline and records basic
 * telemetry.
 */
public final class Mt940Handler {

    public record HandleResult(
            boolean accepted,
            String messageReference,
            Mt940RoutingRules.Destination destination,
            String rejectionReason,
            Instant processedAt
    ) {}

    private final Mt940Parser parser = new Mt940Parser();
    private final Mt940Validator validator = new Mt940Validator();
    private final Mt940RoutingRules routing = new Mt940RoutingRules();

    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    public HandleResult handle(String rawBlock4) {
        Objects.requireNonNull(rawBlock4, "rawBlock4");
        processedCount.incrementAndGet();
        Mt940Message message;
        try {
            message = parser.parse(rawBlock4);
        } catch (Exception e) {
            rejectedCount.incrementAndGet();
            return new HandleResult(false, null, null,
                    "PARSE_ERROR: " + e.getMessage(), Instant.now());
        }

        var validation = validator.validate(message);
        if (validation.hasErrors()) {
            rejectedCount.incrementAndGet();
            return new HandleResult(false, message.messageReference(), null,
                    summarizeErrors(validation), Instant.now());
        }

        var dest = routing.routeFor(message);
        acceptedCount.incrementAndGet();
        return new HandleResult(true, message.messageReference(), dest, null, Instant.now());
    }

    public long processedCount() { return processedCount.get(); }
    public long acceptedCount() { return acceptedCount.get(); }
    public long rejectedCount() { return rejectedCount.get(); }

    private String summarizeErrors(Mt940Validator.ValidationResult vr) {
        StringBuilder sb = new StringBuilder();
        for (var f : vr.findings()) {
            if (f.severity() == Mt940Validator.ValidationFinding.Severity.ERROR) {
                sb.append(f.code()).append(": ").append(f.message()).append("; ");
            }
        }
        return sb.toString();
    }
}
