package com.omnibank.legacy.batch.legacyeodcloserunner;

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
 * In-memory shadow DAO for the retired Legacy EOD Close Runner.
 *
 * <p>// DO NOT MODIFY — retired 2019 under MIG-9608 (replaced by EndOfDayCloseManager).
 *
 * <p>The original implementation talked to a Sybase ASE
 * cluster decommissioned in 2019; this in-memory replacement
 * exists purely so legacy tools that statically reference
 * the type continue to compile.
 */
@Deprecated(since = "2019-01-01", forRemoval = false)
@SuppressWarnings({"unused", "java:S1133"})
public final class LegacyEodCloseRunnerDao {

    private static final Logger log = LoggerFactory.getLogger(LegacyEodCloseRunnerDao.class);
    private final ConcurrentHashMap<UUID, LegacyEodCloseRunnerEntity> store = new ConcurrentHashMap<>();

    /** @deprecated Use {@code EndOfDayCloseManager}'s save method. */
    @Deprecated(since = "2019-01-01")
    public LegacyEodCloseRunnerEntity save(LegacyEodCloseRunnerEntity entity) {
        store.put(entity.id(), entity);
        log.trace("legacy dao save id={}", entity.id());
        return entity;
    }

    /** @deprecated Use {@code EndOfDayCloseManager}'s findById. */
    @Deprecated(since = "2019-01-01")
    public Optional<LegacyEodCloseRunnerEntity> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    /** @deprecated Use {@code EndOfDayCloseManager}'s findAll. */
    @Deprecated(since = "2019-01-01")
    public List<LegacyEodCloseRunnerEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    /** @deprecated Date-range queries should use the modern repository. */
    @Deprecated(since = "2019-01-01")
    public List<LegacyEodCloseRunnerEntity> findCreatedBetween(Instant from, Instant to) {
        var matches = new ArrayList<LegacyEodCloseRunnerEntity>();
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
    public void bulkInsert(Collection<LegacyEodCloseRunnerEntity> entities) {
        for (var e : entities) save(e);
    }
}
