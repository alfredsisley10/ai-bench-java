package com.omnibank.legacy.integration.legacysoapservicefacade;

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
 * In-memory shadow DAO for the retired Legacy SOAP Service Facade.
 *
 * <p>// DO NOT MODIFY — retired 2022 under MIG-9729 (replaced by RestServiceFacade).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2022; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2022-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacySoapServiceFacadeDao {

    private static final Logger log = LoggerFactory.getLogger(LegacySoapServiceFacadeDao.class);
    private final ConcurrentHashMap<UUID, LegacySoapServiceFacadeEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code RestServiceFacade}'s save method. */
    @Deprecated(since = "2022-01-01")
    public LegacySoapServiceFacadeEntity save(LegacySoapServiceFacadeEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code RestServiceFacade}'s findById. */
    @Deprecated(since = "2022-01-01")
    public Optional<LegacySoapServiceFacadeEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code RestServiceFacade}'s findAll. */
    @Deprecated(since = "2022-01-01")
    public List<LegacySoapServiceFacadeEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2022-01-01")
    public List<LegacySoapServiceFacadeEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacySoapServiceFacadeEntity>();
        for (var e : store.values()) {
            if (!e.createdAt().isBefore(from) && !e.createdAt().isAfter(to)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /** @deprecated Counting is now done via the analytics service. */
    @Deprecated(since = "2022-01-01")
    public long count() { return store.size(); }

    /**
     * @deprecated Bulk-import shim retained for the FY2022 historical
     *             data migration job. Do not reuse.
     */
    @Deprecated(since = "2022-01-01")
    public void bulkInsert(Collection<LegacySoapServiceFacadeEntity> entities) {
        for (var e : entities) save(e);
    }
}
