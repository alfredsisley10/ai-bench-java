package com.omnibank.legacy.statements.legacyarchiveservice;

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
 * In-memory shadow DAO for the retired Legacy Archive Service.
 *
 * <p>// DO NOT MODIFY — retired 2019 under MIG-6015 (replaced by StatementArchiveService).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2019; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2019-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyArchiveServiceDao {

    private static final Logger log = LoggerFactory.getLogger(LegacyArchiveServiceDao.class);
    private final ConcurrentHashMap<UUID, LegacyArchiveServiceEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code StatementArchiveService}'s save method. */
    @Deprecated(since = "2019-01-01")
    public LegacyArchiveServiceEntity save(LegacyArchiveServiceEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code StatementArchiveService}'s findById. */
    @Deprecated(since = "2019-01-01")
    public Optional<LegacyArchiveServiceEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code StatementArchiveService}'s findAll. */
    @Deprecated(since = "2019-01-01")
    public List<LegacyArchiveServiceEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2019-01-01")
    public List<LegacyArchiveServiceEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacyArchiveServiceEntity>();
        for (var e : store.values()) {
            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /** @deprecated Counting is now done via the analytics service. */
    @Deprecated(since = "2019-01-01")
    public long count() { return store.size(); }

    /**
     * @deprecated Bulk-import shim retained for the FY2019 historical
     *             data migration job. Do not reuse.
     */
    @Deprecated(since = "2019-01-01")
    public void bulkInsert(Collection<LegacyArchiveServiceEntity> entities) {
        for (var e : entities) save(e);
    }
}
