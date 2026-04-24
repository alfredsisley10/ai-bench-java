package com.aibench.codegen;

import com.aibench.codegen.LegacyShadowGenerator.ShadowSpec;

/**
 * String templates for the deprecated, "retired but still on disk" Java
 * source files emitted by {@link LegacyShadowGenerator}. The shape mimics
 * what a megabank monorepo accumulates: facade + entity + DAO + config +
 * event listener + batch driver, every one annotated {@code @Deprecated}
 * and capped with a {@code // DO NOT MODIFY} banner pointing at the
 * replacement and the migration ticket.
 *
 * <p>The generated code is intentionally verbose: the point is volume at
 * realistic shape, not concision. Each method writes a fully-formed Java
 * source file as a String.
 */
final class LegacyTemplates {

    private LegacyTemplates() {}

    static String buildGradle() {
        return """
                dependencies {
                    implementation("org.slf4j:slf4j-api")
                }

                // Retired module — only present so that `git blame`-style
                // archaeology, rare bug archeology, and dependency graph
                // analysis still work. No tests, no runtime usage.
                tasks.withType<Test> { enabled = false }
                """;
    }

    static String packageInfo(String pkg, ShadowSpec s) {
        return ("""
                /**
                 * %HUMAN% — retired in Q? %YEAR% under %TICKET%.
                 *
                 * <p>Replaced by {@code %REPL%}; this package is retained
                 * because deletion would break a long tail of internal
                 * tooling that still resolves the FQN on the classpath
                 * (audit-log indexer, change-management report exporter,
                 * the legacy COBOL bridge that imports the entity types
                 * via reflection). It is **not** wired into any Spring
                 * context and carries no scheduled tasks.
                 *
                 * <p>If you find yourself reading this code looking for
                 * authoritative behavior, stop — see {@code %REPL%}.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                package %PKG%;
                """)
                .replace("%PKG%", pkg)
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef());
    }

    static String facade(String pkg, ShadowSpec s) {
        return ("""
                package %PKG%;

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
                 * Facade for the %HUMAN% subsystem.
                 *
                 * <p>// DO NOT MODIFY — retired %YEAR% under %TICKET% (replaced by %REPL%).
                 *
                 * <p>This class is preserved because removing it tickled an
                 * obscure NoClassDefFoundError in the legacy audit indexer.
                 * If/when the indexer is rewritten, delete the entire
                 * {@code %PKG%} package.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                @SuppressWarnings({"unused", "java:S1133", "java:S1135"})
                public final class %NAME%Facade {

                    private static final Logger log = LoggerFactory.getLogger(%NAME%Facade.class);
                    private static final Duration LEGACY_DEFAULT_TIMEOUT = Duration.ofSeconds(30);

                    private final Clock clock;
                    private final ConcurrentHashMap<String, LegacyEntry> entries =
                            new ConcurrentHashMap<>();
                    private final AtomicLong invocations = new AtomicLong();

                    public %NAME%Facade(Clock clock) {
                        this.clock = Objects.requireNonNull(clock, "clock");
                    }

                    /**
                     * @deprecated Use {@code %REPL%} instead. This shim returns
                     *             empty results for every input.
                     */
                    @Deprecated(since = "%YEAR%-01-01")
                    public Optional<LegacyEntry> lookup(String correlationId) {
                        invocations.incrementAndGet();
                        log.debug("legacy lookup invoked correlationId={} — replaced by %REPL%",
                                correlationId);
                        return Optional.ofNullable(entries.get(correlationId));
                    }

                    /**
                     * @deprecated Replaced by the corresponding method on {@code %REPL%}.
                     */
                    @Deprecated(since = "%YEAR%-01-01")
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
                     *             {@code %REPL%} which preserves audit ordering.
                     */
                    @Deprecated(since = "%YEAR%-01-01")
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
                """)
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef());
    }

    static String entity(String pkg, ShadowSpec s) {
        return ("""
                package %PKG%;

                import java.math.BigDecimal;
                import java.time.Instant;
                import java.util.Objects;
                import java.util.UUID;

                /**
                 * Persistent entity for the retired %HUMAN%.
                 *
                 * <p>// DO NOT MODIFY — retired %YEAR% under %TICKET% (replaced by %REPL%).
                 *
                 * <p>Schema lives in the {@code legacy_%SUBLOWER%} schema and
                 * is read-only after %YEAR%. New writes go through the {@code %REPL%}
                 * write path; this class exists for the historical-data exporter
                 * and the regulatory audit retention job.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                @SuppressWarnings({"unused", "java:S1133"})
                public final class %NAME%Entity {

                    private final UUID id;
                    private final String externalReference;
                    private final BigDecimal amount;
                    private final String currencyCode;
                    private final String status;
                    private final Instant createdAt;
                    private final Instant retiredAt;
                    private final String retirementReason;

                    public %NAME%Entity(UUID id, String externalReference, BigDecimal amount,
                                        String currencyCode, String status,
                                        Instant createdAt, Instant retiredAt,
                                        String retirementReason) {
                        this.id = Objects.requireNonNull(id, "id");
                        this.externalReference = Objects.requireNonNull(externalReference, "externalReference");
                        this.amount = Objects.requireNonNull(amount, "amount");
                        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
                        this.status = Objects.requireNonNull(status, "status");
                        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
                        this.retiredAt = retiredAt;
                        this.retirementReason = retirementReason;
                    }

                    public UUID id() { return id; }
                    public String externalReference() { return externalReference; }
                    public BigDecimal amount() { return amount; }
                    public String currencyCode() { return currencyCode; }
                    public String status() { return status; }
                    public Instant createdAt() { return createdAt; }
                    public Instant retiredAt() { return retiredAt; }
                    public String retirementReason() { return retirementReason; }

                    /** Marker so the data-export job can skip retired rows. */
                    public boolean isRetired() {
                        return retiredAt != null;
                    }

                    @Override public boolean equals(Object o) {
                        if (!(o instanceof %NAME%Entity that)) return false;
                        return id.equals(that.id);
                    }

                    @Override public int hashCode() { return id.hashCode(); }

                    @Override public String toString() {
                        return "%NAME%Entity[id=" + id + ", ref=" + externalReference
                                + ", amount=" + amount + " " + currencyCode + "]";
                    }
                }
                """)
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef())
                .replace("%SUBLOWER%", s.subsystem().replace("-", "_"));
    }

    static String dao(String pkg, ShadowSpec s) {
        return ("""
                package %PKG%;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                import java.time.Instant;
                import java.util.ArrayList;
                import java.util.Collection;
                import java.util.List;
                import java.util.Optional;
                import java.util.UUID;
                import java.util.concurrent.ConcurrentHashMap;

                /**
                 * In-memory shadow DAO for the retired %HUMAN%.
                 *
                 * <p>// DO NOT MODIFY — retired %YEAR% under %TICKET% (replaced by %REPL%).
                 *
                 * <p>The original implementation talked to a Sybase ASE
                 * cluster decommissioned in %YEAR%; this in-memory replacement
                 * exists purely so legacy tools that statically reference
                 * the type continue to compile.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                @SuppressWarnings({"unused", "java:S1133"})
                public final class %NAME%Dao {

                    private static final Logger log = LoggerFactory.getLogger(%NAME%Dao.class);
                    private final ConcurrentHashMap<UUID, %NAME%Entity> store = new ConcurrentHashMap<>();

                    /** @deprecated Use {@code %REPL%}'s save method. */
                    @Deprecated(since = "%YEAR%-01-01")
                    public %NAME%Entity save(%NAME%Entity entity) {
                        store.put(entity.id(), entity);
                        log.trace("legacy dao save id={}", entity.id());
                        return entity;
                    }

                    /** @deprecated Use {@code %REPL%}'s findById. */
                    @Deprecated(since = "%YEAR%-01-01")
                    public Optional<%NAME%Entity> findById(UUID id) {
                        return Optional.ofNullable(store.get(id));
                    }

                    /** @deprecated Use {@code %REPL%}'s findAll. */
                    @Deprecated(since = "%YEAR%-01-01")
                    public List<%NAME%Entity> findAll() {
                        return new ArrayList<>(store.values());
                    }

                    /** @deprecated Date-range queries should use the modern repository. */
                    @Deprecated(since = "%YEAR%-01-01")
                    public List<%NAME%Entity> findCreatedBetween(Instant from, Instant to) {
                        var matches = new ArrayList<%NAME%Entity>();
                        for (var e : store.values()) {
                            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                                matches.add(e);
                            }
                        }
                        return matches;
                    }

                    /** @deprecated Counting is now done via the analytics service. */
                    @Deprecated(since = "%YEAR%-01-01")
                    public long count() { return store.size(); }

                    /**
                     * @deprecated Bulk-import shim retained for the FY%YEAR% historical
                     *             data migration job. Do not reuse.
                     */
                    @Deprecated(since = "%YEAR%-01-01")
                    public void bulkInsert(Collection<%NAME%Entity> entities) {
                        for (var e : entities) save(e);
                    }
                }
                """)
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef());
    }

    static String configuration(String pkg, ShadowSpec s) {
        return ("""
                package %PKG%;

                import java.time.Clock;
                import java.time.Duration;
                import java.util.Map;
                import java.util.Objects;

                /**
                 * Configuration for the retired %HUMAN%.
                 *
                 * <p>// DO NOT MODIFY — retired %YEAR% under %TICKET% (replaced by %REPL%).
                 *
                 * <p>Used to be a Spring {@code @Configuration} class; the
                 * {@code @Configuration} annotation was stripped during the
                 * %YEAR% retirement so that Spring no longer wires the
                 * surrounding beans into the app context.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                @SuppressWarnings({"unused", "java:S1133"})
                public final class %NAME%Configuration {

                    /** Default timeout the legacy facade used for downstream calls. */
                    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

                    /** Default retry count applied to outbound calls. */
                    public static final int DEFAULT_RETRY_COUNT = 3;

                    /** Source environment the legacy code originally read from. */
                    public static final String LEGACY_ENV_PREFIX = "LEGACY_%NAMEUPPER%_";

                    /** Default legacy connection string format — never resolved at runtime. */
                    public static final String CONNECTION_TEMPLATE =
                            "jdbc:sybase:Tds:legacy-%SUBLOWER%-host:%PORT%/legacy_%SUBLOWER%_db";

                    private static final int %PORTID% = 5000;

                    private %NAME%Configuration() {
                        throw new AssertionError("constants only — see %REPL%");
                    }

                    /**
                     * Build the property map that the retired bean used to
                     * publish to the legacy property service. Returned for
                     * any old tooling that still calls into it.
                     */
                    public static Map<String, String> legacyProperties(Clock clock) {
                        Objects.requireNonNull(clock, "clock");
                        return Map.of(
                                "subsystem", "%SUBLOWER%",
                                "retiredYear", String.valueOf(%YEAR%),
                                "replacement", "%REPL%",
                                "ticket", "%TICKET%",
                                "timeoutSeconds", String.valueOf(DEFAULT_TIMEOUT.toSeconds()),
                                "retryCount", String.valueOf(DEFAULT_RETRY_COUNT)
                        );
                    }
                }
                """)
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef())
                .replace("%SUBLOWER%", s.subsystem().replace("-", "_"))
                .replace("%PORTID%", "DEFAULT_LEGACY_PORT")
                .replace("%PORT%", "DEFAULT_LEGACY_PORT")
                .replace("%NAMEUPPER%", s.name().toUpperCase());
    }

    static String eventListener(String pkg, ShadowSpec s) {
        return ("""
                package %PKG%;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                import java.util.concurrent.atomic.AtomicLong;

                /**
                 * Event listener for the retired %HUMAN%.
                 *
                 * <p>// DO NOT MODIFY — retired %YEAR% under %TICKET% (replaced by %REPL%).
                 *
                 * <p>Used to subscribe to the {@code legacy.%SUBLOWER%.events} JMS
                 * topic. The subscription was severed during the %YEAR%
                 * cutover; this class only counts inbound events for any
                 * ad-hoc tool that still pushes onto it directly.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                @SuppressWarnings({"unused", "java:S1133"})
                public final class %NAME%EventListener {

                    private static final Logger log = LoggerFactory.getLogger(%NAME%EventListener.class);
                    private final AtomicLong handled = new AtomicLong();
                    private final AtomicLong rejected = new AtomicLong();

                    /**
                     * @deprecated Replaced by {@code %REPL%}'s event handler.
                     *             Kept so that ad-hoc replay tools have a sink.
                     */
                    @Deprecated(since = "%YEAR%-01-01")
                    public void onEvent(String eventType, String payload) {
                        if (eventType == null || payload == null) {
                            rejected.incrementAndGet();
                            log.debug("legacy listener rejected null event");
                            return;
                        }
                        handled.incrementAndGet();
                        log.trace("legacy %NAME% absorbed event={} bytes={}",
                                eventType, payload.length());
                    }

                    public long handledCount() { return handled.get(); }
                    public long rejectedCount() { return rejected.get(); }
                }
                """)
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef())
                .replace("%SUBLOWER%", s.subsystem().replace("-", "_"));
    }

    static String batchDriver(String pkg, ShadowSpec s) {
        return ("""
                package %PKG%;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                import java.time.Clock;
                import java.time.Duration;
                import java.time.Instant;
                import java.util.List;
                import java.util.Objects;

                /**
                 * Driver for the nightly batch belonging to the retired %HUMAN%.
                 *
                 * <p>// DO NOT MODIFY — retired %YEAR% under %TICKET% (replaced by %REPL%).
                 *
                 * <p>The cron entry that triggered this batch was deleted from
                 * the scheduler in %YEAR%. The class is still on disk because
                 * the change-management pipeline expects {@code BatchDriver}
                 * subtypes to remain compilable while the manifest of
                 * historical batch jobs in {@code ops/batch-manifest.yaml}
                 * still references them.
                 */
                @Deprecated(since = "%YEAR%-01-01", forRemoval = false)
                @SuppressWarnings({"unused", "java:S1133"})
                public final class %NAME%BatchDriver {

                    private static final Logger log = LoggerFactory.getLogger(%NAME%BatchDriver.class);
                    private static final Duration LEGACY_BATCH_BUDGET = Duration.ofHours(4);

                    private final Clock clock;
                    private final %NAME%Dao dao;
                    private final %NAME%EventListener listener;

                    public %NAME%BatchDriver(Clock clock, %NAME%Dao dao, %NAME%EventListener listener) {
                        this.clock = Objects.requireNonNull(clock, "clock");
                        this.dao = Objects.requireNonNull(dao, "dao");
                        this.listener = Objects.requireNonNull(listener, "listener");
                    }

                    /**
                     * Returns a one-line summary of what the batch *would* have
                     * processed if it were still scheduled. Pure introspection.
                     */
                    public String summarize() {
                        long count = dao.count();
                        long handled = listener.handledCount();
                        return "%NAME% batch retired %YEAR% — would have processed " + count
                                + " entries (lifetime events handled=" + handled + ")";
                    }

                    /**
                     * @deprecated Use {@code %REPL%}'s scheduled job instead.
                     *             Returns an empty list rather than running.
                     */
                    @Deprecated(since = "%YEAR%-01-01")
                    public List<String> runOnce() {
                        Instant start = Instant.now(clock);
                        log.info("legacy batch %NAME% would start at {} but is retired (%TICKET%)",
                                start);
                        return List.of();
                    }
                }
                """)
                .replace("%PKG%", pkg)
                .replace("%NAME%", s.name())
                .replace("%HUMAN%", s.humanName())
                .replace("%YEAR%", String.valueOf(s.retiredYear()))
                .replace("%REPL%", s.replacementName())
                .replace("%TICKET%", s.ticketRef());
    }
}
