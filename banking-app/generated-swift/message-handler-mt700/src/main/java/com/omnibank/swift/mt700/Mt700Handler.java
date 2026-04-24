package com.omnibank.swift.mt700;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end handler for SWIFT MT700. Coordinates parse →
 * validate → route → forward pipeline and records basic
 * telemetry.
 */
public final class Mt700Handler {

    public record HandleResult(
            boolean accepted,
            String messageReference,
            Mt700RoutingRules.Destination destination,
            String rejectionReason,
            Instant processedAt
    ) {}

    private final Mt700Parser parser = new Mt700Parser();
    private final Mt700Validator validator = new Mt700Validator();
    private final Mt700RoutingRules routing = new Mt700RoutingRules();

    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    public HandleResult handle(String rawBlock4) {
        Objects.requireNonNull(rawBlock4, "rawBlock4");
        processedCount.incrementAndGet();
        Mt700Message message;
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

    private String summarizeErrors(Mt700Validator.ValidationResult vr) {
        StringBuilder sb = new StringBuilder();
        for (var f : vr.findings()) {
            if (f.severity() == Mt700Validator.ValidationFinding.Severity.ERROR) {
                sb.append(f.code()).append(": ").append(f.message()).append("; ");
            }
        }
        return sb.toString();
    }
}
