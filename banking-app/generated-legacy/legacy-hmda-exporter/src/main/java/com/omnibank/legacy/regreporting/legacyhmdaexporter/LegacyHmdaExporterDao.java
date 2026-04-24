package com.omnibank.legacy.regreporting.legacyhmdaexporter;

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
 * In-memory shadow DAO for the retired Legacy HMDA Exporter.
 *
 * <p>// DO NOT MODIFY — retired 2020 under MIG-5008 (replaced by HmdaReportGenerator).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2020; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2020-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyHmdaExporterDao {

    private static final Logger log = LoggerFactory.getLogger(LegacyHmdaExporterDao.class);
    private final ConcurrentHashMap<UUID, LegacyHmdaExporterEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code HmdaReportGenerator}'s save method. */
    @Deprecated(since = "2020-01-01")
    public LegacyHmdaExporterEntity save(LegacyHmdaExporterEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code HmdaReportGenerator}'s findById. */
    @Deprecated(since = "2020-01-01")
    public Optional<LegacyHmdaExporterEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code HmdaReportGenerator}'s findAll. */
    @Deprecated(since = "2020-01-01")
    public List<LegacyHmdaExporterEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2020-01-01")
    public List<LegacyHmdaExporterEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacyHmdaExporterEntity>();
        for (var e : store.values()) {
            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /** @deprecated Counting is now done via the analytics service. */
    @Deprecated(since = "2020-01-01")
    public long count() { return store.size(); }

    /**
     * @deprecated Bulk-import shim retained for the FY2020 historical
     *             data migration job. Do not reuse.
     */
    @Deprecated(since = "2020-01-01")
    public void bulkInsert(Collection<LegacyHmdaExporterEntity> entities) {
        for (var e : entities) save(e);
    }
}
