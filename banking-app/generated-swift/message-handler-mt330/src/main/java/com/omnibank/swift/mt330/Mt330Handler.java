package com.omnibank.swift.mt330;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end handler for SWIFT MT330. Coordinates parse →
 * validate → route → forward pipeline and records basic
 * telemetry.
 */
public final class Mt330Handler {

    public record HandleResult(
            boolean accepted,
            String messageReference,
            Mt330RoutingRules.Destination destination,
            String rejectionReason,
            Instant processedAt
    ) {}

    private final Mt330Parser parser = new Mt330Parser();
    private final Mt330Validator validator = new Mt330Validator();
    private final Mt330RoutingRules routing = new Mt330RoutingRules();

    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    public HandleResult handle(String rawBlock4) {
        Objects.requireNonNull(rawBlock4, "rawBlock4");
        processedCount.incrementAndGet();
        Mt330Message message;
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

    private String summarizeErrors(Mt330Validator.ValidationResult vr) {
        StringBuilder sb = new StringBuilder();
        for (var f : vr.findings()) {
            if (f.severity() == Mt330Validator.ValidationFinding.Severity.ERROR) {
                sb.append(f.code()).append(": ").append(f.message()).append("; ");
            }
        }
        return sb.toString();
    }
}
