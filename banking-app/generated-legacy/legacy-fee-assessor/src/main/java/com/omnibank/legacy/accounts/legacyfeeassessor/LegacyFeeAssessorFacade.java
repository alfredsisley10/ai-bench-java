package com.omnibank.legacy.accounts.legacyfeeassessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Facade for the Legacy Fee Assessor subsystem.
 *
 * <p>// DO NOT MODIFY — retired 2021 under MIG-2025 (replaced by AccountFeeEngine).
 *
 * <p>This class is preserved because removing it tickled an
 * obscure NoClassDefFoundError in the legacy audit indexer.
 * If/when the indexer is rewritten, delete the entire
 * {@code com.omnibank.legacy.accounts.legacyfeeassessor} package.
 */
@Deprecated(since = "2021-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133", "java:S1135"})
public final class LegacyFeeAssessorFacade {

    private static final Logger log = LoggerFactory.getLogger(LegacyFeeAssessorFacade.class);
    private static final Duration LEGACY_DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Clock clock;
    private final ConcurrentHashMap<String, LegacyEntry> entries =
            new ConcurrentHashMap<>();
    private final AtomicLong invocations = new AtomicLong();

    public LegacyFeeAssessorFacade(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @deprecated Use {@code AccountFeeEngine} instead. This shim returns
     *             empty results for every input.
     */
    @Deprecated(since = "2021-01-01")
    public Optional<LegacyEntry> lookup(String correlationId) {
        invocations.incrementAndGet();
        log.debug("legacy lookup invoked correlationId={} — replaced by AccountFeeEngine",
                correlationId);
        return Optional.ofNullable(entries.get(correlationId));
    }

    /**
     * @deprecated Replaced by the corresponding method on {@code AccountFeeEngine}.
     */
    @Deprecated(since = "2021-01-01")
    public LegacyEntry store(String correlationId, String payload) {
        invocations.incrementAndGet();
        var entry = new LegacyEntry(
                UUID.randomUUID(),
                correlationId,
                payload,
                Instant.now(clock));
        entries.put(correlationId, entry);
        log.trace("legacy store id={} for correlation={}", entry.id(), correlationId);
        return entry;
    }

    /**
     * @deprecated Diagnostic only. Production code paths must call
     *             {@code AccountFeeEngine} which preserves audit ordering.
     */
    @Deprecated(since = "2021-01-01")
    public List<LegacyEntry> snapshot() {
        return List.copyOf(entries.values());
    }

    long invocationCount() {
        return invocations.get();
    }

    /** Snapshot record used internally by the legacy facade. */
    public record LegacyEntry(UUID id, String correlationId,
                              String payload, Instant recordedAt) {}
}
